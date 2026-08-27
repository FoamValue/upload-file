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
 *
 * <p>Nested groups ({@code merge}, {@code cleanup}, {@code async-merge}, {@code jdbc},
 * {@code redis}) mirror the documented dotted property names, so e.g.
 * {@code upload-file.merge.fsync=false} and
 * {@code upload-file.cleanup.enabled=true} bind as expected.</p>
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

    /** Metadata store type: auto/memory/file/jdbc/redis (T5). auto keeps the old behavior. */
    private String metadataStore = "auto";

    /** Merge behavior. */
    private final Merge merge = new Merge();

    /** Expired-task / orphan cleanup settings. */
    private final Cleanup cleanup = new Cleanup();

    /** Async merge settings. */
    private final AsyncMerge asyncMerge = new AsyncMerge();

    /** JDBC store settings. */
    private final Jdbc jdbc = new Jdbc();

    /** Redis store settings. */
    private final Redis redis = new Redis();

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

    public String getMetadataStore() {
        return metadataStore;
    }

    public void setMetadataStore(String metadataStore) {
        this.metadataStore = metadataStore;
    }

    public Merge getMerge() {
        return merge;
    }

    public Cleanup getCleanup() {
        return cleanup;
    }

    public AsyncMerge getAsyncMerge() {
        return asyncMerge;
    }

    public Jdbc getJdbc() {
        return jdbc;
    }

    public Redis getRedis() {
        return redis;
    }

    /** {@code upload-file.merge.*} */
    public static class Merge {
        /** Whether to fsync the merged temp file before renaming it into place (T2). */
        private boolean fsync = true;

        /** Whether to merge via "temp file + atomic move" instead of writing the final file directly (T2). */
        private boolean atomic = true;

        public boolean isFsync() {
            return fsync;
        }

        public void setFsync(boolean fsync) {
            this.fsync = fsync;
        }

        public boolean isAtomic() {
            return atomic;
        }

        public void setAtomic(boolean atomic) {
            this.atomic = atomic;
        }
    }

    /** {@code upload-file.cleanup.*} */
    public static class Cleanup {
        /** Whether to start the expired-task / orphan-data cleanup scheduler (T1/T3). Default off for rc.1 compatibility. */
        private boolean enabled = false;

        /** Whether to run one cleanup pass at startup (T3). Default off for rc.1 compatibility. */
        private boolean runOnStartup = false;

        /** Cleanup period (T1/T3). */
        @DurationUnit(ChronoUnit.MILLIS)
        private Duration interval = Duration.ofHours(1);

        /** Expiry of incomplete tasks; 0 or negative means never clean (T1). */
        @DurationUnit(ChronoUnit.MILLIS)
        private Duration taskTtl = Duration.ofHours(24);

        /** Whether to enable orphan-data cleanup (T3). Default off for rc.1 compatibility. */
        private boolean orphanEnabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isRunOnStartup() {
            return runOnStartup;
        }

        public void setRunOnStartup(boolean runOnStartup) {
            this.runOnStartup = runOnStartup;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public Duration getTaskTtl() {
            return taskTtl;
        }

        public void setTaskTtl(Duration taskTtl) {
            this.taskTtl = taskTtl;
        }

        public boolean isOrphanEnabled() {
            return orphanEnabled;
        }

        public void setOrphanEnabled(boolean orphanEnabled) {
            this.orphanEnabled = orphanEnabled;
        }
    }

    /** {@code upload-file.async-merge.*} */
    public static class AsyncMerge {
        /** Whether to enable async merge (T4). */
        private boolean enabled = false;

        /** Async merge thread count (T4). */
        private int threadPoolSize = 2;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getThreadPoolSize() {
            return threadPoolSize;
        }

        public void setThreadPoolSize(int threadPoolSize) {
            this.threadPoolSize = threadPoolSize;
        }
    }

    /** {@code upload-file.jdbc.*} */
    public static class Jdbc {
        /** JDBC table name (T5). */
        private String tableName = "upload_task";

        /** SQL to auto-create the JDBC table; the table name is substituted for the first %s (T5). */
        private String initSql =
                "CREATE TABLE IF NOT EXISTS %s (identifier VARCHAR(255) PRIMARY KEY, "
                        + "data CLOB, create_time BIGINT, update_time BIGINT)";

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public String getInitSql() {
            return initSql;
        }

        public void setInitSql(String initSql) {
            this.initSql = initSql;
        }
    }

    /** {@code upload-file.redis.*} */
    public static class Redis {
        /** Redis host (T5). */
        private String host = "localhost";

        /** Redis port (T5). */
        private int port = 6379;

        /** Redis password; empty means no auth (T5). */
        private String password;

        /** Redis key prefix (T5). */
        private String keyPrefix = "upload:task:";

        /** Redis record TTL in seconds; 0 means no expiry (T5). */
        private int ttlSeconds = 0;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public int getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(int ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }
}
