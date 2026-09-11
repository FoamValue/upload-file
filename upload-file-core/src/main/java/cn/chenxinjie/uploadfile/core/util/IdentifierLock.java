/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.util;

import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fixed-size striped lock keyed by an upload identifier.
 *
 * <p>Operations on the same identifier (chunk upload, merge, cleanup) are serialized by the
 * per-identifier lock; unrelated identifiers may share a bucket, which only costs a little
 * extra serialization and never deadlocks. Sharing a single instance between the
 * {@link cn.chenxinjie.uploadfile.core.service.ResumableUploadService} and
 * {@link cn.chenxinjie.uploadfile.core.service.StorageCleanupService} makes cleanup mutually
 * exclusive with in-flight uploads/merges of the same identifier.</p>
 *
 * <p>Since rc.7 the core services acquire the lock through {@link #lockFor(String)} (a reentrant
 * {@link Lock} per bucket) via {@link StripedIdentifierLockProvider}; {@link #forIdentifier(String)}
 * is retained for backward compatibility with external callers that used the monitor directly.</p>
 */
public final class IdentifierLock {

    /** Number of lock buckets; fixed size to avoid unbounded growth. */
    private static final int LOCK_COUNT = 64;

    private final Object[] monitors = new Object[LOCK_COUNT];
    private final Lock[] locks = new Lock[LOCK_COUNT];

    public IdentifierLock() {
        for (int i = 0; i < LOCK_COUNT; i++) {
            monitors[i] = new Object();
            locks[i] = new ReentrantLock();
        }
    }

    /**
     * Returns the monitor object for the given identifier.
     *
     * @throws NullPointerException when {@code identifier} is null
     */
    public Object forIdentifier(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        return monitors[bucket(identifier)];
    }

    /**
     * Returns the reentrant lock for the given identifier's bucket (rc.7); this is what the core
     * services use through {@link StripedIdentifierLockProvider}.
     *
     * @throws NullPointerException when {@code identifier} is null
     */
    public Lock lockFor(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        return locks[bucket(identifier)];
    }

    private static int bucket(String identifier) {
        return (identifier.hashCode() & 0x7fffffff) % LOCK_COUNT;
    }
}
