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
import java.util.Date;
import java.util.Deque;
import java.util.concurrent.Callable;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class Time {

    /**
     * Global offset in seconds. This keeps backwards compatibility with the original behaviour
     * where a single offset applied to the whole JVM.
     */
    private static volatile int offset;

    /**
     * Debug flag enabling additional synchronization and validation. It can be enabled by setting
     * the {@code keycloak.time.debug} system property to {@code true}.
     */
    private static final boolean DEBUG = Boolean.getBoolean("keycloak.time.debug");

    private static final Object DEBUG_LOCK = new Object();

    /**
     * Per-thread offsets. The use of {@link InheritableThreadLocal} ensures that child threads
     * inherit the current stack but each thread works on its own copy to avoid interference.
     */
    private static final InheritableThreadLocal<Deque<OffsetScope>> LOCAL_OFFSETS =
            new InheritableThreadLocal<>() {
                @Override
                protected Deque<OffsetScope> childValue(Deque<OffsetScope> parent) {
                    if (parent == null) {
                        return null;
                    }
                    return new ArrayDeque<>(parent);
                }
            };

    /**
     * Scoped offset that is automatically removed on {@link #close()}. Instances are pushed on a
     * thread local stack and added to all calculations performed by the current thread.
     */
    public static final class OffsetScope implements AutoCloseable {
        private final long offsetMillis;
        private final Deque<OffsetScope> stack;
        private boolean closed;

        private OffsetScope(long offsetMillis) {
            this.offsetMillis = offsetMillis;
            Deque<OffsetScope> s = LOCAL_OFFSETS.get();
            if (s == null) {
                s = new ArrayDeque<>();
                LOCAL_OFFSETS.set(s);
            }

            // Activate immediately after verifying this scope is not already present
            if (DEBUG && s.contains(this)) {
                throw new IllegalStateException("Duplicate offset scope");
            }

            s.addLast(this);
            this.stack = s;
        }

        @Override
        public void close() {
            if (closed) {
                return; // idempotent
            }
            closed = true;
            Deque<OffsetScope> s = stack;
            if (s == null) {
                return;
            }

            // Remove this scope; handle in-order and out-of-order scenarios
            if (!s.isEmpty() && s.peekLast() == this) {
                s.removeLast();
            } else {
                s.remove(this);
                // Out of order close - in debug mode signal with an exception
                if (DEBUG) {
                    throw new IllegalStateException("Offset scopes closed out of order");
                }
            }
            if (s.isEmpty()) {
                LOCAL_OFFSETS.remove();
            }
        }
    }

    /**
     * Creates a new scoped offset measured in seconds. The offset becomes active immediately for
     * the current thread and all its descendants. The returned scope must be closed to remove the
     * offset.
     *
     * @param seconds number of seconds to add to the current time
     * @return scope representing the offset
     */
    public static OffsetScope withOffset(int seconds) {
        return withOffsetMillis(seconds * 1000L);
    }

    /**
     * Creates a new scoped offset measured in milliseconds.
     */
    private static OffsetScope withOffsetMillis(long millis) {
        return new OffsetScope(millis);
    }

    /**
     * Wraps a runnable so that the current thread offset is applied during its execution. This is
     * especially useful when using executor services where threads may have been created before the
     * offset was set.
     */
    public static Runnable runnable(Runnable runnable) {
        final long captured = threadOffset();
        return () -> {
            try (OffsetScope scope = withOffsetMillis(captured)) {
                runnable.run();
            }
        };
    }

    /**
     * Wraps a callable so that the current thread offset is applied during its execution.
     */
    public static <T> Callable<T> callable(Callable<T> callable) {
        final long captured = threadOffset();
        return () -> {
            try (OffsetScope scope = withOffsetMillis(captured)) {
                return callable.call();
            }
        };
    }

    /**
     * Returns current time in seconds adjusted by adding {@link #offset} seconds and any active
     * thread-local offsets.
     *
     * @return current time in seconds
     */
    public static int currentTime() {
        long now = System.currentTimeMillis();
        long adj = safeAdd(offset * 1000L, threadOffset());
        return (int) ((now + adj) / 1000L);
    }

    /**
     * Returns current time in milliseconds adjusted by adding {@link #offset} seconds and any
     * active thread-local offsets.
     *
     * @return current time in milliseconds
     */
    public static long currentTimeMillis() {
        long now = System.currentTimeMillis();
        return safeAdd(now, safeAdd(offset * 1000L, threadOffset()));
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
     * @return Time offset in seconds that will be added to {@link #currentTime()} and {@link #currentTimeMillis()}.
     */
    public static int getOffset() {
        return offset;
    }

    /**
     * Sets time offset in seconds that will be added to {@link #currentTime()} and {@link #currentTimeMillis()}.
     * @param offset Offset (in seconds)
     */
    public static void setOffset(int offset) {
        Time.offset = offset;
    }

    /**
     * Computes the sum of all active thread-local offsets.
     */
    private static long threadOffset() {
        Deque<OffsetScope> s = LOCAL_OFFSETS.get();
        if (s == null || s.isEmpty()) {
            return 0L;
        }

        if (DEBUG) {
            synchronized (DEBUG_LOCK) {
                return sumOffsets(s);
            }
        }

        return sumOffsets(s);
    }

    private static long sumOffsets(Deque<OffsetScope> s) {
        long result = 0L;
        for (OffsetScope scope : s) {
            result = safeAdd(result, scope.offsetMillis);
        }
        return result;
    }

    /**
     * Adds two values using saturation to avoid overflow.
     */
    private static long safeAdd(long a, long b) {
        long r = a + b;
        if (((a ^ r) & (b ^ r)) < 0) {
            return a > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return r;
    }

}
