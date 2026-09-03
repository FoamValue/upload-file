/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.exception;

/**
 * Thrown when a request fails validation and the client can recover by fixing the input:
 * illegal chunk parameters, metadata that disagrees with the first chunk, a file/chunk
 * exceeding the configured size limits, or missing chunks at merge time.
 *
 * <p>Mapped to HTTP {@code 400 Bad Request}. Subclasses {@link IllegalArgumentException},
 * so callers that already catch that broad type keep working.</p>
 */
public class UploadValidationException extends IllegalArgumentException implements UploadErrorCode {

    private static final long serialVersionUID = 1L;

    public UploadValidationException(String message) {
        super(message);
    }

    @Override
    public int getHttpStatusCode() {
        return 400;
    }
}
