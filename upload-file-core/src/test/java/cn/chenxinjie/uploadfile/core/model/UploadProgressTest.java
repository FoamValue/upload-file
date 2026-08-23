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

public class UploadProgressTest {

    private UploadTask task(int chunkTotal) {
        UploadTask task = new UploadTask();
        task.setIdentifier("p1");
        task.setFileName("demo.bin");
        task.setFileSize(100);
        task.setChunkSize(50);
        task.setChunkTotal(chunkTotal);
        task.setUploadedChunks(new TreeSet<>());
        return task;
    }

    @Test
    public void fromComputesPercent() {
        UploadTask task = task(4);
        task.markUploaded(0);
        task.markUploaded(1);

        UploadProgress progress = UploadProgress.from(task);
        assertEquals(2, progress.getUploadedCount());
        assertEquals(50, progress.getProgressPercent());
        assertEquals(2, progress.getUploadedChunks().size());
    }

    @Test
    public void fromMergedTaskReportsHundredPercent() {
        UploadTask task = task(0);
        task.setMerged(true);
        assertEquals(100, UploadProgress.from(task).getProgressPercent());
    }

    @Test
    public void fromEmptyTaskReportsZeroPercent() {
        assertEquals(0, UploadProgress.from(task(0)).getProgressPercent());
    }

    @Test
    public void emptyProgressIsNotMerged() {
        UploadProgress progress = UploadProgress.empty("p2");
        assertFalse(progress.isMerged());
        assertEquals(0, progress.getUploadedCount());
        assertEquals(0, progress.getProgressPercent());
        assertTrue(progress.getUploadedChunks().isEmpty());
    }
}
