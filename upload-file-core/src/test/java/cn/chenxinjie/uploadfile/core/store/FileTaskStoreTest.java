/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.store;

import cn.chenxinjie.uploadfile.core.model.UploadTask;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Optional;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class FileTaskStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

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
    public void saveAndGetRoundTrip() {
        FileTaskStore store = new FileTaskStore(folder.getRoot().toPath());
        UploadTask task = sampleTask("a1");
        task.markUploaded(0);
        task.markUploaded(1);
        store.save(task);

        Optional<UploadTask> loaded = store.get("a1");
        assertTrue(loaded.isPresent());
        assertEquals("a1", loaded.get().getIdentifier());
        assertEquals("demo.txt", loaded.get().getFileName());
        assertEquals(2, loaded.get().uploadedCount());
        assertTrue(loaded.get().isUploaded(0));
        assertTrue(loaded.get().isUploaded(1));
    }

    @Test
    public void persistsAcrossStoreInstances() {
        String dir = folder.getRoot().getAbsolutePath();
        FileTaskStore first = new FileTaskStore(dir);
        UploadTask task = sampleTask("b1");
        task.markUploaded(0);
        first.save(task);

        FileTaskStore second = new FileTaskStore(dir);
        Optional<UploadTask> loaded = second.get("b1");
        assertTrue(loaded.isPresent());
        assertEquals(1, loaded.get().uploadedCount());
        assertTrue(loaded.get().isUploaded(0));
    }

    @Test
    public void removeDeletesFile() {
        FileTaskStore store = new FileTaskStore(folder.getRoot().toPath());
        store.save(sampleTask("c1"));
        assertTrue(store.remove("c1"));
        assertFalse(store.get("c1").isPresent());
        assertFalse(store.remove("c1"));
    }

    @Test
    public void listReturnsAllTasks() {
        FileTaskStore store = new FileTaskStore(folder.getRoot().toPath());
        store.save(sampleTask("d1"));
        store.save(sampleTask("d2"));
        assertEquals(2, store.list().size());
    }

    @Test
    public void listEmptyStoreReturnsEmptyList() {
        FileTaskStore store = new FileTaskStore(folder.getRoot().toPath());
        assertTrue(store.list().isEmpty());
    }

    @Test
    public void getInvalidIdentifierIsRejected() {
        FileTaskStore store = new FileTaskStore(folder.getRoot().toPath());
        assertThrows(IllegalArgumentException.class, () -> store.get("../escape"));
    }

    @Test
    public void legacyJsonWithoutMergeFieldsReadsAsNone() throws Exception {
        // rc.1 metadata has no mergeState/mergeError/mergeStartedAt fields.
        String legacyJson = "{"
                + "\"identifier\":\"legacy1\","
                + "\"fileName\":\"demo.txt\","
                + "\"fileSize\":100,"
                + "\"chunkSize\":50,"
                + "\"chunkTotal\":2,"
                + "\"uploadedChunks\":[0,1],"
                + "\"merged\":false"
                + "}";
        java.nio.file.Files.write(
                new java.io.File(folder.getRoot(), "legacy1.json").toPath(),
                legacyJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        FileTaskStore store = new FileTaskStore(folder.getRoot().toPath());
        UploadTask loaded = store.get("legacy1").get();
        assertEquals(UploadTask.MERGE_STATE_NONE, loaded.mergeState());
        assertFalse(loaded.isMerged());
        assertEquals(2, loaded.uploadedCount());
    }

    @Test
    public void mergeFieldsRoundTripThroughJson() {
        FileTaskStore store = new FileTaskStore(folder.getRoot().toPath());
        UploadTask task = sampleTask("m1");
        task.setMergeState(UploadTask.MERGE_STATE_SUCCEEDED);
        task.setMergeError("boom");
        task.setMergeStartedAt(1234L);
        store.save(task);

        UploadTask loaded = store.get("m1").get();
        assertEquals(UploadTask.MERGE_STATE_SUCCEEDED, loaded.mergeState());
        assertEquals("boom", loaded.getMergeError());
        assertEquals(1234L, loaded.getMergeStartedAt());
    }

    @Test
    public void saveInvalidIdentifierIsRejected() {
        FileTaskStore store = new FileTaskStore(folder.getRoot().toPath());
        assertThrows(IllegalArgumentException.class, () -> store.save(sampleTask("../escape")));
    }

    @Test
    public void listSkipsCorruptAndLeftoverTempFiles() throws Exception {
        FileTaskStore store = new FileTaskStore(folder.getRoot().toPath());
        store.save(sampleTask("good1"));

        // A leftover temp file from an interrupted save (partial JSON) must be skipped.
        java.nio.file.Files.write(
                new File(folder.getRoot(), ".meta-abcd.json").toPath(),
                "{\"identifier\":\"good1\",\"fileName\":\"".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // A corrupt metadata file must also be skipped without failing the whole list operation.
        java.nio.file.Files.write(
                new File(folder.getRoot(), "broken.json").toPath(),
                "{ not valid json ".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(1, store.list().size());
        assertEquals("good1", store.list().iterator().next().getIdentifier());
    }
}
