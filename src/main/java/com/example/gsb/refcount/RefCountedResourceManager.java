package com.example.gsb.refcount;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registry and lifecycle manager for reference-counted resources.
 *
 * <ul>
 *   <li>{@link #register} returns a {@link ResourceHandle}; each
 *       {@link ResourceHandle#acquire()} increments the count and each
 *       {@link ResourceRef#release()} decrements it.</li>
 *   <li>When a handle's count returns to zero its release action runs exactly
 *       once and the strong reference to the resource is dropped.</li>
 *   <li>{@link #detectLeaks()} reports references held longer than the
 *       configured threshold, including where the resource was registered.</li>
 *   <li>{@link #stats()} exposes counters, including how many released
 *       resources have actually been garbage collected (observed through
 *       weak references and a reference queue).</li>
 * </ul>
 */
public final class RefCountedResourceManager implements AutoCloseable {

    private static final Duration DEFAULT_LEAK_THRESHOLD = Duration.ofMinutes(5);

    private final Duration leakThreshold;
    private final List<ResourceHandle<?>> handles = new CopyOnWriteArrayList<>();

    private final ReferenceQueue<Object> reclaimedQueue = new ReferenceQueue<>();
    private final Set<WeakReference<Object>> trackedResources = ConcurrentHashMap.newKeySet();

    private final AtomicLong refIdSequence = new AtomicLong();
    private final AtomicLong totalAcquired = new AtomicLong();
    private final AtomicLong totalReleased = new AtomicLong();
    private final AtomicLong resourcesReleased = new AtomicLong();
    private final AtomicLong resourcesReclaimed = new AtomicLong();

    public RefCountedResourceManager() {
        this(DEFAULT_LEAK_THRESHOLD);
    }

    public RefCountedResourceManager(Duration leakThreshold) {
        this.leakThreshold = Objects.requireNonNull(leakThreshold, "leakThreshold");
    }

    /**
     * Registers a resource together with the action that releases it. The
     * release action is guaranteed to run at most once.
     */
    public <T> ResourceHandle<T> register(String name, T resource, Runnable releaseAction) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(releaseAction, "releaseAction");
        ResourceHandle<T> handle =
                new ResourceHandle<>(this, name, resource, releaseAction, captureRegistrationLocation());
        handles.add(handle);
        trackedResources.add(new WeakReference<>(resource, reclaimedQueue));
        return handle;
    }

    /**
     * Returns one report per reference that has been held longer than the
     * configured leak threshold without the count returning to zero.
     */
    public List<LeakReport> detectLeaks() {
        Instant now = Instant.now();
        List<LeakReport> leaks = new ArrayList<>();
        for (ResourceHandle<?> handle : handles) {
            handle.collectLeaks(now, leakThreshold, leaks);
        }
        return List.copyOf(leaks);
    }

    /** Snapshot of the manager's counters. */
    public ResourceStats stats() {
        drainReclaimedQueue();
        long acquired = totalAcquired.get();
        long released = totalReleased.get();
        return new ResourceStats(
                handles.size(),
                acquired,
                released,
                acquired - released,
                resourcesReleased.get(),
                resourcesReclaimed.get());
    }

    /** Force-releases every resource that is still alive. */
    @Override
    public void close() {
        for (ResourceHandle<?> handle : handles) {
            handle.forceRelease();
        }
    }

    long nextRefId() {
        return refIdSequence.incrementAndGet();
    }

    void onAcquire() {
        totalAcquired.incrementAndGet();
    }

    void onRelease() {
        totalReleased.incrementAndGet();
    }

    void onResourceReleased() {
        resourcesReleased.incrementAndGet();
    }

    private void drainReclaimedQueue() {
        Reference<?> ref;
        while ((ref = reclaimedQueue.poll()) != null) {
            if (trackedResources.remove(ref)) {
                resourcesReclaimed.incrementAndGet();
            }
        }
    }

    private static String captureRegistrationLocation() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            String className = element.getClassName();
            if (!className.startsWith("java.")
                    && !className.startsWith("jdk.")
                    && !className.startsWith("sun.")
                    && !isImplementationClass(className)) {
                return element.toString();
            }
        }
        return "unknown";
    }

    private static boolean isImplementationClass(String className) {
        return className.equals(RefCountedResourceManager.class.getName())
                || className.equals(ResourceHandle.class.getName())
                || className.equals(ResourceRef.class.getName());
    }
}
