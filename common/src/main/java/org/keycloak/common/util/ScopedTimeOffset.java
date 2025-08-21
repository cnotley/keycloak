/*
 * Copyright 2024 Red Hat, Inc. and/or its affiliates
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

/**
 * Represents a temporary time offset scoped to the current thread. Instances are created by
 * {@link Time#withScopedOffset(int)} and must be {@link #close() closed} in the reverse order of creation.
 *
 * @author Keycloak
 */
public final class ScopedTimeOffset implements AutoCloseable {

    static final ScopedTimeOffset NOOP = new ScopedTimeOffset();

    private final Time.OffsetStack stack;
    private final int offset;
    private final int depth;
    private boolean closed;

    private ScopedTimeOffset() {
        this.stack = null;
        this.offset = 0;
        this.depth = 0;
        this.closed = true;
    }

    ScopedTimeOffset(Time.OffsetStack stack, int offset, int depth) {
        this.stack = stack;
        this.offset = offset;
        this.depth = depth;
    }

    /**
     * Removes the scoped offset. The scope must be closed in the thread that created it and in
     * strict LIFO order.
     *
     * @throws IllegalStateException if closed out of order or from a different thread
     */
    @Override
    public void close() {
        if (closed) {
            throw new IllegalStateException("Scope already closed");
        }
        if (Time.SCOPED_OFFSETS.get() != stack) {
            throw new IllegalStateException("Scope closed in different thread");
        }
        stack.pop(offset, depth);
        closed = true;
    }
}

