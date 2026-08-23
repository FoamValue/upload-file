/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties of the upload/download toolkit, prefix {@code upload-file}.
 */
@ConfigurationProperties(prefix = "upload-file")
public class UploadFileProperties {

    /** Root dir for chunks and merged files. */
    private String storageDir = "./upload-file-data";

    /** Task metadata persistence dir; when empty, in-memory storage is used (tasks are lost on restart). */
    private String metadataDir;

    /** Whether to verify chunk MD5. */
    private boolean verifyChecksum = true;

    /** Upload servlet mapping path. */
    private String uploadUrl = "/upload";

    /** Download servlet mapping path. */
    private String downloadUrl = "/download";

    /** Max chunk size in bytes (multipart), -1 means unlimited. */
    private long maxChunkSize = -1;

    /** Max request size in bytes (multipart), -1 means unlimited. */
    private long maxRequestSize = -1;

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }

    public String getMetadataDir() {
        return metadataDir;
    }

    public void setMetadataDir(String metadataDir) {
        this.metadataDir = metadataDir;
    }

    public boolean isVerifyChecksum() {
        return verifyChecksum;
    }

    public void setVerifyChecksum(boolean verifyChecksum) {
        this.verifyChecksum = verifyChecksum;
    }

    public String getUploadUrl() {
        return uploadUrl;
    }

    public void setUploadUrl(String uploadUrl) {
        this.uploadUrl = uploadUrl;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public long getMaxChunkSize() {
        return maxChunkSize;
    }

    public void setMaxChunkSize(long maxChunkSize) {
        this.maxChunkSize = maxChunkSize;
    }

    public long getMaxRequestSize() {
        return maxRequestSize;
    }

    public void setMaxRequestSize(long maxRequestSize) {
        this.maxRequestSize = maxRequestSize;
    }
}
