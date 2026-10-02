/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.servlet;

import cn.chenxinjie.uploadfile.core.exception.AccessDeniedException;
import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.store.FileTaskStore;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import cn.chenxinjie.uploadfile.core.util.CleanupLock;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.mock.web.MockServletContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class UploadFileContextTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void getOrCreateCachesSharedContext() {
        MockServletContext servletContext = new MockServletContext();
        MockServletConfig config = new MockServletConfig(servletContext);
        config.addInitParameter("storage-dir", folder.getRoot().getAbsolutePath());
        config.addInitParameter("metadata-dir", folder.getRoot().getAbsolutePath() + "/meta");
        config.addInitParameter("chunk.max-size", "1024");

        UploadFileContext first = UploadFileContext.getOrCreate(servletContext, config);
        UploadFileContext second = UploadFileContext.getOrCreate(servletContext, config);

        assertSame(first, second);
        assertTrue(first.getTaskStore() instanceof FileTaskStore);
        assertNotNull(first.getUploadService());
        assertNotNull(first.getDownloadService());
        assertNotNull(first.getCleanupService());
    }

    @Test
    public void buildFromInitParamsWiresAllFeatures() throws Exception {
        MockServletContext servletContext = new MockServletContext();
        MockServletConfig config = new MockServletConfig(servletContext);
        config.addInitParameter("storage-dir", folder.getRoot().getAbsolutePath());
        config.addInitParameter("metadata-dir", folder.getRoot().getAbsolutePath() + "/meta");
        config.addInitParameter("metadata-store", "memory");
        config.addInitParameter("merge.fsync", "false");
        config.addInitParameter("merge.atomic", "false");
        config.addInitParameter("cleanup.enabled", "true");
        config.addInitParameter("cleanup.run-on-startup", "true");
        config.addInitParameter("cleanup.interval", "500");
        config.addInitParameter("cleanup.task-ttl", "not-a-number");
        config.addInitParameter("cleanup.orphan-enabled", "true");
        config.addInitParameter("async-merge.enabled", "true");
        config.addInitParameter("async-merge.thread-pool-size", "xyz");
        config.addInitParameter("chunk.max-size", "1024");

        UploadFileContext context = UploadFileContext.getOrCreate(servletContext, config);

        assertTrue(context.getTaskStore() instanceof MemoryTaskStore);
        assertNotNull(context.getAsyncExecutor());
        assertTrue(context.getCleanupService().isRunning());
        context.getCleanupService().stop();
    }

    @Test
    public void buildWithFileMetadataStore() {
        UploadFileContext.Config config = new UploadFileContext.Config();
        config.metadataStore = "file";
        config.maxChunkSize = 1024;
        UploadFileContext context = UploadFileContext.build(
                folder.getRoot().getAbsolutePath(), folder.getRoot().getAbsolutePath() + "/meta", config);
        assertTrue(context.getTaskStore() instanceof FileTaskStore);
    }

    @Test
    public void buildAutoStoreWithAndWithoutMetadataDir() {
        String root = folder.getRoot().getAbsolutePath();
        UploadFileContext.Config withDirConfig = new UploadFileContext.Config();
        withDirConfig.maxChunkSize = 1024;
        UploadFileContext withDir = UploadFileContext.build(root, root + "/meta", withDirConfig);
        assertTrue(withDir.getTaskStore() instanceof FileTaskStore);

        UploadFileContext.Config withoutDirConfig = new UploadFileContext.Config();
        withoutDirConfig.maxChunkSize = 1024;
        UploadFileContext withoutDir = UploadFileContext.build(root, null, withoutDirConfig);
        assertTrue(withoutDir.getTaskStore() instanceof MemoryTaskStore);
    }

    @Test
    public void nullMetadataStoreFallsBackToAuto() {
        UploadFileContext.Config config = new UploadFileContext.Config();
        config.metadataStore = null;
        config.maxChunkSize = 1024;
        UploadFileContext context = UploadFileContext.build(folder.getRoot().getAbsolutePath(), null, config);
        assertTrue(context.getTaskStore() instanceof MemoryTaskStore);
    }

    @Test
    public void maxChunkSizeIsEnforced() throws Exception {
        UploadFileContext.Config config = new UploadFileContext.Config();
        config.maxChunkSize = 4;
        UploadFileContext context = UploadFileContext.build(folder.getRoot().getAbsolutePath(), null, config);

        ChunkUploadRequest req = new ChunkUploadRequest();
        req.setIdentifier("size1");
        req.setFileName("demo.bin");
        req.setChunkSize(4);
        req.setChunkTotal(1);
        req.setChunkIndex(0);
        assertThrows(IllegalArgumentException.class,
                () -> context.getUploadService().uploadChunk(req,
                        new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void gettersExposeWiredComponents() {
        UploadFileContext.Config config = new UploadFileContext.Config();
        config.maxChunkSize = 1024;
        UploadFileContext context = UploadFileContext.build(folder.getRoot().getAbsolutePath(), null, config);
        assertNotNull(context.getTaskStore());
        assertNotNull(context.getUploadService());
        assertNotNull(context.getDownloadService());
        assertNotNull(context.getCleanupService());
    }

    @Test
    public void taskStoreExposedAsTaskStoreType() {
        UploadFileContext.Config config = new UploadFileContext.Config();
        config.maxChunkSize = 1024;
        UploadFileContext context = UploadFileContext.build(folder.getRoot().getAbsolutePath(), null, config);
        TaskStore store = context.getTaskStore();
        assertTrue(store instanceof MemoryTaskStore);
    }

    @Test
    public void rc3InitParamsAreParsed() throws Exception {
        MockServletContext servletContext = new MockServletContext();
        MockServletConfig config = new MockServletConfig(servletContext);
        config.addInitParameter("security.enabled", "true");
        config.addInitParameter("security.token", "secret");
        config.addInitParameter("security.header-name", "X-Custom-Token");
        config.addInitParameter("max-file-size", "1024");
        config.addInitParameter("quota.max-bytes", "2048");
        config.addInitParameter("observability.log-stats", "false");

        UploadFileContext.Config parsed = UploadFileContext.Config.fromInitParams(config);

        assertTrue(parsed.securityEnabled);
        assertEquals("secret", parsed.securityToken);
        assertEquals("X-Custom-Token", parsed.securityHeaderName);
        assertEquals(1024L, parsed.maxFileSize);
        assertEquals(2048L, parsed.quotaMaxBytes);
        assertTrue(!parsed.observabilityLogStats);
    }

    @Test
    public void rc6InitParamsAreParsed() {
        MockServletContext servletContext = new MockServletContext();
        MockServletConfig config = new MockServletConfig(servletContext);
        config.addInitParameter("observability.access-log", "true");
        config.addInitParameter("http.error-body", "standard");
        config.addInitParameter("cancel-not-found-status", "200");

        UploadFileContext.Config parsed = UploadFileContext.Config.fromInitParams(config);

        assertTrue(parsed.observabilityAccessLog);
        assertEquals("standard", parsed.httpErrorBody);
        assertEquals(200, parsed.cancelNotFoundStatus);
    }

    @Test
    public void accessLogListenerEmitsOnDecision() {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("cn.chenxinjie.uploadfile.access");
        java.util.List<java.util.logging.LogRecord> records = new java.util.ArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            UploadFileContext.Config config = new UploadFileContext.Config();
            config.observabilityAccessLog = true;
            config.maxChunkSize = 1024;
            UploadFileContext context = UploadFileContext.build(folder.getRoot().getAbsolutePath(), null, config);
            context.getUploadService().getProgress("audit1");
            assertTrue(records.stream().anyMatch(r -> r.getMessage().contains("upload-file access")));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    public void accessLogListenerLogsDeny() {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("cn.chenxinjie.uploadfile.access");
        java.util.List<java.util.logging.LogRecord> records = new java.util.ArrayList<>();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            UploadFileContext.Config config = new UploadFileContext.Config();
            config.securityEnabled = true;
            config.securityToken = "secret";
            config.observabilityAccessLog = true;
            config.maxChunkSize = 1024;
            UploadFileContext context = UploadFileContext.build(folder.getRoot().getAbsolutePath(), null, config);

            assertThrows(AccessDeniedException.class,
                    () -> context.getUploadService().getProgress("denied"));
            assertTrue(records.stream().anyMatch(r -> r.getLevel() == java.util.logging.Level.WARNING
                    && r.getMessage().contains("DENY")));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    public void logStatsDisabledStillBuildsCleanupService() {
        UploadFileContext.Config config = new UploadFileContext.Config();
        config.observabilityLogStats = false;
        config.maxChunkSize = 1024;
        UploadFileContext context = UploadFileContext.build(folder.getRoot().getAbsolutePath(), null, config);
        assertNotNull(context.getCleanupService());
    }

    @Test
    public void blankMetadataDirFallsBackToMemoryStore() {
        UploadFileContext.Config config = new UploadFileContext.Config();
        config.maxChunkSize = 1024;
        UploadFileContext context = UploadFileContext.build(
                folder.getRoot().getAbsolutePath(), "   ", config);
        assertTrue(context.getTaskStore() instanceof MemoryTaskStore);
    }

    @Test
    public void nullSecurityTokenFailsFast() {
        UploadFileContext.Config config = new UploadFileContext.Config();
        config.securityEnabled = true;
        config.securityToken = null;
        config.maxChunkSize = 1024; // keep the unbounded-upload fail-fast from masking this check
        assertThrows(IllegalArgumentException.class,
                () -> UploadFileContext.build(folder.getRoot().getAbsolutePath(), null, config));
    }

    @Test
    public void blankInitParamFallsBackToDefault() {
        MockServletContext servletContext = new MockServletContext();
        MockServletConfig config = new MockServletConfig(servletContext);
        config.addInitParameter("metadata-store", "   ");

        UploadFileContext.Config parsed = UploadFileContext.Config.fromInitParams(config);

        assertEquals("auto", parsed.metadataStore);
    }

    @Test
    public void accessTokenHeaderDefaultsAndOverrides() {
        UploadFileContext.Config plain = new UploadFileContext.Config();
        plain.maxChunkSize = 1024;
        UploadFileContext context = UploadFileContext.build(folder.getRoot().getAbsolutePath(), null, plain);
        assertEquals("X-Access-Token", context.getAccessTokenHeader());

        UploadFileContext.Config config = new UploadFileContext.Config();
        config.securityHeaderName = "X-Custom";
        config.maxChunkSize = 1024;
        UploadFileContext custom = UploadFileContext.build(folder.getRoot().getAbsolutePath(), null, config);
        assertEquals("X-Custom", custom.getAccessTokenHeader());
    }

    @Test
    public void securityEnabledWithoutTokenFailsFast() {
        UploadFileContext.Config config = new UploadFileContext.Config();
        config.securityEnabled = true;
        config.maxChunkSize = 1024; // keep the unbounded-upload fail-fast from masking this check
        assertThrows(IllegalArgumentException.class,
                () -> UploadFileContext.build(folder.getRoot().getAbsolutePath(), null, config));
    }

    @Test
    public void customCleanupLockIsWired() throws Exception {
        UploadFileContext.Config config = new UploadFileContext.Config();
        config.cleanupEnabled = true;
        config.cleanupIntervalMillis = 1000;
        config.maxChunkSize = 1024;
        CleanupLock lock = new CleanupLock() {
            @Override
            public boolean tryAcquire() {
                return true;
            }

            @Override
            public void release() {
            }
        };
        UploadFileContext context = UploadFileContext.build(folder.getRoot().getAbsolutePath(), null, config, lock);
        assertNotNull(context.getCleanupService());
        context.getCleanupService().stop();
    }

    @Test
    public void buildFailsFastWhenAllLimitsUnset() {
        // A completely unbounded upload (chunk.max-size / max-file-size / quota.max-bytes all 0)
        // is a DoS surface and must refuse to start, mirroring the starter's fail-fast.
        UploadFileContext.Config config = new UploadFileContext.Config();
        assertThrows(IllegalStateException.class,
                () -> UploadFileContext.build(folder.getRoot().getAbsolutePath(), null, config));

        // Any one configured limit is enough to start.
        UploadFileContext.Config quotaOnly = new UploadFileContext.Config();
        quotaOnly.quotaMaxBytes = 1024;
        assertNotNull(UploadFileContext.build(folder.getRoot().getAbsolutePath(), null, quotaOnly));

        UploadFileContext.Config fileOnly = new UploadFileContext.Config();
        fileOnly.maxFileSize = 1024;
        assertNotNull(UploadFileContext.build(folder.getRoot().getAbsolutePath(), null, fileOnly));
    }
}
