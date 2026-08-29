/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.model;

import java.util.Set;
import java.util.TreeSet;

/**
 * Metadata of an upload task, persisted in {@link cn.chenxinjie.uploadfile.core.store.TaskStore}.
 */
public class UploadTask {

    private String identifier;
    private String fileName;
    private long fileSize;
    private long chunkSize;
    private int chunkTotal;

    /** Indices of the chunks that have been uploaded. */
    private Set<Integer> uploadedChunks = new TreeSet<>();

    private boolean merged;
    private String finalPath;
    private long finalFileSize;
    private long createTime;
    private long updateTime;

    /** Async-merge state: NONE/PENDING/RUNNING/SUCCEEDED/FAILED; null (old metadata) is treated as NONE. */
    private String mergeState;
    /** Error message carried when the async merge fails. */
    private String mergeError;
    /** Timestamp when the async merge entered the RUNNING state. */
    private long mergeStartedAt;

    /**
     * Metadata format version. Records written since rc.3 carry {@link #CURRENT_SCHEMA_VERSION};
     * records missing the field (written by rc.2 or earlier) are normalized to {@code 1} on load.
     * Any future format change must bump this version and provide a migration path.
     */
    private int schemaVersion;

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static final String MERGE_STATE_NONE = "NONE";
    public static final String MERGE_STATE_PENDING = "PENDING";
    public static final String MERGE_STATE_RUNNING = "RUNNING";
    public static final String MERGE_STATE_SUCCEEDED = "SUCCEEDED";
    public static final String MERGE_STATE_FAILED = "FAILED";

    /**
     * Normalizes a task deserialized from external storage: ensures the uploaded-chunk set is
     * never null and old metadata without a {@code schemaVersion} is treated as {@code 1}.
     */
    public void normalize() {
        if (uploadedChunks == null) {
            uploadedChunks = new TreeSet<>();
        }
        if (schemaVersion <= 0) {
            schemaVersion = CURRENT_SCHEMA_VERSION;
        }
    }

    /**
     * Returns the async-merge state, never null; missing fields in old metadata
     * deserialize to null and are normalized to {@link #MERGE_STATE_NONE}.
     */
    public String mergeState() {
        return mergeState == null ? MERGE_STATE_NONE : mergeState;
    }

    public static UploadTask from(ChunkUploadRequest req) {
        UploadTask task = new UploadTask();
        task.setIdentifier(req.getIdentifier());
        task.setFileName(req.getFileName());
        task.setFileSize(req.getFileSize());
        task.setChunkSize(req.getChunkSize());
        task.setChunkTotal(req.getChunkTotal());
        task.setSchemaVersion(CURRENT_SCHEMA_VERSION);
        long now = System.currentTimeMillis();
        task.setCreateTime(now);
        task.setUpdateTime(now);
        return task;
    }

    public void markUploaded(int chunkIndex) {
        uploadedChunks.add(chunkIndex);
        updateTime = System.currentTimeMillis();
    }

    public boolean isUploaded(int chunkIndex) {
        return uploadedChunks.contains(chunkIndex);
    }

    public int uploadedCount() {
        return uploadedChunks == null ? 0 : uploadedChunks.size();
    }

    public boolean isComplete() {
        return chunkTotal > 0 && uploadedCount() >= chunkTotal;
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public long getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(long chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkTotal() {
        return chunkTotal;
    }

    public void setChunkTotal(int chunkTotal) {
        this.chunkTotal = chunkTotal;
    }

    public Set<Integer> getUploadedChunks() {
        return uploadedChunks;
    }

    public void setUploadedChunks(Set<Integer> uploadedChunks) {
        this.uploadedChunks = uploadedChunks;
    }

    public boolean isMerged() {
        return merged;
    }

    public void setMerged(boolean merged) {
        this.merged = merged;
    }

    public String getFinalPath() {
        return finalPath;
    }

    public void setFinalPath(String finalPath) {
        this.finalPath = finalPath;
    }

    public long getFinalFileSize() {
        return finalFileSize;
    }

    public void setFinalFileSize(long finalFileSize) {
        this.finalFileSize = finalFileSize;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }

    public String getMergeState() {
        return mergeState;
    }

    public void setMergeState(String mergeState) {
        this.mergeState = mergeState;
    }

    public String getMergeError() {
        return mergeError;
    }

    public void setMergeError(String mergeError) {
        this.mergeError = mergeError;
    }

    public long getMergeStartedAt() {
        return mergeStartedAt;
    }

    public void setMergeStartedAt(long mergeStartedAt) {
        this.mergeStartedAt = mergeStartedAt;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }
}
