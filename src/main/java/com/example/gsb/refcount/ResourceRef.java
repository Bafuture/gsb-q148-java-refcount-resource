package com.example.gsb.refcount;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A single counted reference to a shared resource. Obtained via
 * {@link SharedResource#acquire()}; must be released exactly once via {@link #close()}.
 */
public final class ResourceRef<T> implements AutoCloseable {
    private final SharedResource<T> owner;
    private final long acquiredAtNanos = System.nanoTime();
    private final AtomicBoolean released = new AtomicBoolean(false);

    ResourceRef(SharedResource<T> owner) {
        this.owner = owner;
    }

    /** Returns the underlying resource, or throws if this reference (or the resource) is released. */
    public T get() {
        if (released.get()) {
            throw new RefAlreadyReleasedException(
                    "this reference was already released; the resource can no longer be accessed through it");
        }
        return owner.resourceOrThrow();
    }

    public boolean isReleased() {
        return released.get();
    }

    /**
     * Releases this reference, decrementing the shared count.
     *
     * @throws RefAlreadyReleasedException if this reference was already released
     */
    @Override
    public void close() {
        if (!released.compareAndSet(false, true)) {
            throw new RefAlreadyReleasedException(
                    "this reference (" + Integer.toHexString(System.identityHashCode(this))
                            + ") has already been released; each reference must be released exactly once");
        }
        owner.releaseRef();
    }

    /** For leak diagnostics: how long this reference has been held. */
    long heldNanos() {
        return System.nanoTime() - acquiredAtNanos;
    }
}
