/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.util;

/**
 * String utilities.
 */
public final class StringUtil {

    private StringUtil() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static boolean isNotBlank(String s) {
        return !isBlank(s);
    }

    /**
     * Validates an identifier, preventing path traversal and other security risks.
     */
    public static String requireSafeIdentifier(String identifier) {
        if (isBlank(identifier)) {
            throw new IllegalArgumentException("identifier must not be blank");
        }
        checkNoSeparators(identifier, "identifier");
        return identifier;
    }

    /**
     * Validates a {@code fileName} that is used to build file paths, preventing path traversal.
     */
    public static String requireSafeFileName(String fileName) {
        if (isBlank(fileName)) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        if (fileName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Illegal fileName: " + fileName);
        }
        checkNoSeparators(fileName, "fileName");
        return fileName;
    }

    private static void checkNoSeparators(String value, String field) {
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || ".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException("Illegal " + field + ": " + value);
        }
    }
}
