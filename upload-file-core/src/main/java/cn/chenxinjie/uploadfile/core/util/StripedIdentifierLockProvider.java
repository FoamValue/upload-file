/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.util;

import java.util.Objects;
import java.util.concurrent.locks.Lock;

/**
 * In-process {@link IdentifierLockProvider} backed by the striped {@link IdentifierLock} (rc.7).
 *
 * <p>Wrapping the same {@link IdentifierLock} instance keeps the original "shared lock" contract:
 * the upload service and the cleanup service built from the same instance serialize on the same
 * per-bucket lock.</p>
 */
public class StripedIdentifierLockProvider implements IdentifierLockProvider {

    private final IdentifierLock identifierLock;

    public StripedIdentifierLockProvider() {
        this(new IdentifierLock());
    }

    public StripedIdentifierLockProvider(IdentifierLock identifierLock) {
        this.identifierLock = Objects.requireNonNull(identifierLock, "identifierLock");
    }

    @Override
    public IdentifierLockHandle lock(String identifier) {
        Lock lock = identifierLock.lockFor(identifier);
        lock.lock();
        return lock::unlock;
    }
}
