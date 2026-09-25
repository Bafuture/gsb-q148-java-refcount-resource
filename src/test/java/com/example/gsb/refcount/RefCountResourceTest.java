package com.example.gsb.refcount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RefCountResourceTest {

    @Test
    void acquireIncrementsAndCloseDecrementsCount() {
        try (ResourceManager manager = new ResourceManager(Duration.ofMinutes(1))) {
            SharedResource<StringBuilder> shared =
                    manager.register(new StringBuilder("buf"), r -> {}, "buffer");

            assertThat(shared.refCount()).isZero();

            ResourceRef<StringBuilder> ref1 = shared.acquire();
            ResourceRef<StringBuilder> ref2 = shared.acquire();
            assertThat(shared.refCount()).isEqualTo(2);
            assertThat(ref1.get()).hasToString("buf");
            assertThat(ref2.get()).isSameAs(ref1.get());

            ref1.close();
            assertThat(shared.refCount()).isEqualTo(1);
            assertThat(shared.isReleased()).isFalse();

            ref2.close();
            assertThat(shared.refCount()).isZero();
            assertThat(shared.isReleased()).isTrue();
        }
    }

    @Test
    void releaserRunsExactlyOnceWhenCountReachesZero() {
        AtomicInteger releaseCalls = new AtomicInteger();
        try (ResourceManager manager = new ResourceManager(Duration.ofMinutes(1))) {
            SharedResource<Object> shared = manager.register(new Object(), r -> releaseCalls.incrementAndGet());

            ResourceRef<Object> ref1 = shared.acquire();
            ResourceRef<Object> ref2 = shared.acquire();
            ResourceRef<Object> ref3 = shared.acquire();

            ref1.close();
            ref2.close();
            assertThat(releaseCalls).hasValue(0);

            ref3.close();
            assertThat(releaseCalls).hasValue(1);

            // Force-release via manager close must not run the releaser a second time.
            manager.close();
            assertThat(releaseCalls).hasValue(1);
        }
    }

    @Test
    void doubleReleaseThrowsClearError() {
        try (ResourceManager manager = new ResourceManager(Duration.ofMinutes(1))) {
            SharedResource<Object> shared = manager.register(new Object(), r -> {});
            ResourceRef<Object> ref = shared.acquire();
            ref.close();

            assertThatThrownBy(ref::close)
                    .isInstanceOf(RefAlreadyReleasedException.class)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already been released")
                    .hasMessageContaining("exactly once");
        }
    }

    @Test
    void accessAfterReleaseThrows() {
        try (ResourceManager manager = new ResourceManager(Duration.ofMinutes(1))) {
            SharedResource<Object> shared = manager.register(new Object(), r -> {});
            ResourceRef<Object> ref = shared.acquire();
            ref.close();

            assertThatThrownBy(ref::get).isInstanceOf(RefAlreadyReleasedException.class);
            assertThatThrownBy(shared::acquire).isInstanceOf(ResourceReleasedException.class);
        }
    }

    @Test
    void leakDetectionReportsOutstandingRefsWithRegistrationSite() throws Exception {
        try (ResourceManager manager = new ResourceManager(Duration.ofMillis(50))) {
            SharedResource<Object> shared = manager.register(new Object(), r -> {}, "leaky-handle");
            ResourceRef<Object> leaked = shared.acquire();

            // Not yet past the threshold: no leak.
            assertThat(manager.detectLeaks()).isEmpty();

            Thread.sleep(120);

            List<LeakReport> leaks = manager.detectLeaks();
            assertThat(leaks).hasSize(1);
            LeakReport report = leaks.get(0);
            assertThat(report.description()).isEqualTo("leaky-handle");
            assertThat(report.outstandingRefs()).isEqualTo(1);
            assertThat(report.age()).isGreaterThan(Duration.ofMillis(50));
            // Registration location points back into this test class.
            assertThat(report.registrationLocation())
                    .contains("RefCountResourceTest")
                    .contains("leakDetectionReportsOutstandingRefsWithRegistrationSite");
            assertThat(report.toString()).contains("leaky-handle").contains("RefCountResourceTest");

            leaked.close();
            assertThat(manager.detectLeaks(Duration.ZERO)).isEmpty();
            assertThat(manager.trackedCount()).isZero();
        }
    }

    @Test
    void concurrentAcquireAndReleaseKeepsExactCountAndNeverReleasesEarly() throws Exception {
        int threads = 16;
        int iterations = 5_000;
        AtomicInteger releaseCalls = new AtomicInteger();

        try (ResourceManager manager = new ResourceManager(Duration.ofMinutes(1))) {
            SharedResource<Object> shared = manager.register(new Object(), r -> releaseCalls.incrementAndGet());

            // One long-lived reference keeps the resource alive during the churn.
            ResourceRef<Object> anchor = shared.acquire();

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            ResourceRef<Object> ref = shared.acquire();
                            assertThat(ref.get()).isNotNull();
                            ref.close();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

            // The anchor kept the count above zero, so the releaser must not have run.
            assertThat(releaseCalls).hasValue(0);
            assertThat(shared.refCount()).isEqualTo(1);
            assertThat(shared.isReleased()).isFalse();

            anchor.close();
            assertThat(shared.refCount()).isZero();
            assertThat(releaseCalls).hasValue(1);
        }
    }

    @Test
    void resourceBecomesGarbageCollectableAfterRelease() throws Exception {
        AtomicInteger releaseCalls = new AtomicInteger();
        WeakReference<byte[]> weak;
        ResourceManager manager = new ResourceManager(Duration.ofMinutes(1));
        try {
            byte[] buffer = new byte[64 * 1024];
            weak = new WeakReference<>(buffer);
            SharedResource<byte[]> shared = manager.register(buffer, r -> releaseCalls.incrementAndGet());

            ResourceRef<byte[]> ref = shared.acquire();
            ref.close();
            ref = null;
            buffer = null;

            // Statistics evidence: released, untracked, releaser ran once.
            assertThat(shared.isReleased()).isTrue();
            assertThat(manager.trackedCount()).isZero();
            assertThat(releaseCalls).hasValue(1);
            shared = null;
        } finally {
            manager.close();
        }

        // GC evidence: with no strong references left, the weak reference must clear.
        boolean collected = awaitCollection(weak, Duration.ofSeconds(10));
        assertThat(collected)
                .as("evidence: the released resource object is reclaimed by the GC")
                .isTrue();
        assertThat(weak.get()).isNull();
    }

    private static boolean awaitCollection(WeakReference<?> weak, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (weak.get() != null && System.nanoTime() < deadline) {
            System.gc();
            System.runFinalization();
            // Apply allocation pressure to encourage collection.
            byte[] pressure = new byte[256 * 1024];
            pressure[0] = 1;
            Thread.sleep(50);
        }
        return weak.get() == null;
    }
}
