/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Checksum utility supporting MD5 / SHA-1 / SHA-256.
 */
public final class ChecksumUtil {

    public static final String MD5 = "MD5";
    public static final String SHA_1 = "SHA-1";
    public static final String SHA_256 = "SHA-256";

    private static final int BUFFER_SIZE = 8192;

    private ChecksumUtil() {
    }

    public static String md5(InputStream in) throws IOException {
        return checksum(MD5, in);
    }

    public static String md5(File file) throws IOException {
        return checksum(MD5, file);
    }

    public static String md5(byte[] data) {
        return checksum(MD5, data);
    }

    public static String sha1(File file) throws IOException {
        return checksum(SHA_1, file);
    }

    public static String sha1(byte[] data) {
        return checksum(SHA_1, data);
    }

    public static String sha256(File file) throws IOException {
        return checksum(SHA_256, file);
    }

    public static String sha256(byte[] data) {
        return checksum(SHA_256, data);
    }

    public static String checksum(String algorithm, File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return checksum(algorithm, in);
        }
    }

    public static String checksum(String algorithm, InputStream in) throws IOException {
        MessageDigest md = digest(algorithm);
        byte[] buffer = new byte[BUFFER_SIZE];
        int n;
        while ((n = in.read(buffer)) != -1) {
            md.update(buffer, 0, n);
        }
        return toHex(md.digest());
    }

    public static String checksum(String algorithm, byte[] data) {
        return toHex(digest(algorithm).digest(data));
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unsupported checksum algorithm: " + algorithm, e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
