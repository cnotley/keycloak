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

    private static volatile int offset;

    private static final InheritableThreadLocal<OffsetStack> THREAD_OFFSET = new InheritableThreadLocal<OffsetStack>() {
        @Override
        protected OffsetStack childValue(OffsetStack parentValue) {
            return parentValue == null ? null : parentValue.copy();
        }
    };

    /**
     * Returns current time in seconds adjusted by adding {@link #offset} seconds and any thread-local offsets.
     * @return see description
     */
    public static int currentTime() {
        return (int) ((System.currentTimeMillis() / 1000L) + offset + threadLocalOffsetSeconds());
    }

    /**
     * Returns current time in milliseconds adjusted by adding {@link #offset} seconds and any thread-local offsets.
     * @return see description
     */
    public static long currentTimeMillis() {
        return System.currentTimeMillis() + ((long) offset * 1000L) + (threadLocalOffsetSeconds() * 1000L);
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
     * Starts a new thread-local time offset scope. The returned {@link OffsetScope} must be closed to
     * remove the offset. Offsets are inherited by child threads and nested scopes accumulate their values.
     *
     * <pre>
     * try (Time.OffsetScope s = Time.withOffset(10)) {
     *     // time is now offset by 10 seconds for the current thread
     * }
     * </pre>
     *
     * @param offset Offset in seconds to apply to the current thread
     * @return scope representing the applied offset
     */
    public static OffsetScope withOffset(int offset) {
        OffsetStack stack = THREAD_OFFSET.get();
        if (stack == null) {
            stack = new OffsetStack();
            THREAD_OFFSET.set(stack);
        }
        stack.push(offset);
        return new OffsetScope(stack, offset);
    }

    /**
     * Wraps a {@link Runnable} so that the currently active offset is applied during its execution.
     * This is useful for executor based workflows where threads may be reused.
     *
     * @param runnable runnable to wrap
     * @return wrapped runnable applying the captured offset
     */
    public static Runnable runnableWithOffset(Runnable runnable) {
        OffsetStack captured = THREAD_OFFSET.get();
        captured = captured == null ? null : captured.copy();

        return () -> {
            OffsetStack previous = THREAD_OFFSET.get();
            try {
                if (captured != null) {
                    THREAD_OFFSET.set(captured.copy());
                } else {
                    THREAD_OFFSET.remove();
                }
                runnable.run();
            } finally {
                if (previous != null) {
                    THREAD_OFFSET.set(previous);
                } else {
                    THREAD_OFFSET.remove();
                }
            }
        };
    }

    /**
     * Wraps a {@link Callable} so that the currently active offset is applied during its execution.
     *
     * @param callable callable to wrap
     * @param <V> return type
     * @return wrapped callable applying the captured offset
     */
    public static <V> Callable<V> callableWithOffset(Callable<V> callable) {
        OffsetStack captured = THREAD_OFFSET.get();
        captured = captured == null ? null : captured.copy();

        return () -> {
            OffsetStack previous = THREAD_OFFSET.get();
            try {
                if (captured != null) {
                    THREAD_OFFSET.set(captured.copy());
                } else {
                    THREAD_OFFSET.remove();
                }
                return callable.call();
            } finally {
                if (previous != null) {
                    THREAD_OFFSET.set(previous);
                } else {
                    THREAD_OFFSET.remove();
                }
            }
        };
    }

    private static long threadLocalOffsetSeconds() {
        OffsetStack stack = THREAD_OFFSET.get();
        return stack == null ? 0L : stack.total;
    }

    private static final class OffsetStack {
        private final Deque<Integer> stack = new ArrayDeque<>();
        private long total;

        private OffsetStack() {
        }

        private OffsetStack(OffsetStack other) {
            this.stack.addAll(other.stack);
            this.total = other.total;
        }

        private void push(int value) {
            stack.push(value);
            total += value;
        }

        private void pop(int expected) {
            if (!stack.isEmpty()) {
                int v = stack.pop();
                total -= v;
                // Optional debug safeguard
                assert v == expected : "Mismatched time offset scope";
            }
        }

        private boolean isEmpty() {
            return stack.isEmpty();
        }

        private OffsetStack copy() {
            return new OffsetStack(this);
        }
    }

    /**
     * Represents an active time offset scope. Closing the scope removes the offset from the current thread.
     */
    public static final class OffsetScope implements AutoCloseable {
        private final OffsetStack owner;
        private final int value;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private OffsetScope(OffsetStack owner, int value) {
            this.owner = owner;
            this.value = value;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.pop(value);
                if (owner.isEmpty()) {
                    THREAD_OFFSET.remove();
                }
            }
        }
    }

}
