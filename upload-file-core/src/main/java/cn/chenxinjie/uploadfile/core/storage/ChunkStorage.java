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
 * 分片文件物理存储 SPI。
 *
 * <p>内置实现：{@link LocalFileChunkStorage}（本地文件系统）。</p>
 * <p>可通过实现本接口将分片接入 OSS、HDFS 等对象存储。</p>
 */
public interface ChunkStorage {

    /**
     * 保存一个分片。
     *
     * @param identifier 文件唯一标识
     * @param chunkIndex 分片序号（从 0 开始）
     * @param in         分片内容输入流，本方法消费完整个流
     */
    void saveChunk(String identifier, int chunkIndex, InputStream in) throws IOException;

    boolean chunkExists(String identifier, int chunkIndex);

    /**
     * 返回分片文件；分片不存在时返回一个不存在路径的 {@link File}，由调用方判断。
     */
    File getChunkFile(String identifier, int chunkIndex);

    /**
     * 返回已上传的分片序号（升序）。
     */
    List<Integer> listChunks(String identifier);

    void deleteChunk(String identifier, int chunkIndex);

    /**
     * 删除该标识下的所有分片。
     */
    void deleteChunks(String identifier);
}
