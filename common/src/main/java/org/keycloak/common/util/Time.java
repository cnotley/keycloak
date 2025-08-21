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

import java.util.Arrays;
import java.util.Date;

/**
 * Utilities for dealing with time offsets.
 * <p>
 * A process wide {@link #setOffset(int) global offset} can be applied for testing purposes. In addition a
 * {@link #withScopedOffset(int) scoped offset} can be used to temporarily adjust time calculations for the
 * current thread only. Scopes are nestable and must be closed in a strict LIFO order.
 * </p>
 *
 * <p>
 * Typical usage:
 * </p>
 * <pre>
 * try (ScopedTimeOffset ignore = Time.withScopedOffset(-60)) {
 *     // Temporarily backdate tokens for not-before checks in
 *     // {@link org.keycloak.services.managers.AuthenticationManager}
 * }
 * </pre>
 *
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class Time {

    private static volatile int offset;

    static final ThreadLocal<OffsetStack> SCOPED_OFFSETS = ThreadLocal.withInitial(OffsetStack::new);

    private static volatile Time INSTANCE = new Time();

    protected static Time getInstance() {
        return INSTANCE;
    }

    protected static void setInstance(Time instance) {
        INSTANCE = instance;
    }

    /**
     * Returns current time in seconds adjusted by global and scoped offsets.
     *
     * @return current time in seconds
     */
    public static int currentTime() {
        int adjusted = getInstance().getAdjustedOffset();
        return (int) (System.currentTimeMillis() / 1000) + adjusted;
    }

    /**
     * Returns current time in milliseconds adjusted by global and scoped offsets.
     *
     * @return current time in milliseconds
     */
    public static long currentTimeMillis() {
        int adjusted = getInstance().getAdjustedOffset();
        return System.currentTimeMillis() + (adjusted * 1000L);
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
     * Returns time in milliseconds for a time in seconds. No adjustment is made to the parameter.
     *
     * @param time Time in seconds since the epoch
     * @return Time in milliseconds
     */
    public static long toMillis(long time) {
        return time * 1000L;
    }

    /**
     * Returns time in seconds for a time in milliseconds. No adjustment is made to the parameter.
     *
     * @param millis Time in milliseconds since the epoch
     * @return Time in seconds
     */
    public static long fromMillis(long millis) {
        return millis / 1000L;
    }

    /**
     * @return Time offset in seconds that will be added to {@link #currentTime()} and {@link #currentTimeMillis()}.
     */
    public static int getOffset() {
        return offset;
    }

    /**
     * Sets time offset in seconds that will be added to {@link #currentTime()} and {@link #currentTimeMillis()}.
     *
     * @param offset Offset (in seconds)
     */
    public static void setOffset(int offset) {
        Time.offset = offset;
    }

    /**
     * Creates a scoped time offset for the current thread. Offsets are applied in a LIFO manner and the scope
     * must be closed to remove the offset.
     *
     * <p>Example usage for session timeout persistence in
     * {@link org.keycloak.models.sessions.infinispan.entities.UserSessionEntity}:</p>
     * <pre>
     * try (ScopedTimeOffset ignored = Time.withScopedOffset(5)) {
     *     // persist session with temporary offset
     * }
     * </pre>
     *
     * @param seconds offset in seconds, may be negative
     * @return a scope representing the offset
     */
    public static ScopedTimeOffset withScopedOffset(int seconds) {
        if (seconds == 0) {
            return ScopedTimeOffset.NOOP;
        }
        OffsetStack stack = SCOPED_OFFSETS.get();
        int depth = stack.push(seconds);
        return new ScopedTimeOffset(stack, seconds, depth);
    }

    /**
     * Hook for subclasses to override offset computation.
     *
     * @return sum of global and scoped offsets
     */
    protected int getAdjustedOffset() {
        OffsetStack stack = SCOPED_OFFSETS.get();
        int scoped = stack.current();
        return saturatingAdd(offset, scoped);
    }

    private static int saturatingAdd(int a, int b) {
        long r = (long) a + (long) b;
        if (r > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (r < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) r;
    }

    static final class OffsetStack {

        private int[] offsets = new int[4];
        private int[] totals = new int[4];
        private int size;

        int push(int offset) {
            if (size == offsets.length) {
                int newSize = Math.min(100, size << 1);
                offsets = Arrays.copyOf(offsets, newSize);
                totals = Arrays.copyOf(totals, newSize);
            }
            offsets[size] = offset;
            int total = offset;
            if (size > 0) {
                total = saturatingAdd(totals[size - 1], offset);
            }
            totals[size] = total;
            size++;
            return size;
        }

        void pop(int offset, int depth) {
            if (size == 0 || depth != size || offsets[size - 1] != offset) {
                throw new IllegalStateException("Scoped offset closed out of order");
            }
            size--;
        }

        int current() {
            return size == 0 ? 0 : totals[size - 1];
        }
    }
}

