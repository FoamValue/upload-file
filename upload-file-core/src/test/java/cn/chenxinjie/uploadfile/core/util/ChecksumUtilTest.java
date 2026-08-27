/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ChecksumUtilTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void md5OfBytes() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72",
                ChecksumUtil.md5("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void sha1OfBytes() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d",
                ChecksumUtil.sha1("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void sha256OfBytes() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ChecksumUtil.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void checksumIsLowerCaseAndStable() {
        String s = "hello world";
        String a = ChecksumUtil.md5(s.getBytes(StandardCharsets.UTF_8));
        String b = ChecksumUtil.md5(s.getBytes(StandardCharsets.UTF_8));
        assertEquals(a, b);
        assertEquals(a.toLowerCase(), a);
    }

    @Test
    public void checksumsOfFileMatchByteArray() throws Exception {
        File file = new File(folder.getRoot(), "data.txt");
        Files.write(file.toPath(), "abc".getBytes(StandardCharsets.UTF_8));

        assertEquals(ChecksumUtil.md5("abc".getBytes(StandardCharsets.UTF_8)), ChecksumUtil.md5(file));
        assertEquals(ChecksumUtil.sha1("abc".getBytes(StandardCharsets.UTF_8)), ChecksumUtil.sha1(file));
        assertEquals(ChecksumUtil.sha256("abc".getBytes(StandardCharsets.UTF_8)), ChecksumUtil.sha256(file));
    }

    @Test
    public void genericChecksumOfFile() throws Exception {
        File file = new File(folder.getRoot(), "data.txt");
        Files.write(file.toPath(), "abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(ChecksumUtil.md5(file), ChecksumUtil.checksum("MD5", file));
    }

    @Test
    public void unsupportedAlgorithmThrows() {
        assertThrows(IllegalStateException.class,
                () -> ChecksumUtil.checksum("NOPE", "abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void md5OfInputStreamMatchesByteArray() throws Exception {
        assertEquals(ChecksumUtil.md5("abc".getBytes(StandardCharsets.UTF_8)),
                ChecksumUtil.md5(new java.io.ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8))));
    }
}
