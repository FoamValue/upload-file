/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core;

import cn.chenxinjie.uploadfile.core.exception.ChecksumMismatchException;
import cn.chenxinjie.uploadfile.core.exception.UploadValidationException;
import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.model.MergeStatus;
import cn.chenxinjie.uploadfile.core.model.UploadProgress;
import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.core.service.TrustedUploadService;
import cn.chenxinjie.uploadfile.core.storage.ChunkStorage;
import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.store.TaskStoreQuotaStore;
import cn.chenxinjie.uploadfile.core.util.IdentifierLock;
import cn.chenxinjie.uploadfile.core.util.StripedIdentifierLockProvider;
import org.junit.Assume;
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
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

/**
 * Coverage-gap tests for the classes that the behavioural suites leave partially uncovered:
 * {@link TrustedUploadService} (merge-status / task-store reads),
 * {@link TaskStoreQuotaStore} (non-empty usage summation) and the {@link ResumableUploadService}
 * defensive branches (null SPI setters, negative file size, storage IO failure, over-limit chunk
 * after save, require-checksum mismatch, and the cleanup-directory IO failures).
 */
public class CoreCoverageGapTest {

    private static final int CHUNK_SIZE = 1024;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ResumableUploadService service() {
        return new ResumableUploadService(
                new MemoryTaskStore(),
                new LocalFileChunkStorage(folder.getRoot().toPath().resolve("chunks")),
                new File(folder.getRoot(), "files"));
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
    public void trustedFacadeMergeStatusAndTaskStore() throws Exception {
        ResumableUploadService svc = service();
        TrustedUploadService trusted = new TrustedUploadService(svc);

        // Missing task -> NONE status.
        MergeStatus none = trusted.getMergeStatus("missing-id");
        assertEquals("missing-id", none.getIdentifier());
        assertEquals(UploadTask.MERGE_STATE_NONE, none.getState());

        // Existing task -> status derived from the task record.
        svc.uploadChunk(request("st1", 0, 1),
                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));
        MergeStatus present = trusted.getMergeStatus("st1");
        assertEquals("st1", present.getIdentifier());
        assertEquals(UploadTask.MERGE_STATE_NONE, present.getState());

        // The facade exposes the underlying store unchanged.
        assertSame(svc.getTaskStore(), trusted.getTaskStore());
    }

    @Test
    public void taskStoreQuotaStoreUsedBytesSumsTaskSizes() {
        MemoryTaskStore store = new MemoryTaskStore();
        TaskStoreQuotaStore quota = new TaskStoreQuotaStore(store);
        assertEquals(0, quota.usedBytes());

        UploadTask inProgress = new UploadTask();
        inProgress.setIdentifier("in-progress");
        inProgress.setFileSize(100);
        store.save(inProgress);

        UploadTask merged = new UploadTask();
        merged.setIdentifier("merged");
        merged.setMerged(true);
        merged.setFinalFileSize(250);
        store.save(merged);

        // Negative declared sizes are clamped to zero in the usage summation.
        UploadTask negative = new UploadTask();
        negative.setIdentifier("negative");
        negative.setFileSize(-7);
        store.save(negative);

        assertEquals(350, quota.usedBytes());
    }

    @Test
    public void replacingLockProviderAndQuotaStoreRequiresNonNull() {
        ResumableUploadService svc = service();
        assertThrows(NullPointerException.class, () -> svc.setIdentifierLockProvider(null));
        assertThrows(NullPointerException.class, () -> svc.setQuotaStore(null));
    }

    @Test
    public void replacedLockProviderAndQuotaStoreAreUsed() throws Exception {
        ResumableUploadService svc = service();
        AtomicInteger locks = new AtomicInteger();
        svc.setIdentifierLockProvider(identifier -> {
            locks.incrementAndGet();
            return new StripedIdentifierLockProvider(new IdentifierLock()).lock(identifier);
        });
        svc.setQuotaStore(new TaskStoreQuotaStore(svc.getTaskStore()));

        svc.uploadChunk(request("rep1", 0, 1),
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, locks.get());
    }

    @Test
    public void negativeDeclaredFileSizeRejected() {
        ChunkUploadRequest req = request("neg1", 0, 1);
        req.setFileSize(-1);
        assertThrows(UploadValidationException.class,
                () -> service().uploadChunk(req,
                        new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void quotaDisabledWhenMaxTotalBytesNonPositive() throws Exception {
        ResumableUploadService svc = service();
        svc.setMaxTotalBytes(0); // 0 = quota disabled; the check returns without reserving
        UploadProgress p = svc.uploadChunk(request("noq", 0, 1),
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, p.getUploadedCount());
    }

    /** {@link ChunkStorage} whose save always fails with a non-size-limit IO error. */
    private static ChunkStorage failingStorage(IOException failure) {
        return new ChunkStorage() {
            @Override
            public void saveChunk(String identifier, int chunkIndex, InputStream in) throws IOException {
                throw failure;
            }

            @Override
            public boolean chunkExists(String identifier, int chunkIndex) {
                return false;
            }

            @Override
            public File getChunkFile(String identifier, int chunkIndex) {
                return new File(identifier + "-" + chunkIndex);
            }

            @Override
            public List<Integer> listChunks(String identifier) {
                return Collections.emptyList();
            }

            @Override
            public void deleteChunk(String identifier, int chunkIndex) {
            }

            @Override
            public void deleteChunks(String identifier) {
            }
        };
    }

    @Test
    public void storageIoFailureWithoutLimitMessagePropagates() {
        ResumableUploadService svc = new ResumableUploadService(
                new MemoryTaskStore(), failingStorage(new IOException("backend unavailable")),
                new File(folder.getRoot(), "files-io"));
        svc.setMaxChunkBytes(100);

        IOException e = assertThrows(IOException.class,
                () -> svc.uploadChunk(request("io1", 0, 1),
                        new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8))));
        assertEquals("backend unavailable", e.getMessage());
    }

    @Test
    public void chunkOverLimitAfterSaveIsDeletedAndRejected() throws Exception {
        // Storage that ignores the byte limit, so the size check must catch the oversize chunk
        // after the save instead of aborting mid-stream.
        LocalFileChunkStorage storage = new LocalFileChunkStorage(
                folder.getRoot().toPath().resolve("chunks-unlimited")) {
            @Override
            public long saveChunk(String identifier, int chunkIndex, InputStream in, long maxBytes)
                    throws IOException {
                return super.saveChunk(identifier, chunkIndex, in, 0);
            }
        };
        ResumableUploadService svc = new ResumableUploadService(
                new MemoryTaskStore(), storage, new File(folder.getRoot(), "files-unlimited"));
        svc.setMaxChunkBytes(4);

        assertThrows(UploadValidationException.class,
                () -> svc.uploadChunk(request("late1", 0, 1),
                        new ByteArrayInputStream("payload-larger-than-4".getBytes(StandardCharsets.UTF_8))));
        assertFalse(storage.chunkExists("late1", 0));
        assertEquals(0, svc.getProgress("late1").getUploadedCount());
    }

    @Test
    public void requireChecksumRejectsWrongMd5() throws Exception {
        LocalFileChunkStorage storage = new LocalFileChunkStorage(
                folder.getRoot().toPath().resolve("chunks-reqchk"));
        ResumableUploadService svc = new ResumableUploadService(
                new MemoryTaskStore(), storage, new File(folder.getRoot(), "files-reqchk"));
        svc.setRequireChecksum(true);

        ChunkUploadRequest req = request("reqchk2", 0, 1);
        req.setChunkMd5("00000000000000000000000000000000");
        assertThrows(ChecksumMismatchException.class,
                () -> svc.uploadChunk(req,
                        new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8))));
        assertFalse(storage.chunkExists("reqchk2", 0));
        assertEquals(0, svc.getProgress("reqchk2").getUploadedCount());
    }

    @Test
    public void cancelUploadPropagatesDeleteIoFailure() throws Exception {
        ResumableUploadService svc = service();
        svc.uploadChunk(request("c1", 0, 1),
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));

        Path merged = new File(folder.getRoot(), "files").toPath().resolve("c1");
        Files.createDirectories(merged);
        Path artifact = merged.resolve("artifact.bin");
        Files.write(artifact, new byte[]{1});
        merged.toFile().setWritable(false, false);

        try {
            try {
                Files.delete(artifact);
                Assume.assumeTrue("directory write permission not enforced (e.g. running as root)", false);
            } catch (IOException expected) {
                // Permission enforced; the recursive delete must fail on the artifact.
            }
            assertThrows(UncheckedIOException.class, () -> svc.cancelUpload("c1"));
        } finally {
            merged.toFile().setWritable(true, false);
        }
    }

    @Test
    public void cancelUploadPropagatesWalkIoFailure() throws Exception {
        ResumableUploadService svc = service();
        svc.uploadChunk(request("c2", 0, 1),
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));

        Path merged = new File(folder.getRoot(), "files").toPath().resolve("c2");
        Files.createDirectories(merged);
        Path sub = merged.resolve("sub");
        Files.createDirectories(sub);
        sub.toFile().setReadable(false, false);
        sub.toFile().setExecutable(false, false);

        try {
            try (Stream<Path> walk = Files.walk(merged)) {
                walk.forEach(p -> {
                });
                Assume.assumeTrue("unreadable directory does not fail the walk (e.g. running as root)", false);
            } catch (UncheckedIOException expected) {
                // The walk aborts when descending into the unreadable sub-directory.
            }
            assertThrows(UncheckedIOException.class, () -> svc.cancelUpload("c2"));
        } finally {
            sub.toFile().setReadable(true, false);
            sub.toFile().setExecutable(true, false);
        }
    }
}
