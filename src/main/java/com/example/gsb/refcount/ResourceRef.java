package com.example.gsb.refcount;

import java.time.Instant;

/**
 * A single counted reference to a registered resource. Each reference must be
 * released exactly once; releasing it twice raises
 * {@link ResourceReleasedException}. Implements {@link AutoCloseable} so it
 * can be used with try-with-resources.
 */
public final class ResourceRef<T> implements AutoCloseable {

    private final ResourceHandle<T> handle;
    private final long id;
    private final Instant acquiredAt;

    /** Guarded by the owning handle's lock. */
    private boolean released;

    ResourceRef(ResourceHandle<T> handle, long id, Instant acquiredAt) {
        this.handle = handle;
        this.id = id;
        this.acquiredAt = acquiredAt;
    }

    /** Unique id of this reference, useful for correlating leak reports. */
    public long id() {
        return id;
    }

    Instant acquiredAt() {
        return acquiredAt;
    }

    /**
     * Returns the underlying resource.
     *
     * @throws ResourceReleasedException if this reference was already released
     * @throws IllegalStateException     if the underlying resource is gone
     */
    public T get() {
        return handle.resourceFor(this);
    }

    /** Whether this reference has already been released. */
    public boolean isReleased() {
        return handle.isRefReleased(this);
    }

    /**
     * Releases this reference, decrementing the handle's reference count.
     *
     * @throws ResourceReleasedException if this reference was already released
     */
    public void release() {
        handle.releaseRef(this);
    }

    @Override
    public void close() {
        release();
    }

    boolean isReleasedInternal() {
        return released;
    }

    void markReleased() {
        released = true;
    }

    @Override
    public String toString() {
        return "ResourceRef{#" + id + " -> " + handle.name() + '}';
    }
}
