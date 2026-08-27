/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.util;

import java.util.Objects;

/**
 * Fixed-size striped lock keyed by an upload identifier.
 *
 * <p>Operations on the same identifier (chunk upload, merge, cleanup) are serialized by the
 * per-identifier lock; unrelated identifiers may share a bucket, which only costs a little
 * extra serialization and never deadlocks. Sharing a single instance between the
 * {@link cn.chenxinjie.uploadfile.core.service.ResumableUploadService} and
 * {@link cn.chenxinjie.uploadfile.core.service.StorageCleanupService} makes cleanup mutually
 * exclusive with in-flight uploads/merges of the same identifier.</p>
 */
public final class IdentifierLock {

    /** Number of lock buckets; fixed size to avoid unbounded growth. */
    private static final int LOCK_COUNT = 64;

    private final Object[] locks = new Object[LOCK_COUNT];

    public IdentifierLock() {
        for (int i = 0; i < LOCK_COUNT; i++) {
            locks[i] = new Object();
        }
    }

    /**
     * Returns the monitor object for the given identifier.
     *
     * @throws NullPointerException when {@code identifier} is null
     */
    public Object forIdentifier(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        return locks[(identifier.hashCode() & 0x7fffffff) % LOCK_COUNT];
    }
}
