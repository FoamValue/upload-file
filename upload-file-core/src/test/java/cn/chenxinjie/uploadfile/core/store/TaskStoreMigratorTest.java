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
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TaskStoreMigratorTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private UploadTask task(String id, int uploaded, int total, boolean merged) {
        UploadTask t = new UploadTask();
        t.setIdentifier(id);
        t.setFileName(id + ".bin");
        t.setFileSize(100L * total);
        t.setChunkSize(100);
        t.setChunkTotal(total);
        TreeSet<Integer> chunks = new TreeSet<>();
        for (int i = 0; i < uploaded; i++) {
            chunks.add(i);
        }
        t.setUploadedChunks(chunks);
        t.setMerged(merged);
        t.setSchemaVersion(UploadTask.CURRENT_SCHEMA_VERSION);
        return t;
    }

    @Test
    public void migratesInFlightAndCompletedTasks() {
        TaskStore source = new FileTaskStore(new File(folder.getRoot(), "src-meta").toPath());
        TaskStore target = new MemoryTaskStore();
        source.save(task("in-flight", 2, 5, false));
        source.save(task("done", 3, 3, true));

        int copied = TaskStoreMigrator.migrate(source, target);

        assertEquals(2, copied);
        assertTrue(target.get("in-flight").isPresent());
        assertEquals(2, target.get("in-flight").get().uploadedCount());
        assertFalse(target.get("in-flight").get().isMerged());
        assertTrue(target.get("done").get().isMerged());
    }

    @Test
    public void migratesBetweenFileStoresPreservingRecords() {
        TaskStore source = new FileTaskStore(new File(folder.getRoot(), "src-meta2").toPath());
        TaskStore target = new FileTaskStore(new File(folder.getRoot(), "dst-meta2").toPath());
        source.save(task("a", 1, 3, false));
        source.save(task("b", 2, 2, true));

        TaskStoreMigrator migrator = new TaskStoreMigrator(target);
        assertEquals(2, migrator.migrate(source));
        assertTrue(target.get("a").isPresent());
        assertTrue(target.get("b").isPresent());
        assertEquals(1, target.get("a").get().uploadedCount());
    }

    @Test
    public void migrationIsIdempotent() {
        TaskStore source = new FileTaskStore(new File(folder.getRoot(), "src-meta3").toPath());
        TaskStore target = new MemoryTaskStore();
        source.save(task("x", 1, 2, false));

        assertEquals(1, TaskStoreMigrator.migrate(source, target));
        assertEquals(1, TaskStoreMigrator.migrate(source, target)); // re-run
        assertEquals(1, target.list().size());
    }

    @Test
    public void skipsTasksWithUnsupportedSchemaVersion() {
        TaskStore source = new FileTaskStore(new File(folder.getRoot(), "src-meta4").toPath());
        TaskStore target = new MemoryTaskStore();
        UploadTask future = task("future", 1, 2, false);
        future.setSchemaVersion(TaskStoreMigrator.MAX_SUPPORTED_SCHEMA_VERSION + 1);
        source.save(future);
        source.save(task("ok", 0, 1, false));

        assertEquals(1, TaskStoreMigrator.migrate(source, target));
        assertFalse(target.get("future").isPresent());
        assertTrue(target.get("ok").isPresent());
    }
}
