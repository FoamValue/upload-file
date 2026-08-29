/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.security;

import cn.chenxinjie.uploadfile.core.exception.AccessDeniedException;

import java.security.MessageDigest;
import java.util.Objects;

/**
 * {@link AccessControl} backed by a shared token compared in constant time, so a request is
 * accepted only when the supplied token matches the configured one. A missing or wrong token
 * (or a missing identifier) is rejected with {@link AccessDeniedException}.
 */
public final class TokenAccessControl implements AccessControl {

    private final byte[] expected;
    private final String expectedToken;

    public TokenAccessControl(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        this.expectedToken = token.trim();
        this.expected = expectedToken.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static AccessControl ifConfigured(String token) {
        return token == null || token.trim().isEmpty() ? PermitAllAccessControl.INSTANCE : new TokenAccessControl(token);
    }

    @Override
    public void check(String identifier, String action, String token) {
        if (identifier == null || identifier.trim().isEmpty()) {
            throw new AccessDeniedException("Access denied: missing identifier");
        }
        if (token == null || !constantTimeEquals(token.trim(), expectedToken)) {
            throw new AccessDeniedException("Access denied: invalid token for identifier: " + identifier);
        }
    }

    /**
     * Compares two strings in constant time to avoid leaking information through timing.
     */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return Objects.toString(this.getClass().getSimpleName(), "");
    }
}
