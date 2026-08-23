/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.model;

import java.util.Optional;

/**
 * Result of parsing an HTTP Range header (byte range, inclusive of {@code end}).
 *
 * <p>Three forms are supported:</p>
 * <ul>
 *   <li>{@code bytes=start-end}: explicit range</li>
 *   <li>{@code bytes=start-}: from {@code start} to the end of the file</li>
 *   <li>{@code bytes=-suffix}: the last {@code suffix} bytes</li>
 * </ul>
 */
public class DownloadRange {

    private final long start;
    private final long end;
    private final long total;

    public DownloadRange(long start, long end, long total) {
        if (start < 0 || end < start || total < 0 || end >= total) {
            throw new IllegalArgumentException(
                    "Invalid byte range: start=" + start + ", end=" + end + ", total=" + total);
        }
        this.start = start;
        this.end = end;
        this.total = total;
    }

    /**
     * Parses a Range request header.
     *
     * @return the satisfiable range; {@link Optional#empty()} when the header is missing,
     *         malformed, or unsatisfiable (e.g. {@code start} out of bounds)
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

    /** Number of bytes covered by this range (inclusive of {@code end}). */
    public long getContentLength() {
        return end - start + 1;
    }
}
