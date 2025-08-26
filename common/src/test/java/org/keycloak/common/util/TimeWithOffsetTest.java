package org.keycloak.common.util;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Unit tests for {@link Time#withOffset(int)}.
 */
public class TimeWithOffsetTest {

    @Test
    public void testScopedOffset() throws Exception {
        int base = Time.currentTime();
        try (AutoCloseable c = Time.withOffset(5)) {
            assertEquals(base + 5, Time.currentTime());
        }
        assertEquals(base, Time.currentTime());
    }

    @Test
    public void testNestedAndThreadPropagation() throws Exception {
        int base = Time.currentTime();
        try (AutoCloseable c1 = Time.withOffset(2)) {
            try (AutoCloseable c2 = Time.withOffset(-1)) {
                assertEquals(base + 1, Time.currentTime());
                AtomicInteger v = new AtomicInteger();
                Thread t = new Thread(() -> v.set(Time.currentTime()));
                t.start();
                t.join();
                assertEquals(base + 1, v.get());
                ExecutorService exec = Executors.newSingleThreadExecutor();
                Future<?> f = exec.submit(Time.wrap(() -> v.set(Time.currentTime())));
                f.get();
                exec.shutdown();
                assertEquals(base + 1, v.get());
            }
            assertEquals(base + 2, Time.currentTime());
        }
        assertEquals(base, Time.currentTime());
    }
}
