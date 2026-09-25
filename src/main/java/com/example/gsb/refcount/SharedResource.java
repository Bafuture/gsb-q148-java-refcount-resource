package com.example.gsb.refcount;

/**
 * A reference-counted handle over a registered resource. Thread-safe.
 *
 * <p>The underlying resource is released exactly once, when the count drops to zero.
 * After release the strong reference to the resource is cleared so it becomes
 * eligible for garbage collection.
 */
public final class SharedResource<T> {
    private final ResourceManager manager;
    private final Releaser<? super T> releaser;
    private final String description;

    private T resource;
    private int refCount;
    private boolean released;

    SharedResource(ResourceManager manager, T resource, Releaser<? super T> releaser, String description) {
        this.manager = manager;
        this.resource = resource;
        this.releaser = releaser;
        this.description = description;
    }

    /** Acquires a new counted reference. Fails if the resource is already released. */
    public synchronized ResourceRef<T> acquire() {
        if (released) {
            throw new ResourceReleasedException(
                    "resource [" + description + "] is already released; cannot acquire new references");
        }
        refCount++;
        return new ResourceRef<>(this);
    }

    /** Current number of outstanding references. */
    public synchronized int refCount() {
        return refCount;
    }

    /** Whether the underlying resource has been released (count reached zero). */
    public synchronized boolean isReleased() {
        return released;
    }

    public String description() {
        return description;
    }

    synchronized T resourceOrThrow() {
        if (released) {
            throw new ResourceReleasedException("resource [" + description + "] is already released");
        }
        return resource;
    }

    /** Called by {@link ResourceRef#close()} after its own exactly-once guard passes. */
    void releaseRef() {
        T toRelease;
        synchronized (this) {
            refCount--;
            if (refCount > 0) {
                return;
            }
            // Count hit zero: mark released and drop the strong reference before
            // running the releaser, so the resource becomes GC-eligible afterwards.
            released = true;
            toRelease = resource;
            resource = null;
        }
        manager.untrack(this);
        runReleaser(toRelease);
    }

    /** Force-releases regardless of outstanding count (used by {@link ResourceManager#close()}). */
    void forceRelease() {
        T toRelease;
        synchronized (this) {
            if (released) {
                return;
            }
            released = true;
            refCount = 0;
            toRelease = resource;
            resource = null;
        }
        manager.untrack(this);
        runReleaser(toRelease);
    }

    private void runReleaser(T toRelease) {
        try {
            releaser.release(toRelease);
        } catch (Exception e) {
            throw new ResourceReleaseException("releaser failed for resource [" + description + "]", e);
        }
    }
}
