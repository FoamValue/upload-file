/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core;

import cn.chenxinjie.uploadfile.core.security.AccessContext;
import cn.chenxinjie.uploadfile.core.security.AccessContextHolder;
import cn.chenxinjie.uploadfile.core.security.AccessControlListener;
import cn.chenxinjie.uploadfile.core.security.AccessDecision;
import cn.chenxinjie.uploadfile.core.service.StorageCleanupService;
import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import cn.chenxinjie.uploadfile.core.store.FileTaskStore;
import cn.chenxinjie.uploadfile.core.store.QuotaStore;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import cn.chenxinjie.uploadfile.core.store.TaskStoreQuotaStore;
import cn.chenxinjie.uploadfile.core.util.IdentifierLock;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * rc.8 core coverage: the {@code QuotaStore.reconcile} default, {@code AccessContext} /
 * {@code AccessContextHolder}, the {@code AccessControlListener} 6-arg default bridge, and quota
 * reclaim during orphan cleanup (G16/G17/G21).
 */
public class Rc8Test {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void quotaReconcileDefaultIsANoOp() {
        TaskStore store = new FileTaskStore(folder.getRoot().toPath());
        TaskStoreQuotaStore quota = new TaskStoreQuotaStore(store);
        // The default implementation must exist and be a no-op for derived stores.
        quota.reconcile(store);
        assertEquals(0, quota.usedBytes());
    }

    @Test
    public void quotaReconcileCanBeOverridden() {
        AtomicReference<TaskStore> seen = new AtomicReference<>();
        QuotaStore quota = new QuotaStore() {
            @Override
            public boolean tryReserve(String identifier, long bytes, long limitBytes) {
                return true;
            }

            @Override
            public void release(String identifier) {
            }

            @Override
            public long usedBytes() {
                return 0;
            }

            @Override
            public void reconcile(TaskStore taskStore) {
                seen.set(taskStore);
            }
        };
        TaskStore store = new FileTaskStore(folder.getRoot().toPath());
        quota.reconcile(store);
        assertSame(store, seen.get());
    }

    @Test
    public void accessContextHolderDefaultsToEmptyAndClears() {
        assertSame(AccessContext.EMPTY, AccessContextHolder.current());
        assertNull(AccessContextHolder.current().getMethod());

        AccessContext context = new AccessContext("POST", "/upload", "127.0.0.1", "junit");
        AccessContextHolder.set(context);
        assertSame(context, AccessContextHolder.current());
        assertEquals("POST", AccessContextHolder.current().getMethod());
        assertEquals("/upload", AccessContextHolder.current().getUri());
        assertEquals("127.0.0.1", AccessContextHolder.current().getRemoteAddr());
        assertEquals("junit", AccessContextHolder.current().getUserAgent());

        AccessContextHolder.clear();
        assertSame(AccessContext.EMPTY, AccessContextHolder.current());
    }

    @Test
    public void accessContextHolderSetNullClears() {
        AccessContextHolder.set(new AccessContext("POST", "/upload", "127.0.0.1", "junit"));
        AccessContextHolder.set(null);
        assertSame(AccessContext.EMPTY, AccessContextHolder.current());
    }

    @Test
    public void cleanupServiceIgnoresNullIdentifierLockProvider() {
        StorageCleanupService cleanup = new StorageCleanupService(
                new FileTaskStore(folder.getRoot().toPath()),
                new LocalFileChunkStorage(folder.getRoot().toPath().resolve("chunks")),
                new File(folder.getRoot(), "files"), 0L, false, new IdentifierLock());
        // A null provider must be ignored (the shared striped provider is kept), not NPE.
        cleanup.setIdentifierLockProvider(null);
        cleanup.cleanup();
    }

    @Test
    public void accessContextEqualsHashCodeAndToString() {
        AccessContext a = new AccessContext("GET", "/download", "10.0.0.1", "curl");
        AccessContext b = new AccessContext("GET", "/download", "10.0.0.1", "curl");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertTrue(a.toString().contains("/download"));
        assertFalse(a.equals(new AccessContext("POST", "/download", "10.0.0.1", "curl")));
    }

    @Test
    public void listenerSixArgDefaultBridgesToLegacyFiveArg() {
        AtomicBoolean legacyCalled = new AtomicBoolean(false);
        AccessControlListener listener = (identifier, action, decision, elapsedNanos) -> legacyCalled.set(true);

        // A core service now calls the 6-arg overload; an existing 5-arg-only implementation is bridged.
        listener.onDecision(AccessContext.EMPTY, "id", "upload", AccessDecision.allow(), 1L);
        assertTrue(legacyCalled.get());
    }

    @Test
    public void cleanupOrphansReleasesQuotaForOrphanChunksAndMergedDirs() throws Exception {
        File root = folder.getRoot();
        FileTaskStore store = new FileTaskStore(new File(root, "meta").toPath());
        LocalFileChunkStorage chunks = new LocalFileChunkStorage(new File(root, "chunks").toPath());
        File mergedDir = new File(root, "files");

        // An orphan chunk dir (no task record) and an orphan merged dir (merged-but-unconfirmed with
        // its task key already gone).
        chunks.saveChunk("orphanChunk", 0, new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        Path orphanMerged = mergedDir.toPath().resolve("orphanMerged");
        Files.createDirectories(orphanMerged);
        Files.write(orphanMerged.resolve("file.bin"), "y".getBytes(StandardCharsets.UTF_8));

        Set<String> released = new HashSet<>();
        QuotaStore quota = new QuotaStore() {
            @Override
            public boolean tryReserve(String identifier, long bytes, long limitBytes) {
                return true;
            }

            @Override
            public void release(String identifier) {
                released.add(identifier);
            }

            @Override
            public long usedBytes() {
                return 0;
            }
        };

        StorageCleanupService cleanup = new StorageCleanupService(
                store, chunks, mergedDir, 0L, true, new IdentifierLock());
        cleanup.setQuotaStore(quota);
        cleanup.cleanup();

        assertTrue("orphan chunk quota must be released", released.contains("orphanChunk"));
        assertTrue("orphan merged quota must be released", released.contains("orphanMerged"));
        assertFalse(Files.exists(chunks.getChunkFile("orphanChunk", 0).toPath()));
        assertFalse(Files.exists(orphanMerged));
    }

    @Test
    public void cleanupKeepsTasksWithRecordsAndDoesNotReleaseTheirQuota() throws Exception {
        File root = folder.getRoot();
        FileTaskStore store = new FileTaskStore(new File(root, "meta").toPath());
        LocalFileChunkStorage chunks = new LocalFileChunkStorage(new File(root, "chunks").toPath());
        File mergedDir = new File(root, "files");

        // A live in-progress task (record present) must be left alone and its quota untouched.
        cn.chenxinjie.uploadfile.core.model.UploadTask task =
                new cn.chenxinjie.uploadfile.core.model.UploadTask();
        task.setIdentifier("live");
        task.setFileName("demo.bin");
        task.setFileSize(10);
        task.setChunkTotal(1);
        task.setUploadedChunks(new java.util.TreeSet<>());
        store.save(task);
        chunks.saveChunk("live", 0, new ByteArrayInputStream("z".getBytes(StandardCharsets.UTF_8)));

        Set<String> released = new HashSet<>();
        QuotaStore quota = new QuotaStore() {
            @Override
            public boolean tryReserve(String identifier, long bytes, long limitBytes) {
                return true;
            }

            @Override
            public void release(String identifier) {
                released.add(identifier);
            }

            @Override
            public long usedBytes() {
                return 0;
            }
        };

        StorageCleanupService cleanup = new StorageCleanupService(
                store, chunks, mergedDir, 0L, true, new IdentifierLock());
        cleanup.setQuotaStore(quota);
        cleanup.cleanup();

        assertTrue(chunks.chunkExists("live", 0));
        assertFalse(released.contains("live"));
    }
}
