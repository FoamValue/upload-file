/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.model;

import java.util.Set;
import java.util.TreeSet;

/**
 * 上传进度，用于「查询进度」与「分片上传响应」，客户端可据此实现断点续传。
 */
public class UploadProgress {

    private String identifier;
    private String fileName;
    private long fileSize;
    private long chunkSize;
    private int chunkTotal;
    private Set<Integer> uploadedChunks = new TreeSet<>();
    private int uploadedCount;
    private int progressPercent;
    private boolean merged;

    public static UploadProgress from(UploadTask task) {
        UploadProgress p = new UploadProgress();
        p.setIdentifier(task.getIdentifier());
        p.setFileName(task.getFileName());
        p.setFileSize(task.getFileSize());
        p.setChunkSize(task.getChunkSize());
        p.setChunkTotal(task.getChunkTotal());
        p.setUploadedChunks(new TreeSet<>(task.getUploadedChunks()));
        p.setUploadedCount(task.uploadedCount());
        p.setMerged(task.isMerged());
        p.setProgressPercent(task.getChunkTotal() > 0
                ? (int) (task.uploadedCount() * 100L / task.getChunkTotal())
                : (task.isMerged() ? 100 : 0));
        return p;
    }

    public static UploadProgress empty(String identifier) {
        UploadProgress p = new UploadProgress();
        p.setIdentifier(identifier);
        p.setMerged(false);
        p.setProgressPercent(0);
        return p;
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

    public int getUploadedCount() {
        return uploadedCount;
    }

    public void setUploadedCount(int uploadedCount) {
        this.uploadedCount = uploadedCount;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(int progressPercent) {
        this.progressPercent = progressPercent;
    }

    public boolean isMerged() {
        return merged;
    }

    public void setMerged(boolean merged) {
        this.merged = merged;
    }
}
