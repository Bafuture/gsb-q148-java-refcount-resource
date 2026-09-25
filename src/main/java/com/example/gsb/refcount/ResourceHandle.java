package com.example.gsb.refcount;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Handle for a registered resource. The reference count starts at zero; each
 * {@link #acquire()} increments it and each {@link ResourceRef#release()}
 * decrements it. When the count returns to zero the underlying resource is
 * released exactly once, the handle stops accepting new references, and its
 * strong pointer to the resource is cleared so the object can be garbage
 * collected.
 *
 * <p>All state transitions are serialized on one lock, so concurrent
 * acquire/release from multiple threads keeps the count exact and never
 * releases the resource while references are still outstanding.
 */
public final class ResourceHandle<T> {

    private final RefCountedResourceManager manager;
    private final String name;
    private final Runnable releaseAction;
    private final String registrationLocation;
    private final Instant registeredAt;

    private final Object lock = new Object();
    private final List<ResourceRef<T>> liveRefs = new ArrayList<>();

    private T resource;
    private int activeRefs;
    private boolean released;

    ResourceHandle(RefCountedResourceManager manager,
                   String name,
                   T resource,
                   Runnable releaseAction,
                   String registrationLocation) {
        this.manager = manager;
        this.name = name;
        this.resource = resource;
        this.releaseAction = releaseAction;
        this.registrationLocation = registrationLocation;
        this.registeredAt = Instant.now();
    }

    public String name() {
        return name;
    }

    /** Where (which caller frame) this resource was registered. */
    public String registrationLocation() {
        return registrationLocation;
    }

    public Instant registeredAt() {
        return registeredAt;
    }

    /** Current number of outstanding references. */
    public int activeRefCount() {
        synchronized (lock) {
            return activeRefs;
        }
    }

    /** Whether the underlying resource has been released. */
    public boolean isReleased() {
        synchronized (lock) {
            return released;
        }
    }

    /**
     * Acquires a new counted reference, incrementing the reference count.
     *
     * @throws IllegalStateException if the resource was already released
     */
    public ResourceRef<T> acquire() {
        synchronized (lock) {
            if (released) {
                throw new IllegalStateException(
                        "Resource '" + name + "' has already been released; cannot acquire new references");
            }
            ResourceRef<T> ref = new ResourceRef<>(this, manager.nextRefId(), Instant.now());
            liveRefs.add(ref);
            activeRefs++;
            manager.onAcquire();
            return ref;
        }
    }

    void releaseRef(ResourceRef<T> ref) {
        Runnable action = null;
        synchronized (lock) {
            if (ref.isReleasedInternal()) {
                throw new ResourceReleasedException(
                        "Reference #" + ref.id() + " to resource '" + name
                                + "' was already released; each reference must be released exactly once");
            }
            ref.markReleased();
            liveRefs.remove(ref);
            activeRefs--;
            manager.onRelease();
            if (activeRefs == 0) {
                released = true;
                resource = null; // drop the strong reference so GC can reclaim it
                action = releaseAction;
            }
        }
        // Run the release action outside the lock; the `released` flag
        // guarantees it runs exactly once.
        if (action != null) {
            try {
                action.run();
            } finally {
                manager.onResourceReleased();
            }
        }
    }

    T resourceFor(ResourceRef<T> ref) {
        synchronized (lock) {
            if (ref.isReleasedInternal()) {
                throw new ResourceReleasedException(
                        "Reference #" + ref.id() + " to resource '" + name
                                + "' was already released; cannot access the resource through it");
            }
            T current = resource;
            if (current == null) {
                throw new IllegalStateException("Resource '" + name + "' has been released");
            }
            return current;
        }
    }

    boolean isRefReleased(ResourceRef<T> ref) {
        synchronized (lock) {
            return ref.isReleasedInternal();
        }
    }

    void collectLeaks(Instant now, Duration threshold, List<LeakReport> out) {
        synchronized (lock) {
            for (ResourceRef<T> ref : liveRefs) {
                Duration age = Duration.between(ref.acquiredAt(), now);
                if (age.compareTo(threshold) > 0) {
                    out.add(new LeakReport(name, ref.id(), registrationLocation, ref.acquiredAt(), age));
                }
            }
        }
    }

    /** Force-releases the resource (used when the manager is closed). */
    void forceRelease() {
        Runnable action = null;
        synchronized (lock) {
            if (!released) {
                released = true;
                for (ResourceRef<T> ref : liveRefs) {
                    ref.markReleased();
                    manager.onRelease();
                }
                liveRefs.clear();
                activeRefs = 0;
                resource = null;
                action = releaseAction;
            }
        }
        if (action != null) {
            try {
                action.run();
            } finally {
                manager.onResourceReleased();
            }
        }
    }
}
