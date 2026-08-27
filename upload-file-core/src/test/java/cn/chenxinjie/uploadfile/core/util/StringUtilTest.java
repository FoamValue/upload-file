/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class StringUtilTest {

    @Test
    public void blankChecks() {
        assertTrue(StringUtil.isBlank(null));
        assertTrue(StringUtil.isBlank(""));
        assertTrue(StringUtil.isBlank("  "));
        assertFalse(StringUtil.isBlank(" x "));
        assertTrue(StringUtil.isNotBlank(" x "));
        assertFalse(StringUtil.isNotBlank(""));
    }

    @Test
    public void safeIdentifiersAreReturnedAsIs() {
        assertEquals("abc123", StringUtil.requireSafeIdentifier("abc123"));
    }

    @Test
    public void blankIdentifiersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> StringUtil.requireSafeIdentifier(null));
        assertThrows(IllegalArgumentException.class, () -> StringUtil.requireSafeIdentifier(" "));
    }

    @Test
    public void identifiersWithSeparatorsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> StringUtil.requireSafeIdentifier("a/b"));
        assertThrows(IllegalArgumentException.class, () -> StringUtil.requireSafeIdentifier("a\\b"));
        assertThrows(IllegalArgumentException.class, () -> StringUtil.requireSafeIdentifier("."));
        assertThrows(IllegalArgumentException.class, () -> StringUtil.requireSafeIdentifier(".."));
    }

    @Test
    public void safeFileNamesAreReturnedAsIs() {
        assertEquals("demo.bin", StringUtil.requireSafeFileName("demo.bin"));
    }

    @Test
    public void blankFileNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> StringUtil.requireSafeFileName(null));
        assertThrows(IllegalArgumentException.class, () -> StringUtil.requireSafeFileName(" "));
    }

    @Test
    public void fileNamesWithSeparatorsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> StringUtil.requireSafeFileName("a/b"));
        assertThrows(IllegalArgumentException.class, () -> StringUtil.requireSafeFileName("..\\a"));
        assertThrows(IllegalArgumentException.class, () -> StringUtil.requireSafeFileName("."));
    }

    @Test
    public void fileNameWithNulCharacterIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> StringUtil.requireSafeFileName("a\0b"));
    }
}
