/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.servlet;

import cn.chenxinjie.uploadfile.core.model.CleanupStats;
import cn.chenxinjie.uploadfile.core.security.AccessControl;
import cn.chenxinjie.uploadfile.core.security.PermitAllAccessControl;
import cn.chenxinjie.uploadfile.core.security.TokenAccessControl;
import cn.chenxinjie.uploadfile.core.service.ResumableDownloadService;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.core.service.StorageCleanupService;
import cn.chenxinjie.uploadfile.core.storage.ChunkStorage;
import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import cn.chenxinjie.uploadfile.core.store.FileTaskStore;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import cn.chenxinjie.uploadfile.core.util.CleanupLock;
import cn.chenxinjie.uploadfile.core.util.IdentifierLock;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import java.io.File;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runtime context shared by the upload/download servlets, attached as a {@link ServletContext} attribute.
 *
 * <p>By default it is initialized from the following init-params:</p>
 * <ul>
 *   <li>{@code storage-dir}: root dir for chunks and merged files (default {@code ./upload-file-data})</li>
 *   <li>{@code metadata-dir}: task metadata persistence dir; when not set, in-memory storage is used</li>
 *   <li>{@code metadata-store}: {@code auto|memory|file} (default {@code auto}, keeps the old behavior)</li>
 *   <li>{@code merge.fsync}: fsync the merged temp file before rename (default {@code true})</li>
 *   <li>{@code merge.atomic}: merge via temp file + atomic move (default {@code true})</li>
 *   <li>{@code cleanup.enabled}: start the TTL/orphan cleanup scheduler (default {@code false})</li>
 *   <li>{@code cleanup.run-on-startup}: run one cleanup pass at startup (default {@code false})</li>
 *   <li>{@code cleanup.interval}: cleanup period in ms (default {@code 3600000})</li>
 *   <li>{@code cleanup.task-ttl}: expiry of incomplete tasks in ms, 0 = never (default {@code 86400000})</li>
 *   <li>{@code cleanup.orphan-enabled}: enable orphan-data cleanup (default {@code false})</li>
 *   <li>{@code async-merge.enabled}: enable async merge (default {@code false})</li>
 *   <li>{@code async-merge.thread-pool-size}: async merge thread count (default {@code 2})</li>
 *   <li>{@code chunk.max-size}: maximum bytes accepted for a single chunk, 0 = unlimited (default {@code 0})</li>
 *   <li>{@code security.enabled}: enable access-control checks (default {@code false})</li>
 *   <li>{@code security.token}: shared access token; empty = no checks (default empty)</li>
 *   <li>{@code security.header-name}: token header name (default {@code X-Access-Token})</li>
 *   <li>{@code max-file-size}: per-file total size limit in bytes, 0 = unlimited (default {@code 0})</li>
 *   <li>{@code quota.max-bytes}: global capacity quota in bytes, 0 = off (default {@code 0})</li>
 *   <li>{@code cleanup.use-redis-lock}: use a distributed cleanup lease lock (default {@code false});
 *       requires the lock to be supplied via {@link #build(String, String, Config, CleanupLock)}</li>
 *   <li>{@code observability.log-stats}: log cleanup statistics (default {@code true})</li>
 * </ul>
 *
 * <p>All cleanup/async threads are daemon threads, so they terminate with the container.</p>
 */
public final class UploadFileContext {

    public static final String ATTRIBUTE_NAME = UploadFileContext.class.getName();

    private static final Logger CLEANUP_LOG = Logger.getLogger(StorageCleanupService.class.getName());

    /** Writes a structured cleanup-statistics log line (wired when {@code observability.log-stats}). */
    private static final java.util.function.Consumer<CleanupStats> CLEANUP_LOG_LISTENER = stats ->
            CLEANUP_LOG.log(Level.INFO, "upload-file cleanup: {{0}}", describe(stats));

    private static String describe(CleanupStats stats) {
        return "run=" + stats.getLastRunTime()
                + ", cleanedTasks=" + stats.getCleanedTasks()
                + ", cleanedOrphans=" + stats.getCleanedOrphans()
                + ", elapsedMs=" + stats.getElapsedMillis()
                + ", error=" + (stats.getError() == null ? "null" : stats.getError());
    }

    private final TaskStore taskStore;
    private final ResumableUploadService uploadService;
    private final ResumableDownloadService downloadService;
    private final StorageCleanupService cleanupService;
    private final ExecutorService asyncExecutor;
    private final String accessTokenHeader;

    public UploadFileContext(TaskStore taskStore,
                             ResumableUploadService uploadService,
                             ResumableDownloadService downloadService,
                             StorageCleanupService cleanupService,
                             ExecutorService asyncExecutor,
                             String accessTokenHeader) {
        this.taskStore = taskStore;
        this.uploadService = uploadService;
        this.downloadService = downloadService;
        this.cleanupService = cleanupService;
        this.asyncExecutor = asyncExecutor;
        this.accessTokenHeader = accessTokenHeader;
    }

    public static UploadFileContext getOrCreate(ServletContext servletContext, ServletConfig config) {
        // Lazily build the shared context once and cache it on the ServletContext, so the
        // upload and download servlets always use the same store and directories.
        synchronized (servletContext) {
            UploadFileContext context = (UploadFileContext) servletContext.getAttribute(ATTRIBUTE_NAME);
            if (context == null) {
                String storageDir = initParam(config, "storage-dir", "./upload-file-data");
                String metadataDir = initParam(config, "metadata-dir", null);
                context = build(storageDir, metadataDir, Config.fromInitParams(config));
                servletContext.setAttribute(ATTRIBUTE_NAME, context);
            }
            return context;
        }
    }

    /**
     * Builds a context directly from directories (used when a container such as Spring Boot takes over).
     */
    public static UploadFileContext build(String storageDir, String metadataDir) {
        return build(storageDir, metadataDir, new Config());
    }

    /**
     * Builds a context with explicit settings, applying the metadata store and wiring the
     * cleanup scheduler / async executor when enabled.
     */
    public static UploadFileContext build(String storageDir, String metadataDir, Config config) {
        return build(storageDir, metadataDir, config, null);
    }

    /**
     * Builds a context with explicit settings and an optional distributed {@link CleanupLock}
     * (e.g. a Redis lease lock); when non-null, multi-instance cleanup is coordinated.
     */
    public static UploadFileContext build(String storageDir, String metadataDir, Config config, CleanupLock cleanupLock) {
        TaskStore store = createStore(metadataDir, config);
        ChunkStorage chunkStorage = new LocalFileChunkStorage(Paths.get(storageDir, "chunks"));
        File mergedDir = Paths.get(storageDir, "files").toFile();

        AccessControl accessControl;
        if (config.securityEnabled) {
            // Fail fast: enabling security without a token must not silently open the endpoints.
            if (config.securityToken == null || config.securityToken.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "security.enabled=true requires security.token to be configured");
            }
            accessControl = new TokenAccessControl(config.securityToken);
        } else {
            accessControl = PermitAllAccessControl.INSTANCE;
        }

        // A single shared lock keeps the upload service and the cleanup service mutually
        // exclusive for the same identifier.
        IdentifierLock identifierLock = new IdentifierLock();
        ResumableUploadService uploadService = new ResumableUploadService(
                store, chunkStorage, mergedDir, true, config.mergeFsync, config.mergeAtomic, identifierLock,
                accessControl);
        if (config.maxChunkSize > 0) {
            uploadService.setMaxChunkBytes(config.maxChunkSize);
        }
        if (config.maxFileSize > 0) {
            uploadService.setMaxFileBytes(config.maxFileSize);
        }
        if (config.quotaMaxBytes > 0) {
            uploadService.setMaxTotalBytes(config.quotaMaxBytes);
        }

        ExecutorService asyncExecutor = null;
        if (config.asyncMergeEnabled) {
            ThreadFactory factory = new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "upload-file-async-merge-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            };
            asyncExecutor = Executors.newFixedThreadPool(config.asyncMergeThreadPoolSize, factory);
            uploadService.setAsyncExecutor(asyncExecutor);
        }

        ResumableDownloadService downloadService = new ResumableDownloadService(store, mergedDir);
        downloadService.setAccessControl(accessControl);

        StorageCleanupService cleanupService = new StorageCleanupService(
                store, chunkStorage, mergedDir, config.cleanupTaskTtlMillis, config.cleanupOrphanEnabled,
                identifierLock, cleanupLock);
        if (config.observabilityLogStats) {
            cleanupService.setStatsListener(CLEANUP_LOG_LISTENER);
        }
        if (config.cleanupEnabled) {
            cleanupService.start(config.cleanupIntervalMillis);
            if (config.cleanupRunOnStartup) {
                cleanupService.cleanup();
            }
        }

        return new UploadFileContext(store, uploadService, downloadService, cleanupService, asyncExecutor,
                config.securityHeaderName);
    }

    private static TaskStore createStore(String metadataDir, Config config) {
        String store = config.metadataStore == null ? "auto" : config.metadataStore.trim();
        if ("memory".equalsIgnoreCase(store)) {
            return new MemoryTaskStore();
        }
        if ("file".equalsIgnoreCase(store)) {
            return new FileTaskStore(Paths.get(metadataDir));
        }
        // "auto" (default): file when metadata-dir is set, otherwise memory.
        return metadataDir == null || metadataDir.trim().isEmpty()
                ? new MemoryTaskStore()
                : new FileTaskStore(Paths.get(metadataDir));
    }

    private static String initParam(ServletConfig config, String name, String defaultValue) {
        String value = config.getInitParameter(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    public TaskStore getTaskStore() {
        return taskStore;
    }

    public ResumableUploadService getUploadService() {
        return uploadService;
    }

    public ResumableDownloadService getDownloadService() {
        return downloadService;
    }

    public StorageCleanupService getCleanupService() {
        return cleanupService;
    }

    public ExecutorService getAsyncExecutor() {
        return asyncExecutor;
    }

    /** The configured access-token header name (default {@code X-Access-Token}). */
    public String getAccessTokenHeader() {
        return accessTokenHeader;
    }

    /**
     * Parsed init-param settings for the rc.3 features; all defaults preserve rc.1 behavior.
     */
    public static class Config {
        public String metadataStore = "auto";
        public boolean mergeFsync = true;
        public boolean mergeAtomic = true;
        public boolean cleanupEnabled = false;
        public boolean cleanupRunOnStartup = false;
        public long cleanupIntervalMillis = 3600000L;
        public long cleanupTaskTtlMillis = 86400000L;
        public boolean cleanupOrphanEnabled = false;
        public boolean asyncMergeEnabled = false;
        public int asyncMergeThreadPoolSize = 2;
        public long maxChunkSize = 0;
        public boolean securityEnabled = false;
        public String securityToken = "";
        public String securityHeaderName = "X-Access-Token";
        public long maxFileSize = 0;
        public long quotaMaxBytes = 0;
        public boolean observabilityLogStats = true;
        public boolean cleanupUseRedisLock = false;

        public static Config fromInitParams(ServletConfig config) {
            Config c = new Config();
            c.metadataStore = initParam(config, "metadata-store", c.metadataStore);
            c.mergeFsync = boolParam(config, "merge.fsync", c.mergeFsync);
            c.mergeAtomic = boolParam(config, "merge.atomic", c.mergeAtomic);
            c.cleanupEnabled = boolParam(config, "cleanup.enabled", c.cleanupEnabled);
            c.cleanupRunOnStartup = boolParam(config, "cleanup.run-on-startup", c.cleanupRunOnStartup);
            c.cleanupIntervalMillis = longParam(config, "cleanup.interval", c.cleanupIntervalMillis);
            c.cleanupTaskTtlMillis = longParam(config, "cleanup.task-ttl", c.cleanupTaskTtlMillis);
            c.cleanupOrphanEnabled = boolParam(config, "cleanup.orphan-enabled", c.cleanupOrphanEnabled);
            c.asyncMergeEnabled = boolParam(config, "async-merge.enabled", c.asyncMergeEnabled);
            c.asyncMergeThreadPoolSize = intParam(config, "async-merge.thread-pool-size", c.asyncMergeThreadPoolSize);
            c.maxChunkSize = longParam(config, "chunk.max-size", c.maxChunkSize);
            c.securityEnabled = boolParam(config, "security.enabled", c.securityEnabled);
            c.securityToken = initParam(config, "security.token", c.securityToken);
            c.securityHeaderName = initParam(config, "security.header-name", c.securityHeaderName);
            c.maxFileSize = longParam(config, "max-file-size", c.maxFileSize);
            c.quotaMaxBytes = longParam(config, "quota.max-bytes", c.quotaMaxBytes);
            c.observabilityLogStats = boolParam(config, "observability.log-stats", c.observabilityLogStats);
            c.cleanupUseRedisLock = boolParam(config, "cleanup.use-redis-lock", c.cleanupUseRedisLock);
            return c;
        }

        private static boolean boolParam(ServletConfig config, String name, boolean defaultValue) {
            String v = initParam(config, name, null);
            return v == null ? defaultValue : Boolean.parseBoolean(v);
        }

        private static long longParam(ServletConfig config, String name, long defaultValue) {
            String v = initParam(config, name, null);
            if (v == null) {
                return defaultValue;
            }
            try {
                return Long.parseLong(v);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }

        private static int intParam(ServletConfig config, String name, int defaultValue) {
            String v = initParam(config, name, null);
            if (v == null) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
    }
}
