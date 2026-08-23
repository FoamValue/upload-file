package cn.chenxinjie.uploadfile.springboot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上传/下载工具包配置项，前缀 {@code upload-file}。
 */
@ConfigurationProperties(prefix = "upload-file")
public class UploadFileProperties {

    /** 分片与合并文件的根目录。 */
    private String storageDir = "./upload-file-data";

    /** 任务元数据持久化目录；为空时使用内存存储（重启后任务丢失）。 */
    private String metadataDir;

    /** 是否校验分片 MD5。 */
    private boolean verifyChecksum = true;

    /** 上传 Servlet 映射路径。 */
    private String uploadUrl = "/upload";

    /** 下载 Servlet 映射路径。 */
    private String downloadUrl = "/download";

    /** 单个分片最大字节数（multipart），-1 表示不限。 */
    private long maxChunkSize = -1;

    /** 单个请求最大字节数（multipart），-1 表示不限。 */
    private long maxRequestSize = -1;

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }

    public String getMetadataDir() {
        return metadataDir;
    }

    public void setMetadataDir(String metadataDir) {
        this.metadataDir = metadataDir;
    }

    public boolean isVerifyChecksum() {
        return verifyChecksum;
    }

    public void setVerifyChecksum(boolean verifyChecksum) {
        this.verifyChecksum = verifyChecksum;
    }

    public String getUploadUrl() {
        return uploadUrl;
    }

    public void setUploadUrl(String uploadUrl) {
        this.uploadUrl = uploadUrl;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public long getMaxChunkSize() {
        return maxChunkSize;
    }

    public void setMaxChunkSize(long maxChunkSize) {
        this.maxChunkSize = maxChunkSize;
    }

    public long getMaxRequestSize() {
        return maxRequestSize;
    }

    public void setMaxRequestSize(long maxRequestSize) {
        this.maxRequestSize = maxRequestSize;
    }
}
