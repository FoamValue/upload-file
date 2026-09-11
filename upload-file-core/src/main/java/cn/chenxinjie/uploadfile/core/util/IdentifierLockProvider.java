/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.util;

/**
 * SPI for serializing operations on a single upload identifier (rc.7).
 *
 * <p>The built-in {@link StripedIdentifierLockProvider} serializes within one JVM; the optional
 * {@code RedisIdentifierLockProvider} (in {@code upload-file-store-redis}) serializes across
 * instances that share the same storage. The upload service and the cleanup service must share the
 * same provider so cleanup is mutually exclusive with in-flight uploads/merges.</p>
 */
public interface IdentifierLockProvider {

    /**
     * Acquires the lock for the identifier; the returned handle must be closed (in a
     * try-with-resources block) to release it.
     */
    IdentifierLockHandle lock(String identifier);
}
