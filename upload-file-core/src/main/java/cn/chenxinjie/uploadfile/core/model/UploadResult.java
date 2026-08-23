package cn.chenxinjie.uploadfile.core.model;

import java.util.Set;
import java.util.TreeSet;

/**
 * 上传操作的结果，主要用于「合并分片」的响应。
 */
public class UploadResult {

    private boolean success;
    private String message;
    private String identifier;
    private int chunkTotal;
    private int uploadedCount;
    private Set<Integer> uploadedChunks = new TreeSet<>();
    private boolean merged;
    private String finalPath;
    private long finalFileSize;

    public static UploadResult merged(UploadTask task, String finalPath, long finalFileSize) {
        UploadResult r = new UploadResult();
        r.setSuccess(true);
        r.setMessage("合并成功");
        r.setIdentifier(task.getIdentifier());
        r.setChunkTotal(task.getChunkTotal());
        r.setUploadedCount(task.getChunkTotal());
        r.setUploadedChunks(new TreeSet<>(task.getUploadedChunks()));
        r.setMerged(true);
        r.setFinalPath(finalPath);
        r.setFinalFileSize(finalFileSize);
        return r;
    }

    public static UploadResult error(String identifier, String message) {
        UploadResult r = new UploadResult();
        r.setSuccess(false);
        r.setMessage(message);
        r.setIdentifier(identifier);
        r.setMerged(false);
        return r;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public int getChunkTotal() {
        return chunkTotal;
    }

    public void setChunkTotal(int chunkTotal) {
        this.chunkTotal = chunkTotal;
    }

    public int getUploadedCount() {
        return uploadedCount;
    }

    public void setUploadedCount(int uploadedCount) {
        this.uploadedCount = uploadedCount;
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
}
