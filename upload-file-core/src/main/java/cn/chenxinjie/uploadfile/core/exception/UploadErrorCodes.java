/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.exception;

/**
 * Stable symbolic error-code catalog (rc.6).
 *
 * <p>Single source of truth for the machine-readable codes attached to every
 * {@link UploadErrorCode} failure and rendered by the {@code standard} HTTP error body.
 * Codes are explicit constants — never derived from class names — so they do not drift when
 * an exception is renamed. API docs and this catalog are kept in lockstep (see docs/API).</p>
 */
public final class UploadErrorCodes {

    /** Invalid parameters, metadata disagreement, size limits, or missing chunks on merge. */
    public static final String UPLOAD_VALIDATION = "UPLOAD_VALIDATION";
    /** A chunk MD5 did not match; only the offending chunk was rejected. */
    public static final String UPLOAD_CHECKSUM = "UPLOAD_CHECKSUM";
    /** The task does not exist (e.g. merge/cancel of an unknown identifier). */
    public static final String UPLOAD_NOT_FOUND = "UPLOAD_NOT_FOUND";
    /** Merge-state conflict (chunk to a merged/in-flight task, or cancel during an async merge). */
    public static final String UPLOAD_MERGE_CONFLICT = "UPLOAD_MERGE_CONFLICT";
    /** The access-control layer rejected the operation. */
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    /** The global capacity quota {@code quota.max-bytes} would be exceeded. */
    public static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    /** The requested action parameter is missing. */
    public static final String MISSING_ACTION = "MISSING_ACTION";
    /** The requested action parameter is unknown on this endpoint. */
    public static final String UPLOAD_UNKNOWN_ACTION = "UPLOAD_UNKNOWN_ACTION";
    /** The required identifier parameter is missing. */
    public static final String MISSING_IDENTIFIER = "MISSING_IDENTIFIER";
    /** A server-side failure on an upload endpoint. */
    public static final String UPLOAD_SERVER_ERROR = "UPLOAD_SERVER_ERROR";
    /** A requested HTTP Range cannot be satisfied. */
    public static final String RANGE_NOT_SATISFIABLE = "RANGE_NOT_SATISFIABLE";

    private UploadErrorCodes() {
    }

    /**
     * Maps a typed {@link UploadErrorCode} to its stable catalog code.
     *
     * @return a catalog constant, or {@link #UPLOAD_SERVER_ERROR} for an unknown implementor
     */
    public static String codeOf(UploadErrorCode error) {
        if (error instanceof UploadValidationException) {
            return UPLOAD_VALIDATION;
        }
        if (error instanceof ChecksumMismatchException) {
            return UPLOAD_CHECKSUM;
        }
        if (error instanceof UploadTaskNotFoundException) {
            return UPLOAD_NOT_FOUND;
        }
        if (error instanceof UploadMergeConflictException) {
            return UPLOAD_MERGE_CONFLICT;
        }
        if (error instanceof AccessDeniedException) {
            return ACCESS_DENIED;
        }
        if (error instanceof QuotaExceededException) {
            return QUOTA_EXCEEDED;
        }
        return UPLOAD_SERVER_ERROR;
    }
}
