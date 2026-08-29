/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.util;

/**
 * Distributed lease lock used to prevent multiple instances from running the storage cleanup
 * scheduler at the same time.
 *
 * <p>The core stays free of framework dependencies; the Redis implementation lives in the optional
 * {@code upload-file-store-redis} module. A {@code null} lock in {@code StorageCleanupService}
 * means every instance always proceeds (single-instance behavior).</p>
 */
public interface CleanupLock {

    /**
     * Attempts to acquire the lease.
     *
     * @return {@code true} when this caller holds the lease and may clean; {@code false} when
     *         another instance holds it (this round should be skipped)
     */
    boolean tryAcquire();

    /**
     * Releases the lease; only the holder may release it.
     */
    void release();
}
