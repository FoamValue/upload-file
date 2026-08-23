/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.storage;

import cn.chenxinjie.uploadfile.core.util.Strings;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地文件系统分片存储。
 *
 * <p>目录结构：{@code <root>/<identifier>/<chunkIndex>.part}，写盘采用「临时文件 + 原子改名」，
 * 避免上传中断时留下半个分片。</p>
 */
public class LocalFileChunkStorage implements ChunkStorage {

    private static final String SUFFIX = ".part";

    private final Path rootDir;

    public LocalFileChunkStorage(Path rootDir) {
        this.rootDir = rootDir;
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建分片目录: " + rootDir, e);
        }
    }

    public LocalFileChunkStorage(String rootDir) {
        this(Paths.get(rootDir));
    }

    private Path chunkDir(String identifier) {
        return rootDir.resolve(identifier);
    }

    private Path chunkPath(String identifier, int chunkIndex) {
        return chunkDir(identifier).resolve(chunkIndex + SUFFIX);
    }

    @Override
    public void saveChunk(String identifier, int chunkIndex, InputStream in) throws IOException {
        Strings.requireSafeIdentifier(identifier);
        Path target = chunkPath(identifier, chunkIndex);
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), ".upload-", SUFFIX);
        try {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Override
    public boolean chunkExists(String identifier, int chunkIndex) {
        Strings.requireSafeIdentifier(identifier);
        return Files.isRegularFile(chunkPath(identifier, chunkIndex));
    }

    @Override
    public File getChunkFile(String identifier, int chunkIndex) {
        Strings.requireSafeIdentifier(identifier);
        return chunkPath(identifier, chunkIndex).toFile();
    }

    @Override
    public List<Integer> listChunks(String identifier) {
        Strings.requireSafeIdentifier(identifier);
        List<Integer> result = new ArrayList<>();
        Path dir = chunkDir(identifier);
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
            for (Path path : ds) {
                String name = path.getFileName().toString();
                String index = name.substring(0, name.length() - SUFFIX.length());
                try {
                    result.add(Integer.parseInt(index));
                } catch (NumberFormatException ignored) {
                    // 忽略非分片文件
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("列出分片失败: " + dir, e);
        }
        Collections.sort(result);
        return result;
    }

    @Override
    public void deleteChunk(String identifier, int chunkIndex) {
        Strings.requireSafeIdentifier(identifier);
        try {
            Files.deleteIfExists(chunkPath(identifier, chunkIndex));
        } catch (IOException e) {
            throw new UncheckedIOException("删除分片失败: " + chunkPath(identifier, chunkIndex), e);
        }
    }

    @Override
    public void deleteChunks(String identifier) {
        Strings.requireSafeIdentifier(identifier);
        Path dir = chunkDir(identifier);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("删除分片目录失败: " + dir, e);
        }
    }
}
