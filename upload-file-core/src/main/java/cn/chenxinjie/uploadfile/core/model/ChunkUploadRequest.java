/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.model;

/**
 * Parameters of a single chunk upload request.
 *
 * <p>Sent by the client on each chunk upload, carrying the metadata of the whole file
 * together with the index of the current chunk.</p>
 */
public class ChunkUploadRequest {

    /** Unique file identifier; the MD5 of the whole file is recommended (also the key for chunk dirs and task records). */
    private String identifier;

    /** Original file name (including the extension). */
    private String fileName;

    /** Total file size in bytes; 0 if unknown. */
    private long fileSize;

    /** Chunk size in bytes; values &lt;= 0 fall back to the server default chunk size. */
    private long chunkSize;

    /** Total number of chunks. */
    private int chunkTotal;

    /** Index of the current chunk (starts at 0). */
    private int chunkIndex;

    /** MD5 of the current chunk for integrity verification; may be null (ignored when verification is disabled). */
    private String chunkMd5;

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

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getChunkMd5() {
        return chunkMd5;
    }

    public void setChunkMd5(String chunkMd5) {
        this.chunkMd5 = chunkMd5;
    }
}
