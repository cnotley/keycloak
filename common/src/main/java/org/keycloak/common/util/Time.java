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
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class Time {

    private static final Logger LOGGER = Logger.getLogger(Time.class.getName());

    private static volatile int offset;

    private static volatile boolean DEBUG = Boolean.getBoolean("keycloak.time.debug");

    private static final class OffsetNode {
        final int offset;
        OffsetNode(int offset) {
            this.offset = offset;
        }
    }

    private static final class OffsetStack {
        final Deque<OffsetNode> stack = new ArrayDeque<>();
        volatile int sum;
        volatile boolean cacheValid;
        final ReentrantLock lock = new ReentrantLock(true);

        OffsetStack copy() {
            OffsetStack copy = new OffsetStack();
            for (OffsetNode n : stack.descendingIterator()) {
                copy.stack.addFirst(new OffsetNode(n.offset));
            }
            copy.cacheValid = false;
            copy.currentSum();
            return copy;
        }

        int currentSum() {
            if (cacheValid) {
                return sum;
            }
            if (DEBUG) {
                lock.lock();
            }
            try {
                int s = 0;
                for (OffsetNode n : stack) {
                    s = addClamped(s, n.offset);
                }
                sum = s;
                cacheValid = true;
                return s;
            } finally {
                if (DEBUG) {
                    lock.unlock();
                }
            }
        }
    }

    private static final InheritableThreadLocal<OffsetStack> THREAD_OFFSETS =
            new InheritableThreadLocal<OffsetStack>() {
                @Override
                protected OffsetStack childValue(OffsetStack parentValue) {
                    if (parentValue == null) {
                        return null;
                    }
                    return parentValue.copy();
                }
            };

    private static OffsetStack getStack() {
        OffsetStack stack = THREAD_OFFSETS.get();
        if (stack == null) {
            stack = new OffsetStack();
            THREAD_OFFSETS.set(stack);
        }
        return stack;
    }

    private static int threadOffset() {
        OffsetStack stack = THREAD_OFFSETS.get();
        return stack == null ? 0 : stack.currentSum();
    }

    /**
     * Returns current time in seconds adjusted by adding global and thread offsets.
     * @return see description
     */
    public static int currentTime() {
        return ((int) (System.currentTimeMillis() / 1000)) + offset + threadOffset();
    }

    /**
     * Returns current time in milliseconds adjusted by adding global and thread offsets.
     * @return see description
     */
    public static long currentTimeMillis() {
        return System.currentTimeMillis() + ((long) (offset + threadOffset()) * 1000L);
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

    public static void setDebug(boolean debug) {
        DEBUG = debug;
    }

    private static int addClamped(int base, int delta) {
        long r = (long) base + (long) delta;
        if (r > Integer.MAX_VALUE) {
            if (delta > 1) {
                return Integer.MAX_VALUE;
            }
            return base + Math.max(delta, 0);
        }
        if (r < Integer.MIN_VALUE) {
            if (delta < -1) {
                return Integer.MIN_VALUE;
            }
            return 0;
        }
        return (int) r;
    }

    public static AutoCloseable withTemporaryOffset(int seconds) {
        OffsetStack stack = getStack();
        OffsetNode node = new OffsetNode(seconds);
        if (stack.stack.contains(node)) {
            throw new IllegalStateException("Duplicate offset context");
        }
        stack.stack.push(node);
        stack.cacheValid = false;
        stack.currentSum();
        return new AutoCloseable() {
            boolean closed;
            @Override
            public void close() {
                if (closed) {
                    if (DEBUG) {
                        LOGGER.log(Level.FINE, "Offset already closed: {0}", node.offset);
                    }
                    return;
                }
                closed = true;
                if (!stack.stack.removeFirstOccurrence(node)) {
                    if (DEBUG) {
                        LOGGER.log(Level.WARNING, "Offset context mismatch: {0}", node.offset);
                    }
                    throw new IllegalStateException("Offset context mismatch");
                }
                stack.cacheValid = false;
                stack.currentSum();
                if (stack.stack.isEmpty()) {
                    THREAD_OFFSETS.remove();
                }
            }
        };
    }

    public static Runnable wrapWithOffset(Runnable r) {
        int sum = threadOffset();
        return () -> {
            try (AutoCloseable c = withTemporaryOffset(sum)) {
                r.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    public static <T> Callable<T> wrapWithOffset(Callable<T> c) {
        int sum = threadOffset();
        return () -> {
            try (AutoCloseable ac = withTemporaryOffset(sum)) {
                return c.call();
            }
        };
    }

}
