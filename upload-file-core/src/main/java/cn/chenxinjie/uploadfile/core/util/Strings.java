/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.util;

/**
 * 字符串工具。
 */
public final class Strings {

    private Strings() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static boolean isNotBlank(String s) {
        return !isBlank(s);
    }

    /**
     * 校验 identifier，防止路径穿越等安全风险。
     */
    public static String requireSafeIdentifier(String identifier) {
        if (isBlank(identifier)) {
            throw new IllegalArgumentException("identifier 不能为空");
        }
        checkNoSeparators(identifier, "identifier");
        return identifier;
    }

    /**
     * 校验用于拼接文件路径的 fileName，防止路径穿越。
     */
    public static String requireSafeFileName(String fileName) {
        if (isBlank(fileName)) {
            throw new IllegalArgumentException("fileName 不能为空");
        }
        if (fileName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("非法的 fileName: " + fileName);
        }
        checkNoSeparators(fileName, "fileName");
        return fileName;
    }

    private static void checkNoSeparators(String value, String field) {
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || ".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException("非法的 " + field + ": " + value);
        }
    }
}
