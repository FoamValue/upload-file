/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.store;

import cn.chenxinjie.uploadfile.core.model.UploadTask;
import org.junit.Before;
import org.junit.Test;

import java.util.Optional;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MemoryTaskStoreTest {

    private MemoryTaskStore store;

    @Before
    public void setUp() {
        store = new MemoryTaskStore();
    }

    private UploadTask task(String id) {
        UploadTask task = new UploadTask();
        task.setIdentifier(id);
        task.setFileName(id + ".bin");
        task.setChunkTotal(2);
        task.setUploadedChunks(new TreeSet<>());
        return task;
    }

    @Test
    public void saveAndGetRoundTrip() {
        store.save(task("m1"));
        Optional<UploadTask> loaded = store.get("m1");
        assertTrue(loaded.isPresent());
        assertEquals("m1.bin", loaded.get().getFileName());
    }

    @Test
    public void getUnknownIdentifierIsEmpty() {
        assertFalse(store.get("missing").isPresent());
    }

    @Test
    public void saveOverwritesExistingTask() {
        UploadTask first = task("m2");
        first.markUploaded(0);
        store.save(first);

        UploadTask second = task("m2");
        second.markUploaded(1);
        store.save(second);

        assertEquals(1, store.get("m2").get().uploadedCount());
        assertTrue(store.get("m2").get().isUploaded(1));
        assertFalse(store.get("m2").get().isUploaded(0));
    }

    @Test
    public void removeReturnsTrueOnlyWhenPresent() {
        store.save(task("m3"));
        assertTrue(store.remove("m3"));
        assertFalse(store.remove("m3"));
    }

    @Test
    public void listReturnsAllTasks() {
        store.save(task("m4"));
        store.save(task("m5"));
        assertEquals(2, store.list().size());
    }

    @Test
    public void unsafeIdentifiersAreRejected() {
        // Consistency with FileTaskStore/JdbcTaskStore/RedisTaskStore: unsafe identifiers must
        // be rejected rather than silently treated as a different key.
        assertThrows(IllegalArgumentException.class, () -> store.get("../escape"));
        assertThrows(IllegalArgumentException.class, () -> store.remove("../escape"));
        UploadTask bad = task("../escape");
        assertThrows(IllegalArgumentException.class, () -> store.save(bad));
    }
}
