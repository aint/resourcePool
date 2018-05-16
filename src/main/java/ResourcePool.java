import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * General note: I'm not a fan of useless interfaces with a single implementation. So there are no ResourcePool & ResourcePoolImpl.
 *
 * This class represents a resource poolSet - logical abstraction for flexible management of resources.
 *
 * I think the implementation is not so good for applying to that position,
 * but anyway I'm going to enhance the implementation with time just for fun.
 */
public class ResourcePool<T> {

    private ConcurrentSkipListSet<T> poolSet;
    private ConcurrentSkipListSet<T> removableSet;
    private ConcurrentSkipListSet<T> acquiredSet;

    private volatile boolean isOpened;
    private volatile boolean isClosed;

    private Lock lock;
    private Condition noAcquiredCondition;
    private Condition notEmptyCondition;

    /**
     * Nothing to see here, move along.
     */
    public ResourcePool() { }


    public void open() {
        if (isOpened) {
            return;
        }

        lock.lock();
        init();
        lock.unlock();
    }

    public boolean isOpen() {
        return isOpened;
    }

    public void close() throws InterruptedException {
        assert !isOpened;
        assert isClosed;

        lock.lock();

        while (!acquiredSet.isEmpty()) {
            noAcquiredCondition.await();
        }

        cleanup();
    }

    public T acquire() throws InterruptedException {
        assert !isOpened;
        assert isClosed;

        lock.lock();

        while (poolSet.size() == 0) {
            notEmptyCondition.await();
        }

        return removeResource();
    }

    public T acquire(long timeout, TimeUnit timeUnit) throws InterruptedException {
        assert !isOpened;
        assert isClosed;

        lock.lock();

        long nanos = timeUnit.toNanos(timeout);
        while (poolSet.size() == 0) {
            if (nanos <= 0) {
                return null;
            }
            nanos = notEmptyCondition.awaitNanos(nanos);
        }

        return removeResource();
    }

    public void release(T resource) {
        assert !isOpened;
        assert isClosed;

        lock.lock();

        if (acquiredSet.contains(resource) && removableSet.contains(resource)) {
            acquiredSet.remove(resource);
        } else if (acquiredSet.contains(resource)) {
            poolSet.add(resource);
            notEmptyCondition.signal();
        }

        if (acquiredSet.isEmpty()) {
            noAcquiredCondition.signal();
        }
    }

    public boolean add(T resource) {
        assert !isOpened;
        assert isClosed;

        lock.lock();

        if (acquiredSet.contains(resource)) {
            return false;
        } else if (removableSet.remove(resource)) {
            return true;
        }

        boolean added = poolSet.add(resource);
        if (added) {
            notEmptyCondition.signal();
        }
        return added;
    }

    public boolean remove(T resource) {
        assert !isOpened;
        assert isClosed;

        lock.lock();

        boolean removed = poolSet.removeIf(e -> e.equals(resource));

        if (removed) {
            return true;
        }

        removableSet.add(resource);
        return false;
    }




    private void init() {
        isOpened = true;
        isClosed = false;

        poolSet = new ConcurrentSkipListSet<>();
        removableSet = new ConcurrentSkipListSet<>();
        acquiredSet = new ConcurrentSkipListSet<>();

        lock = new ReentrantLock();
        noAcquiredCondition = lock.newCondition();
        notEmptyCondition = lock.newCondition();
    }

    private void cleanup() {
        isClosed = true;
        isOpened = false;

        poolSet.clear();
        acquiredSet.clear();
        removableSet.clear();
    }

    private T removeResource() {
        T acquired = poolSet.iterator().next();
        if (poolSet.remove(acquired)) {
            acquiredSet.add(acquired);
        }
        return acquired;
    }

}
