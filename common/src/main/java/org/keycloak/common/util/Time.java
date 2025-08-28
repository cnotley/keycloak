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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

import org.jboss.logging.Logger;

/**
 * Utility providing current time with support for temporary thread based offsets.  The
 * implementation keeps backwards compatibility with the historical global offset while
 * allowing additional offsets to be applied on a per thread basis using
 * {@link #withTemporaryOffset(int)}.  Offsets are stacked and propagated to child threads
 * through {@link InheritableThreadLocal}.
 *
 * <p>The implementation favours safety – stack manipulations are validated and attempts to
 * close offsets out of order will result in an {@link IllegalStateException}.</p>
 *
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class Time {

    private static final Logger LOG = Logger.getLogger(Time.class);

    /** Global offset shared by all threads (for backwards compatibility). */
    private static volatile int offset;

    /**
     * Flag enabling optional synchronised blocks while computing sums.  Intended only for
     * debugging and simulating potential race conditions.
     */
    private static final boolean DEBUG_SYNCHRONIZED = Boolean.getBoolean("keycloak.time.debug");

    /** Sequence for creating unique identifiers for offset contexts. */
    private static final AtomicLong IDS = new AtomicLong();

    /** Stack of offset contexts per thread. */
    private static final InheritableThreadLocal<Deque<OffsetContext>> OFFSETS =
            new InheritableThreadLocal<Deque<OffsetContext>>() {
                @Override
                protected Deque<OffsetContext> initialValue() {
                    return new ArrayDeque<>();
                }

                @Override
                protected Deque<OffsetContext> childValue(Deque<OffsetContext> parent) {
                    Deque<OffsetContext> clone = new ArrayDeque<>();
                    for (OffsetContext c : parent) {
                        clone.addLast(c.copyForChild());
                    }
                    return clone;
                }
            };

    /** Set of context identifiers per thread used to detect duplicates. */
    private static final InheritableThreadLocal<Set<Long>> CONTEXT_IDS =
            new InheritableThreadLocal<Set<Long>>() {
                @Override
                protected Set<Long> initialValue() {
                    return new HashSet<>();
                }

                @Override
                protected Set<Long> childValue(Set<Long> parent) {
                    return new HashSet<>(parent);
                }
            };

    /** Cache for the summed thread local offsets. */
    private static final InheritableThreadLocal<SumHolder> SUM_CACHE =
            new InheritableThreadLocal<SumHolder>() {
                @Override
                protected SumHolder initialValue() {
                    return new SumHolder();
                }

                @Override
                protected SumHolder childValue(SumHolder parent) {
                    // Child threads start with the same value but mark as invalid to force recompute
                    SumHolder holder = new SumHolder();
                    holder.sum = parent.sum;
                    holder.valid = false;
                    return holder;
                }
            };

    /** Holder for cached sums. */
    private static final class SumHolder {
        volatile int sum;
        volatile boolean valid;
    }

    /** Context representing a single offset pushed on the stack. */
    private static final class OffsetContext {
        final long id;
        final int offset;
        final int previousSum;
        boolean closed;

        OffsetContext(int offset, int previousSum) {
            this.id = IDS.incrementAndGet();
            this.offset = offset;
            this.previousSum = previousSum;
        }

        OffsetContext copyForChild() {
            OffsetContext c = new OffsetContext(this.offset, this.previousSum);
            c.closed = this.closed;
            return c;
        }
    }

    /**
     * Returns current time in seconds adjusted by adding global and thread local offsets.
     */
    public static int currentTime() {
        long base = System.currentTimeMillis() / 1000L;
        int total = safeAdd(offset, threadLocalSum());
        return safeAdd((int) base, total);
    }

    /**
     * Returns current time in milliseconds adjusted by adding global and thread local offsets.
     */
    public static long currentTimeMillis() {
        long base = System.currentTimeMillis();
        int total = safeAdd(offset, threadLocalSum());
        return base + (total * 1000L);
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

    // -------------------------------------------------------------------------------------
    //  Thread local offset handling
    // -------------------------------------------------------------------------------------

    /**
     * Apply a temporary offset for the current thread.  The returned {@link AutoCloseable}
     * must be closed (typically using try-with-resources) to remove the offset again.
     *
     * @param seconds the offset to apply
     * @return handle used for closing the offset
     */
    public static AutoCloseable withTemporaryOffset(int seconds) {
        if (seconds == 0) {
            return () -> {
                // no-op for zero offsets
            };
        }

        Deque<OffsetContext> stack = OFFSETS.get();
        SumHolder holder = SUM_CACHE.get();
        int previous = threadLocalSum();

        OffsetContext ctx = new OffsetContext(seconds, previous);
        if (!CONTEXT_IDS.get().add(ctx.id)) {
            throw new IllegalStateException("Duplicate offset context");
        }

        stack.addLast(ctx);
        holder.sum = safeAdd(previous, seconds);
        holder.valid = true;

        return new AutoCloseable() {
            private boolean closed;

            @Override
            public void close() {
                if (closed) {
                    LOG.debugf("Offset context %d already closed. Stack=%s", ctx.id, stack);
                    return;
                }
                closed = true;
                removeContext(ctx);
            }
        };
    }

    /** Removes the provided context from the current stack performing validation. */
    private static void removeContext(OffsetContext ctx) {
        Deque<OffsetContext> stack = OFFSETS.get();
        if (stack.isEmpty()) {
            return;
        }

        OffsetContext top = stack.peekLast();
        if (top == ctx) {
            stack.removeLast();
        } else if (stack.remove(ctx)) {
            // out of order close – remove and signal misuse
            CONTEXT_IDS.get().remove(ctx.id);
            SUM_CACHE.get().valid = false;
            throw new IllegalStateException("Offset contexts closed out of order");
        } else {
            // nothing to remove
            return;
        }

        CONTEXT_IDS.get().remove(ctx.id);
        SUM_CACHE.get().valid = false;
    }

    /** Compute the sum of thread local offsets caching the result. */
    private static int threadLocalSum() {
        SumHolder holder = SUM_CACHE.get();
        if (!holder.valid) {
            holder.sum = recomputeThreadLocalSum();
            holder.valid = true;
        }
        return holder.sum;
    }

    /** Recompute the sum, optionally synchronised when debugging is enabled. */
    private static int recomputeThreadLocalSum() {
        Deque<OffsetContext> stack = OFFSETS.get();
        int sum = 0;
        if (DEBUG_SYNCHRONIZED) {
            synchronized (stack) {
                for (OffsetContext c : stack) {
                    sum = safeAdd(sum, c.offset);
                }
            }
        } else {
            for (OffsetContext c : stack) {
                sum = safeAdd(sum, c.offset);
            }
        }
        return sum;
    }

    /** Saturating add protecting against integer overflow. */
    private static int safeAdd(int a, int b) {
        long r = (long) a + (long) b;
        if (r > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (r < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) r;
    }

    // -------------------------------------------------------------------------------------
    //  Wrappers
    // -------------------------------------------------------------------------------------

    /**
     * Wrap a runnable so that the current offset stack is applied while it runs.
     */
    public static Runnable wrapWithOffset(Runnable r) {
        int sum = threadLocalSum();
        return () -> {
            try (AutoCloseable ignored = withTemporaryOffset(sum)) {
                r.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    /**
     * Wrap a callable so that the current offset stack is applied while it runs.
     */
    public static <T> Callable<T> wrapWithOffset(Callable<T> c) {
        int sum = threadLocalSum();
        return () -> {
            try (AutoCloseable ignored = withTemporaryOffset(sum)) {
                return c.call();
            }
        };
    }
}

