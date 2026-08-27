/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

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

    /** Whether to fsync the merged temp file before renaming it into place (T2). */
    private boolean mergeFsync = true;

    /** Whether to merge via "temp file + atomic move" instead of writing the final file directly (T2). */
    private boolean mergeAtomic = true;

    /** Whether to start the expired-task / orphan-data cleanup scheduler (T1/T3). Default off for rc.1 compatibility. */
    private boolean cleanupEnabled = false;

    /** Whether to run one cleanup pass at startup (T3). Default off for rc.1 compatibility. */
    private boolean cleanupRunOnStartup = false;

    /** Cleanup period (T1/T3). */
    @DurationUnit(ChronoUnit.MILLIS)
    private Duration cleanupInterval = Duration.ofHours(1);

    /** Expiry of incomplete tasks; 0 or negative means never clean (T1). */
    @DurationUnit(ChronoUnit.MILLIS)
    private Duration cleanupTaskTtl = Duration.ofHours(24);

    /** Whether to enable orphan-data cleanup (T3). Default off for rc.1 compatibility. */
    private boolean cleanupOrphanEnabled = false;

    /** Whether to enable async merge (T4). */
    private boolean asyncMergeEnabled = false;

    /** Async merge thread count (T4). */
    private int asyncMergeThreadPoolSize = 2;

    /** Metadata store type: auto/memory/file/jdbc/redis (T5). auto keeps the old behavior. */
    private String metadataStore = "auto";

    /** JDBC table name (T5). */
    private String jdbcTableName = "upload_task";

    /** SQL to auto-create the JDBC table; the table name is substituted for the first %s (T5). */
    private String jdbcInitSql =
            "CREATE TABLE IF NOT EXISTS %s (identifier VARCHAR(255) PRIMARY KEY, "
                    + "data CLOB, create_time BIGINT, update_time BIGINT)";

    /** Redis host (T5). */
    private String redisHost = "localhost";

    /** Redis port (T5). */
    private int redisPort = 6379;

    /** Redis password; empty means no auth (T5). */
    private String redisPassword;

    /** Redis key prefix (T5). */
    private String redisKeyPrefix = "upload:task:";

    /** Redis record TTL in seconds; 0 means no expiry (T5). */
    private int redisTtlSeconds = 0;

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

    public boolean isMergeFsync() {
        return mergeFsync;
    }

    public void setMergeFsync(boolean mergeFsync) {
        this.mergeFsync = mergeFsync;
    }

    public boolean isMergeAtomic() {
        return mergeAtomic;
    }

    public void setMergeAtomic(boolean mergeAtomic) {
        this.mergeAtomic = mergeAtomic;
    }

    public boolean isCleanupEnabled() {
        return cleanupEnabled;
    }

    public void setCleanupEnabled(boolean cleanupEnabled) {
        this.cleanupEnabled = cleanupEnabled;
    }

    public boolean isCleanupRunOnStartup() {
        return cleanupRunOnStartup;
    }

    public void setCleanupRunOnStartup(boolean cleanupRunOnStartup) {
        this.cleanupRunOnStartup = cleanupRunOnStartup;
    }

    public Duration getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(Duration cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }

    public Duration getCleanupTaskTtl() {
        return cleanupTaskTtl;
    }

    public void setCleanupTaskTtl(Duration cleanupTaskTtl) {
        this.cleanupTaskTtl = cleanupTaskTtl;
    }

    public boolean isCleanupOrphanEnabled() {
        return cleanupOrphanEnabled;
    }

    public void setCleanupOrphanEnabled(boolean cleanupOrphanEnabled) {
        this.cleanupOrphanEnabled = cleanupOrphanEnabled;
    }

    public boolean isAsyncMergeEnabled() {
        return asyncMergeEnabled;
    }

    public void setAsyncMergeEnabled(boolean asyncMergeEnabled) {
        this.asyncMergeEnabled = asyncMergeEnabled;
    }

    public int getAsyncMergeThreadPoolSize() {
        return asyncMergeThreadPoolSize;
    }

    public void setAsyncMergeThreadPoolSize(int asyncMergeThreadPoolSize) {
        this.asyncMergeThreadPoolSize = asyncMergeThreadPoolSize;
    }

    public String getMetadataStore() {
        return metadataStore;
    }

    public void setMetadataStore(String metadataStore) {
        this.metadataStore = metadataStore;
    }

    public String getJdbcTableName() {
        return jdbcTableName;
    }

    public void setJdbcTableName(String jdbcTableName) {
        this.jdbcTableName = jdbcTableName;
    }

    public String getJdbcInitSql() {
        return jdbcInitSql;
    }

    public void setJdbcInitSql(String jdbcInitSql) {
        this.jdbcInitSql = jdbcInitSql;
    }

    public String getRedisHost() {
        return redisHost;
    }

    public void setRedisHost(String redisHost) {
        this.redisHost = redisHost;
    }

    public int getRedisPort() {
        return redisPort;
    }

    public void setRedisPort(int redisPort) {
        this.redisPort = redisPort;
    }

    public String getRedisPassword() {
        return redisPassword;
    }

    public void setRedisPassword(String redisPassword) {
        this.redisPassword = redisPassword;
    }

    public String getRedisKeyPrefix() {
        return redisKeyPrefix;
    }

    public void setRedisKeyPrefix(String redisKeyPrefix) {
        this.redisKeyPrefix = redisKeyPrefix;
    }

    public int getRedisTtlSeconds() {
        return redisTtlSeconds;
    }

    public void setRedisTtlSeconds(int redisTtlSeconds) {
        this.redisTtlSeconds = redisTtlSeconds;
    }
}
