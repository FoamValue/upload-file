/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import cn.chenxinjie.uploadfile.core.error.UploadErrorRenderer;
import cn.chenxinjie.uploadfile.core.error.UploadErrorRenderers;
import cn.chenxinjie.uploadfile.core.model.CleanupStats;
import cn.chenxinjie.uploadfile.core.security.AccessControl;
import cn.chenxinjie.uploadfile.core.security.AccessControlListener;
import cn.chenxinjie.uploadfile.core.security.PermitAllAccessControl;
import cn.chenxinjie.uploadfile.core.security.TokenAccessControl;
import cn.chenxinjie.uploadfile.core.service.ResumableDownloadService;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.core.service.StorageCleanupService;
import cn.chenxinjie.uploadfile.core.storage.ChunkStorage;
import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import cn.chenxinjie.uploadfile.core.store.FileTaskStore;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.store.QuotaStore;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import cn.chenxinjie.uploadfile.core.store.TaskStoreMigrator;
import cn.chenxinjie.uploadfile.core.store.TaskStoreQuotaStore;
import cn.chenxinjie.uploadfile.core.util.CleanupLock;
import cn.chenxinjie.uploadfile.core.util.IdentifierLock;
import cn.chenxinjie.uploadfile.core.util.IdentifierLockProvider;
import cn.chenxinjie.uploadfile.core.util.StripedIdentifierLockProvider;
import cn.chenxinjie.uploadfile.servlet.DownloadServlet;
import cn.chenxinjie.uploadfile.servlet.UploadServlet;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Configuration;

import javax.servlet.MultipartConfigElement;
import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.springframework.util.unit.DataSize;

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
 *
 * <p>Endpoint registration is controllable since rc.6 (see {@code upload-file.endpoint.*}):
 * {@code endpoint.enabled=false} is a beans-only mode (no servlet registered) and
 * {@code endpoint.download-enabled} defaults to {@code false} (minimal exposure). Multipart
 * limits follow {@code upload-file.multipart.strategy} ({@code component|spring|unlimited}).
 * Access decisions are observable through {@link AccessControlListener} beans and an optional
 * structured {@code observability.access-log}.</p>
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {"javax.servlet.Servlet", "javax.servlet.MultipartConfigElement"})
@EnableConfigurationProperties(UploadFileProperties.class)
public class UploadFileAutoConfiguration {

    private static final Log LOG = LogFactory.getLog(UploadFileAutoConfiguration.class);

    private static final String JDBC_STORE_CLASS = "cn.chenxinjie.uploadfile.store.jdbc.JdbcTaskStore";
    private static final String REDIS_STORE_CLASS = "cn.chenxinjie.uploadfile.store.redis.RedisTaskStore";
    private static final String REDIS_LOCK_CLASS = "cn.chenxinjie.uploadfile.store.redis.RedisCleanupLock";
    private static final String REDIS_IDENTIFIER_LOCK_CLASS =
            "cn.chenxinjie.uploadfile.store.redis.RedisIdentifierLockProvider";
    private static final String REDIS_QUOTA_STORE_CLASS =
            "cn.chenxinjie.uploadfile.store.redis.RedisQuotaStore";

    /** Slack added to a derived multipart request limit (boundaries/headers, rc.7). */
    private static final long MULTIPART_OVERHEAD = 1024 * 1024;

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

    /**
     * Startup guardrail (rc.7): warn when the endpoints are exposed but the effective policy is
     * permit-all (security disabled and no host {@code AccessControl} bean), so an open endpoint is
     * never silent. When security is enabled with a blank token, {@link #uploadFileAccessControl}
     * already fails fast.
     */
    @Bean
    public ApplicationRunner uploadFileSecurityWarning(UploadFileProperties properties, AccessControl accessControl) {
        return args -> {
            if (properties.getEndpoint().isEnabled() && accessControl instanceof PermitAllAccessControl) {
                LOG.warn("upload-file: endpoints are enabled but no access control is configured "
                        + "(upload-file.security.enabled=false and no host AccessControl bean); "
                        + "the upload endpoint is open to anyone");
            }
        };
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

    /**
     * Identifier-lock provider (rc.7): the in-process striped lock by default, or a distributed
     * Redis lock when {@code upload-file.lock.identifier-lock=redis}. Shared by the upload service
     * and the cleanup service so cleanup stays mutually exclusive with in-flight uploads.
     */
    @Bean
    @ConditionalOnMissingBean
    public IdentifierLockProvider uploadFileIdentifierLockProvider(UploadFileProperties properties,
                                                                  IdentifierLock identifierLock) {
        String type = properties.getLock().getIdentifierLock();
        if ("redis".equalsIgnoreCase(type == null ? "" : type.trim())) {
            if (isClassPresent(REDIS_IDENTIFIER_LOCK_CLASS)) {
                return cn.chenxinjie.uploadfile.store.redis.RedisIdentifierLockProvider.create(
                        properties.getRedis().getHost(), properties.getRedis().getPort(),
                        properties.getRedis().getPassword(),
                        properties.getRedis().getKeyPrefix() + "lock:",
                        (int) Math.max(1, properties.getLock().getTtl().getSeconds()),
                        properties.getLock().getAcquireTimeout().toMillis());
            }
            LOG.warn("upload-file.lock.identifier-lock=redis but upload-file-store-redis is not on the "
                    + "classpath; falling back to the in-process lock");
        }
        return new StripedIdentifierLockProvider(identifierLock);
    }

    /**
     * Global-capacity quota store (rc.7): the task-store-derived default, or an atomic Redis counter
     * when {@code upload-file.quota.store=redis}.
     */
    @Bean
    @ConditionalOnMissingBean
    public QuotaStore uploadFileQuotaStore(TaskStore taskStore, UploadFileProperties properties) {
        String store = properties.getQuota().getStore();
        if ("redis".equalsIgnoreCase(store == null ? "" : store.trim())) {
            if (isClassPresent(REDIS_QUOTA_STORE_CLASS)) {
                return cn.chenxinjie.uploadfile.store.redis.RedisQuotaStore.create(
                        properties.getRedis().getHost(), properties.getRedis().getPort(),
                        properties.getRedis().getPassword(), properties.getRedis().getKeyPrefix() + "quota:");
            }
            LOG.warn("upload-file.quota.store=redis but upload-file-store-redis is not on the classpath; "
                    + "falling back to the task-store quota");
        }
        return new TaskStoreQuotaStore(taskStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public ResumableUploadService resumableUploadService(TaskStore taskStore,
                                                         ChunkStorage chunkStorage,
                                                         UploadFileProperties properties,
                                                         IdentifierLock identifierLock,
                                                         IdentifierLockProvider identifierLockProvider,
                                                         QuotaStore quotaStore,
                                                         AccessControl accessControl,
                                                         ObjectProvider<ExecutorService> asyncExecutorProvider,
                                                         ObjectProvider<AccessControlListener> accessControlListeners) {
        File mergedDir = Paths.get(properties.getStorageDir(), "files").toFile();
        ResumableUploadService service = new ResumableUploadService(
                taskStore, chunkStorage, mergedDir,
                properties.isVerifyChecksum(), properties.getMerge().isFsync(), properties.getMerge().isAtomic(),
                identifierLock, accessControl);
        service.setIdentifierLockProvider(identifierLockProvider);
        service.setQuotaStore(quotaStore);
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
        accessControlListeners.orderedStream().forEach(service::addAccessControlListener);
        return service;
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    public ResumableDownloadService resumableDownloadService(TaskStore taskStore,
                                                             UploadFileProperties properties,
                                                             AccessControl accessControl,
                                                             ObjectProvider<AccessControlListener> accessControlListeners) {
        File mergedDir = Paths.get(properties.getStorageDir(), "files").toFile();
        ResumableDownloadService service = new ResumableDownloadService(taskStore, mergedDir);
        service.setAccessControl(accessControl);
        accessControlListeners.orderedStream().forEach(service::addAccessControlListener);
        return service;
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean
    public StorageCleanupService storageCleanupService(TaskStore taskStore,
                                                       ChunkStorage chunkStorage,
                                                       UploadFileProperties properties,
                                                       IdentifierLock identifierLock,
                                                       IdentifierLockProvider identifierLockProvider,
                                                       QuotaStore quotaStore,
                                                       ObjectProvider<CleanupLock> cleanupLockProvider) {
        File mergedDir = Paths.get(properties.getStorageDir(), "files").toFile();
        CleanupLock cleanupLock = properties.getCleanup().isUseRedisLock() ? cleanupLockProvider.getIfAvailable() : null;
        StorageCleanupService cleanup = new StorageCleanupService(
                taskStore, chunkStorage, mergedDir,
                properties.getCleanup().getTaskTtl().toMillis(), properties.getCleanup().isOrphanEnabled(),
                identifierLock, cleanupLock);
        cleanup.setIdentifierLockProvider(identifierLockProvider);
        cleanup.setQuotaStore(quotaStore);
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

    /**
     * Structured access-decision log (rc.6), active only when {@code observability.access-log=true}.
     * Host-provided {@link AccessControlListener} beans are added in addition to this log.
     */
    @Bean
    @ConditionalOnProperty(prefix = "upload-file", name = "observability.access-log", havingValue = "true")
    public AccessControlListener uploadFileAccessLogListener() {
        return (identifier, action, decision, elapsedNanos) -> {
            double elapsedMs = elapsedNanos / 1_000_000.0;
            if (decision.allowed()) {
                LOG.info("upload-file access: action=" + action + ", identifier=" + identifier
                        + ", decision=ALLOW, elapsedMs=" + elapsedMs);
            } else {
                LOG.warn("upload-file access: action=" + action + ", identifier=" + identifier
                        + ", decision=DENY, status=" + decision.statusCode()
                        + ", reason=" + decision.reason() + ", elapsedMs=" + elapsedMs);
            }
        };
    }

    /** Endpoint registration gate: {@code endpoint.enabled=false} = beans-only mode (rc.6). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "upload-file.endpoint", name = "enabled", havingValue = "true", matchIfMissing = true)
    public static class EndpointRegistrationConfiguration {

        /**
         * Registers the upload servlet unless {@code endpoint.upload-enabled=false} (rc.6).
         *
         * <p>The servlet instance is exposed as a bean so a host can override it by type
         * ({@code @Bean UploadServlet}); the registration can additionally be overridden by defining a
         * bean named {@code uploadFileServletRegistration}. A host that defines either wins.</p>
         */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnProperty(prefix = "upload-file.endpoint", name = "upload-enabled", havingValue = "true", matchIfMissing = true)
        public static class UploadServletRegistrationConfiguration {

            @Bean
            @ConditionalOnMissingBean
            public UploadServlet uploadFileServlet(ResumableUploadService uploadService,
                                                   UploadFileProperties properties,
                                                   ObjectProvider<UploadErrorRenderer> errorRendererProvider) {
                UploadServlet servlet = new UploadServlet();
                servlet.setUploadService(uploadService);
                servlet.setAccessTokenHeader(properties.getSecurity().getHeaderName());
                // rc.7: a host-provided UploadErrorRenderer bean wins over the property-selected
                // legacy/standard renderer, so a business envelope (e.g. ApiResponse) can be plugged in.
                servlet.setErrorRenderer(errorRendererProvider.getIfAvailable(
                        () -> UploadErrorRenderers.from(properties.getHttp().getErrorBody())));
                servlet.setCancelNotFoundStatus(properties.getHttp().getCancelNotFoundStatus());
                return servlet;
            }

            @Bean
            @ConditionalOnMissingBean(name = "uploadFileServletRegistration")
            public ServletRegistrationBean<UploadServlet> uploadFileServletRegistration(
                    UploadServlet uploadFileServlet, UploadFileProperties properties,
                    Environment environment) {
                ServletRegistrationBean<UploadServlet> registration =
                        new ServletRegistrationBean<>(uploadFileServlet, properties.getUploadUrl());
                registration.setName("uploadFileServlet");
                registration.setLoadOnStartup(1);
                registration.setMultipartConfig(multipartConfig(properties, environment));
                return registration;
            }
        }

        /**
         * Registers the download servlet only when {@code endpoint.download-enabled=true} (rc.6, off by default).
         *
         * <p>Same override contract as the upload servlet: a host-provided {@code DownloadServlet} bean or a
         * bean named {@code downloadFileServletRegistration} takes precedence.</p>
         */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnProperty(prefix = "upload-file.endpoint", name = "download-enabled", havingValue = "true")
        public static class DownloadServletRegistrationConfiguration {

            @Bean
            @ConditionalOnMissingBean
            public DownloadServlet downloadFileServlet(ResumableDownloadService downloadService,
                                                       UploadFileProperties properties,
                                                       ObjectProvider<UploadErrorRenderer> errorRendererProvider) {
                DownloadServlet servlet = new DownloadServlet();
                servlet.setDownloadService(downloadService);
                servlet.setAccessTokenHeader(properties.getSecurity().getHeaderName());
                // rc.7: host bean wins (see uploadFileServlet).
                servlet.setErrorRenderer(errorRendererProvider.getIfAvailable(
                        () -> UploadErrorRenderers.from(properties.getHttp().getErrorBody())));
                return servlet;
            }

            @Bean
            @ConditionalOnMissingBean(name = "downloadFileServletRegistration")
            public ServletRegistrationBean<DownloadServlet> downloadFileServletRegistration(
                    DownloadServlet downloadFileServlet, UploadFileProperties properties) {
                ServletRegistrationBean<DownloadServlet> registration =
                        new ServletRegistrationBean<>(downloadFileServlet, properties.getDownloadUrl());
                registration.setName("downloadFileServlet");
                registration.setLoadOnStartup(1);
                return registration;
            }
        }
    }

    /**
     * Builds the servlet {@code @MultipartConfig} limits from {@code upload-file.multipart.strategy}
     * (rc.6): {@code component} (rc.5 behaviour, from max-chunk/max-request), {@code spring}
     * (follow {@code spring.servlet.multipart.*} / {@code spring.http.multipart.*}, Boot defaults
     * 1 MB file / 10 MB request when unset) or {@code unlimited} (container limits off).
     */
    private static MultipartConfigElement multipartConfig(UploadFileProperties properties, Environment environment) {
        String strategy = properties.getMultipart().getStrategy();
        if ("spring".equalsIgnoreCase(strategy)) {
            long maxFile = dataSizeBytes(environment,
                    "spring.servlet.multipart.max-file-size", "spring.http.multipart.max-file-size", "1MB");
            long maxRequest = dataSizeBytes(environment,
                    "spring.servlet.multipart.max-request-size", "spring.http.multipart.max-request-size", "10MB");
            int threshold = (int) Math.min(dataSizeBytes(environment,
                    "spring.servlet.multipart.file-size-threshold",
                    "spring.http.multipart.file-size-threshold", "0B"), Integer.MAX_VALUE);
            return new MultipartConfigElement(null, maxFile, maxRequest, threshold);
        }
        if ("unlimited".equalsIgnoreCase(strategy)) {
            return new MultipartConfigElement(null, -1, -1, 1024 * 1024);
        }
        // rc.7 safe default: an unset request limit must not leave the container unbounded. Derive
        // a bounded limit from the per-chunk limit (or, failing that, the per-file limit); only when
        // none of the three is configured do we keep -1 and warn about the DoS surface.
        long maxRequestSize = properties.getMaxRequestSize();
        if (maxRequestSize <= 0) {
            if (properties.getMaxChunkSize() > 0) {
                maxRequestSize = properties.getMaxChunkSize() + MULTIPART_OVERHEAD;
                LOG.info("upload-file.multipart.max-request-size not set; deriving the container limit "
                        + maxRequestSize + " from max-chunk-size");
            } else if (properties.getMaxFileSize() > 0) {
                maxRequestSize = properties.getMaxFileSize() + MULTIPART_OVERHEAD;
                LOG.info("upload-file.multipart.max-request-size not set; deriving the container limit "
                        + maxRequestSize + " from max-file-size");
            } else {
                LOG.warn("upload-file.multipart: max-request-size/max-chunk-size/max-file-size are all "
                        + "unset; the container multipart limit is unbounded (DoS surface)");
            }
        }
        return new MultipartConfigElement(
                null, properties.getMaxChunkSize(), maxRequestSize, 1024 * 1024);
    }

    private static long dataSizeBytes(Environment environment, String... keysAndDefault) {
        String fallback = keysAndDefault[keysAndDefault.length - 1];
        for (int k = 0; k < keysAndDefault.length - 1; k++) {
            String value = environment.getProperty(keysAndDefault[k]);
            if (value != null && !value.trim().isEmpty()) {
                try {
                    return DataSize.parse(value.trim()).toBytes();
                } catch (IllegalArgumentException ignored) {
                    LOG.warn("Cannot parse multipart limit '" + value + "' for " + keysAndDefault[k]
                            + "; using " + fallback);
                }
            }
        }
        return DataSize.parse(fallback).toBytes();
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
