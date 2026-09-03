/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.exception;

/**
 * Thrown when a chunk MD5 does not match the expected value.
 * Mapped to HTTP {@code 400 Bad Request} (the client can re-upload the chunk).
 */
public class ChecksumMismatchException extends RuntimeException implements UploadErrorCode {

    private static final long serialVersionUID = 1L;

    public ChecksumMismatchException(String message) {
        super(message);
    }

    @Override
    public int getHttpStatusCode() {
        return 400;
    }
}
