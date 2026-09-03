/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.exception;

/**
 * Marks a core exception whose failure has a stable HTTP status-code semantic, so an HTTP
 * integration layer (a servlet, a Spring {@code @ControllerAdvice}, or any other boundary)
 * can map it without guessing from the exception type or message.
 *
 * <p>Recommended mapping by integrations: any {@code UploadErrorCode} uses
 * {@link #getHttpStatusCode()}; anything else is a server-side failure ({@code 500}).</p>
 *
 * <p>The built-in typed exceptions intentionally subclass their generic Java counterparts
 * ({@code IllegalArgumentException}, {@code NoSuchElementException}, {@code IllegalStateException})
 * so existing callers that catch the broad types keep working.</p>
 */
public interface UploadErrorCode {

    /**
     * Returns the HTTP status code the failure should be reported with.
     */
    int getHttpStatusCode();
}
