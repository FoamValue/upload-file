/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.model;

import org.junit.Test;

import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UploadResultTest {

    @Test
    public void mergedCarriesFinalFileInfo() {
        UploadTask task = new UploadTask();
        task.setIdentifier("r1");
        task.setChunkTotal(3);
        task.setUploadedChunks(new TreeSet<>());

        UploadResult result = UploadResult.merged(task, "/data/files/r1/demo.bin", 1024L);

        assertTrue(result.isSuccess());
        assertTrue(result.isMerged());
        assertEquals("Merged successfully", result.getMessage());
        assertEquals("/data/files/r1/demo.bin", result.getFinalPath());
        assertEquals(1024L, result.getFinalFileSize());
        assertEquals(3, result.getUploadedCount());
        assertEquals(3, result.getChunkTotal());
        assertTrue(result.getUploadedChunks().isEmpty());
        assertEquals("r1", result.getIdentifier());
    }

    @Test
    public void errorIsNotSuccessful() {
        UploadResult result = UploadResult.error("r2", "something went wrong");
        assertFalse(result.isSuccess());
        assertFalse(result.isMerged());
        assertEquals("something went wrong", result.getMessage());
        assertEquals("r2", result.getIdentifier());
    }
}
