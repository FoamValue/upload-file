/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.store;

import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.util.StringUtil;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-based metadata store. Each task is stored as an {@code identifier.json} file,
 * written via "temp file + atomic rename".
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
            throw new UncheckedIOException("Unable to create metadata directory: " + rootDir, e);
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
        StringUtil.requireSafeIdentifier(identifier);
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
            task.normalize();
            cache.put(identifier, task);
            return Optional.of(task);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read task metadata: " + path, e);
        }
    }

    @Override
    public void save(UploadTask task) {
        StringUtil.requireSafeIdentifier(task.getIdentifier());
        Path path = taskPath(task.getIdentifier());
        Path tmp = null;
        try {
            Files.createDirectories(rootDir);
            // Serialize to a temp file and atomically rename it into place, so a crash during
            // the write never corrupts the previous metadata file.
            byte[] bytes = gson.toJson(task).getBytes(StandardCharsets.UTF_8);
            tmp = Files.createTempFile(rootDir, ".meta-", SUFFIX);
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            // Keep the in-memory cache in sync so later reads hit the cache instead of the disk.
            cache.put(task.getIdentifier(), task);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save task metadata: " + path, e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // ignore cleanup failure
                }
            }
        }
    }

    @Override
    public boolean remove(String identifier) {
        StringUtil.requireSafeIdentifier(identifier);
        cache.remove(identifier);
        try {
            return Files.deleteIfExists(taskPath(identifier));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete task metadata: " + identifier, e);
        }
    }

    @Override
    public Collection<UploadTask> list() {
        List<UploadTask> result = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(rootDir, "*" + SUFFIX)) {
            for (Path path : ds) {
                String name = path.getFileName().toString();
                // Skip internal temp files (e.g. a leftover ".meta-*.json" from an interrupted save).
                if (name.startsWith(".")) {
                    continue;
                }
                String identifier = name.substring(0, name.length() - SUFFIX.length());
                try {
                    get(identifier).ifPresent(result::add);
                } catch (RuntimeException ignored) {
                    // Skip a corrupt metadata file without failing the whole list operation,
                    // matching the behavior of the JDBC/Redis stores.
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list task metadata: " + rootDir, e);
        }
        return Collections.unmodifiableList(result);
    }
}
