/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.store;

/**
 * SPI for the global capacity quota (rc.7).
 *
 * <p>{@link ResumableUploadService} routes its {@code quota.max-bytes} check through a
 * {@code QuotaStore}. The default {@link TaskStoreQuotaStore} recomputes usage from the
 * {@link TaskStore} on every call (the rc.6 behaviour, approximate and lock-free); the optional
 * {@code RedisQuotaStore} (in {@code upload-file-store-redis}) keeps an atomic counter so
 * concurrent uploads across instances cannot overshoot the limit.</p>
 *
 * <p>The reserve/release model is idempotent per identifier: {@link #tryReserve(String, long, long)}
 * sets that identifier's attributed size (replacing any previous value), matching the core's
 * "exclude this identifier, then add its size" check.</p>
 */
public interface QuotaStore {

    /**
     * Reserves {@code bytes} for {@code identifier} when doing so keeps the total within
     * {@code limitBytes}; returns {@code false} when the limit would be exceeded. A
     * {@code limitBytes <= 0} disables the quota and always returns {@code true}.
     */
    boolean tryReserve(String identifier, long bytes, long limitBytes);

    /**
     * Releases any reservation previously made for {@code identifier}; a no-op when none exists.
     */
    void release(String identifier);

    /**
     * Best-effort current usage in bytes.
     */
    long usedBytes();
}
