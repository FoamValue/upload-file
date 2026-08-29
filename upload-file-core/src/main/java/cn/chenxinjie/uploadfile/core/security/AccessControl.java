/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.security;

import cn.chenxinjie.uploadfile.core.exception.AccessDeniedException;

/**
 * SPI for access control of upload/download operations.
 *
 * <p>The core services invoke {@link #check(String, String, String)} at each entry point before any
 * state is read or mutated; the integration layer extracts the credential (e.g. an HTTP token) and
 * passes it in. Built-in implementations: {@link PermitAllAccessControl} (no-op, the default) and
 * {@link TokenAccessControl} (constant-time shared-token comparison).</p>
 */
public interface AccessControl {

    /** Upload chunk / create task. */
    String ACTION_UPLOAD = "upload";
    /** Query upload progress. */
    String ACTION_PROGRESS = "progress";
    /** Merge chunks (sync). */
    String ACTION_MERGE = "merge";
    /** Submit an async merge. */
    String ACTION_MERGE_ASYNC = "mergeAsync";
    /** Query the async merge status. */
    String ACTION_MERGE_STATUS = "mergeStatus";
    /** Download the merged file. */
    String ACTION_DOWNLOAD = "download";

    /**
     * Checks whether the operation is allowed.
     *
     * @param identifier the file identifier (may be null when unknown, e.g. progress on a missing task)
     * @param action     one of the {@code ACTION_*} constants
     * @param token      the credential supplied by the caller; null when absent
     * @throws AccessDeniedException when the operation is not allowed
     */
    void check(String identifier, String action, String token);
}
