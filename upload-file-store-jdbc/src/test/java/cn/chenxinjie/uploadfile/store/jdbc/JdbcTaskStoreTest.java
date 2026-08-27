/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.store.jdbc;

import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.util.ChecksumUtil;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;

import java.util.Optional;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JdbcTaskStoreTest {

    private JdbcDataSource dataSource;
    private JdbcTaskStore store;

    @Before
    public void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:task-store-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        store = new JdbcTaskStore(dataSource, "upload_task", JdbcTaskStore.DEFAULT_INIT_SQL);
    }

    private UploadTask sampleTask(String id) {
        UploadTask task = new UploadTask();
        task.setIdentifier(id);
        task.setFileName("demo.txt");
        task.setFileSize(100);
        task.setChunkSize(50);
        task.setChunkTotal(2);
        task.setUploadedChunks(new TreeSet<>());
        return task;
    }

    @Test
    public void saveGetRoundTrip() {
        UploadTask task = sampleTask("a1");
        task.markUploaded(0);
        store.save(task);

        Optional<UploadTask> loaded = store.get("a1");
        assertTrue(loaded.isPresent());
        assertEquals("a1", loaded.get().getIdentifier());
        assertEquals(1, loaded.get().uploadedCount());
        assertTrue(loaded.get().isUploaded(0));
    }

    @Test
    public void updateOverwrites() {
        UploadTask task = sampleTask("b1");
        task.markUploaded(0);
        store.save(task);
        task.markUploaded(1);
        store.save(task);

        assertEquals(2, store.get("b1").get().uploadedCount());
    }

    @Test
    public void removeDeletes() {
        store.save(sampleTask("c1"));
        assertTrue(store.remove("c1"));
        assertFalse(store.get("c1").isPresent());
        assertFalse(store.remove("c1"));
    }

    @Test
    public void listReturnsAllTasks() {
        store.save(sampleTask("d1"));
        store.save(sampleTask("d2"));
        assertEquals(2, store.list().size());
    }

    @Test
    public void getUnknownIdentifierIsEmpty() {
        assertFalse(store.get("nope").isPresent());
    }

    @Test
    public void mergeFieldsSurviveRoundTrip() {
        UploadTask task = sampleTask("e1");
        task.setMergeState(UploadTask.MERGE_STATE_FAILED);
        task.setMergeError("boom");
        task.setMergeStartedAt(42L);
        store.save(task);

        UploadTask loaded = store.get("e1").get();
        assertEquals(UploadTask.MERGE_STATE_FAILED, loaded.mergeState());
        assertEquals("boom", loaded.getMergeError());
        assertEquals(42L, loaded.getMergeStartedAt());
    }

    @Test
    public void workableWithAnUploadService() throws Exception {
        // Integration sanity: CRUD + list is usable by the core services.
        cn.chenxinjie.uploadfile.core.service.ResumableUploadService service =
                new cn.chenxinjie.uploadfile.core.service.ResumableUploadService(
                        store,
                        new cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage(
                                "target/jdbc-chunks-" + System.nanoTime()),
                        new java.io.File("target/jdbc-files-" + System.nanoTime()));

        cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest req = new cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest();
        req.setIdentifier("f1");
        req.setFileName("demo.bin");
        req.setChunkSize(5);
        req.setChunkTotal(1);
        req.setChunkIndex(0);
        byte[] data = "hello".getBytes("UTF-8");
        req.setChunkMd5(ChecksumUtil.md5(data));

        service.uploadChunk(req, new java.io.ByteArrayInputStream(data));
        cn.chenxinjie.uploadfile.core.model.UploadResult result = service.merge("f1");
        assertTrue(result.isSuccess());
        assertTrue(service.getProgress("f1").isMerged());
    }
}
