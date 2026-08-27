/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.servlet;

import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.store.FileTaskStore;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.mock.web.MockServletContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

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
        UploadFileContext context = UploadFileContext.build(
                folder.getRoot().getAbsolutePath(), folder.getRoot().getAbsolutePath() + "/meta", config);
        assertTrue(context.getTaskStore() instanceof FileTaskStore);
    }

    @Test
    public void buildAutoStoreWithAndWithoutMetadataDir() {
        String root = folder.getRoot().getAbsolutePath();
        UploadFileContext withDir = UploadFileContext.build(root, root + "/meta", new UploadFileContext.Config());
        assertTrue(withDir.getTaskStore() instanceof FileTaskStore);

        UploadFileContext withoutDir = UploadFileContext.build(root, null, new UploadFileContext.Config());
        assertTrue(withoutDir.getTaskStore() instanceof MemoryTaskStore);
    }

    @Test
    public void nullMetadataStoreFallsBackToAuto() {
        UploadFileContext.Config config = new UploadFileContext.Config();
        config.metadataStore = null;
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
        UploadFileContext context = UploadFileContext.build(folder.getRoot().getAbsolutePath(), null);
        assertNotNull(context.getTaskStore());
        assertNotNull(context.getUploadService());
        assertNotNull(context.getDownloadService());
        assertNotNull(context.getCleanupService());
    }

    @Test
    public void taskStoreExposedAsTaskStoreType() {
        UploadFileContext context = UploadFileContext.build(folder.getRoot().getAbsolutePath(), null);
        TaskStore store = context.getTaskStore();
        assertTrue(store instanceof MemoryTaskStore);
    }
}
