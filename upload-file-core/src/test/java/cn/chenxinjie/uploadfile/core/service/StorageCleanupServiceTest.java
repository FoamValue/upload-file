/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.service;

import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.storage.ChunkStorage;
import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import cn.chenxinjie.uploadfile.core.store.FileTaskStore;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class StorageCleanupServiceTest {

    private static final long HOUR = 3600_000L;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private TaskStore newStore() throws IOException {
        return new FileTaskStore(new File(folder.getRoot(), "meta").toPath());
    }

    private LocalFileChunkStorage newChunks() throws IOException {
        return new LocalFileChunkStorage(new File(folder.getRoot(), "chunks").toPath());
    }

    private File mergedDir() {
        return new File(folder.getRoot(), "files");
    }

    private static UploadTask task(String id, int chunkTotal, long updateTime) {
        UploadTask task = new UploadTask();
        task.setIdentifier(id);
        task.setFileName("demo.bin");
        task.setFileSize(1024L * chunkTotal);
        task.setChunkSize(1024);
        task.setChunkTotal(chunkTotal);
        task.setUpdateTime(updateTime);
        return task;
    }

    @Test
    public void expiredIncompleteTasksAreRemoved() throws Exception {
        TaskStore store = newStore();
        LocalFileChunkStorage chunks = newChunks();
        UploadTask task = task("t1", 2, System.currentTimeMillis() - 2 * HOUR);
        store.save(task);
        chunks.saveChunk("t1", 0, stream("a"));

        StorageCleanupService svc = new StorageCleanupService(store, chunks, mergedDir(), HOUR, false);
        svc.cleanup();

        assertFalse(store.get("t1").isPresent());
        assertFalse(chunks.chunkExists("t1", 0));
    }

    @Test
    public void ttlZeroNeverCleans() throws Exception {
        TaskStore store = newStore();
        LocalFileChunkStorage chunks = newChunks();
        UploadTask task = task("t2", 2, System.currentTimeMillis() - 2 * HOUR);
        store.save(task);
        chunks.saveChunk("t2", 0, stream("a"));

        StorageCleanupService svc = new StorageCleanupService(store, chunks, mergedDir(), 0, false);
        svc.cleanup();

        assertTrue(store.get("t2").isPresent());
        assertTrue(chunks.chunkExists("t2", 0));
    }

    @Test
    public void freshTasksAreKept() throws Exception {
        TaskStore store = newStore();
        LocalFileChunkStorage chunks = newChunks();
        store.save(task("t3", 2, System.currentTimeMillis()));

        StorageCleanupService svc = new StorageCleanupService(store, chunks, mergedDir(), HOUR, false);
        svc.cleanup();

        assertTrue(store.get("t3").isPresent());
    }

    @Test
    public void mergedTasksAreNeverCleaned() throws Exception {
        TaskStore store = newStore();
        LocalFileChunkStorage chunks = newChunks();
        UploadTask task = task("t4", 1, System.currentTimeMillis() - 10 * HOUR);
        task.setMerged(true);
        task.setFinalPath("dummy");
        store.save(task);

        StorageCleanupService svc = new StorageCleanupService(store, chunks, mergedDir(), HOUR, false);
        svc.cleanup();

        assertTrue(store.get("t4").isPresent());
    }

    @Test
    public void orphanChunkDirsAreRemoved() throws Exception {
        TaskStore store = newStore();
        LocalFileChunkStorage chunks = newChunks();
        store.save(task("keep", 1, System.currentTimeMillis()));
        chunks.saveChunk("keep", 0, stream("k"));
        chunks.saveChunk("orphan", 0, stream("o")); // no task record

        StorageCleanupService svc = new StorageCleanupService(store, chunks, mergedDir(), HOUR, true);
        svc.cleanup();

        assertTrue(chunks.chunkExists("keep", 0));
        assertFalse(chunks.chunkExists("orphan", 0));
    }

    @Test
    public void orphanMergedDirsAreRemoved() throws Exception {
        TaskStore store = newStore();
        LocalFileChunkStorage chunks = newChunks();
        store.save(task("keep", 1, System.currentTimeMillis()));
        File keep = new File(mergedDir(), "keep");
        File orphan = new File(mergedDir(), "orphan");
        assertTrue(keep.mkdirs());
        assertTrue(orphan.mkdirs());

        StorageCleanupService svc = new StorageCleanupService(store, chunks, mergedDir(), HOUR, true);
        svc.cleanup();

        assertTrue(keep.isDirectory());
        assertFalse(orphan.exists());
    }

    @Test
    public void customChunkStorageWithoutListIdentifiersIsUntouched() throws Exception {
        TaskStore store = newStore();
        ChunkStorage custom = new MapChunkStorage();
        // Orphan scan must not delete anything when the ChunkStorage cannot enumerate identifiers.
        StorageCleanupService svc = new StorageCleanupService(store, custom, mergedDir(), HOUR, true);
        svc.cleanup(); // must not throw and must not remove anything
        assertTrue(store.list().isEmpty());
    }

    @Test
    public void taskWithZeroUpdateTimeIsKept() throws Exception {
        TaskStore store = newStore();
        LocalFileChunkStorage chunks = newChunks();
        store.save(task("t5", 1, 0)); // updateTime 0 = unknown, must not be treated as expired

        StorageCleanupService svc = new StorageCleanupService(store, chunks, mergedDir(), HOUR, false);
        svc.cleanup();

        assertTrue(store.get("t5").isPresent());
    }

    @Test
    public void startAndStopLifecycle() throws Exception {
        StorageCleanupService svc = new StorageCleanupService(new MemoryTaskStore(), newChunks(), mergedDir(), HOUR, false);
        assertFalse(svc.isRunning());

        svc.start(50);
        assertTrue(svc.isRunning());

        svc.start(10); // idempotent: a second start must not reschedule
        svc.stop();
        assertFalse(svc.isRunning());
    }

    @Test
    public void startWithInvalidIntervalDoesNothing() throws Exception {
        StorageCleanupService svc = new StorageCleanupService(new MemoryTaskStore(), newChunks(), mergedDir(), HOUR, false);
        svc.start(0);
        assertFalse(svc.isRunning());
    }

    @Test
    public void restartAfterStopReschedules() throws Exception {
        StorageCleanupService svc = new StorageCleanupService(new MemoryTaskStore(), newChunks(), mergedDir(), HOUR, false);
        svc.start(50);
        assertTrue(svc.isRunning());
        svc.stop();
        assertFalse(svc.isRunning());

        // A second start must work even though stop() shut the scheduler down.
        svc.start(50);
        assertTrue(svc.isRunning());
        svc.stop();
    }

    @Test
    public void orphanCleanupIsSkippedForMemoryStore() throws Exception {
        // With an in-memory store, all task records are lost on restart, so every on-disk dir
        // would look like an orphan; the orphan scan must be skipped entirely.
        MemoryTaskStore store = new MemoryTaskStore();
        LocalFileChunkStorage chunks = newChunks();
        chunks.saveChunk("orphan", 0, stream("o"));
        File orphanDir = new File(mergedDir(), "orphan");
        assertTrue(orphanDir.mkdirs());

        StorageCleanupService svc = new StorageCleanupService(store, chunks, mergedDir(), HOUR, true);
        svc.cleanup();

        assertTrue(chunks.chunkExists("orphan", 0));
        assertTrue(orphanDir.isDirectory());
    }

    @Test
    public void errorListenerIsInvokedOnCleanupFailure() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StorageCleanupService svc = new StorageCleanupService(
                new FailingStore(), newChunks(), mergedDir(), HOUR, false);
        svc.setErrorListener(failure::set);
        svc.start(50);

        // The first scheduled run fails inside cleanup(); the listener must observe it.
        for (int i = 0; i < 100 && failure.get() == null; i++) {
            Thread.sleep(20);
        }
        svc.stop();

        assertNotNull(failure.get());
        assertTrue(failure.get() instanceof RuntimeException);
    }

    private static InputStream stream(String data) {
        return new ByteArrayInputStream(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static class FailingStore implements TaskStore {
        @Override
        public Optional<UploadTask> get(String identifier) {
            return Optional.empty();
        }

        @Override
        public void save(UploadTask task) {
        }

        @Override
        public boolean remove(String identifier) {
            return false;
        }

        @Override
        public Collection<UploadTask> list() {
            throw new RuntimeException("boom");
        }
    }

    /** Minimal ChunkStorage that does NOT override {@link ChunkStorage#listIdentifiers()}. */
    private static class MapChunkStorage implements ChunkStorage {
        private final Map<String, List<Integer>> chunks = new HashMap<>();

        @Override
        public void saveChunk(String identifier, int chunkIndex, InputStream in) {
            chunks.computeIfAbsent(identifier, k -> new ArrayList<>()).add(chunkIndex);
        }

        @Override
        public boolean chunkExists(String identifier, int chunkIndex) {
            return chunks.getOrDefault(identifier, new ArrayList<>()).contains(chunkIndex);
        }

        @Override
        public File getChunkFile(String identifier, int chunkIndex) {
            return null;
        }

        @Override
        public List<Integer> listChunks(String identifier) {
            return chunks.getOrDefault(identifier, new ArrayList<>());
        }

        @Override
        public void deleteChunk(String identifier, int chunkIndex) {
            chunks.getOrDefault(identifier, new ArrayList<>()).remove((Integer) chunkIndex);
        }

        @Override
        public void deleteChunks(String identifier) {
            chunks.remove(identifier);
        }
    }
}
