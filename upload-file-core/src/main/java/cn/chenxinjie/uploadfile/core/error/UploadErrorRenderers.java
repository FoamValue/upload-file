/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.error;

import cn.chenxinjie.uploadfile.core.exception.UploadErrorCode;
import cn.chenxinjie.uploadfile.core.exception.UploadErrorCodes;
import cn.chenxinjie.uploadfile.core.model.MergeStatus;
import cn.chenxinjie.uploadfile.core.model.UploadHttpError;
import cn.chenxinjie.uploadfile.core.model.UploadProgress;
import cn.chenxinjie.uploadfile.core.model.UploadResult;
import cn.chenxinjie.uploadfile.core.security.AccessControl;

/**
 * Built-in {@link UploadErrorRenderer} implementations and shared failure helpers (rc.6).
 *
 * <p>The {@code legacy} renderer reproduces the rc.5 per-endpoint failure bodies byte-for-byte;
 * the {@code standard} renderer emits a uniform {@link UploadHttpError} with a stable symbolic
 * code. Integrations pick one via {@link #from(String)} (the {@code http.error-body} setting).</p>
 */
public final class UploadErrorRenderers {

    private static final UploadErrorRenderer LEGACY = new LegacyRenderer();
    private static final UploadErrorRenderer STANDARD = new StandardRenderer();

    private UploadErrorRenderers() {
    }

    /** The rc.5-compatible renderer (per-endpoint empty/error models). */
    public static UploadErrorRenderer legacy() {
        return LEGACY;
    }

    /** The rc.6 uniform renderer ({@link UploadHttpError} + symbolic code). */
    public static UploadErrorRenderer standard() {
        return STANDARD;
    }

    /**
     * Selects a renderer from the {@code http.error-body} mode; anything but {@code standard}
     * keeps the legacy body.
     */
    public static UploadErrorRenderer from(String mode) {
        return "standard".equalsIgnoreCase(mode) ? STANDARD : LEGACY;
    }

    /**
     * Resolves the symbolic code for a failure. Typed {@link UploadErrorCode}s resolve through the
     * catalog; everything else is a server failure.
     */
    public static String codeOf(Throwable error) {
        if (error instanceof UploadErrorCode) {
            return ((UploadErrorCode) error).code();
        }
        return UploadErrorCodes.UPLOAD_SERVER_ERROR;
    }

    /**
     * Resolves the HTTP status for a failure: typed {@link UploadErrorCode}s report their own code;
     * a raw {@link IllegalArgumentException} (blank/unsafe identifiers etc.) is a client error;
     * anything else is a server failure ({@code 500}).
     */
    public static int statusOf(Throwable error) {
        if (error instanceof UploadErrorCode) {
            return ((UploadErrorCode) error).getHttpStatusCode();
        }
        if (error instanceof IllegalArgumentException) {
            return 400;
        }
        return 500;
    }

    private static final class LegacyRenderer implements UploadErrorRenderer {
        @Override
        public Object render(String action, String identifier, int status, String code, String message) {
            if (AccessControl.ACTION_MERGE.equals(action)
                    || AccessControl.ACTION_CANCEL.equals(action)
                    || AccessControl.ACTION_DOWNLOAD.equals(action)) {
                return UploadResult.error(identifier, message);
            }
            if (AccessControl.ACTION_MERGE_ASYNC.equals(action)
                    || AccessControl.ACTION_MERGE_STATUS.equals(action)) {
                return MergeStatus.none(identifier);
            }
            return UploadProgress.empty(identifier);
        }
    }

    private static final class StandardRenderer implements UploadErrorRenderer {
        @Override
        public Object render(String action, String identifier, int status, String code, String message) {
            return UploadHttpError.of(code, status, message, identifier, action);
        }
    }
}
