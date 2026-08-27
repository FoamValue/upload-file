/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MergeStatusTest {

    @Test
    public void fromCarriesAllFields() {
        UploadTask task = new UploadTask();
        task.setIdentifier("s1");
        task.setMerged(true);
        task.setFinalPath("/data/files/s1/demo.bin");
        task.setFinalFileSize(1024L);
        task.setMergeState(UploadTask.MERGE_STATE_FAILED);
        task.setMergeError("boom");

        MergeStatus status = MergeStatus.from(task);
        assertEquals("s1", status.getIdentifier());
        assertEquals(UploadTask.MERGE_STATE_FAILED, status.getState());
        assertTrue(status.isMerged());
        assertEquals("/data/files/s1/demo.bin", status.getFinalPath());
        assertEquals(1024L, status.getFinalFileSize());
        assertEquals("boom", status.getMessage());
    }

    @Test
    public void synchronouslyMergedTaskReportsSucceeded() {
        UploadTask task = new UploadTask();
        task.setIdentifier("s2");
        task.setMerged(true);
        task.setFinalPath("/x");
        task.setFinalFileSize(5L);

        MergeStatus status = MergeStatus.from(task);
        assertEquals(UploadTask.MERGE_STATE_SUCCEEDED, status.getState());
        assertTrue(status.isMerged());
        assertEquals("/x", status.getFinalPath());
        assertEquals(5L, status.getFinalFileSize());
    }

    @Test
    public void noneCarriesIdentifierAndNoneState() {
        MergeStatus status = MergeStatus.none("n1");
        assertEquals("n1", status.getIdentifier());
        assertEquals(UploadTask.MERGE_STATE_NONE, status.getState());
        assertFalse(status.isMerged());
        assertNull(status.getMessage());
        assertNull(status.getFinalPath());
    }
}
