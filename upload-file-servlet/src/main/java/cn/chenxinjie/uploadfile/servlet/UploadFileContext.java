/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.servlet;

import cn.chenxinjie.uploadfile.core.service.ResumableDownloadService;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.core.storage.ChunkStorage;
import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import cn.chenxinjie.uploadfile.core.store.FileTaskStore;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.store.TaskStore;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import java.io.File;
import java.nio.file.Paths;

/**
 * 供上传/下载 Servlet 共享的运行时上下文，挂载在 {@link ServletContext} 属性上。
 *
 * <p>默认按以下 init-param 初始化：</p>
 * <ul>
 *   <li>{@code storage-dir}：分片与合并文件的根目录（默认 {@code ./upload-file-data}）</li>
 *   <li>{@code metadata-dir}：任务元数据持久化目录；不配置时使用内存存储</li>
 * </ul>
 */
public final class UploadFileContext {

    public static final String ATTRIBUTE_NAME = UploadFileContext.class.getName();

    private final TaskStore taskStore;
    private final ResumableUploadService uploadService;
    private final ResumableDownloadService downloadService;

    public UploadFileContext(TaskStore taskStore,
                             ResumableUploadService uploadService,
                             ResumableDownloadService downloadService) {
        this.taskStore = taskStore;
        this.uploadService = uploadService;
        this.downloadService = downloadService;
    }

    public static UploadFileContext getOrCreate(ServletContext servletContext, ServletConfig config) {
        synchronized (servletContext) {
            UploadFileContext context = (UploadFileContext) servletContext.getAttribute(ATTRIBUTE_NAME);
            if (context == null) {
                String storageDir = initParam(config, "storage-dir", "./upload-file-data");
                String metadataDir = initParam(config, "metadata-dir", null);
                context = build(storageDir, metadataDir);
                servletContext.setAttribute(ATTRIBUTE_NAME, context);
            }
            return context;
        }
    }

    /**
     * 直接按目录构建（供 Spring Boot 等容器接管时使用）。
     */
    public static UploadFileContext build(String storageDir, String metadataDir) {
        TaskStore store = metadataDir == null || metadataDir.trim().isEmpty()
                ? new MemoryTaskStore()
                : new FileTaskStore(Paths.get(metadataDir));
        ChunkStorage chunkStorage = new LocalFileChunkStorage(Paths.get(storageDir, "chunks"));
        File mergedDir = Paths.get(storageDir, "files").toFile();
        ResumableUploadService uploadService = new ResumableUploadService(store, chunkStorage, mergedDir);
        ResumableDownloadService downloadService = new ResumableDownloadService(store, mergedDir);
        return new UploadFileContext(store, uploadService, downloadService);
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
}
