/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.store;

import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.util.Strings;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于本地文件的元数据存储。每个任务一个 {@code identifier.json} 文件，写盘采用「临时文件 + 原子改名」。
 */
public class FileTaskStore implements TaskStore {

    private static final String SUFFIX = ".json";

    private final Path rootDir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, UploadTask> cache = new ConcurrentHashMap<>();

    public FileTaskStore(Path rootDir) {
        this.rootDir = rootDir;
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建元数据目录: " + rootDir, e);
        }
    }

    public FileTaskStore(String rootDir) {
        this(Paths.get(rootDir));
    }

    private Path taskPath(String identifier) {
        return rootDir.resolve(identifier + SUFFIX);
    }

    @Override
    public Optional<UploadTask> get(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return Optional.empty();
        }
        Strings.requireSafeIdentifier(identifier);
        UploadTask cached = cache.get(identifier);
        if (cached != null) {
            return Optional.of(cached);
        }
        Path path = taskPath(identifier);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            UploadTask task = gson.fromJson(json, UploadTask.class);
            if (task == null) {
                return Optional.empty();
            }
            if (task.getUploadedChunks() == null) {
                task.setUploadedChunks(new TreeSet<>());
            }
            cache.put(identifier, task);
            return Optional.of(task);
        } catch (IOException e) {
            throw new UncheckedIOException("读取任务元数据失败: " + path, e);
        }
    }

    @Override
    public void save(UploadTask task) {
        Strings.requireSafeIdentifier(task.getIdentifier());
        Path path = taskPath(task.getIdentifier());
        Path tmp = null;
        try {
            Files.createDirectories(rootDir);
            byte[] bytes = gson.toJson(task).getBytes(StandardCharsets.UTF_8);
            tmp = Files.createTempFile(rootDir, ".meta-", SUFFIX);
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            cache.put(task.getIdentifier(), task);
        } catch (IOException e) {
            throw new UncheckedIOException("保存任务元数据失败: " + path, e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 忽略清理失败
                }
            }
        }
    }

    @Override
    public boolean remove(String identifier) {
        Strings.requireSafeIdentifier(identifier);
        cache.remove(identifier);
        try {
            return Files.deleteIfExists(taskPath(identifier));
        } catch (IOException e) {
            throw new UncheckedIOException("删除任务元数据失败: " + identifier, e);
        }
    }

    @Override
    public Collection<UploadTask> list() {
        List<UploadTask> result = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(rootDir, "*" + SUFFIX)) {
            for (Path path : ds) {
                String name = path.getFileName().toString();
                String identifier = name.substring(0, name.length() - SUFFIX.length());
                get(identifier).ifPresent(result::add);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("列出任务元数据失败: " + rootDir, e);
        }
        return Collections.unmodifiableList(result);
    }
}
