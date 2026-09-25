package com.example.gsb.refcount;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for reference-counted resources. Thread-safe.
 *
 * <p>Usage:
 * <pre>{@code
 * try (ResourceManager manager = new ResourceManager(Duration.ofSeconds(30))) {
 *     SharedResource<Handle> shared = manager.register(handle, Handle::close);
 *     try (ResourceRef<Handle> ref = shared.acquire()) {
 *         ref.get().doWork();
 *     }
 * }
 * }</pre>
 */
public final class ResourceManager implements AutoCloseable {
    private static final List<String> COMPONENT_CLASSES = List.of(
            ResourceManager.class.getName(), SharedResource.class.getName(), ResourceRef.class.getName());

    private final Map<SharedResource<?>, TrackedEntry> tracked = new ConcurrentHashMap<>();
    private final Duration leakThreshold;
    private final Clock clock;

    private record TrackedEntry(Instant registeredAt, List<StackTraceElement> registrationSite) {}

    public ResourceManager(Duration leakThreshold) {
        this(leakThreshold, Clock.systemUTC());
    }

    public ResourceManager(Duration leakThreshold, Clock clock) {
        this.leakThreshold = Objects.requireNonNull(leakThreshold);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Registers a resource with its release action and returns the countable handle. */
    public <T> SharedResource<T> register(T resource, Releaser<? super T> releaser) {
        return register(resource, releaser, String.valueOf(resource));
    }

    /** Registers a resource with an explicit description used in error messages and leak reports. */
    public <T> SharedResource<T> register(T resource, Releaser<? super T> releaser, String description) {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(releaser, "releaser");
        SharedResource<T> shared = new SharedResource<>(this, resource, releaser, description);
        tracked.put(shared, new TrackedEntry(clock.instant(), captureRegistrationSite()));
        return shared;
    }

    /** Number of resources currently tracked (registered and not yet fully released). */
    public int trackedCount() {
        return tracked.size();
    }

    /** Finds resources still holding references beyond the manager's configured leak threshold. */
    public List<LeakReport> detectLeaks() {
        return detectLeaks(leakThreshold);
    }

    /** Finds resources still holding references beyond the given threshold. */
    public List<LeakReport> detectLeaks(Duration threshold) {
        Instant now = clock.instant();
        List<LeakReport> leaks = new ArrayList<>();
        for (Map.Entry<SharedResource<?>, TrackedEntry> entry : tracked.entrySet()) {
            SharedResource<?> shared = entry.getKey();
            int refs = shared.refCount();
            if (refs <= 0) {
                continue;
            }
            Duration age = Duration.between(entry.getValue().registeredAt(), now);
            if (age.compareTo(threshold) > 0) {
                leaks.add(new LeakReport(shared.description(), refs,
                        entry.getValue().registeredAt(), age, entry.getValue().registrationSite()));
            }
        }
        return leaks;
    }

    void untrack(SharedResource<?> shared) {
        tracked.remove(shared);
    }

    /** Force-releases every still-tracked resource. Each releaser still runs at most once. */
    @Override
    public void close() {
        for (SharedResource<?> shared : List.copyOf(tracked.keySet())) {
            shared.forceRelease();
        }
    }

    private static List<StackTraceElement> captureRegistrationSite() {
        List<StackTraceElement> stack = new ArrayList<>(Arrays.asList(new Throwable().getStackTrace()));
        stack.removeIf(el -> COMPONENT_CLASSES.contains(el.getClassName()));
        return List.copyOf(stack);
    }
}
