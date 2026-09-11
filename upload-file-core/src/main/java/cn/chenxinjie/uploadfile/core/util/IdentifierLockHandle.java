/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.util;

/**
 * A held identifier lock (rc.7). Closing the handle releases the lock; {@link #close()} does not
 * declare a checked exception so it can be used directly in a try-with-resources block.
 */
public interface IdentifierLockHandle extends AutoCloseable {

    @Override
    void close();
}
