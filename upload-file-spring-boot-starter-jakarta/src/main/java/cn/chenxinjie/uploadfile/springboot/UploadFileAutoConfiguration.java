/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

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
import cn.chenxinjie.uploadfile.core.store.TaskStoreMigrator;
import cn.chenxinjie.uploadfile.core.util.CleanupLock;
import cn.chenxinjie.uploadfile.core.util.IdentifierLock;
import cn.chenxinjie.uploadfile.servlet.DownloadServlet;
import cn.chenxinjie.uploadfile.servlet.UploadServlet;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;

import jakarta.servlet.MultipartConfigElement;
import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Auto-wires the core services and registers the upload/download servlets.
 *
 * <p>Every component can be overridden by defining a bean of the same type
 * ({@code @ConditionalOnMissingBean}).</p>
 *
 * <p>The metadata store is chosen via {@code upload-file.metadata-store}
 * ({@code auto|memory|file|jdbc|redis}); {@code auto} reproduces the rc.1 behavior
 * (file when {@code metadata-dir} is set, otherwise memory). jdbc/redis fall back to
 * {@code auto} with a warning when the module/DataSource is missing.</p>
 * <p>Boot 3/4 style: discovered through {@code META-INF/spring/org.springframework.boot.autoconfigure
 * .AutoConfiguration.imports}, not the Boot 2 {@code spring.factories} mechanism.</p>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {"jakarta.servlet.Servlet", "jakarta.servlet.MultipartConfigElement"})
@EnableConfigurationProperties(UploadFileProperties.class)
public class UploadFileAutoConfiguration {

    private static final Log LOG = LogFactory.getLog(UploadFileAutoConfiguration.class);

    private static final String JDBC_STORE_CLASS = "cn.chenxinjie.uploadfile.store.jdbc.JdbcTaskStore";
    private static final String REDIS_STORE_CLASS = "cn.chenxinjie.uploadfile.store.redis.RedisTaskStore";
    private static final String REDIS_LOCK_CLASS = "cn.chenxinjie.uploadfile.store.redis.RedisCleanupLock";

    /** Writes one structured cleanup-stats log line per pass (wired when {@code observability.log-stats}). */
    private static final Consumer<CleanupStats> CLEANUP_STATS_LOG = stats -> LOG.info(
            "upload-file cleanup: run=" + stats.getLastRunTime()
                    + ", cleanedTasks=" + stats.getCleanedTasks()
                    + ", cleanedOrphans=" + stats.getCleanedOrphans()
                    + ", elapsedMs=" + stats.getElapsedMillis()
                    + ", error=" + (stats.getError() == null ? "null" : stats.getError()));

    /**
     * Access-control policy: enabled + non-blank token -> shared-token check; enabled but blank
     * token fails fast at startup (a misconfiguration must not silently open the endpoints);
     * disabled -> permit all (the rc.2 behavior).
     */
    @Bean
    @ConditionalOnMissingBean
    public AccessControl uploadFileAccessControl(UploadFileProperties properties) {
        UploadFileProperties.Security security = properties.getSecurity();
        if (!security.isEnabled()) {
            return PermitAllAccessControl.INSTANCE;
        }
        if (security.getToken() == null || security.getToken().trim().isEmpty()) {
            throw new IllegalStateException(
                    "upload-file.security.enabled=true requires upload-file.security.token to be configured");
        }
        return new TokenAccessControl(security.getToken());
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskStore uploadFileTaskStore(UploadFileProperties properties,
                                         ObjectProvider<DataSource> dataSourceProvider) {
        String store = properties.getMetadataStore() == null ? "auto" : properties.getMetadataStore().trim();
        switch (store.toLowerCase()) {
            case "memory":
                return new MemoryTaskStore();
            case "file":
                return new FileTaskStore(Paths.get(requireMetadataDir(properties)));
            case "jdbc":
                if (isClassPresent(JDBC_STORE_CLASS)) {
                    DataSource ds = dataSourceProvider.getIfAvailable();
                    if (ds != null) {
                        return new cn.chenxinjie.uploadfile.store.jdbc.JdbcTaskStore(
                                ds, properties.getJdbc().getTableName(), properties.getJdbc().getInitSql());
                    }
                    LOG.warn("upload-file.metadata-store=jdbc but no DataSource bean found; "
                            + "falling back to the auto store");
                } else {
                    LOG.warn("upload-file.metadata-store=jdbc but upload-file-store-jdbc is not on the classpath; "
                            + "falling back to the auto store");
                }
                break;
            case "redis":
                if (isClassPresent(REDIS_STORE_CLASS)) {
                    return cn.chenxinjie.uploadfile.store.redis.RedisTaskStore.create(
                            properties.getRedis().getHost(), properties.getRedis().getPort(),
                            properties.getRedis().getPassword(),
                            properties.getRedis().getKeyPrefix(), properties.getRedis().getTtlSeconds());
                }
                LOG.warn("upload-file.metadata-store=redis but upload-file-store-redis is not on the classpath; "
                        + "falling back to the auto store");
                break;
            case "auto":
            default:
                break;
        }
        // "auto" (or an unknown/unavailable store): reproduce the rc.1 behavior
        // (file when metadata-dir is set, otherwise memory).
        if (properties.getMetadataDir() != null && !properties.getMetadataDir().trim().isEmpty()) {
            return new FileTaskStore(Paths.get(properties.getMetadataDir()));
        }
        return new MemoryTaskStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public ChunkStorage uploadFileChunkStorage(UploadFileProperties properties) {
        return new LocalFileChunkStorage(Paths.get(properties.getStorageDir(), "chunks"));
    }

    /**
     * A single shared lock keeps the upload service and the cleanup service mutually exclusive
     * for the same identifier.
     */
    @Bean
    @ConditionalOnMissingBean
    public IdentifierLock uploadFileIdentifierLock() {
        return new IdentifierLock();
    }

    @Bean
    @ConditionalOnMissingBean
    public ResumableUploadService resumableUploadService(TaskStore taskStore,
                                                         ChunkStorage chunkStorage,
                                                         UploadFileProperties properties,
                                                         IdentifierLock identifierLock,
                                                         AccessControl accessControl,
                                                         ObjectProvider<ExecutorService> asyncExecutorProvider) {
        File mergedDir = Paths.get(properties.getStorageDir(), "files").toFile();
        ResumableUploadService service = new ResumableUploadService(
                taskStore, chunkStorage, mergedDir,
                properties.isVerifyChecksum(), properties.getMerge().isFsync(), properties.getMerge().isAtomic(),
                identifierLock, accessControl);
        if (properties.getMaxChunkSize() > 0) {
            service.setMaxChunkBytes(properties.getMaxChunkSize());
        }
        if (properties.getMaxFileSize() > 0) {
            service.setMaxFileBytes(properties.getMaxFileSize());
        }
        if (properties.getQuota().getMaxBytes() > 0) {
            service.setMaxTotalBytes(properties.getQuota().getMaxBytes());
        }
        ExecutorService asyncExecutor = asyncExecutorProvider.getIfUnique();
        if (asyncExecutor != null) {
            service.setAsyncExecutor(asyncExecutor);
        }
        return service;
    }

    @Bean
    @ConditionalOnMissingBean
    public ResumableDownloadService resumableDownloadService(TaskStore taskStore,
                                                             UploadFileProperties properties,
                                                             AccessControl accessControl) {
        File mergedDir = Paths.get(properties.getStorageDir(), "files").toFile();
        ResumableDownloadService service = new ResumableDownloadService(taskStore, mergedDir);
        service.setAccessControl(accessControl);
        return service;
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean
    public StorageCleanupService storageCleanupService(TaskStore taskStore,
                                                       ChunkStorage chunkStorage,
                                                       UploadFileProperties properties,
                                                       IdentifierLock identifierLock,
                                                       ObjectProvider<CleanupLock> cleanupLockProvider) {
        File mergedDir = Paths.get(properties.getStorageDir(), "files").toFile();
        CleanupLock cleanupLock = properties.getCleanup().isUseRedisLock() ? cleanupLockProvider.getIfAvailable() : null;
        StorageCleanupService cleanup = new StorageCleanupService(
                taskStore, chunkStorage, mergedDir,
                properties.getCleanup().getTaskTtl().toMillis(), properties.getCleanup().isOrphanEnabled(),
                identifierLock, cleanupLock);
        cleanup.setErrorListener(t -> LOG.warn("Upload-file storage cleanup failed", t));
        if (properties.getObservability().isLogStats()) {
            cleanup.setStatsListener(CLEANUP_STATS_LOG);
        }
        if (properties.getCleanup().isEnabled()) {
            cleanup.start(properties.getCleanup().getInterval().toMillis());
            if (properties.getCleanup().isRunOnStartup()) {
                try {
                    cleanup.cleanup();
                } catch (RuntimeException e) {
                    LOG.warn("Upload-file startup cleanup failed", e);
                }
            }
        }
        return cleanup;
    }

    /**
     * Redis lease lock so only one instance runs the cleanup scheduler at a time (rc.3).
     */
    @Bean
    @ConditionalOnProperty(prefix = "upload-file", name = "cleanup.use-redis-lock", havingValue = "true")
    @ConditionalOnClass(name = REDIS_LOCK_CLASS)
    public CleanupLock uploadFileRedisCleanupLock(UploadFileProperties properties) {
        return cn.chenxinjie.uploadfile.store.redis.RedisCleanupLock.create(
                properties.getRedis().getHost(), properties.getRedis().getPort(),
                properties.getRedis().getPassword(), properties.getRedis().getKeyPrefix(), 60);
    }

    /**
     * Exposes the task-store migration helper when {@code upload-file.migration.enabled=true};
     * call {@code migrator.migrate(oldFileStore)} to move records into the currently active store.
     * Migration is an explicit operation and never runs automatically.
     */
    @Bean
    @ConditionalOnProperty(prefix = "upload-file", name = "migration.enabled", havingValue = "true")
    public TaskStoreMigrator uploadFileTaskStoreMigrator(TaskStore taskStore) {
        return new TaskStoreMigrator(taskStore);
    }

    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnProperty(prefix = "upload-file", name = "async-merge.enabled", havingValue = "true")
    @ConditionalOnMissingBean(name = "uploadFileAsyncMergeExecutor")
    public ExecutorService uploadFileAsyncMergeExecutor(UploadFileProperties properties) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "upload-file-async-merge-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newFixedThreadPool(properties.getAsyncMerge().getThreadPoolSize(), factory);
    }

    @Bean
    public ServletRegistrationBean<UploadServlet> uploadFileServletRegistration(
            ResumableUploadService uploadService, UploadFileProperties properties) {
        UploadServlet servlet = new UploadServlet();
        servlet.setUploadService(uploadService);
        servlet.setAccessTokenHeader(properties.getSecurity().getHeaderName());
        ServletRegistrationBean<UploadServlet> registration =
                new ServletRegistrationBean<>(servlet, properties.getUploadUrl());
        registration.setName("uploadFileServlet");
        registration.setLoadOnStartup(1);
        registration.setMultipartConfig(new MultipartConfigElement(
                null, properties.getMaxChunkSize(), properties.getMaxRequestSize(), 1024 * 1024));
        return registration;
    }

    @Bean
    public ServletRegistrationBean<DownloadServlet> downloadFileServletRegistration(
            ResumableDownloadService downloadService, UploadFileProperties properties) {
        DownloadServlet servlet = new DownloadServlet();
        servlet.setDownloadService(downloadService);
        servlet.setAccessTokenHeader(properties.getSecurity().getHeaderName());
        ServletRegistrationBean<DownloadServlet> registration =
                new ServletRegistrationBean<>(servlet, properties.getDownloadUrl());
        registration.setName("downloadFileServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }

    private static String requireMetadataDir(UploadFileProperties properties) {
        String dir = properties.getMetadataDir();
        if (dir == null || dir.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "upload-file.metadata-store=file requires upload-file.metadata-dir to be set");
        }
        return dir;
    }

    private static boolean isClassPresent(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
