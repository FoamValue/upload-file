/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.security;

/**
 * Result of an {@link AccessControl} decision (rc.6).
 *
 * <p>A denial carries an explicit HTTP status so an integration can distinguish
 * {@code 401} (unauthenticated) from {@code 403} (authenticated but forbidden),
 * and a human-readable reason for audit/logging.</p>
 */
public final class AccessDecision {

    private static final AccessDecision ALLOW = new AccessDecision(true, 200, null);

    private final boolean allowed;
    private final int statusCode;
    private final String reason;

    private AccessDecision(boolean allowed, int statusCode, String reason) {
        this.allowed = allowed;
        this.statusCode = statusCode;
        this.reason = reason;
    }

    /** Permits the operation. */
    public static AccessDecision allow() {
        return ALLOW;
    }

    /**
     * Rejects the operation.
     *
     * @param statusCode an HTTP 4xx/5xx status ({@code 401}/{@code 403} are typical)
     * @param reason     a human-readable reason (may be null)
     */
    public static AccessDecision deny(int statusCode, String reason) {
        if (statusCode < 400 || statusCode > 599) {
            throw new IllegalArgumentException("deny status must be an HTTP 4xx/5xx code, got " + statusCode);
        }
        return new AccessDecision(false, statusCode, reason);
    }

    public boolean allowed() {
        return allowed;
    }

    /** HTTP status to report when denied; {@code 200} when allowed. */
    public int statusCode() {
        return statusCode;
    }

    public String reason() {
        return reason;
    }

    @Override
    public String toString() {
        return allowed ? "ALLOW" : "DENY(" + statusCode + (reason != null ? ", " + reason : "") + ")";
    }
}
