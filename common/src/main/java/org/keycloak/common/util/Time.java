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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.jboss.logging.Logger;

/**
 * Global time utilities with optional scoped offsets.
 *
 * <p>The historic {@link #setOffset(int)} method allows process wide
 * modification of the system time. In addition to that a new scoped
 * mechanism can be used through {@link #withOffset(int)} allowing time
 * manipulation that is confined to the current thread and automatically
 * propagated to child threads.</p>
 *
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public final class Time {

    private static final Logger LOGGER = Logger.getLogger(Time.class);

    /** Global offset shared by the whole process. */
    private static volatile int offset;

    /**
     * Thread scoped offsets. Each thread keeps a stack of offsets so that
     * nested scopes can be combined. The {@link InheritableThreadLocal}
     * ensures new threads start with a copy of their parents stack.
     */
    private static final InheritableThreadLocal<OffsetStack> SCOPED_OFFSETS = new InheritableThreadLocal<>() {
        @Override
        protected OffsetStack childValue(OffsetStack parent) {
            return parent == null ? null : parent.copy();
        }
    };

    private Time() {
    }

    /**
     * Returns current time in seconds adjusted by the global offset and any
     * active scoped offsets.
     *
     * @return current time in seconds
     */
    public static int currentTime() {
        long now = System.currentTimeMillis() / 1000L;
        long totalOffset = totalOffsetSeconds();
        long adjusted = saturatedAdd(now, totalOffset);
        if (adjusted > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (adjusted < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) adjusted;
    }

    /**
     * Returns current time in milliseconds adjusted by the global offset and
     * any active scoped offsets.
     *
     * @return current time in milliseconds
     */
    public static long currentTimeMillis() {
        long now = System.currentTimeMillis();
        long totalOffsetMillis;
        long seconds = totalOffsetSeconds();
        try {
            totalOffsetMillis = Math.multiplyExact(seconds, 1000L);
        } catch (ArithmeticException e) {
            totalOffsetMillis = seconds > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return saturatedAdd(now, totalOffsetMillis);
    }

    /**
     * Returns {@link Date} object, its value set to time
     *
     * @param time Time in milliseconds since the epoch
     * @return see description
     */
    public static Date toDate(int time) {
        return new Date(time * 1000L);
    }

    /**
     * Returns {@link Date} object, its value set to time
     *
     * @param time Time in milliseconds since the epoch
     * @return see description
     */
    public static Date toDate(long time) {
        return new Date(time);
    }

    /**
     * Returns time in milliseconds for a time in seconds. No adjustment is made
     * to the parameter.
     *
     * @param time Time in seconds since the epoch
     * @return Time in milliseconds
     */
    public static long toMillis(long time) {
        return time * 1000L;
    }

    /**
     * Performs the inverse conversion to {@link #toMillis(long)} using
     * saturation arithmetic.
     *
     * @param time Time in milliseconds since the epoch
     * @return Time in seconds since the epoch
     */
    public static int fromMillis(long time) {
        long seconds = time / 1000L;
        if (seconds > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (seconds < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) seconds;
    }

    /**
     * @return Time offset in seconds that will be added to
     *         {@link #currentTime()} and {@link #currentTimeMillis()}.
     */
    public static int getOffset() {
        return offset;
    }

    /**
     * Sets time offset in seconds that will be added to
     * {@link #currentTime()} and {@link #currentTimeMillis()}.
     *
     * @param offset Offset (in seconds)
     */
    public static void setOffset(int offset) {
        Time.offset = offset;
    }

    /**
     * Creates a new scoped offset that is active for the current thread and
     * automatically cleared when the returned handle is closed. Offsets from
     * nested scopes add up.
     *
     * <p>The offset is effective immediately and propagated to any child
     * threads spawned while the scope is active. Misuses such as out-of-order
     * or double closing are logged and safely ignored.</p>
     *
     * @param seconds number of seconds to offset, may be negative
     * @return handle used to close the scope
     */
    public static AutoCloseable withOffset(int seconds) {
        OffsetStack stack = SCOPED_OFFSETS.get();
        if (stack == null) {
            stack = new OffsetStack();
            SCOPED_OFFSETS.set(stack);
        }
        int index = stack.push(seconds);
        final OffsetStack captured = stack;
        return new AutoCloseable() {
            private boolean closed;

            @Override
            public void close() {
                if (closed) {
                    LOGGER.warn("Offset scope closed more than once");
                    return;
                }
                closed = true;
                OffsetStack current = SCOPED_OFFSETS.get();
                if (current != captured) {
                    LOGGER.warn("Offset scope closed from different thread");
                    return;
                }
                current.pop(index);
                if (current.isEmpty()) {
                    SCOPED_OFFSETS.remove();
                }
            }
        };
    }

    /**
     * Wraps a runnable so that the current offset context is installed when the
     * runnable executes. This is primarily intended for use with asynchronous
     * executors.
     *
     * @param runnable runnable to wrap
     * @return wrapped runnable
     */
    public static Runnable wrap(Runnable runnable) {
        OffsetStack context = SCOPED_OFFSETS.get();
        final OffsetStack captured = context == null ? null : context.copy();
        return () -> {
            OffsetStack previous = SCOPED_OFFSETS.get();
            if (captured == null) {
                SCOPED_OFFSETS.remove();
            } else {
                SCOPED_OFFSETS.set(captured.copy());
            }
            try {
                runnable.run();
            } finally {
                if (previous == null) {
                    SCOPED_OFFSETS.remove();
                } else {
                    SCOPED_OFFSETS.set(previous);
                }
            }
        };
    }

    private static long totalOffsetSeconds() {
        OffsetStack stack = SCOPED_OFFSETS.get();
        long scoped = stack == null ? 0 : stack.total;
        return saturatedAdd(offset, scoped);
    }

    private static long saturatedAdd(long a, long b) {
        long r = a + b;
        if (((a ^ r) & (b ^ r)) < 0) {
            return b >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return r;
    }

    /** Stack used to keep scoped offsets per thread. */
    private static final class OffsetStack {
        private final List<Integer> offsets = new ArrayList<>();
        private long total;

        int push(int value) {
            offsets.add(value);
            total = saturatedAdd(total, value);
            return offsets.size() - 1;
        }

        void pop(int index) {
            if (index < 0 || index >= offsets.size()) {
                LOGGER.warn("Offset scope already closed or out of order");
                return;
            }
            int removed = offsets.remove(index);
            total = saturatedAdd(total, -removed);
        }

        boolean isEmpty() {
            return offsets.isEmpty();
        }

        OffsetStack copy() {
            OffsetStack c = new OffsetStack();
            c.offsets.addAll(this.offsets);
            c.total = this.total;
            return c;
        }
    }
}
