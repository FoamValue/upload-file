/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.exception;

/**
 * Thrown when a chunk MD5 does not match the expected value.
 */
public class ChecksumMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ChecksumMismatchException(String message) {
        super(message);
    }
}
