/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.exception;

/**
 * Thrown when accepting a request would exceed the configured global capacity quota.
 * Mapped to HTTP {@code 507 Insufficient Storage} by the servlet layer.
 */
public class QuotaExceededException extends RuntimeException implements UploadErrorCode {

    private static final long serialVersionUID = 1L;

    public QuotaExceededException(String message) {
        super(message);
    }

    @Override
    public int getHttpStatusCode() {
        return 507;
    }
}
