package cn.chenxinjie.uploadfile.core.model;

/**
 * 单个分片的上传请求参数。
 *
 * <p>由客户端在每次上传分片时提交，携带整个文件的元信息与当前分片的序号。</p>
 */
public class ChunkUploadRequest {

    /** 文件唯一标识，建议使用整个文件的 MD5（也用于分片目录与任务记录的主键）。 */
    private String identifier;

    /** 原始文件名（含扩展名）。 */
    private String fileName;

    /** 整个文件的字节大小；未知时可传 0。 */
    private long fileSize;

    /** 单个分片的字节大小；小于等于 0 时使用服务端默认分片大小。 */
    private long chunkSize;

    /** 总分片数。 */
    private int chunkTotal;

    /** 当前分片序号（从 0 开始）。 */
    private int chunkIndex;

    /** 当前分片的 MD5，用于上传完整性校验；可空（关闭校验时忽略）。 */
    private String chunkMd5;

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

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getChunkMd5() {
        return chunkMd5;
    }

    public void setChunkMd5(String chunkMd5) {
        this.chunkMd5 = chunkMd5;
    }
}
