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

import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
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

    @Test
    public void constructorDefaultsUseUploadTaskTable() {
        JdbcTaskStore oneArg = new JdbcTaskStore(dataSource);
        assertEquals("upload_task", oneArg.getTableName());

        JdbcTaskStore twoArg = new JdbcTaskStore(dataSource, "upload_task");
        assertEquals("upload_task", twoArg.getTableName());

        assertEquals("upload_task", store.getTableName());
    }

    @Test
    public void rawInitSqlWithoutPlaceholderIsUsedAsIs() {
        // A fully specified statement needs no %s substitution.
        JdbcTaskStore raw = new JdbcTaskStore(dataSource, "t_raw",
                "CREATE TABLE t_raw (id INT PRIMARY KEY, data CLOB, create_time BIGINT, update_time BIGINT)");
        assertEquals("t_raw", raw.getTableName());
    }

    @Test
    public void invalidInitSqlFailsConstruction() {
        assertThrows(IllegalStateException.class,
                () -> new JdbcTaskStore(dataSource, "t_bad", "THIS IS NOT VALID SQL"));
    }

    @Test
    public void getNullAndEmptyIdentifiersReturnEmpty() {
        assertFalse(store.get(null).isPresent());
        assertFalse(store.get("").isPresent());
    }

    @Test
    public void jsonLiteralNullRecordReadsAsEmpty() throws Exception {
        insertRaw("jn", "null");
        assertFalse(store.get("jn").isPresent());
    }

    @Test
    public void recordWithoutUploadedChunksReadsAsEmptySet() throws Exception {
        // An explicit null must be normalized to an empty set (Gson's constructor-initialized
        // default would otherwise hide this fixup path).
        insertRaw("nm", "{\"identifier\":\"nm\",\"fileName\":\"x.bin\",\"chunkTotal\":2,"
                + "\"uploadedChunks\":null}");
        UploadTask task = store.get("nm").get();
        assertEquals(0, task.uploadedCount());
        assertEquals(0, task.getUploadedChunks().size());
    }

    @Test
    public void saveRollsBackWhenInsertFails() {
        JdbcTaskStore narrow = new JdbcTaskStore(dataSource, "narrow",
                "CREATE TABLE narrow (identifier VARCHAR(4) PRIMARY KEY, data CLOB, create_time BIGINT, update_time BIGINT)");
        assertThrows(IllegalStateException.class,
                () -> narrow.save(sampleTask("too-long-identifier")));
    }

    @Test
    public void removeOnMissingTableFails() {
        JdbcTaskStore missing = new JdbcTaskStore(dataSource, "missing_table", null);
        assertThrows(IllegalStateException.class, () -> missing.remove("x"));
    }

    @Test
    public void listOnMissingTableFails() {
        JdbcTaskStore missing = new JdbcTaskStore(dataSource, "missing_table", null);
        assertThrows(IllegalStateException.class, missing::list);
    }

    @Test
    public void listSkipsCorruptRecords() throws Exception {
        store.save(sampleTask("good1"));
        insertRaw("bad1", "{ not valid json ");
        assertEquals(1, store.list().size());
        assertEquals("good1", store.list().iterator().next().getIdentifier());
    }

    @Test
    public void listNormalizesNullUploadedChunks() throws Exception {
        insertRaw("nm2", "{\"identifier\":\"nm2\",\"fileName\":\"x.bin\",\"chunkTotal\":2,"
                + "\"uploadedChunks\":null}");
        assertEquals(1, store.list().size());
        assertEquals(0, store.list().iterator().next().uploadedCount());
    }

    private void insertRaw(String identifier, String dataJson) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO upload_task (identifier, data, create_time, update_time) VALUES ('"
                    + identifier + "', '" + dataJson.replace("'", "''") + "', 0, 0)");
        }
    }
}
