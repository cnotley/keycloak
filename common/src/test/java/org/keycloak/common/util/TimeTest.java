package org.keycloak.common.util;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class TimeTest {

    @Test
    public void testTemporaryOffsetScope() throws Exception {
        long baseMillis = System.currentTimeMillis();
        int base = (int) (baseMillis / 1000);
        try (Time.OffsetScope s1 = Time.withOffset(10)) {
            int diff = Time.currentTime() - base;
            assertTrue(diff >= 10 && diff <= 11);
            try (Time.OffsetScope s2 = Time.withOffset(5)) {
                diff = Time.currentTime() - base;
                assertTrue(diff >= 15 && diff <= 16);
            }
            diff = Time.currentTime() - base;
            assertTrue(diff >= 10 && diff <= 12);
        }
        int diff = Time.currentTime() - base;
        assertTrue(diff >= 0 && diff <= 2);
    }

    @Test
    public void testOutOfOrderCloseAndThreadPropagation() throws Exception {
        int base = Time.currentTime();
        Time.OffsetScope s1 = Time.withOffset(3);
        Time.OffsetScope s2 = Time.withOffset(4);
        // Close outer first
        s1.close();
        assertTrue(Time.currentTime() - base >= 4);
        s2.close();
        assertTrue(Time.currentTime() - base < 2);
        // ensure thread propagation
        try (Time.OffsetScope s3 = Time.withOffset(5)) {
            AtomicInteger val = new AtomicInteger();
            Thread t = new Thread(() -> val.set(Time.currentTime()));
            t.start();
            t.join();
            assertTrue(val.get() - base >= 5);
        }
    }

    @Test
    public void testDetectSkewedNodesAndConsensus() {
        long now = System.currentTimeMillis() / 1000L;
        List<Map<String, Object>> samples = new ArrayList<>();
        Map<String, Object> a = new HashMap<>();
        a.put("nodeId", "a");
        a.put("timestamp", now + 5);
        samples.add(a);
        Map<String, Object> b = new HashMap<>();
        b.put("nodeId", "b");
        b.put("timestamp", now);
        samples.add(b);
        Set<String> skewed = Time.detectSkewedNodes(samples);
        assertTrue(skewed.contains("a"));
        assertFalse(skewed.contains("b"));

        List<Long> ts = new ArrayList<>();
        ts.add(now + 2);
        ts.add(now + 2);
        ts.add(now - 100); // outlier
        long offset = Time.computeConsensusOffset(ts);
        assertEquals(2, offset);
    }
}

