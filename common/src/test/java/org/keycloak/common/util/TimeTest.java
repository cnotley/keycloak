package org.keycloak.common.util;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class TimeTest {

    @Test
    public void threadLocalOffsetIsIsolatedAndInherited() throws Exception {
        int baseline = Time.currentTime();
        final int[] recorded = new int[2];

        Thread preCreated = new Thread(() -> recorded[0] = Time.currentTime());

        try (Time.OffsetContext ctx = Time.withOffset(20)) {
            // current thread sees offset
            int current = Time.currentTime();
            assertTrue(Math.abs((current - baseline) - 20) <= 1);

            // nested offset
            try (Time.OffsetContext nested = Time.withOffset(-5)) {
                int nestedTime = Time.currentTime();
                assertTrue(Math.abs((nestedTime - baseline) - 15) <= 1);
            }
            int afterNested = Time.currentTime();
            assertTrue(Math.abs((afterNested - baseline) - 20) <= 1);

            // child thread created after offset inherits it
            Thread child = new Thread(() -> recorded[1] = Time.currentTime());
            child.start();
            child.join();
            assertTrue(Math.abs((recorded[1] - baseline) - 20) <= 1);

            // thread created before offset does not inherit
            preCreated.start();
            preCreated.join();
            assertTrue(Math.abs(recorded[0] - baseline) <= 1);
        }

        // offset removed
        int restored = Time.currentTime();
        assertTrue(Math.abs(restored - baseline) <= 1);
    }
}
