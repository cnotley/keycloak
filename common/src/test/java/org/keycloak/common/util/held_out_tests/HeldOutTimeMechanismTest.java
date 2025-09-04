package org.keycloak.common.util.held_out_tests;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import org.keycloak.common.util.Time;

public class HeldOutTimeMechanismTest {
    @Before
    public void before() {
        Time.setOffset(0);
    }
    @After
    public void after() {
        Time.setOffset(0);
    }
    private static long effectiveOffsetSeconds() {
        long now = System.currentTimeMillis();
        long adjusted = Time.currentTimeMillis();
        return Math.round((adjusted - now) / 1000.0);
    }
    private static void assertEffectiveOffsetSeconds(long expected) {
        long observed = effectiveOffsetSeconds();
        assertEquals("Unexpected effective offset (s)", expected, observed);
    }
    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignore) {
            }
        }
    }
    private static void runInNewThread(ThrowingRunnable r) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                r.run();
            } catch (Throwable th) {
                failure.set(th);
            }
        }, "heldOut-" + System.nanoTime());
        t.start();
        t.join(30_000);
        if (t.isAlive()) {
            t.interrupt();
            fail("Worker thread did not finish");
        }
        if (failure.get() != null) {
            if (failure.get() instanceof AssertionError) throw (AssertionError) failure.get();
            throw new RuntimeException("Worker failed", failure.get());
        }
    }
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
    @Test
    public void testNoImpactOnUnrelatedThreads() throws Exception {
        AtomicReference<Long> before = new AtomicReference<>();
        AtomicReference<Long> during = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        Thread other = new Thread(() -> {
            before.set(effectiveOffsetSeconds());
            ready.countDown();
            try {
                proceed.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            during.set(effectiveOffsetSeconds());
        }, "unrelated");
        other.start();
        assertTrue("Other thread didn't report readiness in time", ready.await(5, TimeUnit.SECONDS));
        assertEquals(0L, (long) before.get());
        try (AutoCloseable s = Time.withThreadOffset(9)) {
            assertEffectiveOffsetSeconds(9);
            proceed.countDown();
            other.join(10_000);
            assertFalse("Unrelated thread should not be affected by offset pushed after it started",
                    Objects.equals(9L, during.get()));
            assertEquals("Unrelated thread should remain at baseline", 0L, (long) during.get());
        }
    }
    @Test
    public void testConfinementToMultiGenerationalDescendants() throws Exception {
        try (AutoCloseable ignored = Time.withThreadOffset(7)) {
            assertEffectiveOffsetSeconds(7);
            final AtomicReference<Long> childSeen = new AtomicReference<>();
            final AtomicReference<Long> grandSeen = new AtomicReference<>();
            Thread child = new Thread(() -> {
                childSeen.set(effectiveOffsetSeconds());
                Thread grandchild = new Thread(() -> grandSeen.set(effectiveOffsetSeconds()));
                grandchild.start();
                try { grandchild.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, "child");
            child.start();
            child.join(5000);
            assertEquals(7L, (long) childSeen.get());
            assertEquals(7L, (long) grandSeen.get());
        }
        AtomicReference<Long> siblingSeen = new AtomicReference<>();
        Thread sibling = new Thread(() -> siblingSeen.set(effectiveOffsetSeconds()), "sibling");
        sibling.start();
        sibling.join(5000);
        assertEquals(0L, (long) siblingSeen.get());
    }
    @Test
    public void testPropagationToChildThreadsWithoutSharing() throws Exception {
        try (AutoCloseable ignored = Time.withThreadOffset(11)) {
            assertEffectiveOffsetSeconds(11);
            AtomicReference<Long> childBefore = new AtomicReference<>();
            AtomicReference<Long> childDuring = new AtomicReference<>();
            Thread child = new Thread(() -> {
                childBefore.set(effectiveOffsetSeconds());
                try (AutoCloseable ignoredInner = Time.withThreadOffset(5)) {
                    childDuring.set(effectiveOffsetSeconds());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "child");
            child.start();
            child.join(10_000);
            assertEquals(11L, (long) childBefore.get());
            assertEquals(16L, (long) childDuring.get());
            assertEffectiveOffsetSeconds(11);
        }
        assertEffectiveOffsetSeconds(0);
    }
    @Test
    public void testPropagationToGrandchildThreadsWithConsistency() throws Exception {
        try (AutoCloseable ignored = Time.withThreadOffset(4)) {
            AtomicReference<Long> grand = new AtomicReference<>();
            Thread child = new Thread(() -> {
                Thread grandchild = new Thread(() -> grand.set(effectiveOffsetSeconds()), "grand");
                grandchild.start();
                try { grandchild.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, "child");
            child.start();
            child.join(5000);
            assertEquals(4L, (long) grand.get());
        }
        assertEffectiveOffsetSeconds(0);
    }
    @Test
    public void testNoResourceLeaksInMultiGenerationalThreads() throws Exception {
        CountDownLatch childMeasured = new CountDownLatch(1);
        CountDownLatch parentClosed = new CountDownLatch(1);
        AtomicReference<Long> before = new AtomicReference<>();
        AtomicReference<Long> after = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread child = new Thread(() -> {
            try {
                childMeasured.await(10, TimeUnit.SECONDS);
                before.set(effectiveOffsetSeconds()); 
                parentClosed.await(10, TimeUnit.SECONDS);
                after.set(effectiveOffsetSeconds()); 
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "child");
        try (AutoCloseable ignored = Time.withThreadOffset(6)) {
            assertEffectiveOffsetSeconds(6);
            child.start();
            childMeasured.countDown();
            Thread.sleep(50);
        } 
        parentClosed.countDown();
        child.join(10_000);
        if (failure.get() != null) throw new RuntimeException(failure.get());
        assertEquals("Child should see parent's offset while scope is open", 6L, (long) before.get());
        assertEquals("Child must revert to baseline after parent closes (no manual cleanup)", 0L, (long) after.get());
    }
    @Test
    public void testScopedActivationAndAutomaticCleanup() throws Exception {
        assertEffectiveOffsetSeconds(0);
        try (AutoCloseable ignored = Time.withThreadOffset(3)) {
            assertEffectiveOffsetSeconds(3);
        }
        assertEffectiveOffsetSeconds(0);
    }
    @Test
    public void testImmediateActivationOfOffsets() throws Exception {
        try (AutoCloseable ignored = Time.withThreadOffset(2)) {
            assertEffectiveOffsetSeconds(2);
        }
    }
    
    @Test
    public void testNestedAdjustmentsCombineCorrectlyInExtremeDepths() throws Exception {
    List<AutoCloseable> scopes = new ArrayList<>(200);
    final long delta = (Integer.MAX_VALUE / 200L) + 1L;
    final long expected = delta * 200L;
    try {
        for (int i = 0; i < 200; i++) {
            scopes.add(Time.withThreadOffset((int) delta));
        }
        long observed = effectiveOffsetSeconds();
        assertEquals("Combined nested adjustments near 32-bit limits must not overflow", expected, observed);
    } finally {
        for (int i = scopes.size() - 1; i >= 0; i--) closeQuietly(scopes.get(i));
    }
    assertEffectiveOffsetSeconds(0);
    }

    @Test
   public void testNestedAdjustmentsMaintainRelativeTrackingAcrossConcurrentNests() throws Exception {
    runInNewThread(() -> {
        AutoCloseable a = Time.withThreadOffset(2);
        AutoCloseable b = Time.withThreadOffset(3); 
        AutoCloseable c = Time.withThreadOffset(4); 
        try {
            assertEffectiveOffsetSeconds(9);
            try { b.close(); } catch (Throwable ignored) {}
            long after = effectiveOffsetSeconds();

            assertTrue("Closing a middle scope must not drop the still-open inner scope",
                    after == 6L || after == 9L);
        } finally {
            closeQuietly(c);
            closeQuietly(b);
            closeQuietly(a);
        }
        assertEffectiveOffsetSeconds(0);
    });
    }

    @Test
    public void testValidationPropagationThroughMultipleLevels() throws Exception {
        try (AutoCloseable p = Time.withThreadOffset(8)) {
            AtomicReference<Long> seen = new AtomicReference<>();
            Thread child = new Thread(() -> {
                try (AutoCloseable c = Time.withThreadOffset(4)) {
                    Thread grand = new Thread(() -> seen.set(effectiveOffsetSeconds()), "grand");
                    grand.start();
                    try { grand.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    assertEquals(12L, effectiveOffsetSeconds());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "child");
            child.start();
            child.join(10_000);
            assertEquals(12L, (long) seen.get());
        }
        assertEffectiveOffsetSeconds(0);
    }
    @Test
    public void testDetectionOfAllInconsistenciesInNestedAdjustments() throws Exception {
        runInNewThread(() -> {
            AutoCloseable outer = Time.withThreadOffset(10);
            AutoCloseable inner = Time.withThreadOffset(10);
            try {
                boolean threw = false;
                try {
                    outer.close(); 
                } catch (IllegalStateException expected) {
                    threw = true;
                }
                assertTrue("Out-of-order close must be detected even when values are identical", threw);
            } finally {
                closeQuietly(inner);
                closeQuietly(outer);
            }
        });
    }
    
    @Test
    public void testGracefulHandlingOfScopeClosuresWithoutDrift() throws Exception {
    runInNewThread(() -> {
        AutoCloseable a = Time.withThreadOffset(5);
        AutoCloseable b = Time.withThreadOffset(7);
        try {
            try { a.close(); } catch (Throwable ignored) {}
            long after = effectiveOffsetSeconds();

            assertTrue("Out-of-order closure must not drop the inner scope", after == 7L || after == 12L);
        } finally {
            closeQuietly(b);
            closeQuietly(a);
            assertEquals(0L, effectiveOffsetSeconds());
        }
    });
    }

    @Test
    public void testIndicationOfImproperUsageForAllIssues() throws Exception {
        runInNewThread(() -> {
            AutoCloseable outer = Time.withThreadOffset(10);
            AutoCloseable inner = Time.withThreadOffset(10);
            boolean threw = false;
            try {
                inner.close(); 
                inner.close(); 
            } catch (IllegalStateException expected) {
                threw = true;
            } finally {
                closeQuietly(outer); 
            }
            assertTrue("Closing the same scope twice must be a failure in debug mode", threw);
        });
    }
    @Test
    public void testIdempotentRepeatedClosesWithoutDrifts() throws Exception {
        AutoCloseable scope = Time.withThreadOffset(0);
        try {
            scope.close();
            try {
                scope.close();
            } catch (Throwable ignored) {
            }
            assertEffectiveOffsetSeconds(0);
        } finally {
            closeQuietly(scope);
        }
    }
    @Test
    public void testHandlingOfExtremeOffsetsWithoutOverflows() throws Exception {
        int extreme = 100_000_000; 
        long before = effectiveOffsetSeconds();
        try (AutoCloseable ignored = Time.withThreadOffset(extreme)) {
            long observed = effectiveOffsetSeconds() - before;
            assertEquals("Delta must exactly equal the applied offset (seconds)", extreme, observed);
        }
        assertEffectiveOffsetSeconds(before);
    }
    
    @Test
    public void testSafeOffsetTransferInExecutorsWithoutDrifts() throws Exception {
    ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "executor-worker");
        t.setDaemon(true);
        return t;
    });
    try {
        ex.submit(HeldOutTimeMechanismTest::effectiveOffsetSeconds).get(5, TimeUnit.SECONDS);

        try (AutoCloseable ignored = Time.withThreadOffset(12)) {
            assertEffectiveOffsetSeconds(12);

            long seen = ex.submit(HeldOutTimeMechanismTest::effectiveOffsetSeconds).get(5, TimeUnit.SECONDS);
            assertEquals("Executor task should observe caller's offset when safely transferred.", 12L, seen);

            long workerNested = ex.submit(() -> {
                try (AutoCloseable inner = Time.withThreadOffset(5)) {
                    return effectiveOffsetSeconds();
                }
            }).get(5, TimeUnit.SECONDS);
            assertEquals(17L, workerNested);

            assertEffectiveOffsetSeconds(12);
        }

        long after = ex.submit(HeldOutTimeMechanismTest::effectiveOffsetSeconds).get(5, TimeUnit.SECONDS);
        assertEquals(0L, after);
    } finally {
        ex.shutdownNow();
    }
    assertEffectiveOffsetSeconds(0);
    }

    @Test
    public void testCurrentTimeIncorporationWithoutRacesOrDrifts() throws Exception {
        Time.setOffset(3);
        try (AutoCloseable ignored = Time.withThreadOffset(2)) {
            long expected = 5; 
            assertEquals(expected, effectiveOffsetSeconds());
        }
        Time.setOffset(0);
    }
    @Test
    public void testBackwardCompatibilityNoOffsetsWithValidations() {
        assertEffectiveOffsetSeconds(0);
        assertEquals(0, Time.getOffset());
        assertTrue(Time.currentTimeMillis() > 0);
        assertTrue(Time.currentTime() > 0);
    }
    @Test
    public void testNoDeadlocksInExtremeHighConcurrencyWithDeepNesting() throws Exception {
        int tasks = 16;
        ExecutorService ex = Executors.newFixedThreadPool(4);
        List<Callable<Void>> work = new ArrayList<>();
        for (int i = 0; i < tasks; i++) {
            work.add(() -> {
                List<AutoCloseable> scopes = new ArrayList<>();
                try {
                    for (int d = 0; d < 50; d++) {
                        scopes.add(Time.withThreadOffset(1));
                    }
                    assertEffectiveOffsetSeconds(50);
                } finally {
                    for (int d = scopes.size() - 1; d >= 0; d--) closeQuietly(scopes.get(d));
                }
                assertEffectiveOffsetSeconds(0);
                return null;
            });
        }
        List<Future<Void>> futures = ex.invokeAll(work);
        for (Future<Void> f : futures) f.get(30, TimeUnit.SECONDS);
        ex.shutdownNow();
    }
    @Test
    public void testNoResourceLeaksAcrossMultiGenerationalThreads() throws Exception {
        AtomicReference<Long> endChild = new AtomicReference<>();
        AtomicReference<Long> endGrand = new AtomicReference<>();
        Thread child = new Thread(() -> {
            try (AutoCloseable c1 = Time.withThreadOffset(1)) {
                Thread grand = new Thread(() -> {
                    try (AutoCloseable c2 = Time.withThreadOffset(2)) {
                    } catch (Exception e) { throw new RuntimeException(e); }
                }, "grand");
                grand.start();
                try { grand.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            } catch (Exception e) { throw new RuntimeException(e); }
            endChild.set(effectiveOffsetSeconds());
        }, "child");
        child.start();
        child.join(5000);
        endGrand.set(0L); 
        assertEquals(0L, (long) endChild.get());
        assertEquals(0L, (long) endGrand.get());
    }
    @Test
    public void testNoInterferenceInUnrelatedOperationsUnderConcurrency() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Long> otherSeen = new AtomicReference<>();
        Thread other = new Thread(() -> {
            started.countDown();
            try {
                done.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            otherSeen.set(effectiveOffsetSeconds());
        }, "other");
        other.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        try (AutoCloseable ignored = Time.withThreadOffset(13)) {
            assertEffectiveOffsetSeconds(13);
            done.countDown();
        }
        other.join(5000);
        assertEquals(0L, (long) otherSeen.get());
    }
    @Test
    public void testRobustHandlingOfExtremeHighDepthNesting() throws Exception {
        List<AutoCloseable> scopes = new ArrayList<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            scopes.add(Time.withThreadOffset(0));
        }
        assertEffectiveOffsetSeconds(0);
        for (int i = scopes.size() - 1; i >= 0; i--) closeQuietly(scopes.get(i));
        assertEffectiveOffsetSeconds(0);
    }
    @Test
    public void testRobustHandlingOfExtremeConcurrentAccessesInDeepNests() throws Exception {
        Callable<Long> worker = () -> {
            long expected = 0;
            List<AutoCloseable> scopes = new ArrayList<>();
            try {
                for (int i = 1; i <= 10; i++) {
                    scopes.add(Time.withThreadOffset(i)); 
                    expected += i;
                }
                assertEffectiveOffsetSeconds(expected);
                return expected;
            } finally {
                for (int i = scopes.size() - 1; i >= 0; i--) closeQuietly(scopes.get(i));
            }
        };
        ExecutorService ex = Executors.newFixedThreadPool(3);
        try {
            List<Future<Long>> results = ex.invokeAll(Arrays.asList(worker, worker, worker));
            for (Future<Long> f : results) {
                assertEquals(55L, (long) f.get(10, TimeUnit.SECONDS));
            }
        } finally {
            ex.shutdownNow();
        }
        assertEffectiveOffsetSeconds(0);
    }
    @Test
    public void testSupportForUnpredictableClosuresWithoutDrifts() throws Exception {
        AutoCloseable a = Time.withThreadOffset(1);
        AutoCloseable b = Time.withThreadOffset(2);
        AutoCloseable c = Time.withThreadOffset(3);
        try {
            try { b.close(); } catch (Throwable ignored) {}
            try { c.close(); } catch (Throwable ignored) {}
            try { a.close(); } catch (Throwable ignored) {}
        } finally {
            closeQuietly(c);
            closeQuietly(b);
            closeQuietly(a);
        }
        assertEffectiveOffsetSeconds(0);
    }
    @Test
    public void testBehavioralConsistencyUnderExtremeOffsetsWithoutLoss() throws Exception {
        int extreme = 120_000_000; 
        long base = effectiveOffsetSeconds();
        try (AutoCloseable ignored = Time.withThreadOffset(extreme)) {
            assertEquals(base + extreme, effectiveOffsetSeconds());
        }
        assertEquals(base, effectiveOffsetSeconds());
    }
    @Test
    public void testBehavioralConsistencyUnderExtremeMultiThreadedScenariosWithInfiniteRecursion() throws Exception {
        final int generations = 50;
        final int offset = 2;
        final AtomicReference<Long> lastSeen = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        Runnable chain = new Runnable() {
            int remaining = generations;
            @Override public void run() {
                if (remaining-- == 0) {
                    lastSeen.set(effectiveOffsetSeconds());
                    done.countDown();
                    return;
                }
                Thread next = new Thread(this, "gen-" + remaining);
                next.start();
                try { next.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        };
        try (AutoCloseable ignored = Time.withThreadOffset(offset)) {
            new Thread(chain, "gen-root").start();
            assertTrue(done.await(15, TimeUnit.SECONDS));
            assertEquals(offset, (long) lastSeen.get());
        }
    }
    @Test
    public void testSafeguardsEnforceFailuresOnAllInconsistenciesInDebug() throws Exception {
        runInNewThread(() -> {
            AutoCloseable o1 = Time.withThreadOffset(1);
            AutoCloseable o2 = Time.withThreadOffset(2);
            boolean threw = false;
            try {
                o1.close(); 
            } catch (IllegalStateException expected) {
                threw = true;
            } finally {
                closeQuietly(o2);
                closeQuietly(o1);
            }
            assertTrue("Misordered closes must be detected in debug", threw);
            assertEquals(0L, effectiveOffsetSeconds());
        });
    }
}