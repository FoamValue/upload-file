package cn.chenxinjie.uploadfile.core.model;

import java.util.Set;
import java.util.TreeSet;

/**
 * 一次上传任务的元数据，持久化到 {@link cn.chenxinjie.uploadfile.core.store.TaskStore}。
 */
public class UploadTask {

    private String identifier;
    private String fileName;
    private long fileSize;
    private long chunkSize;
    private int chunkTotal;

    /** 已上传完成的分片序号集合。 */
    private Set<Integer> uploadedChunks = new TreeSet<>();

    private boolean merged;
    private String finalPath;
    private long finalFileSize;
    private long createTime;
    private long updateTime;

    public static UploadTask from(ChunkUploadRequest req) {
        UploadTask task = new UploadTask();
        task.setIdentifier(req.getIdentifier());
        task.setFileName(req.getFileName());
        task.setFileSize(req.getFileSize());
        task.setChunkSize(req.getChunkSize());
        task.setChunkTotal(req.getChunkTotal());
        long now = System.currentTimeMillis();
        task.setCreateTime(now);
        task.setUpdateTime(now);
        return task;
    }

    public void markUploaded(int chunkIndex) {
        uploadedChunks.add(chunkIndex);
        updateTime = System.currentTimeMillis();
    }

    public boolean isUploaded(int chunkIndex) {
        return uploadedChunks.contains(chunkIndex);
    }

    public int uploadedCount() {
        return uploadedChunks == null ? 0 : uploadedChunks.size();
    }

    public boolean isComplete() {
        return chunkTotal > 0 && uploadedCount() >= chunkTotal;
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public long getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(long chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkTotal() {
        return chunkTotal;
    }

    public void setChunkTotal(int chunkTotal) {
        this.chunkTotal = chunkTotal;
    }

    public Set<Integer> getUploadedChunks() {
        return uploadedChunks;
    }

    public void setUploadedChunks(Set<Integer> uploadedChunks) {
        this.uploadedChunks = uploadedChunks;
    }

    public boolean isMerged() {
        return merged;
    }

    public void setMerged(boolean merged) {
        this.merged = merged;
    }

    public String getFinalPath() {
        return finalPath;
    }

    public void setFinalPath(String finalPath) {
        this.finalPath = finalPath;
    }

    public long getFinalFileSize() {
        return finalFileSize;
    }

    public void setFinalFileSize(long finalFileSize) {
        this.finalFileSize = finalFileSize;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }
}
