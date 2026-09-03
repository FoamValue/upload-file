/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.service;

import cn.chenxinjie.uploadfile.core.exception.ChecksumMismatchException;
import cn.chenxinjie.uploadfile.core.exception.QuotaExceededException;
import cn.chenxinjie.uploadfile.core.exception.UploadMergeConflictException;
import cn.chenxinjie.uploadfile.core.exception.UploadTaskNotFoundException;
import cn.chenxinjie.uploadfile.core.exception.UploadValidationException;
import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.model.MergeStatus;
import cn.chenxinjie.uploadfile.core.model.UploadProgress;
import cn.chenxinjie.uploadfile.core.model.UploadResult;
import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.security.AccessControl;
import cn.chenxinjie.uploadfile.core.security.PermitAllAccessControl;
import cn.chenxinjie.uploadfile.core.storage.ChunkStorage;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import cn.chenxinjie.uploadfile.core.util.ChecksumUtil;
import cn.chenxinjie.uploadfile.core.util.IdentifierLock;
import cn.chenxinjie.uploadfile.core.util.StringUtil;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

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

    private final TaskStore taskStore;
    private final ChunkStorage chunkStorage;
    private final File mergedFileDir;
    private final boolean verifyChecksum;
    private final boolean mergeFsync;
    private final boolean mergeAtomic;
    private final IdentifierLock identifierLock;
    private volatile AccessControl accessControl;

    /** Maximum bytes accepted for a single chunk; 0 or negative means unlimited. */
    private volatile long maxChunkBytes;

    /** Maximum total bytes for a single file; 0 or negative means unlimited. */
    private volatile long maxFileBytes;

    /** Maximum total on-disk capacity in bytes; 0 or negative means unlimited. */
    private volatile long maxTotalBytes;

    /** Async merge executor; when null, async merge is disabled and only the synchronous entry is used. */
    private volatile ExecutorService asyncExecutor;

    public ResumableUploadService(TaskStore taskStore, ChunkStorage chunkStorage, File mergedFileDir) {
        this(taskStore, chunkStorage, mergedFileDir, true);
    }

    public ResumableUploadService(TaskStore taskStore, ChunkStorage chunkStorage, File mergedFileDir, boolean verifyChecksum) {
        this(taskStore, chunkStorage, mergedFileDir, verifyChecksum, true, true);
    }

    public ResumableUploadService(TaskStore taskStore, ChunkStorage chunkStorage, File mergedFileDir,
                                  boolean verifyChecksum, boolean mergeFsync, boolean mergeAtomic) {
        this(taskStore, chunkStorage, mergedFileDir, verifyChecksum, mergeFsync, mergeAtomic, new IdentifierLock());
    }

    /**
     * Creates the service with a caller-provided shared lock; pass the same instance to
     * {@link StorageCleanupService} so cleanup is mutually exclusive with in-flight uploads.
     */
    public ResumableUploadService(TaskStore taskStore, ChunkStorage chunkStorage, File mergedFileDir,
                                  boolean verifyChecksum, boolean mergeFsync, boolean mergeAtomic,
                                  IdentifierLock identifierLock) {
        this(taskStore, chunkStorage, mergedFileDir, verifyChecksum, mergeFsync, mergeAtomic, identifierLock,
                PermitAllAccessControl.INSTANCE);
    }

    /**
     * Creates the service with a caller-provided shared lock and access control.
     */
    public ResumableUploadService(TaskStore taskStore, ChunkStorage chunkStorage, File mergedFileDir,
                                  boolean verifyChecksum, boolean mergeFsync, boolean mergeAtomic,
                                  IdentifierLock identifierLock, AccessControl accessControl) {
        this.taskStore = Objects.requireNonNull(taskStore, "taskStore");
        this.chunkStorage = Objects.requireNonNull(chunkStorage, "chunkStorage");
        this.mergedFileDir = Objects.requireNonNull(mergedFileDir, "mergedFileDir");
        this.verifyChecksum = verifyChecksum;
        this.mergeFsync = mergeFsync;
        this.mergeAtomic = mergeAtomic;
        this.identifierLock = Objects.requireNonNull(identifierLock, "identifierLock");
        this.accessControl = Objects.requireNonNull(accessControl, "accessControl");
    }

    /**
     * Replaces the access-control policy; defaults to {@link PermitAllAccessControl} (no-op).
     */
    public void setAccessControl(AccessControl accessControl) {
        this.accessControl = accessControl;
    }

    /**
     * Enables async merge. When enabled, {@link #submitMerge(String)} becomes available and
     * new chunk uploads are rejected while the merge is pending/running/finished.
     */
    public void setAsyncExecutor(ExecutorService asyncExecutor) {
        this.asyncExecutor = asyncExecutor;
    }

    public boolean isAsyncMergeEnabled() {
        return asyncExecutor != null;
    }

    /**
     * Sets the maximum number of bytes accepted for a single chunk; 0 or negative disables the
     * limit. A chunk larger than the limit is rejected and its bytes are discarded, guarding
     * against disk exhaustion from oversized uploads.
     */
    public void setMaxChunkBytes(long maxChunkBytes) {
        this.maxChunkBytes = maxChunkBytes;
    }

    /**
     * Sets the maximum total size of a single file; 0 or negative disables the limit. The declared
     * {@code fileSize} is checked when the task is created (first chunk) and again before merging.
     */
    public void setMaxFileBytes(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    /**
     * Sets the maximum total on-disk capacity; 0 or negative disables the quota. The limit is
     * checked approximately by summing the declared sizes of in-progress tasks and the final sizes
     * of merged tasks, before accepting a new task and before merging.
     */
    public void setMaxTotalBytes(long maxTotalBytes) {
        this.maxTotalBytes = maxTotalBytes;
    }

    private Object lockFor(String identifier) {
        // Hash the identifier into a fixed-size bucket so concurrent uploads of the
        // same file are serialized without allocating an unbounded number of locks.
        return identifierLock.forIdentifier(identifier);
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
        return uploadChunk(request, null, in);
    }

    /**
     * Uploads a chunk with an access token (see {@link AccessControl}).
     */
    public UploadProgress uploadChunk(ChunkUploadRequest request, String token, InputStream in) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(in, "inputStream");
        String identifier = StringUtil.requireSafeIdentifier(request.getIdentifier());
        accessControl.check(identifier, AccessControl.ACTION_UPLOAD, token);
        int chunkIndex = request.getChunkIndex();
        int chunkTotal = request.getChunkTotal();
        if (chunkTotal <= 0) {
            throw new UploadValidationException("chunkTotal must be greater than 0");
        }
        if (chunkIndex < 0 || chunkIndex >= chunkTotal) {
            throw new UploadValidationException("chunkIndex out of range: " + chunkIndex);
        }
        long chunkSize = request.getChunkSize() > 0 ? request.getChunkSize() : DEFAULT_CHUNK_SIZE;

        synchronized (lockFor(identifier)) {
            UploadTask task = taskStore.get(identifier).orElse(null);
            if (task == null) {
                // First chunk of this identifier: create the task record before storing any chunk.
                StringUtil.requireSafeFileName(request.getFileName());
                if (maxFileBytes > 0 && request.getFileSize() > maxFileBytes) {
                    throw new UploadValidationException("File size " + request.getFileSize()
                            + " exceeds the maximum allowed size of " + maxFileBytes + " bytes (" + identifier + ")");
                }
                if (maxTotalBytes > 0 && request.getFileSize() > 0) {
                    checkQuota(identifier, request.getFileSize());
                }
                task = UploadTask.from(request);
                task.setChunkSize(chunkSize);
                taskStore.save(task);
            } else {
                // The metadata captured from the first chunk is authoritative; reject later chunks
                // whose declared metadata disagrees so an inconsistent client cannot corrupt the merge.
                validateConsistentMetadata(request, chunkSize, task);
            }
            if (task.isMerged()) {
                throw new UploadMergeConflictException("Task already merged, cannot upload chunks: " + identifier);
            }
            if (asyncExecutor != null) {
                // When async merge is enabled, reject new chunks while a merge is in flight
                // or already finished, so the merged result is never silently inconsistent.
                String state = task.mergeState();
                if (UploadTask.MERGE_STATE_PENDING.equals(state)
                        || UploadTask.MERGE_STATE_RUNNING.equals(state)
                        || UploadTask.MERGE_STATE_SUCCEEDED.equals(state)) {
                    throw new UploadMergeConflictException("Async merge is running or finished, cannot upload chunks: " + identifier);
                }
            }
            if (!task.getUploadedChunks().contains(chunkIndex)) {
                // Chunk not yet uploaded: store it, optionally verify it, then record the progress.
                chunkStorage.saveChunk(identifier, chunkIndex, in);
                if (maxChunkBytes > 0) {
                    // Reject an oversized chunk and discard its bytes before any progress is recorded.
                    File saved = chunkStorage.getChunkFile(identifier, chunkIndex);
                    if (saved.isFile() && saved.length() > maxChunkBytes) {
                        chunkStorage.deleteChunk(identifier, chunkIndex);
                        throw new UploadValidationException("Chunk " + chunkIndex
                                + " exceeds the maximum allowed size of " + maxChunkBytes + " bytes");
                    }
                }
                if (verifyChecksum && StringUtil.isNotBlank(request.getChunkMd5())) {
                    // Recompute the MD5 of the persisted chunk and compare it with the expected
                    // value, so a corrupted transfer is rejected before the progress is recorded.
                    File saved = chunkStorage.getChunkFile(identifier, chunkIndex);
                    String actual = ChecksumUtil.md5(saved);
                    if (!actual.equalsIgnoreCase(request.getChunkMd5().trim())) {
                        // Only reject the offending chunk; keep the other uploaded chunks intact.
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

    private static void validateConsistentMetadata(ChunkUploadRequest request, long chunkSize, UploadTask task) {
        String identifier = task.getIdentifier();
        if (request.getChunkTotal() != task.getChunkTotal()) {
            throw new UploadValidationException("chunkTotal mismatch: expected "
                    + task.getChunkTotal() + ", got " + request.getChunkTotal() + " (" + identifier + ")");
        }
        if (task.getChunkSize() > 0 && chunkSize != task.getChunkSize()) {
            throw new UploadValidationException("chunkSize mismatch: expected "
                    + task.getChunkSize() + ", got " + chunkSize + " (" + identifier + ")");
        }
        if (request.getFileSize() > 0 && task.getFileSize() > 0 && request.getFileSize() != task.getFileSize()) {
            throw new UploadValidationException("fileSize mismatch: expected "
                    + task.getFileSize() + ", got " + request.getFileSize() + " (" + identifier + ")");
        }
        if (StringUtil.isNotBlank(request.getFileName())
                && !request.getFileName().equals(task.getFileName())) {
            throw new UploadValidationException("fileName mismatch: expected "
                    + task.getFileName() + ", got " + request.getFileName() + " (" + identifier + ")");
        }
    }

    /**
     * Queries upload progress; an empty progress is returned when the task does not exist,
     * so the client can treat it as a brand-new upload.
     */
    public UploadProgress getProgress(String identifier) {
        return getProgress(identifier, null);
    }

    /**
     * Queries upload progress with an access token (see {@link AccessControl}).
     */
    public UploadProgress getProgress(String identifier, String token) {
        StringUtil.requireSafeIdentifier(identifier);
        accessControl.check(identifier, AccessControl.ACTION_PROGRESS, token);
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
     * Returns the current task metadata for the identifier, or empty when no task exists.
     *
     * <p>This is the stable read interface for the integration layer's <b>confirm phase</b>: after a
     * merge succeeds, read the task (or the {@code UploadResult}/{@code MergeStatus} returned by the
     * merge call) and use {@link UploadTask#getFinalPath()} to locate the merged artifact, instead of
     * guessing the directory layout. The returned object is a live snapshot of the store record;
     * treat it as read-only.</p>
     */
    public Optional<UploadTask> getTask(String identifier) {
        StringUtil.requireSafeIdentifier(identifier);
        return taskStore.get(identifier);
    }

    /**
     * Merges all uploaded chunks into the complete file and cleans up the chunks.
     *
     * <p>The merged file is written to {@code <mergedFileDir>/<identifier>/<fileName>}.
     * With atomic merge enabled, the file is first written to a temp file in the same directory,
     * optionally fsync'd, then moved into place with {@code ATOMIC_MOVE}; the temp file is removed
     * on any failure so a corrupt file is never left behind.</p>
     *
     * @throws IllegalStateException chunks are incomplete or the merged size does not match the declared one
     * @throws NoSuchElementException the task does not exist
     */
    public UploadResult merge(String identifier) throws IOException {
        return merge(identifier, null);
    }

    /**
     * Merges with an access token (see {@link AccessControl}).
     */
    public UploadResult merge(String identifier, String token) throws IOException {
        StringUtil.requireSafeIdentifier(identifier);
        accessControl.check(identifier, AccessControl.ACTION_MERGE, token);
        synchronized (lockFor(identifier)) {
            UploadTask task = taskStore.get(identifier).orElse(null);
            if (task == null) {
                throw new UploadTaskNotFoundException("Upload task not found: " + identifier);
            }
            if (task.isMerged()) {
                return UploadResult.merged(task, task.getFinalPath(), task.getFinalFileSize());
            }
            if (maxFileBytes > 0 && task.getFileSize() > maxFileBytes) {
                throw new UploadValidationException("File size " + task.getFileSize()
                        + " exceeds the maximum allowed size of " + maxFileBytes + " bytes (" + identifier + ")");
            }
            if (maxTotalBytes > 0) {
                checkQuota(identifier, task.getFileSize());
            }
            int missing = 0;
            for (int i = 0; i < task.getChunkTotal(); i++) {
                // Every chunk must be on disk before merging, otherwise the merged file would be corrupt.
                if (!chunkStorage.chunkExists(identifier, i)) {
                    missing++;
                }
            }
            if (missing > 0) {
                throw new UploadMergeConflictException("Missing " + missing + " chunk(s) not yet uploaded: " + identifier);
            }
            StringUtil.requireSafeFileName(task.getFileName());
            Path dir = mergedFileDir.toPath().resolve(identifier);
            Files.createDirectories(dir);
            Path out = dir.resolve(task.getFileName());
            Path tmp = null;
            try {
                Path writeTarget = out;
                if (mergeAtomic) {
                    tmp = dir.resolve(task.getFileName() + ".merge-" + UUID.randomUUID() + ".tmp");
                    writeTarget = tmp;
                }
                writeMergedFile(task, writeTarget);
                long size = Files.size(writeTarget);
                if (task.getFileSize() > 0 && size != task.getFileSize()) {
                    // Guard against data loss: if the size does not match the declared one, discard the result.
                    Files.deleteIfExists(writeTarget);
                    throw new IllegalStateException("Merged file size mismatch, expected "
                            + task.getFileSize() + ", actual " + size + " (" + identifier + ")");
                }
                if (mergeAtomic) {
                    atomicMove(tmp, out);
                }
                // Merge succeeded: persist the merged state BEFORE removing the chunks, so an
                // interrupted save (e.g. IO error) never leaves the task with both the chunks
                // deleted and the task not marked as merged.
                task.setMerged(true);
                task.setFinalPath(out.toAbsolutePath().toString());
                task.setFinalFileSize(size);
                taskStore.save(task);
                try {
                    chunkStorage.deleteChunks(identifier);
                } catch (RuntimeException cleanupFailure) {
                    // Best-effort cleanup: the merged file and task state are already committed;
                    // leftover chunks are reclaimed by the orphan-data cleanup later.
                }
                return UploadResult.merged(task, out.toAbsolutePath().toString(), size);
            } finally {
                // Remove the temp file on any failure (or when it was already moved into place).
                if (tmp != null) {
                    Files.deleteIfExists(tmp);
                }
            }
        }
    }

    /**
     * Submits the merge to the async executor (returns 202-style PENDING status) and returns
     * the current status. Submitting the same identifier while PENDING/RUNNING is idempotent.
     *
     * @throws IllegalStateException async merge is not enabled
     * @throws NoSuchElementException the task does not exist
     */
    public MergeStatus submitMerge(String identifier) {
        return submitMerge(identifier, null);
    }

    /**
     * Submits the merge with an access token (see {@link AccessControl}).
     */
    public MergeStatus submitMerge(String identifier, String token) {
        StringUtil.requireSafeIdentifier(identifier);
        accessControl.check(identifier, AccessControl.ACTION_MERGE_ASYNC, token);
        if (asyncExecutor == null) {
            throw new IllegalStateException("Async merge is not enabled");
        }
        synchronized (lockFor(identifier)) {
            UploadTask task = taskStore.get(identifier).orElse(null);
            if (task == null) {
                throw new UploadTaskNotFoundException("Upload task not found: " + identifier);
            }
            if (task.isMerged()) {
                return MergeStatus.from(task);
            }
            String state = task.mergeState();
            if (UploadTask.MERGE_STATE_PENDING.equals(state) || UploadTask.MERGE_STATE_RUNNING.equals(state)) {
                // Already submitted/in flight: return the current status without re-submitting.
                return MergeStatus.from(task);
            }
            task.setMergeState(UploadTask.MERGE_STATE_PENDING);
            task.setMergeError(null);
            taskStore.save(task);
            try {
                asyncExecutor.submit(() -> doAsyncMerge(identifier));
            } catch (RuntimeException e) {
                // The executor rejected the task (e.g. it was shut down); roll the state back so
                // the task is never stuck in a pending merge that cannot finish.
                task.setMergeState(UploadTask.MERGE_STATE_NONE);
                taskStore.save(task);
                throw e;
            }
            return MergeStatus.from(task);
        }
    }

    /**
     * Returns the async merge status; {@code NONE} when the task does not exist or was never submitted.
     */
    public MergeStatus getMergeStatus(String identifier) {
        return getMergeStatus(identifier, null);
    }

    /**
     * Returns the async merge status with an access token (see {@link AccessControl}).
     */
    public MergeStatus getMergeStatus(String identifier, String token) {
        StringUtil.requireSafeIdentifier(identifier);
        accessControl.check(identifier, AccessControl.ACTION_MERGE_STATUS, token);
        UploadTask task = taskStore.get(identifier).orElse(null);
        return task == null ? MergeStatus.none(identifier) : MergeStatus.from(task);
    }

    /**
     * Cancels an upload task and reclaims all of its data: the task record, the uploaded chunks,
     * and any merged (but not yet claimed by the integration) artifact under the identifier.
     * After cancellation the identifier can be reused for a brand-new upload.
     *
     * <p>This is the explicit alternative to waiting for the task TTL and the cleanup scheduler
     * ({@link StorageCleanupService}), for use when an upload is abandoned or the merged artifact
     * has been moved into business storage.</p>
     *
     * @return {@code true} when a task existed and was removed, {@code false} when there was nothing to cancel
     * @throws UploadMergeConflictException when the task has an async merge pending or running;
     *         retry after it settles
     */
    public boolean cancelUpload(String identifier) {
        return cancelUpload(identifier, null);
    }

    /**
     * Cancels an upload task with an access token (see {@link AccessControl}).
     */
    public boolean cancelUpload(String identifier, String token) {
        StringUtil.requireSafeIdentifier(identifier);
        accessControl.check(identifier, AccessControl.ACTION_CANCEL, token);
        synchronized (lockFor(identifier)) {
            UploadTask task = taskStore.get(identifier).orElse(null);
            if (task == null) {
                return false;
            }
            String state = task.mergeState();
            if (UploadTask.MERGE_STATE_PENDING.equals(state) || UploadTask.MERGE_STATE_RUNNING.equals(state)) {
                throw new UploadMergeConflictException(
                        "Async merge is " + state + ", cannot cancel the task: " + identifier);
            }
            // Reclaim the on-disk data first (chunks, then the merged artifact dir), then the record;
            // a crash in between leaves only orphan data, which the cleanup service reclaims.
            chunkStorage.deleteChunks(identifier);
            deleteDirectoryQuietly(mergedFileDir.toPath().resolve(identifier));
            taskStore.remove(identifier);
            return true;
        }
    }

    private static void deleteDirectoryQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete directory: " + dir, e);
        }
    }

    private void doAsyncMerge(String identifier) {
        synchronized (lockFor(identifier)) {
            UploadTask task = taskStore.get(identifier).orElse(null);
            if (task == null || !UploadTask.MERGE_STATE_PENDING.equals(task.mergeState())) {
                return;
            }
            task.setMergeState(UploadTask.MERGE_STATE_RUNNING);
            task.setMergeStartedAt(System.currentTimeMillis());
            taskStore.save(task);
        }
        try {
            merge(identifier);
            synchronized (lockFor(identifier)) {
                UploadTask task = taskStore.get(identifier).orElse(null);
                if (task != null) {
                    task.setMergeState(UploadTask.MERGE_STATE_SUCCEEDED);
                    task.setMergeError(null);
                    taskStore.save(task);
                }
            }
        } catch (Exception e) {
            synchronized (lockFor(identifier)) {
                UploadTask task = taskStore.get(identifier).orElse(null);
                if (task != null) {
                    task.setMergeState(UploadTask.MERGE_STATE_FAILED);
                    task.setMergeError(e.getMessage());
                    taskStore.save(task);
                }
            }
        }
    }

    private void writeMergedFile(UploadTask task, Path target) throws IOException {
        // Concatenate the chunks in index order to rebuild the original file.
        String identifier = task.getIdentifier();
        FileOutputStream fos = new FileOutputStream(target.toFile());
        BufferedOutputStream bos = new BufferedOutputStream(fos);
        try {
            for (int i = 0; i < task.getChunkTotal(); i++) {
                File chunkFile = chunkStorage.getChunkFile(identifier, i);
                if (!chunkFile.isFile()) {
                    throw new IllegalStateException("Missing chunk: " + i + " (" + identifier + ")");
                }
                Files.copy(chunkFile.toPath(), bos);
            }
            bos.flush();
            if (mergeFsync && mergeAtomic) {
                // Persist the bytes before the rename so the final file is durable on crash.
                FileChannel channel = fos.getChannel();
                channel.force(true);
            }
        } finally {
            bos.close();
        }
    }

    private static void atomicMove(Path src, Path target) throws IOException {
        try {
            Files.move(src, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Some file systems do not support atomic moves; fall back to a plain rename.
            Files.move(src, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Approximate capacity check: the usage is the sum of the final sizes of merged tasks and the
     * declared sizes of in-progress tasks (excluding {@code excludeIdentifier} when given), plus the
     * incoming {@code extraBytes}. When the total would exceed the configured quota the request is
     * rejected, guarding against disk exhaustion across many uploads.
     */
    private void checkQuota(String excludeIdentifier, long extraBytes) {
        long used = 0;
        for (UploadTask t : taskStore.list()) {
            if (excludeIdentifier != null && excludeIdentifier.equals(t.getIdentifier())) {
                continue;
            }
            used += Math.max(0, t.isMerged() ? t.getFinalFileSize() : t.getFileSize());
        }
        long requested = Math.max(0, extraBytes);
        if (maxTotalBytes > 0 && used + requested > maxTotalBytes) {
            throw new QuotaExceededException("Storage quota exceeded: used " + used
                    + " bytes, requested " + requested + " bytes, limit " + maxTotalBytes + " bytes");
        }
    }

    public TaskStore getTaskStore() {
        return taskStore;
    }
}
