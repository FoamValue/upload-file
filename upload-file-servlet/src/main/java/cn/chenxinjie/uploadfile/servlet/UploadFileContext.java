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
 * Runtime context shared by the upload/download servlets, attached as a {@link ServletContext} attribute.
 *
 * <p>By default it is initialized from the following init-params:</p>
 * <ul>
 *   <li>{@code storage-dir}: root dir for chunks and merged files (default {@code ./upload-file-data})</li>
 *   <li>{@code metadata-dir}: task metadata persistence dir; when not set, in-memory storage is used</li>
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
        // Lazily build the shared context once and cache it on the ServletContext, so the
        // upload and download servlets always use the same store and directories.
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
     * Builds a context directly from directories (used when a container such as Spring Boot takes over).
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
