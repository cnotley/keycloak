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
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Utility methods for dealing with time within Keycloak.
 *
 * <p>The class supports two different kinds of offsets:</p>
 * <ul>
 *     <li>A global offset, configured via {@link #setOffset(int)}, typically used
 *     for cluster wide clock skew adjustments.</li>
 *     <li>Scoped offsets which are temporary and thread local. They can be
 *     established via {@link #withOffset(int)} and are automatically restored
 *     when the returned {@link OffsetScope} is closed.</li>
 * </ul>
 *
 * <p>Scoped offsets compose with the global offset and with each other. Nested
 * scopes accumulate their offsets and are fully thread safe. Offsets are
 * inherited by child threads created inside a scope and can be propagated to
 * asynchronous executions by using {@link #wrap(Runnable)} or
 * {@link #wrap(Callable)}.</p>
 *
 * <p>The implementation avoids arithmetic overflows by saturating additions to
 * the {@code int} range. When no scoped offsets are active the behaviour is
 * identical to previous versions of this class.</p>
 *
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class Time {

    /** Global offset in seconds applied to all time queries. */
    private static volatile int offset;

    /**
     * Thread local container for stacked temporary offsets. An inheritable
     * thread local is used so that newly created threads inherit the offset of
     * their parent thread. Each thread holds its own {@link OffsetState}.
     */
    private static final InheritableThreadLocal<OffsetState> LOCAL_OFFSET =
            new InheritableThreadLocal<>() {
                @Override
                protected OffsetState childValue(OffsetState parent) {
                    return parent == null ? null : parent.copy();
                }
            };

    /**
     * Returns current time in seconds adjusted by adding the global and
     * any active scoped offsets.
     *
     * @return current time in seconds
     */
    public static int currentTime() {
        return (int) (currentTimeMillis() / 1000L);
    }

    /**
     * Returns current time in milliseconds adjusted by adding the global and
     * any active scoped offsets.
     *
     * @return current time in milliseconds
     */
    public static long currentTimeMillis() {
        long threadOffset = getThreadOffset();
        long totalOffset = saturatingAdd(offset, threadOffset);
        return System.currentTimeMillis() + (totalOffset * 1000L);
    }

    /**
     * Returns {@link Date} object, its value set to time.
     *
     * @param time Time in milliseconds since the epoch
     * @return see description
     */
    public static Date toDate(int time) {
        return new Date(time * 1000L);
    }

    /**
     * Returns {@link Date} object, its value set to time.
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
     * @return Time offset in seconds that will be added to {@link #currentTime()}
     * and {@link #currentTimeMillis()}.
     */
    public static int getOffset() {
        return offset;
    }

    /**
     * Sets time offset in seconds that will be added to {@link #currentTime()}
     * and {@link #currentTimeMillis()}.
     *
     * @param offset Offset (in seconds)
     */
    public static void setOffset(int offset) {
        Time.offset = offset;
    }

    /**
     * Establishes a temporary, thread local offset in seconds. The offset takes
     * effect immediately and is removed once the returned {@link OffsetScope}
     * is closed. Scopes can be nested and offsets accumulate using saturating
     * arithmetic. The global offset configured via {@link #setOffset(int)} is
     * unaffected.
     *
     * @param seconds offset in seconds
     * @return a scope object that must be closed to restore the previous state
     */
    public static OffsetScope withOffset(int seconds) {
        OffsetState state = LOCAL_OFFSET.get();
        if (state == null) {
            state = new OffsetState();
            LOCAL_OFFSET.set(state);
        }

        ScopeEntry entry = new ScopeEntry(seconds);
        state.entries.add(entry);
        state.recalculate();
        return new OffsetScope(state, entry);
    }

    /**
     * Wraps a runnable so that the current scoped offset is used during its
     * execution. The previous offset is restored once the runnable finishes.
     *
     * @param runnable runnable to wrap
     * @return wrapped runnable
     */
    public static Runnable wrap(Runnable runnable) {
        OffsetState captured = LOCAL_OFFSET.get();
        return () -> {
            OffsetState previous = LOCAL_OFFSET.get();
            try {
                if (captured != null) {
                    LOCAL_OFFSET.set(captured.copy());
                } else {
                    LOCAL_OFFSET.remove();
                }
                runnable.run();
            } finally {
                if (previous != null) {
                    LOCAL_OFFSET.set(previous);
                } else {
                    LOCAL_OFFSET.remove();
                }
            }
        };
    }

    /**
     * Wraps a callable so that the current scoped offset is used during its
     * execution. The previous offset is restored once the callable finishes.
     *
     * @param callable callable to wrap
     * @param <V> return type
     * @return wrapped callable
     */
    public static <V> Callable<V> wrap(Callable<V> callable) {
        OffsetState captured = LOCAL_OFFSET.get();
        return () -> {
            OffsetState previous = LOCAL_OFFSET.get();
            try {
                if (captured != null) {
                    LOCAL_OFFSET.set(captured.copy());
                } else {
                    LOCAL_OFFSET.remove();
                }
                return callable.call();
            } finally {
                if (previous != null) {
                    LOCAL_OFFSET.set(previous);
                } else {
                    LOCAL_OFFSET.remove();
                }
            }
        };
    }

    /**
     * Returns the sum of all scoped offsets for the current thread. The result
     * is saturated to the {@code int} range.
     */
    private static long getThreadOffset() {
        OffsetState state = LOCAL_OFFSET.get();
        return state == null ? 0 : state.current;
    }

    private static long saturatingAdd(long a, long b) {
        long r = a + b;
        if (r > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (r < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return r;
    }

    /** State holder for per-thread offsets. */
    private static class OffsetState {
        private final Deque<ScopeEntry> entries = new ArrayDeque<>();
        private long current;

        private void recalculate() {
            long sum = 0;
            List<ScopeEntry> snapshot = new ArrayList<>(entries);
            for (ScopeEntry e : snapshot) {
                sum = saturatingAdd(sum, e.offset);
            }
            this.current = sum;
            if (entries.isEmpty()) {
                LOCAL_OFFSET.remove();
            }
        }

        OffsetState copy() {
            OffsetState c = new OffsetState();
            for (ScopeEntry e : entries) {
                c.entries.add(new ScopeEntry(e.offset));
            }
            c.current = this.current;
            return c;
        }
    }

    /** Represents a single scoped offset. */
    public static final class OffsetScope implements AutoCloseable {
        private final OffsetState state;
        private ScopeEntry entry;
        private boolean closed;

        OffsetScope(OffsetState state, ScopeEntry entry) {
            this.state = state;
            this.entry = entry;
        }

        @Override
        public void close() {
            if (closed) {
                return; // double close is a no-op
            }
            closed = true;

            if (state == null || entry == null) {
                return;
            }

            boolean removed;
            if (!state.entries.isEmpty() && state.entries.peekLast() == entry) {
                state.entries.removeLast();
                removed = true;
            } else {
                removed = state.entries.remove(entry);
            }
            state.recalculate();

            entry = null;
            if (!removed) {
                throw new IllegalStateException("Offset scopes closed out of order");
            }
        }
    }

    private static class ScopeEntry {
        final long offset;

        ScopeEntry(long offset) {
            this.offset = offset;
        }
    }
}
