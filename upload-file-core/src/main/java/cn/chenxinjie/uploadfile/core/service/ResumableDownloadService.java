/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.service;

import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import cn.chenxinjie.uploadfile.core.util.Strings;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * 断点续传下载核心服务，基于 HTTP Range 提供区间读取。
 */
public class ResumableDownloadService {

    private static final int BUFFER_SIZE = 8192;

    private final TaskStore taskStore;
    private final File mergedFileDir;

    public ResumableDownloadService(TaskStore taskStore, File mergedFileDir) {
        this.taskStore = Objects.requireNonNull(taskStore, "taskStore");
        this.mergedFileDir = Objects.requireNonNull(mergedFileDir, "mergedFileDir");
    }

    /**
     * 定位已合并的完整文件。
     */
    public Optional<File> resolveFile(String identifier) {
        if (Strings.isBlank(identifier)) {
            return Optional.empty();
        }
        UploadTask task = taskStore.get(identifier).orElse(null);
        if (task == null) {
            return Optional.empty();
        }
        if (Strings.isNotBlank(task.getFinalPath())) {
            File file = new File(task.getFinalPath());
            if (file.isFile()) {
                return Optional.of(file);
            }
        }
        if (task.isMerged() && Strings.isNotBlank(task.getFileName())) {
            File file = new File(mergedFileDir, identifier + File.separator + task.getFileName());
            if (file.isFile()) {
                return Optional.of(file);
            }
        }
        return Optional.empty();
    }

    /**
     * 解析下载时的文件名（用于 Content-Disposition）。
     */
    public String resolveFileName(String identifier) {
        UploadTask task = taskStore.get(identifier).orElse(null);
        return task != null && Strings.isNotBlank(task.getFileName()) ? task.getFileName() : identifier;
    }

    /**
     * 将文件指定区间 [start, start + length) 的字节写入输出流。
     *
     * @return 实际写入的字节数
     */
    public long writeRange(File file, long start, long length, OutputStream out) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            skipFully(in, start);
            byte[] buffer = new byte[BUFFER_SIZE];
            long remaining = length;
            while (remaining > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) {
                    break;
                }
                out.write(buffer, 0, n);
                remaining -= n;
            }
            return length - remaining;
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long skipped = 0;
        while (skipped < n) {
            long s = in.skip(n - skipped);
            if (s <= 0) {
                if (in.read() == -1) {
                    throw new EOFException("文件提前结束");
                }
                skipped++;
            } else {
                skipped += s;
            }
        }
    }
}
