package com.example.gsb.refcount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RefCountedResourceManagerTest {

    private RefCountedResourceManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void acquireIncrementsAndReleaseDecrementsCount() {
        manager = new RefCountedResourceManager(Duration.ofMinutes(5));
        ResourceHandle<String> handle = manager.register("file-handle", "fd-7", () -> { });

        assertThat(handle.activeRefCount()).isZero();

        ResourceRef<String> first = handle.acquire();
        ResourceRef<String> second = handle.acquire();
        assertThat(handle.activeRefCount()).isEqualTo(2);
        assertThat(first.get()).isEqualTo("fd-7");
        assertThat(second.get()).isEqualTo("fd-7");

        first.release();
        assertThat(handle.activeRefCount()).isEqualTo(1);
        assertThat(handle.isReleased()).isFalse();

        second.release();
        assertThat(handle.activeRefCount()).isZero();
        assertThat(handle.isReleased()).isTrue();
    }

    @Test
    void resourceIsReleasedExactlyOnceWhenCountReachesZero() {
        AtomicInteger releaseActions = new AtomicInteger();
        manager = new RefCountedResourceManager(Duration.ofMinutes(5));
        ResourceHandle<String> handle =
                manager.register("buffer", "buf-1", releaseActions::incrementAndGet);

        ResourceRef<String> first = handle.acquire();
        ResourceRef<String> second = handle.acquire();
        ResourceRef<String> third = handle.acquire();

        first.release();
        second.release();
        assertThat(releaseActions).hasValue(0);
        assertThat(handle.isReleased()).isFalse();

        third.release();
        assertThat(releaseActions).hasValue(1);
        assertThat(handle.isReleased()).isTrue();

        assertThatThrownBy(handle::acquire)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("buffer")
                .hasMessageContaining("released");

        // Closing the manager afterwards must not run the action a second time.
        manager.close();
        assertThat(releaseActions).hasValue(1);
        assertThat(manager.stats().resourcesReleased()).isEqualTo(1);
    }

    @Test
    void releasingTheSameReferenceTwiceFailsWithClearError() {
        manager = new RefCountedResourceManager(Duration.ofMinutes(5));
        ResourceHandle<String> handle = manager.register("socket", "sock-3", () -> { });

        ResourceRef<String> ref = handle.acquire();
        ref.release();

        assertThatThrownBy(ref::release)
                .isInstanceOf(ResourceReleasedException.class)
                .hasMessageContaining("already released")
                .hasMessageContaining("socket")
                .hasMessageContaining("#" + ref.id());

        assertThatThrownBy(ref::get)
                .isInstanceOf(ResourceReleasedException.class)
                .hasMessageContaining("already released");

        assertThat(ref.isReleased()).isTrue();
    }

    @Test
    void leakDetectionReportsLongLivedReferencesWithRegistrationLocation() throws Exception {
        manager = new RefCountedResourceManager(Duration.ofMillis(50));
        ResourceHandle<String> handle = manager.register("db-connection", "conn-1", () -> { });

        ResourceRef<String> leaked = handle.acquire();
        Thread.sleep(150);

        List<LeakReport> leaks = manager.detectLeaks();
        assertThat(leaks).hasSize(1);
        LeakReport report = leaks.get(0);
        assertThat(report.resourceName()).isEqualTo("db-connection");
        assertThat(report.referenceId()).isEqualTo(leaked.id());
        assertThat(report.registrationLocation())
                .contains("RefCountedResourceManagerTest")
                .contains("leakDetectionReportsLongLivedReferencesWithRegistrationLocation");
        assertThat(report.age()).isGreaterThan(Duration.ofMillis(50));

        leaked.release();
        assertThat(manager.detectLeaks()).isEmpty();
    }

    @Test
    void concurrentAcquireAndReleaseKeepsExactCountWithoutPrematureRelease() throws Exception {
        AtomicInteger releaseActions = new AtomicInteger();
        manager = new RefCountedResourceManager(Duration.ofMinutes(5));
        ResourceHandle<byte[]> handle =
                manager.register("shared-buffer", new byte[1024], releaseActions::incrementAndGet);

        // An owner reference keeps the count >= 1 for the duration of the storm,
        // so the resource must never be released while workers are active.
        ResourceRef<byte[]> owner = handle.acquire();

        int threads = 16;
        int iterations = 1_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < iterations; i++) {
                    try (ResourceRef<byte[]> ref = handle.acquire()) {
                        assertThat(ref.get()).hasSize(1024);
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // No premature release while the owner reference was held.
        assertThat(releaseActions).hasValue(0);
        assertThat(handle.isReleased()).isFalse();
        assertThat(handle.activeRefCount()).isEqualTo(1);

        owner.release();
        assertThat(releaseActions).hasValue(1);
        assertThat(handle.activeRefCount()).isZero();
        assertThat(handle.isReleased()).isTrue();

        ResourceStats stats = manager.stats();
        assertThat(stats.totalAcquired()).isEqualTo((long) threads * iterations + 1);
        assertThat(stats.totalReleased()).isEqualTo(stats.totalAcquired());
        assertThat(stats.activeReferences()).isZero();
    }

    @Test
    void releasedResourceCanBeGarbageCollectedAndIsObservedViaWeakReferences() throws Exception {
        manager = new RefCountedResourceManager(Duration.ofMinutes(5));
        AtomicInteger releaseActions = new AtomicInteger();

        Object resource = new Object();
        WeakReference<Object> weak = new WeakReference<>(resource);
        ResourceHandle<Object> handle =
                manager.register("temp-object", resource, releaseActions::incrementAndGet);
        resource = null; // drop our strong reference; the handle now holds the only one

        ResourceRef<Object> ref = handle.acquire();
        ref.release();
        assertThat(releaseActions).hasValue(1);
        assertThat(manager.stats().resourcesReleased()).isEqualTo(1);

        // Evidence 1: our own weak reference is cleared once GC reclaims the object.
        awaitCleared(weak);
        assertThat(weak.get()).isNull();

        // Evidence 2: the manager's weak-reference tracking reports the reclamation.
        ResourceStats stats = awaitReclaimed();
        assertThat(stats.resourcesReclaimed()).isEqualTo(1);
    }

    @Test
    void managerCloseReleasesRemainingResourcesExactlyOnce() {
        AtomicInteger releaseActions = new AtomicInteger();
        manager = new RefCountedResourceManager(Duration.ofMinutes(5));
        manager.register("never-acquired", "x", releaseActions::incrementAndGet);

        manager.close();
        manager.close();

        assertThat(releaseActions).hasValue(1);
        assertThat(manager.stats().resourcesReleased()).isEqualTo(1);
    }

    private static void awaitCleared(WeakReference<?> ref) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (ref.get() != null && System.nanoTime() < deadline) {
            System.gc();
            System.runFinalization();
            Thread.sleep(50);
        }
    }

    private ResourceStats awaitReclaimed() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        ResourceStats stats;
        do {
            System.gc();
            System.runFinalization();
            Thread.sleep(50);
            stats = manager.stats();
        } while (stats.resourcesReclaimed() == 0 && System.nanoTime() < deadline);
        return stats;
    }
}
