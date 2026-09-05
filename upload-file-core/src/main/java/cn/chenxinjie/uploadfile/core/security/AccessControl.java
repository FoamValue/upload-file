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
 * <p>The core services evaluate {@link #decide(String, String, String)} at each entry point
 * before any state is read or mutated; the integration layer extracts the credential (e.g. an
 * HTTP token) and passes it in. Built-in implementations: {@link PermitAllAccessControl}
 * (no-op, the default) and {@link TokenAccessControl} (constant-time shared-token comparison).</p>
 *
 * <h2>Evolution (rc.6)</h2>
 *
 * <ul>
 *   <li>{@link #check(String, String, String)} is retained and {@code @Deprecated}; existing
 *       implementations keep compiling unchanged. Implementations may express a denial with an
 *       {@link AccessDeniedException} carrying a custom status ({@code 401} default,
 *       {@code 403} for an authenticated-but-forbidden caller).</li>
 *   <li>{@link #decide(String, String, String)} returns an {@link AccessDecision}; its default
 *       implementation derives from {@code check()} (returns normally → allow; throws
 *       {@code AccessDeniedException} → deny with the exception's status), so decision-based and
 *       exception-based implementations are both valid and interoperable.</li>
 * </ul>
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
    /** Cancel an upload task and reclaim its data. */
    String ACTION_CANCEL = "cancel";

    /**
     * Returns an {@link AccessDecision} for the operation.
     *
     * @param identifier the file identifier (may be null when unknown, e.g. progress on a missing task)
     * @param action     one of the {@code ACTION_*} constants
     * @param token      the credential supplied by the caller; null when absent
     */
    default AccessDecision decide(String identifier, String action, String token) {
        try {
            check(identifier, action, token);
            return AccessDecision.allow();
        } catch (AccessDeniedException e) {
            return AccessDecision.deny(e.getStatusCode(), e.getMessage());
        }
    }

    /**
     * Checks whether the operation is allowed (legacy entry point).
     *
     * @param identifier the file identifier (may be null when unknown, e.g. progress on a missing task)
     * @param action     one of the {@code ACTION_*} constants
     * @param token      the credential supplied by the caller; null when absent
     * @throws AccessDeniedException when the operation is not allowed
     * @deprecated since 1.0.0-rc.6 — prefer overriding {@link #decide(String, String, String)};
     *             kept so existing implementations compile and behave unchanged.
     */
    @Deprecated
    void check(String identifier, String action, String token) throws AccessDeniedException;
}
