/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.exception;

import java.util.NoSuchElementException;

/**
 * Thrown when an operation targets an upload task that does not exist.
 *
 * <p>Mapped to HTTP {@code 404 Not Found}. Subclasses {@link NoSuchElementException},
 * so callers that already catch that broad type keep working.</p>
 */
public class UploadTaskNotFoundException extends NoSuchElementException implements UploadErrorCode {

    private static final long serialVersionUID = 1L;

    public UploadTaskNotFoundException(String message) {
        super(message);
    }

    @Override
    public int getHttpStatusCode() {
        return 404;
    }
}
