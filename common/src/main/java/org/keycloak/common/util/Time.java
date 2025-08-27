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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class Time {

    /**
     * Global offset applied to all threads.  This retains backward compatibility
     * with previous versions of this utility.
     */
    private static volatile int offset;

    /**
     * Thread local offset state.  The state keeps a stack of offsets to allow
     * nested scopes.  {@link InheritableThreadLocal} is used so that newly
     * created threads start with the same offset as their parent thread.  Each
     * thread maintains its own stack; closing a scope only affects the thread
     * that created it.
     */
    private static final InheritableThreadLocal<OffsetState> LOCAL_OFFSET =
            new InheritableThreadLocal<OffsetState>() {
                @Override
                protected OffsetState childValue(OffsetState parentValue) {
                    if (parentValue == null) {
                        return null;
                    }
                    // copy total offset but create an empty stack so that
                    // offsets on the child do not affect the parent
                    OffsetState child = new OffsetState();
                    child.offset = parentValue.offset;
                    return child;
                }
            };

    /**
     * Returns current time in seconds adjusted by adding {@link #offset) seconds.
     * @return see description
     */
    public static int currentTime() {
        long now = System.currentTimeMillis() / 1000;
        long allOffset = offset + getLocalOffset();
        long result = safeAdd(now, allOffset);
        if (result > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (result < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) result;
    }

    /**
     * Returns current time in milliseconds adjusted by adding {@link #offset) seconds.
     * @return see description
     */
    public static long currentTimeMillis() {
        long base = System.currentTimeMillis();
        long allOffset = offset + getLocalOffset();
        long millisOffset = safeMultiply(allOffset, 1000L);
        return safeAdd(base, millisOffset);
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
     * Returns the thread local offset currently active for this thread.
     */
    private static long getLocalOffset() {
        OffsetState state = LOCAL_OFFSET.get();
        return state == null ? 0L : state.offset;
    }

    /**
     * Create a new scope that applies the given offset (in seconds) for the
     * duration of the scope.  Offsets in nested scopes accumulate.  Closing the
     * returned scope restores the previous state.
     *
     * @param seconds offset in seconds
     * @return a scope that must be closed to revert the offset
     */
    public static OffsetScope withOffset(int seconds) {
        if (seconds == 0) {
            // return a no-op scope
            return OffsetScope.NOOP;
        }

        OffsetState state = LOCAL_OFFSET.get();
        if (state == null) {
            state = new OffsetState();
            LOCAL_OFFSET.set(state);
        }

        OffsetScope scope = new OffsetScope(state, seconds);
        state.push(scope);
        return scope;
    }

    /**
     * Wrap a runnable so that the current offset is applied while it executes
     * and cleared afterwards.  This is useful when submitting tasks to an
     * executor service.
     */
    public static Runnable runnable(Runnable r) {
        final long captured = getLocalOffset();
        return () -> {
            int portion = toIntSaturated(captured);
            OffsetScope scope = captured == 0 ? OffsetScope.NOOP : withOffset(portion);
            try {
                r.run();
            } finally {
                scope.close();
            }
        };
    }

    /**
     * Wrap a callable so that the current offset is applied while it executes
     * and cleared afterwards.  This is useful when submitting tasks to an
     * executor service.
     */
    public static <V> Callable<V> callable(Callable<V> c) {
        final long captured = getLocalOffset();
        return () -> {
            int portion = toIntSaturated(captured);
            OffsetScope scope = captured == 0 ? OffsetScope.NOOP : withOffset(portion);
            try {
                return c.call();
            } finally {
                scope.close();
            }
        };
    }

    // ------------------------------------------------------------ helpers

    private static long safeAdd(long a, long b) {
        long res = a + b;
        if (((a ^ res) & (b ^ res)) < 0) {
            return b > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return res;
    }

    private static long safeMultiply(long a, long b) {
        long res = a * b;
        long absA = Math.abs(a);
        long absB = Math.abs(b);
        if (absA > 1 && absB > 1 && (absA > Long.MAX_VALUE / absB)) {
            return ((a ^ b) < 0) ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return res;
    }

    private static int toIntSaturated(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }

    // ------------------------------------------------------------ inner classes

    /**
     * State stored in the thread local.  It keeps the accumulated offset and a
     * stack of active scopes so that nested scopes compose correctly.
     */
    private static final class OffsetState {
        long offset;
        final Deque<OffsetScope> stack = new ArrayDeque<>();

        void push(OffsetScope scope) {
            offset = safeAdd(offset, scope.offsetSeconds);
            stack.addLast(scope);
        }

        void pop(OffsetScope scope) {
            stack.remove(scope);
            offset = safeAdd(offset, -scope.offsetSeconds);
            if (stack.isEmpty()) {
                LOCAL_OFFSET.remove();
            }
        }
    }

    /**
     * Represents a temporary offset scope.  Scopes may be nested and are
     * required to be closed in LIFO order.  Closing a scope out of order will
     * restore the correct state but will throw an {@link IllegalStateException}.
     */
    public static class OffsetScope implements AutoCloseable {
        private final OffsetState state;
        private final int offsetSeconds;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private static final OffsetScope NOOP = new OffsetScope(null, 0) {
            @Override
            public void close() {
                // no-op
            }
        };

        private OffsetScope(OffsetState state, int offsetSeconds) {
            this.state = state;
            this.offsetSeconds = offsetSeconds;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true) || state == null) {
                return; // already closed or noop
            }

            OffsetScope last = state.stack.peekLast();
            if (last != this) {
                // remove this scope even if out of order to avoid leaking
                state.pop(this);
                throw new IllegalStateException("Time offset scopes closed out of order");
            }
            state.pop(this);
        }
    }

}
