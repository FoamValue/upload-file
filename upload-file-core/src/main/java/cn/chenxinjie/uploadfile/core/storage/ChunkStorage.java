/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.storage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * SPI for the physical storage of chunk files.
 *
 * <p>Built-in implementation: {@link LocalFileChunkStorage} (local file system).</p>
 * <p>Implement this interface to back chunks with object storage such as OSS or HDFS.</p>
 */
public interface ChunkStorage {

    /**
     * Stores a chunk.
     *
     * @param identifier unique file identifier
     * @param chunkIndex chunk index (starts at 0)
     * @param in         input stream of the chunk content; the whole stream is consumed by this method
     */
    void saveChunk(String identifier, int chunkIndex, InputStream in) throws IOException;

    boolean chunkExists(String identifier, int chunkIndex);

    /**
     * Returns the chunk file; when the chunk does not exist, a {@link File} pointing to a
     * non-existent path is returned and the caller must check it.
     */
    File getChunkFile(String identifier, int chunkIndex);

    /**
     * Returns the uploaded chunk indices (ascending).
     */
    List<Integer> listChunks(String identifier);

    void deleteChunk(String identifier, int chunkIndex);

    /**
     * Deletes all chunks of the given identifier.
     */
    void deleteChunks(String identifier);

    /**
     * Returns the identifiers currently present on disk (used by the orphan-data cleanup).
     *
     * <p>Default implementation returns an empty set, so custom implementations that do not
     * override this method are never wrongly cleaned. Only implementations that can enumerate
     * their on-disk data (e.g. {@link LocalFileChunkStorage}) should override it.</p>
     */
    default java.util.Set<String> listIdentifiers() {
        return java.util.Collections.emptySet();
    }
}
