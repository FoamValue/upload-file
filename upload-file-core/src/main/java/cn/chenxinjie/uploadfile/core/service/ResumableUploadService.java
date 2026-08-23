/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.service;

import cn.chenxinjie.uploadfile.core.exception.ChecksumMismatchException;
import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.model.UploadProgress;
import cn.chenxinjie.uploadfile.core.model.UploadResult;
import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.storage.ChunkStorage;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import cn.chenxinjie.uploadfile.core.util.ChecksumUtil;
import cn.chenxinjie.uploadfile.core.util.Strings;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Core resumable-upload service.
 *
 * <p>Responsibilities: chunk persistence, progress tracking, chunk verification, merge and cleanup.</p>
 *
 * <p>Chunk operations of the same identifier are serialized by a per-identifier striped lock,
 * keeping task creation and progress tracking consistent under concurrent uploads.</p>
 */
public class ResumableUploadService {

    public static final int DEFAULT_CHUNK_SIZE = 5 * 1024 * 1024;

    /** Number of lock buckets; fixed size to avoid unbounded growth. */
    private static final int LOCK_COUNT = 64;

    private final TaskStore taskStore;
    private final ChunkStorage chunkStorage;
    private final File mergedFileDir;
    private final boolean verifyChecksum;
    private final Object[] locks = new Object[LOCK_COUNT];

    public ResumableUploadService(TaskStore taskStore, ChunkStorage chunkStorage, File mergedFileDir) {
        this(taskStore, chunkStorage, mergedFileDir, true);
    }

    public ResumableUploadService(TaskStore taskStore, ChunkStorage chunkStorage, File mergedFileDir, boolean verifyChecksum) {
        this.taskStore = Objects.requireNonNull(taskStore, "taskStore");
        this.chunkStorage = Objects.requireNonNull(chunkStorage, "chunkStorage");
        this.mergedFileDir = Objects.requireNonNull(mergedFileDir, "mergedFileDir");
        this.verifyChecksum = verifyChecksum;
        for (int i = 0; i < LOCK_COUNT; i++) {
            locks[i] = new Object();
        }
    }

    private Object lockFor(String identifier) {
        return locks[(identifier.hashCode() & 0x7fffffff) % LOCK_COUNT];
    }

    /**
     * Uploads a chunk.
     *
     * <p>Already-uploaded chunks are skipped (idempotent), which enables resumable upload.</p>
     *
     * @return the current progress
     * @throws ChecksumMismatchException chunk MD5 does not match the expected value
     *         (only the offending chunk is rejected; other uploaded chunks are unaffected)
     */
    public UploadProgress uploadChunk(ChunkUploadRequest request, InputStream in) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(in, "inputStream");
        String identifier = Strings.requireSafeIdentifier(request.getIdentifier());
        int chunkIndex = request.getChunkIndex();
        int chunkTotal = request.getChunkTotal();
        if (chunkTotal <= 0) {
            throw new IllegalArgumentException("chunkTotal must be greater than 0");
        }
        if (chunkIndex < 0 || chunkIndex >= chunkTotal) {
            throw new IllegalArgumentException("chunkIndex out of range: " + chunkIndex);
        }
        long chunkSize = request.getChunkSize() > 0 ? request.getChunkSize() : DEFAULT_CHUNK_SIZE;

        synchronized (lockFor(identifier)) {
            UploadTask task = taskStore.get(identifier).orElse(null);
            if (task == null) {
                Strings.requireSafeFileName(request.getFileName());
                task = UploadTask.from(request);
                task.setChunkSize(chunkSize);
                taskStore.save(task);
            }
            if (task.isMerged()) {
                throw new IllegalStateException("Task already merged, cannot upload chunks: " + identifier);
            }
            if (!task.getUploadedChunks().contains(chunkIndex)) {
                chunkStorage.saveChunk(identifier, chunkIndex, in);
                if (verifyChecksum && Strings.isNotBlank(request.getChunkMd5())) {
                    File saved = chunkStorage.getChunkFile(identifier, chunkIndex);
                    String actual = ChecksumUtil.md5(saved);
                    if (!actual.equalsIgnoreCase(request.getChunkMd5().trim())) {
                        chunkStorage.deleteChunk(identifier, chunkIndex);
                        throw new ChecksumMismatchException(
                                "Chunk " + chunkIndex + " MD5 mismatch, expected "
                                        + request.getChunkMd5() + ", actual " + actual);
                    }
                }
                task.markUploaded(chunkIndex);
                taskStore.save(task);
            }
            return UploadProgress.from(task);
        }
    }

    /**
     * Queries upload progress; an empty progress is returned when the task does not exist,
     * so the client can treat it as a brand-new upload.
     */
    public UploadProgress getProgress(String identifier) {
        Strings.requireSafeIdentifier(identifier);
        UploadTask task = taskStore.get(identifier).orElse(null);
        return task == null ? UploadProgress.empty(identifier) : UploadProgress.from(task);
    }

    /**
     * Returns whether the given chunk has already been uploaded.
     */
    public boolean isChunkUploaded(String identifier, int chunkIndex) {
        UploadTask task = taskStore.get(identifier).orElse(null);
        return task != null && task.isUploaded(chunkIndex);
    }

    /**
     * Merges all uploaded chunks into the complete file and cleans up the chunks.
     *
     * <p>The merged file is written to {@code <mergedFileDir>/<identifier>/<fileName>}.</p>
     *
     * @throws IllegalStateException chunks are incomplete or the merged size does not match the declared one
     * @throws NoSuchElementException the task does not exist
     */
    public UploadResult merge(String identifier) throws IOException {
        Strings.requireSafeIdentifier(identifier);
        synchronized (lockFor(identifier)) {
            UploadTask task = taskStore.get(identifier).orElse(null);
            if (task == null) {
                throw new NoSuchElementException("Upload task not found: " + identifier);
            }
            if (task.isMerged()) {
                return UploadResult.merged(task, task.getFinalPath(), task.getFinalFileSize());
            }
            int missing = 0;
            for (int i = 0; i < task.getChunkTotal(); i++) {
                if (!chunkStorage.chunkExists(identifier, i)) {
                    missing++;
                }
            }
            if (missing > 0) {
                throw new IllegalStateException("Missing " + missing + " chunk(s) not yet uploaded: " + identifier);
            }
            Strings.requireSafeFileName(task.getFileName());
            Path dir = mergedFileDir.toPath().resolve(identifier);
            Files.createDirectories(dir);
            Path out = dir.resolve(task.getFileName());
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
                for (int i = 0; i < task.getChunkTotal(); i++) {
                    File chunkFile = chunkStorage.getChunkFile(identifier, i);
                    if (!chunkFile.isFile()) {
                        throw new IllegalStateException("Missing chunk: " + i + " (" + identifier + ")");
                    }
                    Files.copy(chunkFile.toPath(), os);
                }
            }
            long size = Files.size(out);
            if (task.getFileSize() > 0 && size != task.getFileSize()) {
                Files.deleteIfExists(out);
                throw new IllegalStateException("Merged file size mismatch, expected "
                        + task.getFileSize() + ", actual " + size + " (" + identifier + ")");
            }
            chunkStorage.deleteChunks(identifier);
            task.setMerged(true);
            task.setFinalPath(out.toAbsolutePath().toString());
            task.setFinalFileSize(size);
            taskStore.save(task);
            return UploadResult.merged(task, out.toAbsolutePath().toString(), size);
        }
    }

    public TaskStore getTaskStore() {
        return taskStore;
    }
}
