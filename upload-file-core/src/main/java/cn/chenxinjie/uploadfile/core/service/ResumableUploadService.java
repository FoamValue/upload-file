package cn.chenxinjie.uploadfile.core.service;

import cn.chenxinjie.uploadfile.core.exception.ChecksumMismatchException;
import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.model.UploadProgress;
import cn.chenxinjie.uploadfile.core.model.UploadResult;
import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.storage.ChunkStorage;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import cn.chenxinjie.uploadfile.core.util.ChecksumUtil;
import cn.chenxinjie.uploadfile.core.util.Strings;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 断点续传上传核心服务。
 *
 * <p>职责：分片保存、进度记录、分片校验、分片合并与清理。</p>
 *
 * <p>同一 identifier 的分片操作用按标识分片的锁（striped lock）串行化，
 * 保证并发上传时任务创建与进度记录的一致性。</p>
 */
public class ResumableUploadService {

    public static final int DEFAULT_CHUNK_SIZE = 5 * 1024 * 1024;

    /** 锁桶数量，固定大小避免无界增长。 */
    private static final int LOCK_COUNT = 64;

    private final TaskStore taskStore;
    private final ChunkStorage chunkStorage;
    private final File mergedFileDir;
    private final boolean verifyChecksum;
    private final Object[] locks = new Object[LOCK_COUNT];

    public ResumableUploadService(TaskStore taskStore, ChunkStorage chunkStorage, File mergedFileDir) {
        this(taskStore, chunkStorage, mergedFileDir, true);
    }

    public ResumableUploadService(TaskStore taskStore, ChunkStorage chunkStorage, File mergedFileDir, boolean verifyChecksum) {
        this.taskStore = Objects.requireNonNull(taskStore, "taskStore");
        this.chunkStorage = Objects.requireNonNull(chunkStorage, "chunkStorage");
        this.mergedFileDir = Objects.requireNonNull(mergedFileDir, "mergedFileDir");
        this.verifyChecksum = verifyChecksum;
        for (int i = 0; i < LOCK_COUNT; i++) {
            locks[i] = new Object();
        }
    }

    private Object lockFor(String identifier) {
        return locks[(identifier.hashCode() & 0x7fffffff) % LOCK_COUNT];
    }

    /**
     * 上传一个分片。
     *
     * <p>分片已上传过时会直接跳过（幂等），可用于断点续传。</p>
     *
     * @return 当前进度
     * @throws ChecksumMismatchException 分片 MD5 与期望值不一致（仅拒绝该分片，不影响其它已上传分片）
     */
    public UploadProgress uploadChunk(ChunkUploadRequest request, InputStream in) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(in, "inputStream");
        String identifier = Strings.requireSafeIdentifier(request.getIdentifier());
        int chunkIndex = request.getChunkIndex();
        int chunkTotal = request.getChunkTotal();
        if (chunkTotal <= 0) {
            throw new IllegalArgumentException("chunkTotal 必须大于 0");
        }
        if (chunkIndex < 0 || chunkIndex >= chunkTotal) {
            throw new IllegalArgumentException("chunkIndex 越界: " + chunkIndex);
        }
        long chunkSize = request.getChunkSize() > 0 ? request.getChunkSize() : DEFAULT_CHUNK_SIZE;

        synchronized (lockFor(identifier)) {
            UploadTask task = taskStore.get(identifier).orElse(null);
            if (task == null) {
                Strings.requireSafeFileName(request.getFileName());
                task = UploadTask.from(request);
                task.setChunkSize(chunkSize);
                taskStore.save(task);
            }
            if (task.isMerged()) {
                throw new IllegalStateException("任务已合并，不可再上传分片: " + identifier);
            }
            if (!task.getUploadedChunks().contains(chunkIndex)) {
                chunkStorage.saveChunk(identifier, chunkIndex, in);
                if (verifyChecksum && Strings.isNotBlank(request.getChunkMd5())) {
                    File saved = chunkStorage.getChunkFile(identifier, chunkIndex);
                    String actual = ChecksumUtil.md5(saved);
                    if (!actual.equalsIgnoreCase(request.getChunkMd5().trim())) {
                        chunkStorage.deleteChunk(identifier, chunkIndex);
                        throw new ChecksumMismatchException(
                                "分片 " + chunkIndex + " MD5 不一致，期望 "
                                        + request.getChunkMd5() + "，实际 " + actual);
                    }
                }
                task.markUploaded(chunkIndex);
                taskStore.save(task);
            }
            return UploadProgress.from(task);
        }
    }

    /**
     * 查询上传进度；任务不存在时返回空进度，便于客户端作为「全新上传」处理。
     */
    public UploadProgress getProgress(String identifier) {
        Strings.requireSafeIdentifier(identifier);
        UploadTask task = taskStore.get(identifier).orElse(null);
        return task == null ? UploadProgress.empty(identifier) : UploadProgress.from(task);
    }

    /**
     * 判断指定分片是否已上传完成。
     */
    public boolean isChunkUploaded(String identifier, int chunkIndex) {
        UploadTask task = taskStore.get(identifier).orElse(null);
        return task != null && task.isUploaded(chunkIndex);
    }

    /**
     * 合并所有已上传分片为完整文件，并清理分片。
     *
     * <p>合并后文件写入 {@code <mergedFileDir>/<identifier>/<fileName>}。</p>
     *
     * @throws IllegalStateException 分片不完整或合并后文件大小与声明不一致
     * @throws NoSuchElementException 任务不存在
     */
    public UploadResult merge(String identifier) throws IOException {
        Strings.requireSafeIdentifier(identifier);
        synchronized (lockFor(identifier)) {
            UploadTask task = taskStore.get(identifier).orElse(null);
            if (task == null) {
                throw new NoSuchElementException("上传任务不存在: " + identifier);
            }
            if (task.isMerged()) {
                return UploadResult.merged(task, task.getFinalPath(), task.getFinalFileSize());
            }
            int missing = 0;
            for (int i = 0; i < task.getChunkTotal(); i++) {
                if (!chunkStorage.chunkExists(identifier, i)) {
                    missing++;
                }
            }
            if (missing > 0) {
                throw new IllegalStateException("存在未上传的分片，缺少 " + missing + " 个: " + identifier);
            }
            Strings.requireSafeFileName(task.getFileName());
            Path dir = mergedFileDir.toPath().resolve(identifier);
            Files.createDirectories(dir);
            Path out = dir.resolve(task.getFileName());
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
                for (int i = 0; i < task.getChunkTotal(); i++) {
                    File chunkFile = chunkStorage.getChunkFile(identifier, i);
                    if (!chunkFile.isFile()) {
                        throw new IllegalStateException("分片缺失: " + i + " (" + identifier + ")");
                    }
                    Files.copy(chunkFile.toPath(), os);
                }
            }
            long size = Files.size(out);
            if (task.getFileSize() > 0 && size != task.getFileSize()) {
                Files.deleteIfExists(out);
                throw new IllegalStateException("合并后文件大小不一致，期望 "
                        + task.getFileSize() + "，实际 " + size + " (" + identifier + ")");
            }
            chunkStorage.deleteChunks(identifier);
            task.setMerged(true);
            task.setFinalPath(out.toAbsolutePath().toString());
            task.setFinalFileSize(size);
            taskStore.save(task);
            return UploadResult.merged(task, out.toAbsolutePath().toString(), size);
        }
    }

    public TaskStore getTaskStore() {
        return taskStore;
    }
}
