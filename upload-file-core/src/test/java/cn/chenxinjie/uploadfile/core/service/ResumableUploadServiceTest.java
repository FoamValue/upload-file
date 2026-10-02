/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.service;

import cn.chenxinjie.uploadfile.core.exception.ChecksumMismatchException;
import cn.chenxinjie.uploadfile.core.exception.AccessDeniedException;
import cn.chenxinjie.uploadfile.core.exception.QuotaExceededException;
import cn.chenxinjie.uploadfile.core.exception.UploadValidationException;
import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.model.MergeStatus;
import cn.chenxinjie.uploadfile.core.model.UploadProgress;
import cn.chenxinjie.uploadfile.core.model.UploadResult;
import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.security.PermitAllAccessControl;
import cn.chenxinjie.uploadfile.core.security.TokenAccessControl;
import cn.chenxinjie.uploadfile.core.storage.ChunkStorage;
import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import cn.chenxinjie.uploadfile.core.store.FileTaskStore;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import cn.chenxinjie.uploadfile.core.util.ChecksumUtil;
import cn.chenxinjie.uploadfile.core.util.IdentifierLock;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ResumableUploadServiceTest {

    private static final int CHUNK_SIZE = 1024;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ResumableUploadService service;
    private File mergedDir;

    @Before
    public void setUp() throws IOException {
        mergedDir = new File(folder.getRoot(), "files");
        service = new ResumableUploadService(
                new FileTaskStore(new File(folder.getRoot(), "meta").toPath()),
                new LocalFileChunkStorage(new File(folder.getRoot(), "chunks").toPath()),
                mergedDir);
    }

    private ChunkUploadRequest request(String id, int index, int total) {
        ChunkUploadRequest req = new ChunkUploadRequest();
        req.setIdentifier(id);
        req.setFileName("demo.bin");
        req.setFileSize(CHUNK_SIZE * total);
        req.setChunkSize(CHUNK_SIZE);
        req.setChunkTotal(total);
        req.setChunkIndex(index);
        return req;
    }

    @Test
    public void uploadAllChunksAndMerge() throws Exception {
        int total = 4;
        byte[] data = new byte[CHUNK_SIZE * total];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 251);
        }

        for (int i = 0; i < total; i++) {
            byte[] chunk = Arrays.copyOfRange(data, i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE);
            UploadProgress p = service.uploadChunk(request("f1", i, total), new ByteArrayInputStream(chunk));
            assertEquals(i + 1, p.getUploadedCount());
            assertTrue(service.isChunkUploaded("f1", i));
        }

        assertEquals(100, service.getProgress("f1").getProgressPercent());

        UploadResult result = service.merge("f1");
        assertTrue(result.isSuccess());
        assertTrue(result.isMerged());
        File finalFile = new File(result.getFinalPath());
        assertTrue(finalFile.isFile());
        assertArrayEquals(data, Files.readAllBytes(finalFile.toPath()));
        assertEquals(CHUNK_SIZE * total, result.getFinalFileSize());
    }

    @Test
    public void reuploadSameChunkIsIdempotent() throws Exception {
        byte[] chunk = "data".getBytes(StandardCharsets.UTF_8);
        service.uploadChunk(request("f2", 0, 2), new ByteArrayInputStream(chunk));
        UploadProgress p = service.uploadChunk(request("f2", 0, 2), new ByteArrayInputStream(chunk));
        assertEquals(1, p.getUploadedCount());
    }

    @Test
    public void mergeWithoutAllChunksFails() throws Exception {
        service.uploadChunk(request("f3", 0, 4), new ByteArrayInputStream("a".getBytes()));
        service.uploadChunk(request("f3", 1, 4), new ByteArrayInputStream("b".getBytes()));
        assertThrows(IllegalStateException.class, () -> service.merge("f3"));
    }

    @Test
    public void checksumMismatchRejectsChunk() throws Exception {
        ChunkUploadRequest req = request("f4", 0, 2);
        req.setChunkMd5("00000000000000000000000000000000");
        assertThrows(ChecksumMismatchException.class,
                () -> service.uploadChunk(req, new ByteArrayInputStream("bad".getBytes())));
        assertFalse(service.isChunkUploaded("f4", 0));
        assertEquals(0, service.getProgress("f4").getUploadedCount());
    }

    @Test
    public void validReuploadAfterMismatchSucceeds() throws Exception {
        byte[] chunk = "ok-data".getBytes(StandardCharsets.UTF_8);
        ChunkUploadRequest bad = request("f7", 0, 2);
        bad.setChunkMd5("00000000000000000000000000000000");
        assertThrows(ChecksumMismatchException.class,
                () -> service.uploadChunk(bad, new ByteArrayInputStream(chunk)));

        ChunkUploadRequest good = request("f7", 0, 2);
        good.setChunkMd5(ChecksumUtil.md5(chunk));
        UploadProgress p = service.uploadChunk(good, new ByteArrayInputStream(chunk));
        assertEquals(1, p.getUploadedCount());
        assertTrue(service.isChunkUploaded("f7", 0));
    }

    @Test
    public void pathTraversalFileNameRejected() {
        ChunkUploadRequest req = request("f8", 0, 1);
        req.setFileName("../evil.txt");
        assertThrows(IllegalArgumentException.class,
                () -> service.uploadChunk(req, new ByteArrayInputStream("x".getBytes())));
    }

    @Test
    public void blankFileNameRejected() {
        ChunkUploadRequest req = request("f9", 0, 1);
        req.setFileName("");
        assertThrows(IllegalArgumentException.class,
                () -> service.uploadChunk(req, new ByteArrayInputStream("x".getBytes())));
    }

    @Test
    public void mergeRejectsUnsafeFileName() throws Exception {
        byte[] chunk = "data".getBytes(StandardCharsets.UTF_8);
        service.uploadChunk(request("f10", 0, 1), new ByteArrayInputStream(chunk));

        UploadTask task = service.getTaskStore().get("f10").get();
        task.setFileName("../../escape.bin");
        service.getTaskStore().save(task);

        assertThrows(IllegalArgumentException.class, () -> service.merge("f10"));
    }

    @Test
    public void checksumMatchAcceptsChunk() throws Exception {
        byte[] chunk = "ok-data".getBytes(StandardCharsets.UTF_8);
        ChunkUploadRequest req = request("f5", 0, 2);
        req.setChunkMd5(ChecksumUtil.md5(chunk));
        UploadProgress p = service.uploadChunk(req, new ByteArrayInputStream(chunk));
        assertEquals(1, p.getUploadedCount());
    }

    @Test
    public void missingIdentifierRejected() {
        ChunkUploadRequest req = new ChunkUploadRequest();
        req.setChunkIndex(0);
        req.setChunkTotal(1);
        assertThrows(IllegalArgumentException.class,
                () -> service.uploadChunk(req, new ByteArrayInputStream("x".getBytes())));
    }

    @Test
    public void pathTraversalIdentifierRejected() {
        ChunkUploadRequest req = request("../evil", 0, 1);
        assertThrows(IllegalArgumentException.class,
                () -> service.uploadChunk(req, new ByteArrayInputStream("x".getBytes())));
    }

    @Test
    public void outOfRangeChunkIndexRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.uploadChunk(request("f6", 5, 2), new ByteArrayInputStream("x".getBytes())));
    }

    @Test
    public void progressForUnknownIdentifierIsEmpty() {
        UploadProgress p = service.getProgress("nope");
        assertEquals(0, p.getUploadedCount());
        assertFalse(p.isMerged());
    }

    @Test
    public void mergeUnknownIdentifierFails() {
        assertThrows(java.util.NoSuchElementException.class, () -> service.merge("nope"));
    }

    @Test
    public void mergeIsIdempotent() throws Exception {
        byte[] chunk = new byte[CHUNK_SIZE];
        service.uploadChunk(request("f11", 0, 1), new ByteArrayInputStream(chunk));
        UploadResult first = service.merge("f11");
        UploadResult second = service.merge("f11");

        assertTrue(first.isSuccess());
        assertTrue(second.isSuccess());
        assertEquals(first.getFinalPath(), second.getFinalPath());
        assertEquals(first.getFinalFileSize(), second.getFinalFileSize());
    }

    @Test
    public void uploadAfterMergeIsRejected() throws Exception {
        byte[] chunk = new byte[CHUNK_SIZE];
        service.uploadChunk(request("f12", 0, 1), new ByteArrayInputStream(chunk));
        service.merge("f12");

        assertThrows(IllegalStateException.class,
                () -> service.uploadChunk(request("f12", 0, 1), new ByteArrayInputStream(chunk)));
    }

    @Test
    public void atomicMergeLeavesNoTempFileOnSuccess() throws Exception {
        byte[] chunk = new byte[CHUNK_SIZE];
        service.uploadChunk(request("a1", 0, 1), new ByteArrayInputStream(chunk));
        UploadResult result = service.merge("a1");

        assertTrue(result.isSuccess());
        File taskDir = new File(mergedDir, "a1");
        File[] files = taskDir.listFiles();
        assertNotNull(files);
        for (File f : files) {
            assertFalse(f.getName().contains(".merge-"));
        }
    }

    @Test
    public void mergeFailureCleansUpTempFile() throws Exception {
        byte[] chunk = new byte[CHUNK_SIZE];
        service.uploadChunk(request("a2", 0, 1), new ByteArrayInputStream(chunk));

        UploadTask task = service.getTaskStore().get("a2").get();
        task.setFileSize(CHUNK_SIZE + 1); // declare a wrong size to force a rollback
        service.getTaskStore().save(task);

        assertThrows(UploadValidationException.class, () -> service.merge("a2"));

        File taskDir = new File(mergedDir, "a2");
        assertTrue(taskDir.isDirectory());
        File[] files = taskDir.listFiles();
        assertNotNull(files);
        assertEquals(0, files.length);
        assertFalse(service.getProgress("a2").isMerged());
    }

    @Test
    public void mergeWithoutAtomicWritesDirectly() throws Exception {
        // merge.atomic=false preserves the rc.1 direct-write behavior.
        ResumableUploadService legacy = new ResumableUploadService(
                new FileTaskStore(new File(folder.getRoot(), "meta2").toPath()),
                new LocalFileChunkStorage(new File(folder.getRoot(), "chunks2").toPath()),
                new File(folder.getRoot(), "files2"),
                true, true, false);
        byte[] chunk = new byte[CHUNK_SIZE];
        legacy.uploadChunk(request("b1", 0, 1), new ByteArrayInputStream(chunk));
        UploadResult result = legacy.merge("b1");
        assertTrue(result.isSuccess());
        assertTrue(new File(result.getFinalPath()).isFile());
    }

    @Test
    public void submitMergeWithoutAsyncFails() throws Exception {
        byte[] chunk = new byte[CHUNK_SIZE];
        service.uploadChunk(request("c1", 0, 1), new ByteArrayInputStream(chunk));
        // rc.6: async-merge-not-enabled is a client error with a stable code.
        assertThrows(UploadValidationException.class, () -> service.submitMerge("c1"));
    }

    @Test
    public void asyncMergeReachesSucceeded() throws Exception {
        ResumableUploadService svc = asyncService();
        svc.uploadChunk(request("d1", 0, 1), new ByteArrayInputStream(new byte[CHUNK_SIZE]));

        MergeStatus submitted = svc.submitMerge("d1");
        assertEquals(UploadTask.MERGE_STATE_PENDING, submitted.getState());

        MergeStatus terminal = awaitTerminal(svc, "d1");
        assertEquals(UploadTask.MERGE_STATE_SUCCEEDED, terminal.getState());
        assertTrue(terminal.isMerged());
    }

    @Test
    public void asyncMergeFailedCarriesError() throws Exception {
        ResumableUploadService svc = asyncService();
        // Only 1 of 2 chunks uploaded, so the merge must fail.
        svc.uploadChunk(request("d2", 0, 2), new ByteArrayInputStream(new byte[CHUNK_SIZE]));

        svc.submitMerge("d2");
        MergeStatus terminal = awaitTerminal(svc, "d2");
        assertEquals(UploadTask.MERGE_STATE_FAILED, terminal.getState());
        assertTrue(terminal.getMessage() != null && !terminal.getMessage().isEmpty());
        assertFalse(terminal.isMerged());
    }

    @Test
    public void uploadChunkRejectedWhileAsyncMergeInFlight() throws Exception {
        ResumableUploadService svc = asyncService();
        byte[] chunk = new byte[CHUNK_SIZE];
        svc.uploadChunk(request("d3", 0, 1), new ByteArrayInputStream(chunk));
        svc.submitMerge("d3");

        // PENDING/RUNNING/SUCCEEDED all reject new chunks once the merge was submitted.
        assertThrows(IllegalStateException.class,
                () -> svc.uploadChunk(request("d3", 0, 1), new ByteArrayInputStream(chunk)));
    }

    @Test
    public void repeatedAsyncSubmitIsIdempotent() throws Exception {
        ResumableUploadService svc = asyncService();
        byte[] chunk = new byte[CHUNK_SIZE];
        svc.uploadChunk(request("d4", 0, 1), new ByteArrayInputStream(chunk));

        svc.submitMerge("d4");
        MergeStatus again = svc.submitMerge("d4"); // must not re-submit / throw
        assertEquals(UploadTask.MERGE_STATE_PENDING, again.getState());

        MergeStatus terminal = awaitTerminal(svc, "d4");
        assertEquals(UploadTask.MERGE_STATE_SUCCEEDED, terminal.getState());
    }

    @Test
    public void getMergeStatusForUnknownIdentifierIsNone() {
        assertEquals(UploadTask.MERGE_STATE_NONE, service.getMergeStatus("nope").getState());
    }

    @Test
    public void getMergeStatusForSynchronouslyMergedTaskIsSucceeded() throws Exception {
        byte[] chunk = new byte[CHUNK_SIZE];
        service.uploadChunk(request("e1", 0, 1), new ByteArrayInputStream(chunk));
        service.merge("e1");

        MergeStatus status = service.getMergeStatus("e1");
        assertEquals(UploadTask.MERGE_STATE_SUCCEEDED, status.getState());
        assertTrue(status.isMerged());
    }

    @Test
    public void uploadChunkAllowedAfterAsyncFailure() throws Exception {
        ResumableUploadService svc = asyncService();
        svc.uploadChunk(request("d5", 0, 2), new ByteArrayInputStream(new byte[CHUNK_SIZE])); // 1 of 2
        svc.submitMerge("d5");
        assertEquals(UploadTask.MERGE_STATE_FAILED, awaitTerminal(svc, "d5").getState());

        // After FAILED the missing chunk can still be uploaded and merged again.
        svc.uploadChunk(request("d5", 1, 2), new ByteArrayInputStream(new byte[CHUNK_SIZE]));
        assertEquals(2, svc.getProgress("d5").getUploadedCount());

        svc.submitMerge("d5");
        assertEquals(UploadTask.MERGE_STATE_SUCCEEDED, awaitTerminal(svc, "d5").getState());
    }

    @Test
    public void atomicMergeWithoutFsyncSucceeds() throws Exception {
        ResumableUploadService noFsync = new ResumableUploadService(
                new FileTaskStore(new File(folder.getRoot(), "meta4").toPath()),
                new LocalFileChunkStorage(new File(folder.getRoot(), "chunks4").toPath()),
                new File(folder.getRoot(), "files4"),
                true, false, true);
        byte[] chunk = new byte[CHUNK_SIZE];
        noFsync.uploadChunk(request("e2", 0, 1), new ByteArrayInputStream(chunk));
        UploadResult result = noFsync.merge("e2");
        assertTrue(result.isSuccess());
        assertTrue(new File(result.getFinalPath()).isFile());
    }

    @Test
    public void mergeKeepsChunksWhenTaskSaveFails() throws Exception {
        // Regression: if the metadata save fails after the file is merged, the chunks must NOT
        // have been deleted and the PERSISTED task must NOT be marked merged, so the task stays
        // recoverable after a restart (a retried merge can still succeed from the chunks).
        File metaDir = new File(folder.getRoot(), "meta-fail");
        LocalFileChunkStorage chunks = new LocalFileChunkStorage(new File(folder.getRoot(), "chunks-fail").toPath());
        SaveFailingStore failing = new SaveFailingStore(new FileTaskStore(metaDir.toPath()));
        ResumableUploadService svc = new ResumableUploadService(
                failing, chunks, new File(folder.getRoot(), "files-fail"));

        byte[] chunk = new byte[CHUNK_SIZE];
        svc.uploadChunk(request("fail1", 0, 1), new ByteArrayInputStream(chunk));
        failing.failNextSave = true;

        assertThrows(UncheckedIOException.class, () -> svc.merge("fail1"));

        // The durable metadata (read from disk, bypassing the in-memory cache) is not merged
        // and the chunks are still on disk.
        FileTaskStore fresh = new FileTaskStore(metaDir.toPath());
        assertFalse(fresh.get("fail1").get().isMerged());
        assertTrue(chunks.chunkExists("fail1", 0));

        // Once the failure clears, a retried merge succeeds from the remaining chunks.
        failing.failNextSave = false;
        ResumableUploadService retried = new ResumableUploadService(
                new FileTaskStore(metaDir.toPath()), chunks, new File(folder.getRoot(), "files-fail"));
        UploadResult result = retried.merge("fail1");
        assertTrue(result.isSuccess());
    }

    @Test
    public void oversizedChunkExceedingMaxChunkBytesRejected() throws Exception {
        LocalFileChunkStorage chunks = new LocalFileChunkStorage(new File(folder.getRoot(), "chunks-size").toPath());
        ResumableUploadService svc = new ResumableUploadService(
                new FileTaskStore(new File(folder.getRoot(), "meta-size").toPath()),
                chunks,
                new File(folder.getRoot(), "files-size"));
        svc.setMaxChunkBytes(4);

        ChunkUploadRequest req = request("size1", 0, 1);
        assertThrows(IllegalArgumentException.class,
                () -> svc.uploadChunk(req, new ByteArrayInputStream("hello-chunk-too-big".getBytes(StandardCharsets.UTF_8))));

        // No progress recorded and no chunk file left behind.
        assertEquals(0, svc.getProgress("size1").getUploadedCount());
        assertFalse(svc.isChunkUploaded("size1", 0));
        assertFalse(chunks.chunkExists("size1", 0));
    }

    @Test
    public void chunkWithinMaxChunkBytesAccepted() throws Exception {
        ResumableUploadService svc = new ResumableUploadService(
                new FileTaskStore(new File(folder.getRoot(), "meta-size2").toPath()),
                new LocalFileChunkStorage(new File(folder.getRoot(), "chunks-size2").toPath()),
                new File(folder.getRoot(), "files-size2"));
        svc.setMaxChunkBytes(16);

        UploadProgress p = svc.uploadChunk(request("size2", 0, 1),
                new ByteArrayInputStream("small".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, p.getUploadedCount());
    }

    @Test
    public void submitMergeRollsBackWhenExecutorRejected() throws Exception {
        ResumableUploadService svc = asyncService();
        byte[] chunk = new byte[CHUNK_SIZE];
        svc.uploadChunk(request("d6", 0, 1), new ByteArrayInputStream(chunk));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        svc.setAsyncExecutor(executor);
        executor.shutdown(); // simulate a shut-down executor

        assertThrows(RejectedExecutionException.class, () -> svc.submitMerge("d6"));

        // The rollback must leave the task in NONE so it is not stuck in a pending merge.
        assertEquals(UploadTask.MERGE_STATE_NONE, svc.getMergeStatus("d6").getState());
        // Chunks can still be uploaded afterwards.
        svc.uploadChunk(request("d6", 0, 1), new ByteArrayInputStream(chunk));
        assertEquals(1, svc.getProgress("d6").getUploadedCount());
    }

    @Test
    public void chunkTotalMismatchAcrossChunksRejected() throws Exception {
        service.uploadChunk(request("m1", 0, 2), new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> service.uploadChunk(request("m1", 1, 3), new ByteArrayInputStream("b".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void chunkSizeMismatchAcrossChunksRejected() throws Exception {
        service.uploadChunk(request("m2", 0, 2), new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)));
        ChunkUploadRequest req = request("m2", 1, 2);
        req.setChunkSize(CHUNK_SIZE * 2);
        assertThrows(IllegalArgumentException.class,
                () -> service.uploadChunk(req, new ByteArrayInputStream("b".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void fileSizeMismatchAcrossChunksRejected() throws Exception {
        service.uploadChunk(request("m3", 0, 2), new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)));
        ChunkUploadRequest req = request("m3", 1, 2);
        req.setFileSize(CHUNK_SIZE * 2 + 1);
        assertThrows(IllegalArgumentException.class,
                () -> service.uploadChunk(req, new ByteArrayInputStream("b".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void fileNameMismatchAcrossChunksRejected() throws Exception {
        service.uploadChunk(request("m4", 0, 2), new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)));
        ChunkUploadRequest req = request("m4", 1, 2);
        req.setFileName("other.bin");
        assertThrows(IllegalArgumentException.class,
                () -> service.uploadChunk(req, new ByteArrayInputStream("b".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void sharedIdentifierLockSerializesConcurrentUploads() throws Exception {
        IdentifierLock lock = new IdentifierLock();
        ResumableUploadService svc = new ResumableUploadService(
                new FileTaskStore(new File(folder.getRoot(), "meta-lock").toPath()),
                new LocalFileChunkStorage(new File(folder.getRoot(), "chunks-lock").toPath()),
                new File(folder.getRoot(), "files-lock"),
                true, true, true, lock);

        AtomicBoolean completed = new AtomicBoolean(false);
        Thread t;
        java.util.concurrent.locks.Lock held = lock.lockFor("lock1");
        held.lock();
        try {
            t = new Thread(() -> {
                try {
                    svc.uploadChunk(request("lock1", 0, 1), new ByteArrayInputStream(new byte[1]));
                    completed.set(true);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            t.start();
            Thread.sleep(150);
            // The upload must block while the shared lock is held.
            assertFalse(completed.get());
        } finally {
            held.unlock();
        }
        t.join();
        assertTrue(completed.get());
        assertEquals(1, svc.getProgress("lock1").getUploadedCount());
    }

    @Test
    public void zeroOrNegativeChunkTotalRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.uploadChunk(request("z1", 0, 0), new ByteArrayInputStream(new byte[1])));
        assertThrows(IllegalArgumentException.class,
                () -> service.uploadChunk(request("z1", 0, -1), new ByteArrayInputStream(new byte[1])));
    }

    @Test
    public void nonPositiveChunkSizeFallsBackToServerDefault() throws Exception {
        ChunkUploadRequest req = request("z2", 0, 1);
        req.setChunkSize(0);
        service.uploadChunk(req, new ByteArrayInputStream(new byte[1]));
        assertEquals(ResumableUploadService.DEFAULT_CHUNK_SIZE,
                service.getProgress("z2").getChunkSize());
    }

    @Test
    public void isAsyncMergeEnabledReflectsExecutorPresence() throws Exception {
        assertFalse(service.isAsyncMergeEnabled());
        ResumableUploadService svc = asyncService();
        assertTrue(svc.isAsyncMergeEnabled());
    }

    @Test
    public void submitMergeUnknownIdentifierThrows() throws Exception {
        ResumableUploadService svc = asyncService();
        assertThrows(java.util.NoSuchElementException.class, () -> svc.submitMerge("nope"));
    }

    @Test
    public void submitMergeOnSynchronouslyMergedTaskReturnsStatus() throws Exception {
        ResumableUploadService svc = asyncService();
        byte[] chunk = new byte[CHUNK_SIZE];
        svc.uploadChunk(request("sy1", 0, 1), new ByteArrayInputStream(chunk));
        svc.merge("sy1");

        MergeStatus status = svc.submitMerge("sy1");
        assertEquals(UploadTask.MERGE_STATE_SUCCEEDED, status.getState());
        assertTrue(status.isMerged());
    }

    @Test
    public void doAsyncMergeReturnsEarlyWhenTaskDisappears() throws Exception {
        ResumableUploadService svc = new ResumableUploadService(
                new FileTaskStore(new File(folder.getRoot(), "meta-em").toPath()),
                new LocalFileChunkStorage(new File(folder.getRoot(), "chunks-em").toPath()),
                new File(folder.getRoot(), "files-em"));
        svc.uploadChunk(request("em1", 0, 1), new ByteArrayInputStream(new byte[1]));

        ManualExecutor executor = new ManualExecutor();
        svc.setAsyncExecutor(executor);
        svc.submitMerge("em1"); // state becomes PENDING, the task is queued but not run
        svc.getTaskStore().remove("em1"); // the task disappears before the merge runs

        executor.runAll(); // doAsyncMerge must observe the missing task and return

        assertEquals(UploadTask.MERGE_STATE_NONE, svc.getMergeStatus("em1").getState());
    }

    @Test
    public void mergeIgnoresChunkCleanupFailure() throws Exception {
        LocalFileChunkStorage delegate = new LocalFileChunkStorage(new File(folder.getRoot(), "chunks-cf").toPath());
        FailingDeleteChunkStorage chunks = new FailingDeleteChunkStorage(delegate);
        ResumableUploadService svc = new ResumableUploadService(
                new MemoryTaskStore(), chunks, new File(folder.getRoot(), "files-cf"));

        byte[] chunk = new byte[CHUNK_SIZE];
        svc.uploadChunk(request("cf1", 0, 1), new ByteArrayInputStream(chunk));

        // The merge must succeed even though the chunk cleanup throws (best-effort).
        UploadResult result = svc.merge("cf1");
        assertTrue(result.isSuccess());
        assertTrue(result.isMerged());
        assertTrue(new File(result.getFinalPath()).isFile());
    }

    @Test
    public void mergeWithDefaultConstructorAndVerifyFlag() throws Exception {
        // Cover the 4-arg constructor (verifyChecksum=false) and the 3-arg convenience path.
        ResumableUploadService noVerify = new ResumableUploadService(
                new FileTaskStore(new File(folder.getRoot(), "meta-nv").toPath()),
                new LocalFileChunkStorage(new File(folder.getRoot(), "chunks-nv").toPath()),
                new File(folder.getRoot(), "files-nv"), false);
        ChunkUploadRequest req = request("nv1", 0, 1);
        req.setChunkMd5("00000000000000000000000000000000"); // must be ignored when verification is off
        UploadProgress p = noVerify.uploadChunk(req, new ByteArrayInputStream(new byte[1]));
        assertEquals(1, p.getUploadedCount());
    }

    @Test
    public void accessControlDeniesWithoutToken() throws Exception {
        ResumableUploadService secured = securedService("topsecret");

        assertThrows(AccessDeniedException.class,
                () -> secured.uploadChunk(request("s1", 0, 1), new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))));
        assertThrows(AccessDeniedException.class, () -> secured.getProgress("s1"));
        assertThrows(AccessDeniedException.class, () -> secured.merge("s1"));
        assertThrows(AccessDeniedException.class, () -> secured.submitMerge("s1"));
        assertThrows(AccessDeniedException.class, () -> secured.getMergeStatus("s1"));
    }

    @Test
    public void accessControlAllowsWithCorrectToken() throws Exception {
        ResumableUploadService secured = securedService("topsecret");
        ChunkUploadRequest req = request("s2", 0, 1);
        req.setFileSize("hello".length());
        UploadProgress p = secured.uploadChunk(req, "topsecret",
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, p.getUploadedCount());

        assertEquals(1, secured.getProgress("s2", "topsecret").getUploadedCount());
        UploadResult result = secured.merge("s2", "topsecret");
        assertTrue(result.isSuccess());
        assertEquals(UploadTask.MERGE_STATE_SUCCEEDED, secured.getMergeStatus("s2", "topsecret").getState());
    }

    @Test
    public void accessControlRejectsWrongTokenPerEndpoint() throws Exception {
        ResumableUploadService secured = securedService("topsecret");
        assertThrows(AccessDeniedException.class,
                () -> secured.uploadChunk(request("s3", 0, 1), "wrong",
                        new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))));
        assertThrows(AccessDeniedException.class, () -> secured.getProgress("s3", "wrong"));
    }

    @Test
    public void accessControlCanBeReplacedViaSetter() throws Exception {
        ResumableUploadService svc = new ResumableUploadService(
                new FileTaskStore(new File(folder.getRoot(), "meta-ac").toPath()),
                new LocalFileChunkStorage(new File(folder.getRoot(), "chunks-ac").toPath()),
                new File(folder.getRoot(), "files-ac"));
        svc.setAccessControl(new TokenAccessControl("tk"));
        assertThrows(AccessDeniedException.class, () -> svc.getProgress("ac1"));

        svc.setAccessControl(PermitAllAccessControl.INSTANCE);
        assertEquals(0, svc.getProgress("ac1").getUploadedCount());
    }

    @Test
    public void fileSizeExceedingMaxFileBytesRejectedAtFirstChunk() throws Exception {
        ResumableUploadService svc = sizeLimitedService();
        svc.setMaxFileBytes(8);
        ChunkUploadRequest req = request("big1", 0, 1);
        req.setFileSize(100);
        assertThrows(IllegalArgumentException.class,
                () -> svc.uploadChunk(req, new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))));
        assertFalse(svc.isChunkUploaded("big1", 0));
    }

    @Test
    public void fileSizeWithinMaxFileBytesAccepted() throws Exception {
        ResumableUploadService svc = sizeLimitedService();
        svc.setMaxFileBytes(8);
        ChunkUploadRequest req = request("small1", 0, 1);
        req.setFileSize(3);
        assertEquals(1, svc.uploadChunk(req, new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8))).getUploadedCount());
    }

    @Test
    public void mergeRejectsFileExceedingMaxFileBytes() throws Exception {
        ResumableUploadService svc = sizeLimitedService();
        ChunkUploadRequest req = request("big2", 0, 1);
        req.setFileSize(100);
        // The limit is applied before merge; upload it first without the limit being set.
        svc.uploadChunk(req, new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));
        svc.setMaxFileBytes(8);

        // A task whose declared size exceeds the (now configured) limit is rejected at merge time.
        assertThrows(IllegalArgumentException.class, () -> svc.merge("big2"));
    }

    @Test
    public void quotaExceededRejectsNewTask() throws Exception {
        ResumableUploadService svc = sizeLimitedService();
        svc.setMaxTotalBytes(100);
        // one existing merged task of 60 bytes
        UploadTask existing = new UploadTask();
        existing.setIdentifier("done1");
        existing.setFileName("d.bin");
        existing.setChunkTotal(1);
        existing.setMerged(true);
        existing.setFinalFileSize(60);
        existing.setSchemaVersion(UploadTask.CURRENT_SCHEMA_VERSION);
        svc.getTaskStore().save(existing);

        ChunkUploadRequest req = request("quota1", 0, 1);
        req.setFileSize(50); // 60 + 50 > 100
        assertThrows(QuotaExceededException.class,
                () -> svc.uploadChunk(req, new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void quotaWithinLimitAcceptsNewTask() throws Exception {
        ResumableUploadService svc = sizeLimitedService();
        svc.setMaxTotalBytes(100);
        UploadTask existing = new UploadTask();
        existing.setIdentifier("done2");
        existing.setFileName("d.bin");
        existing.setChunkTotal(1);
        existing.setMerged(true);
        existing.setFinalFileSize(60);
        existing.setSchemaVersion(UploadTask.CURRENT_SCHEMA_VERSION);
        svc.getTaskStore().save(existing);

        ChunkUploadRequest req = request("quota2", 0, 1);
        req.setFileSize(30); // 60 + 30 <= 100
        assertEquals(1, svc.uploadChunk(req, new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))).getUploadedCount());
    }

    @Test
    public void quotaExceededRejectsMerge() throws Exception {
        ResumableUploadService svc = sizeLimitedService();
        ChunkUploadRequest req = request("quota3", 0, 1);
        req.setFileSize(120);
        svc.uploadChunk(req, new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));
        svc.setMaxTotalBytes(100);

        assertThrows(QuotaExceededException.class, () -> svc.merge("quota3"));
    }

    @Test
    public void quotaDisabledByDefault() throws Exception {
        ResumableUploadService svc = new ResumableUploadService(
                new FileTaskStore(new File(folder.getRoot(), "meta-q").toPath()),
                new LocalFileChunkStorage(new File(folder.getRoot(), "chunks-q").toPath()),
                new File(folder.getRoot(), "files-q"));
        ChunkUploadRequest req = request("q1", 0, 1);
        req.setFileSize(Long.MAX_VALUE);
        assertEquals(1, svc.uploadChunk(req, new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))).getUploadedCount());
    }

    private ResumableUploadService securedService(String token) throws IOException {
        ResumableUploadService svc = new ResumableUploadService(
                new FileTaskStore(new File(folder.getRoot(), "meta-sec" + token.hashCode()).toPath()),
                new LocalFileChunkStorage(new File(folder.getRoot(), "chunks-sec" + token.hashCode()).toPath()),
                new File(folder.getRoot(), "files-sec" + token.hashCode()),
                true, true, true, new IdentifierLock(), new TokenAccessControl(token));
        return svc;
    }

    private ResumableUploadService sizeLimitedService() throws IOException {
        return new ResumableUploadService(
                new FileTaskStore(new File(folder.getRoot(), "meta-size3").toPath()),
                new LocalFileChunkStorage(new File(folder.getRoot(), "chunks-size3").toPath()),
                new File(folder.getRoot(), "files-size3"));
    }

    private ResumableUploadService asyncService() throws IOException {
        ResumableUploadService svc = new ResumableUploadService(
                new FileTaskStore(new File(folder.getRoot(), "meta3").toPath()),
                new LocalFileChunkStorage(new File(folder.getRoot(), "chunks3").toPath()),
                new File(folder.getRoot(), "files3"));
        svc.setAsyncExecutor(Executors.newSingleThreadExecutor());
        return svc;
    }

    /** Executor that queues tasks and runs them only on demand. */
    private static final class ManualExecutor extends AbstractExecutorService {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            return pending;
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return false;
        }

        void runAll() {
            for (Runnable r : new ArrayList<>(tasks)) {
                r.run();
            }
        }
    }

    /** {@link ChunkStorage} that fails on {@code deleteChunks} (delegates everything else). */
    private static final class FailingDeleteChunkStorage implements ChunkStorage {
        private final ChunkStorage delegate;

        FailingDeleteChunkStorage(ChunkStorage delegate) {
            this.delegate = delegate;
        }

        @Override
        public void saveChunk(String identifier, int chunkIndex, InputStream in) throws IOException {
            delegate.saveChunk(identifier, chunkIndex, in);
        }

        @Override
        public boolean chunkExists(String identifier, int chunkIndex) {
            return delegate.chunkExists(identifier, chunkIndex);
        }

        @Override
        public File getChunkFile(String identifier, int chunkIndex) {
            return delegate.getChunkFile(identifier, chunkIndex);
        }

        @Override
        public List<Integer> listChunks(String identifier) {
            return delegate.listChunks(identifier);
        }

        @Override
        public void deleteChunk(String identifier, int chunkIndex) {
            delegate.deleteChunk(identifier, chunkIndex);
        }

        @Override
        public void deleteChunks(String identifier) {
            throw new RuntimeException("simulated chunk cleanup failure");
        }
    }

    private static MergeStatus awaitTerminal(ResumableUploadService svc, String identifier)
            throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            MergeStatus status = svc.getMergeStatus(identifier);
            String state = status.getState();
            if (UploadTask.MERGE_STATE_SUCCEEDED.equals(state)
                    || UploadTask.MERGE_STATE_FAILED.equals(state)) {
                return status;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Merge did not reach a terminal state: " + identifier);
    }

    /** Delegating {@link TaskStore} that can be made to fail on the next {@code save}. */
    private static final class SaveFailingStore implements TaskStore {
        private final TaskStore delegate;
        volatile boolean failNextSave;

        SaveFailingStore(TaskStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<UploadTask> get(String identifier) {
            return delegate.get(identifier);
        }

        @Override
        public void save(UploadTask task) {
            if (failNextSave) {
                throw new UncheckedIOException("simulated metadata write failure",
                        new IOException("disk full"));
            }
            delegate.save(task);
        }

        @Override
        public boolean remove(String identifier) {
            return delegate.remove(identifier);
        }

        @Override
        public Collection<UploadTask> list() {
            return delegate.list();
        }
    }
}
