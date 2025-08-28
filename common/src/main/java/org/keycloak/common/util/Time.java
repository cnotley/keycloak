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

/**
 * Time utility that allows offsetting the current time. Historically a single
 * global offset was used which made tests interfering with each other when run
 * concurrently.  This class now provides a thread confined offset mechanism
 * which is also inherited by child threads.  Offsets can temporarily be pushed
 * on a stack and are automatically restored using the returned
 * {@link OffsetContext}.
 *
 * <p>Debug safeguards can be enabled by setting the system property
 * {@code keycloak.time.debug=true}. When enabled any out of order closing of
 * {@link OffsetContext} instances results in an {@link IllegalStateException}.
 *
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class Time {

    /** Global offset applicable to all threads. */
    private static volatile int offset;

    /** Flag used to activate additional consistency checks. */
    private static final boolean DEBUG = Boolean.getBoolean("keycloak.time.debug");

    /**
     * Per thread offsets.  Uses {@link InheritableThreadLocal} so that child
     * threads see the same offset as their parents at the time of creation.
     */
    private static final InheritableThreadLocal<OffsetStack> THREAD_OFFSETS = new InheritableThreadLocal<>() {
        @Override
        protected OffsetStack childValue(OffsetStack parentValue) {
            return parentValue == null ? null : parentValue.copy();
        }
    };

    /** Holds offsets for a particular thread in a stack to support nesting. */
    private static final class OffsetStack {
        private final Deque<Integer> stack = new ArrayDeque<>();
        private int currentOffset = 0;

        OffsetStack() {
        }

        OffsetStack(OffsetStack other) {
            this.stack.addAll(other.stack);
            this.currentOffset = other.currentOffset;
        }

        OffsetStack copy() {
            return new OffsetStack(this);
        }

        void push(int value) {
            stack.push(value);
            currentOffset += value;
        }

        void pop(int expected) {
            Integer top = stack.peek();
            if (DEBUG && (top == null || top.intValue() != expected)) {
                throw new IllegalStateException("Time offset context closed out of order");
            }
            int popped = stack.pop();
            currentOffset -= popped;
        }

        int getOffset() {
            return currentOffset;
        }

        boolean isEmpty() {
            return stack.isEmpty();
        }
    }

    private static int threadOffset() {
        OffsetStack stack = THREAD_OFFSETS.get();
        return stack == null ? 0 : stack.getOffset();
    }

    private static OffsetStack getOrCreateStack() {
        OffsetStack stack = THREAD_OFFSETS.get();
        if (stack == null) {
            stack = new OffsetStack();
            THREAD_OFFSETS.set(stack);
        }
        return stack;
    }

    /**
     * Returns current time in seconds adjusted by adding the global offset and
     * any thread specific offset.
     *
     * @return current time with offset applied
     */
    public static int currentTime() {
        return (int) (System.currentTimeMillis() / 1000) + offset + threadOffset();
    }

    /**
     * Returns current time in milliseconds adjusted by adding the global offset
     * and any thread specific offset.
     *
     * @return current time in milliseconds with offset applied
     */
    public static long currentTimeMillis() {
        return System.currentTimeMillis() + ((long) (offset + threadOffset())) * 1000L;
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
     * @return Global time offset in seconds that will be added to all threads.
     */
    public static int getOffset() {
        return offset;
    }

    /**
     * Sets global time offset in seconds that will be added to
     * {@link #currentTime()} and {@link #currentTimeMillis()} for all threads.
     *
     * @param offset Offset (in seconds)
     */
    public static void setOffset(int offset) {
        Time.offset = offset;
    }

    /**
     * Returns the effective offset for the current thread, which is a
     * combination of the global offset and any thread specific adjustments.
     *
     * @return effective offset for the current thread
     */
    public static int getCurrentOffset() {
        return offset + threadOffset();
    }

    /**
     * Temporarily adjusts the current thread's time offset.  The returned
     * {@link OffsetContext} must be closed to restore the previous offset.
     *
     * <pre>
     * try (OffsetContext oc = Time.withOffset(10)) {
     *     // time is moved 10 seconds into the future for this thread and its children
     * }
     * </pre>
     *
     * @param offset offset in seconds to apply
     * @return context that must be closed to remove the offset
     */
    public static OffsetContext withOffset(int offset) {
        getOrCreateStack().push(offset);
        return new OffsetContext(offset);
    }

    /**
     * Context object returned from {@link #withOffset(int)} that restores the
     * previous offset when closed.  The context is thread confined and affects
     * only the thread that created it and its descendants.
     */
    public static class OffsetContext implements AutoCloseable {
        private final int offset;
        private boolean closed;

        private OffsetContext(int offset) {
            this.offset = offset;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }

            OffsetStack stack = THREAD_OFFSETS.get();
            if (stack != null) {
                stack.pop(offset);
                if (stack.isEmpty()) {
                    THREAD_OFFSETS.remove();
                }
            }
            closed = true;
        }
    }
}

