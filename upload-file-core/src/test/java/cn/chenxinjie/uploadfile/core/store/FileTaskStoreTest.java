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
    public void saveInvalidIdentifierIsRejected() {
        FileTaskStore store = new FileTaskStore(folder.getRoot().toPath());
        assertThrows(IllegalArgumentException.class, () -> store.save(sampleTask("../escape")));
    }
}
