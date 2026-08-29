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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class UploadTaskTest {

    private UploadTask task(int chunkTotal) {
        UploadTask task = new UploadTask();
        task.setIdentifier("t1");
        task.setFileName("demo.bin");
        task.setChunkTotal(chunkTotal);
        task.setUploadedChunks(new TreeSet<>());
        return task;
    }

    @Test
    public void uploadedCountIsZeroWhenSetIsNull() {
        UploadTask task = task(2);
        task.setUploadedChunks(null);
        assertEquals(0, task.uploadedCount());
    }

    @Test
    public void isCompleteReflectsProgress() {
        UploadTask task = task(2);
        task.markUploaded(0);
        assertFalse(task.isComplete());
        task.markUploaded(1);
        assertTrue(task.isComplete());

        UploadTask zero = task(0);
        assertFalse(zero.isComplete());
    }

    @Test
    public void createAndMergeTimestampsRoundTrip() {
        UploadTask task = task(1);
        task.setCreateTime(111L);
        task.setMergeStartedAt(222L);
        assertEquals(111L, task.getCreateTime());
        assertEquals(222L, task.getMergeStartedAt());

        task.setUpdateTime(333L);
        assertEquals(333L, task.getUpdateTime());
    }

    @Test
    public void mergeStateGetters() {
        UploadTask task = task(1);
        assertEquals(UploadTask.MERGE_STATE_NONE, task.mergeState());
        task.setMergeState(UploadTask.MERGE_STATE_FAILED);
        assertEquals(UploadTask.MERGE_STATE_FAILED, task.mergeState());
        assertEquals(UploadTask.MERGE_STATE_FAILED, task.getMergeState());
    }

    @Test
    public void schemaVersionDefaultsToCurrent() {
        UploadTask task = new UploadTask();
        assertEquals(0, task.getSchemaVersion());
        task.normalize();
        assertEquals(UploadTask.CURRENT_SCHEMA_VERSION, task.getSchemaVersion());
    }

    @Test
    public void fromRequestStampsCurrentSchemaVersion() {
        ChunkUploadRequest req = new ChunkUploadRequest();
        req.setIdentifier("id1");
        req.setFileName("a.bin");
        req.setChunkTotal(1);
        UploadTask task = UploadTask.from(req);
        assertEquals(UploadTask.CURRENT_SCHEMA_VERSION, task.getSchemaVersion());
    }

    @Test
    public void normalizeRepairsNullUploadedChunksAndKeepsSchemaVersion() {
        UploadTask task = task(2);
        task.setUploadedChunks(null);
        task.setSchemaVersion(UploadTask.CURRENT_SCHEMA_VERSION);
        task.normalize();
        assertTrue(task.getUploadedChunks() != null && task.getUploadedChunks().isEmpty());
        assertEquals(UploadTask.CURRENT_SCHEMA_VERSION, task.getSchemaVersion());
    }

    @Test
    public void setSchemaVersionRoundTrips() {
        UploadTask task = task(1);
        task.setSchemaVersion(2);
        assertEquals(2, task.getSchemaVersion());
        // normalize() never downgrades an explicitly set version.
        task.normalize();
        assertEquals(2, task.getSchemaVersion());
    }

    @Test
    public void newTaskMergeFieldsAreNullUntilSet() {
        UploadTask task = task(1);
        assertNull(task.getMergeState());
        assertNull(task.getMergeError());
        assertEquals(0L, task.getMergeStartedAt());
    }
}
