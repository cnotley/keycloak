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

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class Time {

    /**
     * Global offset applied to every time computation.  This is kept for backward
     * compatibility, but users are encouraged to use {@link #withOffset(int)} to
     * apply offsets that are scoped to the current thread and its descendants.
     */
    private static volatile int offset;

    /**
     * Thread local stack of offsets.  Each element stores the cumulative offset at
     * the time it was pushed so that offset computation is O(1).  The stack is
     * inheritable so that child threads created while an offset is in effect see
     * the same adjusted time as their parent.
     */
    private static final InheritableThreadLocal<OffsetContext> THREAD_OFFSET = new InheritableThreadLocal<>();

    private static final boolean DEBUG = Boolean.getBoolean("keycloak.time.debug");

    /**
     * Handle representing an offset pushed onto the current thread.  When
     * {@link #close()} is invoked the offset is removed restoring the previous
     * value.  Instances are AutoCloseable allowing usage with
     * try-with-resources blocks.
     */
    public static final class OffsetContext implements AutoCloseable {
        private final OffsetContext parent;
        private final int offset;
        private final int total;
        private boolean closed;

        private OffsetContext(OffsetContext parent, int offset) {
            this.parent = parent;
            this.offset = offset;
            this.total = (parent == null ? 0 : parent.total) + offset;
        }

        @Override
        public void close() {
            if (DEBUG && closed) {
                throw new IllegalStateException("Offset already closed");
            }
            closed = true;
            OffsetContext current = THREAD_OFFSET.get();
            if (DEBUG && current != this) {
                throw new IllegalStateException("Offsets closed out of order");
            }
            if (parent == null) {
                THREAD_OFFSET.remove();
            } else {
                THREAD_OFFSET.set(parent);
            }
        }
    }

    private static int threadOffset() {
        OffsetContext ctx = THREAD_OFFSET.get();
        return ctx == null ? 0 : ctx.total;
    }

    private static int totalOffset() {
        return offset + threadOffset();
    }

    /**
     * Pushes a temporary offset for the current thread.  The returned context must
     * be closed to restore the previous state.  Any child thread created while the
     * context is active inherits the offset as part of its initial state.
     *
     * @param seconds amount of offset in seconds to add
     * @return handle that removes the offset when closed
     */
    public static OffsetContext withOffset(int seconds) {
        OffsetContext parent = THREAD_OFFSET.get();
        OffsetContext ctx = new OffsetContext(parent, seconds);
        THREAD_OFFSET.set(ctx);
        return ctx;
    }

    /**
     * Returns current time in seconds adjusted by the global and thread specific
     * offsets.
     *
     * @return current time in seconds
     */
    public static int currentTime() {
        long seconds = System.currentTimeMillis() / 1000L;
        return Math.toIntExact(seconds + totalOffset());
    }

    /**
     * Returns current time in milliseconds adjusted by the global and thread
     * specific offsets.
     *
     * @return current time in milliseconds
     */
    public static long currentTimeMillis() {
        long offMillis = Math.multiplyExact((long) totalOffset(), 1000L);
        return Math.addExact(System.currentTimeMillis(), offMillis);
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

}
