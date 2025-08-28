package org.keycloak.common.util;

import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;

import org.junit.Test;

/**
 * Tests for thread local time offsets in {@link Time}.
 */
public class TimeTest {

    @Test
    public void testNestedOffsetsAndRestoration() {
        long base = Time.currentTimeMillis();
        try (Time.OffsetContext oc1 = Time.withOffset(2)) { // +2s
            long innerBase = Time.currentTimeMillis();
            assertTrue(innerBase - base >= 2000);

            try (Time.OffsetContext oc2 = Time.withOffset(-1)) { // now +1s
                long inner = Time.currentTimeMillis();
                long diff = inner - base;
                assertTrue(diff >= 1000 && diff < 2000 + 1000);
            }

            long after = Time.currentTimeMillis();
            long diff = after - base;
            assertTrue(diff >= 2000 && diff < 3000);
        }

        long end = Time.currentTimeMillis();
        assertTrue(end - base < 1000); // restored
    }

    @Test
    public void testIsolationAndInheritance() throws Exception {
        long base = Time.currentTimeMillis();
        CountDownLatch latch = new CountDownLatch(1);
        final long[] unrelated = new long[1];

        Thread other = new Thread(() -> {
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            unrelated[0] = Time.currentTimeMillis();
        });
        other.start();

        final long[] childTime = new long[1];
        try (Time.OffsetContext ctx = Time.withOffset(5)) { // +5s
            Thread child = new Thread(() -> childTime[0] = Time.currentTimeMillis());
            child.start();
            latch.countDown();
            child.join();
            other.join();
            long parent = Time.currentTimeMillis();
            assertTrue(Math.abs(childTime[0] - parent) < 1000); // child inherited
            assertTrue(Math.abs(unrelated[0] - base) < 1000); // unrelated unaffected
        }

        long after = Time.currentTimeMillis();
        assertTrue(after - base < 1000); // offset restored
    }
}
