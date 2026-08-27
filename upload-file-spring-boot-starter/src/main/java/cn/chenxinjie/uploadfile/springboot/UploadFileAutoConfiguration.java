/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import cn.chenxinjie.uploadfile.core.service.ResumableDownloadService;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.core.service.StorageCleanupService;
import cn.chenxinjie.uploadfile.core.storage.ChunkStorage;
import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import cn.chenxinjie.uploadfile.core.store.FileTaskStore;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import cn.chenxinjie.uploadfile.core.util.IdentifierLock;
import cn.chenxinjie.uploadfile.servlet.DownloadServlet;
import cn.chenxinjie.uploadfile.servlet.UploadServlet;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.servlet.MultipartConfigElement;
import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

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
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {"javax.servlet.Servlet", "javax.servlet.MultipartConfigElement"})
@EnableConfigurationProperties(UploadFileProperties.class)
public class UploadFileAutoConfiguration {

    private static final Log LOG = LogFactory.getLog(UploadFileAutoConfiguration.class);

    private static final String JDBC_STORE_CLASS = "cn.chenxinjie.uploadfile.store.jdbc.JdbcTaskStore";
    private static final String REDIS_STORE_CLASS = "cn.chenxinjie.uploadfile.store.redis.RedisTaskStore";

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
                                                         ObjectProvider<ExecutorService> asyncExecutorProvider) {
        File mergedDir = Paths.get(properties.getStorageDir(), "files").toFile();
        ResumableUploadService service = new ResumableUploadService(
                taskStore, chunkStorage, mergedDir,
                properties.isVerifyChecksum(), properties.getMerge().isFsync(), properties.getMerge().isAtomic(),
                identifierLock);
        if (properties.getMaxChunkSize() > 0) {
            service.setMaxChunkBytes(properties.getMaxChunkSize());
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
                                                             UploadFileProperties properties) {
        File mergedDir = Paths.get(properties.getStorageDir(), "files").toFile();
        return new ResumableDownloadService(taskStore, mergedDir);
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean
    public StorageCleanupService storageCleanupService(TaskStore taskStore,
                                                       ChunkStorage chunkStorage,
                                                       UploadFileProperties properties,
                                                       IdentifierLock identifierLock) {
        File mergedDir = Paths.get(properties.getStorageDir(), "files").toFile();
        StorageCleanupService cleanup = new StorageCleanupService(
                taskStore, chunkStorage, mergedDir,
                properties.getCleanup().getTaskTtl().toMillis(), properties.getCleanup().isOrphanEnabled(),
                identifierLock);
        cleanup.setErrorListener(t -> LOG.warn("Upload-file storage cleanup failed", t));
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
