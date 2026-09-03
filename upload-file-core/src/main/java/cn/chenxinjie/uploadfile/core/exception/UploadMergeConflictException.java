/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.exception;

/**
 * Thrown when an operation conflicts with the current state of the upload task: uploading a
 * chunk to an already-merged task, uploading while an async merge is in flight, merging while
 * chunks are missing, or cancelling a task whose async merge is pending/running.
 *
 * <p>Mapped to HTTP {@code 409 Conflict}. Subclasses {@link IllegalStateException},
 * so callers that already catch that broad type keep working.</p>
 */
public class UploadMergeConflictException extends IllegalStateException implements UploadErrorCode {

    private static final long serialVersionUID = 1L;

    public UploadMergeConflictException(String message) {
        super(message);
    }

    @Override
    public int getHttpStatusCode() {
        return 409;
    }
}
