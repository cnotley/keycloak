/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.common.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.lang.InheritableThreadLocal;

/**
 * Utility class for obtaining current time with optional offsets. The class supports a
 * global offset as well as temporary offsets that are active only within the current
 * thread and any of its children. Temporary offsets are established through the
 * {@link #withOffset(int)} method and are typically used with a try-with-resources
 * statement. Offsets are additive and nested scopes accumulate.
 *
 * <p>In addition a "consensus" offset can be applied to mitigate clock skew in clustered
 * environments. The consensus offset can be computed using {@link #computeConsensusOffset(List)}
 * and applied with {@link #setConsensusOffset(int)}. All calls to {@link #currentTime()} and
 * {@link #currentTimeMillis()} automatically include any active offsets.</p>
 */
public class Time {

    /** Global offset in seconds. */
    private static volatile int offset;

    /** Cluster wide consensus offset in seconds. */
    private static volatile int consensusOffset;

    /** Thread-local stack of offsets, propagated to child threads. */
    private static final InheritableThreadLocal<OffsetContext> THREAD_OFFSETS =
            new InheritableThreadLocal<OffsetContext>() {
                @Override
                protected OffsetContext childValue(OffsetContext parent) {
                    return parent == null ? null : parent.copy();
                }
            };

    private static class OffsetContext {
        Deque<Integer> offsets = new ArrayDeque<>();
        int total;

        OffsetContext copy() {
            OffsetContext c = new OffsetContext();
            c.offsets.addAll(this.offsets);
            c.total = this.total;
            return c;
        }

        void push(int o) {
            offsets.addLast(o);
            total += o;
        }

        void remove(int o) {
            if (offsets.removeFirstOccurrence(o)) {
                total -= o;
            }
        }
    }

    /**
     * Scope representing a temporary offset. Closing the scope will revert the offset.
     */
    public static class OffsetScope implements AutoCloseable {
        private final int value;
        private final OffsetContext context;
        private boolean closed = false;

        private OffsetScope(int value, OffsetContext context) {
            this.value = value;
            this.context = context;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            OffsetContext ctx = THREAD_OFFSETS.get();
            if (ctx != null) {
                ctx.remove(value);
                if (ctx.offsets.isEmpty()) {
                    THREAD_OFFSETS.remove();
                }
            }
        }
    }

    private static int threadOffset() {
        OffsetContext ctx = THREAD_OFFSETS.get();
        return ctx == null ? 0 : ctx.total;
    }

    private static int combinedOffset() {
        return offset + consensusOffset + threadOffset();
    }

    /**
     * Returns current time in seconds adjusted by all active offsets.
     */
    public static int currentTime() {
        return ((int) (System.currentTimeMillis() / 1000)) + combinedOffset();
    }

    /**
     * Returns current time in milliseconds adjusted by all active offsets.
     */
    public static long currentTimeMillis() {
        return System.currentTimeMillis() + (combinedOffset() * 1000L);
    }

    /**
     * Establishes a temporary offset for the current thread. The returned scope should be
     * closed to restore the previous state. Scopes are stackable and may be closed in any
     * order without side effects.
     *
     * @param seconds offset in seconds to apply
     * @return a scope that restores state on close
     */
    public static OffsetScope withOffset(int seconds) {
        OffsetContext ctx = THREAD_OFFSETS.get();
        if (ctx == null) {
            ctx = new OffsetContext();
            THREAD_OFFSETS.set(ctx);
        }
        ctx.push(seconds);
        return new OffsetScope(seconds, ctx);
    }

    /**
     * Wraps a runnable so that the current offset context is propagated when executed.
     */
    public static Runnable propagate(Runnable r) {
        OffsetContext parent = THREAD_OFFSETS.get();
        return () -> {
            OffsetContext old = THREAD_OFFSETS.get();
            if (parent != null) {
                THREAD_OFFSETS.set(parent.copy());
            } else {
                THREAD_OFFSETS.remove();
            }
            try {
                r.run();
            } finally {
                if (old != null) {
                    THREAD_OFFSETS.set(old);
                } else {
                    THREAD_OFFSETS.remove();
                }
            }
        };
    }

    /**
     * Wraps a callable so that the current offset context is propagated when executed.
     */
    public static <V> Callable<V> propagate(Callable<V> c) {
        OffsetContext parent = THREAD_OFFSETS.get();
        return () -> {
            OffsetContext old = THREAD_OFFSETS.get();
            if (parent != null) {
                THREAD_OFFSETS.set(parent.copy());
            } else {
                THREAD_OFFSETS.remove();
            }
            try {
                return c.call();
            } finally {
                if (old != null) {
                    THREAD_OFFSETS.set(old);
                } else {
                    THREAD_OFFSETS.remove();
                }
            }
        };
    }

    /**
     * Returns {@link Date} object, its value set to time
     * @param time Time in milliseconds since the epoch
     * @return see description
     */
    public static Date toDate(int time) {
        return new Date(time * 1000L);
    }

    /**
     * Returns {@link Date} object, its value set to time
     * @param time Time in milliseconds since the epoch
     * @return see description
     */
    public static Date toDate(long time) {
        return new Date(time);
    }

    /**
     * Returns time in milliseconds for a time in seconds. No adjustment is made to the parameter.
     * @param time Time in seconds since the epoch
     * @return Time in milliseconds
     */
    public static long toMillis(long time) {
        return time * 1000L;
    }

    /**
     * @return Global time offset in seconds that will be added to {@link #currentTime()} and {@link #currentTimeMillis()}.
     */
    public static int getOffset() {
        return offset;
    }

    /**
     * Sets global time offset in seconds that will be added to {@link #currentTime()} and {@link #currentTimeMillis()}.
     * @param offset Offset (in seconds)
     */
    public static void setOffset(int offset) {
        Time.offset = offset;
    }

    /**
     * Sets consensus offset in seconds that is applied to all time queries.
     * @param offset consensus offset in seconds
     */
    public static void setConsensusOffset(int offset) {
        Time.consensusOffset = offset;
    }

    /**
     * @return consensus offset in seconds
     */
    public static int getConsensusOffset() {
        return consensusOffset;
    }

    /**
     * Detects nodes with significant clock skew (> 1 second) compared to the local time.
     * Each sample map must contain a "nodeId" (String) and "timestamp" (long seconds).
     */
    public static Set<String> detectSkewedNodes(List<Map<String, Object>> nodeSamples) {
        long now = System.currentTimeMillis() / 1000L;
        Set<String> result = new HashSet<>();
        if (nodeSamples == null) {
            return result;
        }
        for (Map<String, Object> sample : nodeSamples) {
            if (sample == null) continue;
            Object nodeId = sample.get("nodeId");
            Object timestamp = sample.get("timestamp");
            if (nodeId instanceof String && timestamp instanceof Number) {
                long ts = ((Number) timestamp).longValue();
                if (Math.abs(ts - now) > 1) {
                    result.add((String) nodeId);
                }
            }
        }
        return result;
    }

    /**
     * Computes a consensus offset from a list of node timestamps. Extreme outliers (up to
     * one third on each side) are ignored and the median of the remaining differences is
     * returned.
     *
     * @param nodeTimestamps timestamps (in seconds) reported by cluster nodes
     * @return consensus offset in seconds compared to the local system time
     */
    public static long computeConsensusOffset(List<Long> nodeTimestamps) {
        if (nodeTimestamps == null || nodeTimestamps.isEmpty()) {
            return 0L;
        }
        long now = System.currentTimeMillis() / 1000L;
        List<Long> diffs = new ArrayList<>();
        for (Long ts : nodeTimestamps) {
            if (ts != null) {
                diffs.add(ts - now);
            }
        }
        if (diffs.isEmpty()) {
            return 0L;
        }
        Collections.sort(diffs);
        int trim = diffs.size() / 3;
        List<Long> trimmed = diffs.subList(trim, diffs.size() - trim);
        if (trimmed.isEmpty()) {
            trimmed = diffs; // not enough values to trim
        }
        int middle = trimmed.size() / 2;
        if (trimmed.size() % 2 == 0) {
            return (trimmed.get(middle - 1) + trimmed.get(middle)) / 2;
        } else {
            return trimmed.get(middle);
        }
    }
}

