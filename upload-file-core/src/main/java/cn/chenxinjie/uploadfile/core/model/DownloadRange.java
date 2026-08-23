package cn.chenxinjie.uploadfile.core.model;

import java.util.Optional;

/**
 * HTTP Range 请求的解析结果（字节范围，含 end 端）。
 *
 * <p>支持三种写法：</p>
 * <ul>
 *   <li>{@code bytes=start-end}：指定范围</li>
 *   <li>{@code bytes=start-}：从 start 到文件末尾</li>
 *   <li>{@code bytes=-suffix}：最后 suffix 个字节</li>
 * </ul>
 */
public class DownloadRange {

    private final long start;
    private final long end;
    private final long total;

    public DownloadRange(long start, long end, long total) {
        if (start < 0 || end < start || total < 0 || end >= total) {
            throw new IllegalArgumentException(
                    "非法字节范围: start=" + start + ", end=" + end + ", total=" + total);
        }
        this.start = start;
        this.end = end;
        this.total = total;
    }

    /**
     * 解析 Range 请求头。
     *
     * @return 可满足的范围；头部缺失、格式非法或不可满足（如 start 越界）时返回 {@link Optional#empty()}
     */
    public static Optional<DownloadRange> parse(String rangeHeader, long total) {
        if (rangeHeader == null || total < 0) {
            return Optional.empty();
        }
        String header = rangeHeader.trim();
        if (!header.startsWith("bytes=")) {
            return Optional.empty();
        }
        String spec = header.substring("bytes=".length()).trim();
        if (spec.contains(",")) {
            spec = spec.substring(0, spec.indexOf(',')).trim();
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return Optional.empty();
        }
        String startStr = spec.substring(0, dash).trim();
        String endStr = spec.substring(dash + 1).trim();
        if (startStr.isEmpty() && endStr.isEmpty()) {
            return Optional.empty();
        }
        long start;
        long end;
        try {
            if (startStr.isEmpty()) {
                long suffix = Long.parseLong(endStr);
                if (suffix <= 0) {
                    return Optional.empty();
                }
                start = Math.max(0, total - suffix);
                end = total - 1;
            } else {
                start = Long.parseLong(startStr);
                end = endStr.isEmpty() ? total - 1 : Long.parseLong(endStr);
            }
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (total == 0 || start >= total || start > end) {
            return Optional.empty();
        }
        if (end >= total) {
            end = total - 1;
        }
        return Optional.of(new DownloadRange(start, end, total));
    }

    public long getStart() {
        return start;
    }

    public long getEnd() {
        return end;
    }

    public long getTotal() {
        return total;
    }

    /** 返回本范围的实际字节数（end 端含）。 */
    public long getContentLength() {
        return end - start + 1;
    }
}
