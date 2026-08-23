/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.model;

import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DownloadRangeTest {

    @Test
    public void parsePrefixRange() {
        Optional<DownloadRange> r = DownloadRange.parse("bytes=0-499", 1000);
        assertTrue(r.isPresent());
        assertEquals(0, r.get().getStart());
        assertEquals(499, r.get().getEnd());
        assertEquals(500, r.get().getContentLength());
    }

    @Test
    public void parseOpenEndedRange() {
        Optional<DownloadRange> r = DownloadRange.parse("bytes=500-", 1000);
        assertTrue(r.isPresent());
        assertEquals(500, r.get().getStart());
        assertEquals(999, r.get().getEnd());
    }

    @Test
    public void parseSuffixRange() {
        Optional<DownloadRange> r = DownloadRange.parse("bytes=-500", 1000);
        assertTrue(r.isPresent());
        assertEquals(500, r.get().getStart());
        assertEquals(999, r.get().getEnd());
    }

    @Test
    public void parseSuffixLargerThanFileClampsToZero() {
        Optional<DownloadRange> r = DownloadRange.parse("bytes=-2000", 1000);
        assertTrue(r.isPresent());
        assertEquals(0, r.get().getStart());
        assertEquals(999, r.get().getEnd());
    }

    @Test
    public void parseEndClampedToFileSize() {
        Optional<DownloadRange> r = DownloadRange.parse("bytes=0-999999", 1000);
        assertTrue(r.isPresent());
        assertEquals(999, r.get().getEnd());
    }

    @Test
    public void parseUnsatisfiableReturnsEmpty() {
        assertFalse(DownloadRange.parse("bytes=1000-", 1000).isPresent());
        assertFalse(DownloadRange.parse("bytes=-0", 1000).isPresent());
        assertFalse(DownloadRange.parse("bytes=5-2", 1000).isPresent());
    }

    @Test
    public void parseMalformedReturnsEmpty() {
        assertFalse(DownloadRange.parse(null, 1000).isPresent());
        assertFalse(DownloadRange.parse("bytes=abc", 1000).isPresent());
        assertFalse(DownloadRange.parse("items=0-1", 1000).isPresent());
        assertFalse(DownloadRange.parse("bytes=", 1000).isPresent());
        assertFalse(DownloadRange.parse("bytes=-", 1000).isPresent());
        assertFalse(DownloadRange.parse("bytes=0--1", 1000).isPresent());
    }

    @Test
    public void emptyFileReturnsEmpty() {
        assertFalse(DownloadRange.parse("bytes=0-", 0).isPresent());
    }

    @Test
    public void singleByteRange() {
        Optional<DownloadRange> r = DownloadRange.parse("bytes=0-0", 1000);
        assertTrue(r.isPresent());
        assertEquals(0, r.get().getStart());
        assertEquals(0, r.get().getEnd());
        assertEquals(1, r.get().getContentLength());
    }

    @Test
    public void multipleRangesUsesFirst() {
        Optional<DownloadRange> r = DownloadRange.parse("bytes=0-9,100-199", 1000);
        assertTrue(r.isPresent());
        assertEquals(0, r.get().getStart());
        assertEquals(9, r.get().getEnd());
    }

    @Test
    public void whitespaceIsTolerated() {
        Optional<DownloadRange> r = DownloadRange.parse("bytes= 10 - 20 ", 1000);
        assertTrue(r.isPresent());
        assertEquals(10, r.get().getStart());
        assertEquals(20, r.get().getEnd());
    }
}
