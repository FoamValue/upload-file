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
     * @return the number of bytes written to disk
     */
    long saveChunk(String identifier, int chunkIndex, InputStream in) throws IOException;

    /**
     * Stores a chunk with a byte limit, aborting mid-stream once the limit is exceeded so an
     * oversized chunk is never fully written to disk (M5).
     *
     * <p>Default implementation delegates to {@link #saveChunk(String, int, InputStream)} and
     * then checks the size, so custom implementations that want true streaming should override
     * this method.</p>
     *
     * @param maxBytes maximum bytes to write; a value &lt;= 0 means unlimited
     * @throws IOException if the chunk exceeds {@code maxBytes} (the partial temp file is removed)
     */
    default long saveChunk(String identifier, int chunkIndex, InputStream in, long maxBytes)
            throws IOException {
        long written = saveChunk(identifier, chunkIndex, in);
        if (maxBytes > 0 && written > maxBytes) {
            deleteChunk(identifier, chunkIndex);
            throw new IOException("Chunk " + chunkIndex + " exceeds the maximum allowed size of "
                    + maxBytes + " bytes (wrote " + written + ")");
        }
        return written;
    }

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
