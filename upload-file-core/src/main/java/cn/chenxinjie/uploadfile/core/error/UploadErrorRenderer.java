/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.error;

/**
 * Renders a failure body for an HTTP integration (rc.6).
 *
 * <p>An integration selects a renderer to shape the JSON written on failure. The built-in
 * {@code legacy} renderer keeps the rc.5 per-endpoint models ({@code UploadProgress.empty} /
 * {@code UploadResult.error} / {@code MergeStatus.none}); {@code standard} renders a uniform
 * {@code UploadHttpError} with a stable symbolic {@code code}. A host that needs its own envelope
 * (e.g. its unified {@code ApiResponse}) implements this SPI and installs its renderer.</p>
 */
public interface UploadErrorRenderer {

    /**
     * Builds the failure body for the given endpoint context.
     *
     * @param action     one of the {@code AccessControl.ACTION_*} constants
     * @param identifier the file identifier (may be null)
     * @param status     the HTTP status that will be sent
     * @param code       a stable symbolic code from the {@code UploadErrorCodes} catalog
     * @param message    a human-readable, non-leaking message
     * @return the body object to serialize as JSON
     */
    Object render(String action, String identifier, int status, String code, String message);
}
