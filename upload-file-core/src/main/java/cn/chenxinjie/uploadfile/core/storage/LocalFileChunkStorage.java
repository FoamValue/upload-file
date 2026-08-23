/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.storage;

import cn.chenxinjie.uploadfile.core.util.StringUtil;

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
 * Local file system chunk storage.
 *
 * <p>Directory layout: {@code <root>/<identifier>/<chunkIndex>.part}. Chunks are written via
 * "temp file + atomic rename", so an interrupted upload never leaves a half-written chunk.</p>
 */
public class LocalFileChunkStorage implements ChunkStorage {

    private static final String SUFFIX = ".part";

    private final Path rootDir;

    public LocalFileChunkStorage(Path rootDir) {
        this.rootDir = rootDir;
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create chunk directory: " + rootDir, e);
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
        StringUtil.requireSafeIdentifier(identifier);
        Path target = chunkPath(identifier, chunkIndex);
        Files.createDirectories(target.getParent());
        // Write to a temp file in the same directory first, then atomically rename it into place.
        // This guarantees that an interrupted upload never leaves a half-written chunk behind.
        Path tmp = Files.createTempFile(target.getParent(), ".upload-", SUFFIX);
        try {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some file systems do not support atomic moves; fall back to a plain rename.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Override
    public boolean chunkExists(String identifier, int chunkIndex) {
        StringUtil.requireSafeIdentifier(identifier);
        return Files.isRegularFile(chunkPath(identifier, chunkIndex));
    }

    @Override
    public File getChunkFile(String identifier, int chunkIndex) {
        StringUtil.requireSafeIdentifier(identifier);
        return chunkPath(identifier, chunkIndex).toFile();
    }

    @Override
    public List<Integer> listChunks(String identifier) {
        StringUtil.requireSafeIdentifier(identifier);
        List<Integer> result = new ArrayList<>();
        Path dir = chunkDir(identifier);
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
            for (Path path : ds) {
                // Derive the chunk index from the file name (e.g. "3.part" -> 3).
                String name = path.getFileName().toString();
                String index = name.substring(0, name.length() - SUFFIX.length());
                try {
                    result.add(Integer.parseInt(index));
                } catch (NumberFormatException ignored) {
                    // ignore non-chunk files
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list chunks: " + dir, e);
        }
        Collections.sort(result);
        return result;
    }

    @Override
    public void deleteChunk(String identifier, int chunkIndex) {
        StringUtil.requireSafeIdentifier(identifier);
        try {
            Files.deleteIfExists(chunkPath(identifier, chunkIndex));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete chunk: " + chunkPath(identifier, chunkIndex), e);
        }
    }

    @Override
    public void deleteChunks(String identifier) {
        StringUtil.requireSafeIdentifier(identifier);
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
            throw new UncheckedIOException("Failed to delete chunk directory: " + dir, e);
        }
    }
}
