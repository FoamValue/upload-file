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
     * <p>This is the original frozen-SPI method, binary-compatible with rc.7/rc.8: custom
     * implementations compiled against those versions keep working unchanged. New
     * implementations should prefer {@link #saveChunk(String, int, InputStream, long)}, which
     * reports the written byte count and can enforce the limit mid-stream.</p>
     *
     * @param identifier unique file identifier
     * @param chunkIndex chunk index (starts at 0)
     * @param in         input stream of the chunk content; the whole stream is consumed by this method
     */
    void saveChunk(String identifier, int chunkIndex, InputStream in) throws IOException;

    /**
     * Stores a chunk with a byte limit and returns the number of bytes actually written.
     *
     * <p>The default implementation wraps the stream in a byte counter and delegates to
     * {@link #saveChunk(String, int, InputStream)}, so size accounting is always based on the
     * actual bytes read (H1) even for implementations that only know the original signature; when
     * the limit is exceeded the chunk is deleted and an {@link IOException} is thrown. Implementations
     * that want to abort mid-stream instead of writing the whole payload first should override this
     * method (see {@link LocalFileChunkStorage}).</p>
     *
     * @param maxBytes maximum bytes to write; a value &lt;= 0 means unlimited
     * @return the number of bytes actually written
     * @throws IOException if the chunk exceeds {@code maxBytes} (the chunk is deleted)
     */
    default long saveChunk(String identifier, int chunkIndex, InputStream in, long maxBytes)
            throws IOException {
        CountingInputStream counting = new CountingInputStream(in);
        saveChunk(identifier, chunkIndex, counting);
        long written = counting.written();
        if (maxBytes > 0 && written > maxBytes) {
            deleteChunk(identifier, chunkIndex);
            throw new IOException("Chunk " + chunkIndex + " exceeds the maximum allowed size of "
                    + maxBytes + " bytes (wrote " + written + ")");
        }
        return written;
    }

    /**
     * Counts the bytes read from the delegate stream, so the actual written size is known even
     * when delegating to the legacy 3-arg method of a custom implementation.
     */
    final class CountingInputStream extends java.io.InputStream {
        private final java.io.InputStream delegate;
        private long written;

        CountingInputStream(java.io.InputStream delegate) {
            this.delegate = delegate;
        }

        long written() {
            return written;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                written++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) {
                written += n;
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
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
