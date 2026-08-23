package cn.chenxinjie.uploadfile.core.exception;

/**
 * 分片 MD5 校验不一致时抛出。
 */
public class ChecksumMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ChecksumMismatchException(String message) {
        super(message);
    }
}
