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

import java.util.Date;
import java.util.Deque;
import java.util.ArrayDeque;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class Time {
    /**
     * Global baseline offset shared by all threads. This behaves as the legacy offset and is applied to all threads in addition
     * to any active thread-local offsets. Tests that want to advance or rewind time globally may continue to manipulate this
     * offset, although doing so can still cause issues in highly concurrent environments.
     */
    private static volatile int offset;

    /**
     * Thread-local stack of temporary offsets. Inheritable so that child threads start with a copy of their parent's
     * offset stack, preserving the parent's view of time at creation. Any adjustments made in a child thereafter are
     * independent of other threads.  The stack always has at least a single entry representing the current thread's
     * local overlay to the global offset. A value of 0 indicates no additional thread-local adjustment relative to the
     * global baseline.
     */
    private static final InheritableThreadLocal<Deque<OffsetHolder>> localOffsets = new InheritableThreadLocal<Deque<OffsetHolder>>() {
        @Override
        protected Deque<OffsetHolder> initialValue() {
            ArrayDeque<OffsetHolder> deque = new ArrayDeque<>();
            deque.add(new OffsetHolder(0));
            return deque;
        }

        @Override
        protected Deque<OffsetHolder> childValue(Deque<OffsetHolder> parentValue) {
            return new ArrayDeque<>(parentValue);
        }
    };

    private static final boolean DEBUG = Boolean.getBoolean("keycloak.time.offset.debug");
    private static final int MAX_STACK_DEPTH = 1000;

    private static class OffsetHolder {
        final int offset;
        OffsetHolder(int offset) {
            this.offset = offset;
        }
    }

    /**
     * @return current time in seconds adjusted by adding {@link #offset} plus any active thread-local offsets.
     */
    public static int currentTime() {
        long base = System.currentTimeMillis() / 1000L;
        long total = base + offset + currentThreadOffset();
        if (DEBUG) {
            if (total > Integer.MAX_VALUE || total < Integer.MIN_VALUE) {
                throw new IllegalStateException("Adjusted time seconds overflow: " + total);
            }
        }
        return (int) total;
    }

    /**
     * @return current time in milliseconds adjusted by adding {@link #offset} plus any active thread-local offsets.
     */
    public static long currentTimeMillis() {
        long threadOffset = offset + currentThreadOffset();
        long millis = System.currentTimeMillis();
        long result = millis + threadOffset * 1000L;
        if (DEBUG) {
            // detect overflow of signed long
            if ((threadOffset > 0 && result < millis) || (threadOffset < 0 && result > millis)) {
                throw new IllegalStateException("Adjusted time millis overflow: " + result);
            }
        }
        return result;
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
     * @return current effective time offset for this thread, which is the sum of the global baseline offset
     * and any active thread-local overlay.
     */
    public static int getOffset() {
        return offset + currentThreadOffset();
    }

    /**
     * Sets time offset in seconds that will be added to {@link #currentTime()} and {@link #currentTimeMillis()}.
     * Adjusts the global baseline offset that applies to all threads. Tests that require isolated adjustments should use
     * {@link #withThreadOffset(int)} to avoid interfering with other threads.
     *
     * @param offset Offset (in seconds)
     */
    public static void setOffset(int offset) {
        Time.offset = offset;
    }

    /**
     * Push a thread-local offset adjustment for the current thread and any subsequently created child threads.
     * The returned {@link AutoCloseable} must be closed to restore the previous offset once the temporary adjustment
     * is no longer needed. Adjustments are stacked to support nesting.
     *
     * @param offsetSeconds absolute offset, in seconds, relative to the global baseline. To apply a relative adjustment,
     * callers should calculate the desired absolute offset (for example, current offset + delta).
     */
    public static AutoCloseable withThreadOffset(int offsetSeconds) {
        Deque<OffsetHolder> stack = localOffsets.get();
        if (DEBUG && stack.size() > MAX_STACK_DEPTH) {
            throw new IllegalStateException("Time offset nesting too deep: " + stack.size());
        }
        final OffsetHolder holder = new OffsetHolder(offsetSeconds);
        stack.addLast(holder);
        return () -> {
            Deque<OffsetHolder> s = localOffsets.get();
            if (DEBUG) {
                // Ensure we are unwinding in LIFO order
                OffsetHolder top = s.peekLast();
                if (top != holder) {
                    throw new IllegalStateException("Time offset contexts closed out of order");
                }
            }
            s.removeLast();
        };
    }

    private static int currentThreadOffset() {
        Deque<OffsetHolder> s = localOffsets.get();
        OffsetHolder top = s.peekLast();
        return top == null ? 0 : top.offset;
    }

}
