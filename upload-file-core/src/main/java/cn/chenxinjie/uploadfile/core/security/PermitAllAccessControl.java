/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.security;

/**
 * {@link AccessControl} that allows every operation. Used as the default when no access control
 * is configured, preserving the pre-rc.3 behavior exactly.
 */
public final class PermitAllAccessControl implements AccessControl {

    /** Shared singleton. */
    public static final PermitAllAccessControl INSTANCE = new PermitAllAccessControl();

    private PermitAllAccessControl() {
    }

    @Override
    public void check(String identifier, String action, String token) {
        // permit all
    }
}
