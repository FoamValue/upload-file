/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.exception;

/**
 * Thrown when an upload/download operation is rejected by the access-control layer
 * (e.g. a missing or wrong token, or an owner mismatch). Mapped by default to HTTP
 * {@code 401}; an {@link #AccessDeniedException(int, String)} instance can carry another
 * status (typically {@code 403} for an authenticated-but-forbidden caller), driven by the
 * {@code AccessDecision} returned from an {@code AccessControl.decide(...)} implementation.
 */
public class AccessDeniedException extends RuntimeException implements UploadErrorCode {

    private static final long serialVersionUID = 1L;

    private final int statusCode;

    public AccessDeniedException(String message) {
        this(401, message);
    }

    public AccessDeniedException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    @Override
    public int getHttpStatusCode() {
        return statusCode;
    }
}
