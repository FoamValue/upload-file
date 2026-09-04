/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.servlet;

import cn.chenxinjie.uploadfile.core.exception.AccessDeniedException;
import cn.chenxinjie.uploadfile.core.model.DownloadRange;
import cn.chenxinjie.uploadfile.core.service.ResumableDownloadService;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.Optional;

/**
 * Resumable-download servlet supporting HTTP Range requests.
 *
 * <p>Endpoint contract:</p>
 * <ul>
 *   <li>{@code GET /download?identifier=xxx}: download the full file (200)</li>
 *   <li>{@code GET /download?identifier=xxx} with {@code Range: bytes=start-end}: range download (206)</li>
 *   <li>An unsatisfiable Range returns 416; a missing file returns 404</li>
 * </ul>
 */
@WebServlet(name = "downloadFileServlet", urlPatterns = "/download", loadOnStartup = 1)
public class DownloadServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final int BUFFER_SIZE = 8192;

    private ResumableDownloadService downloadService;

    /** Name of the header carrying the access token; a {@code token} query param is also accepted. */
    private volatile String accessTokenHeader = "X-Access-Token";

    public void setDownloadService(ResumableDownloadService downloadService) {
        this.downloadService = downloadService;
    }

    public void setAccessTokenHeader(String accessTokenHeader) {
        if (accessTokenHeader != null && !accessTokenHeader.trim().isEmpty()) {
            this.accessTokenHeader = accessTokenHeader.trim();
        }
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        if (downloadService == null) {
            UploadFileContext context = UploadFileContext.getOrCreate(config.getServletContext(), config);
            downloadService = context.getDownloadService();
            accessTokenHeader = context.getAccessTokenHeader();
        }
    }

    /** Reads the access token from the configured header, falling back to a {@code token} query param. */
    private String token(HttpServletRequest req) {
        String fromHeader = req.getHeader(accessTokenHeader);
        if (fromHeader != null && !fromHeader.trim().isEmpty()) {
            return fromHeader.trim();
        }
        String fromParam = req.getParameter("token");
        return fromParam == null || fromParam.trim().isEmpty() ? null : fromParam.trim();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String identifier = req.getParameter("identifier");
        if (identifier == null || identifier.trim().isEmpty()) {
            resp.sendError(400, "Missing identifier");
            return;
        }
        String token = token(req);
        Optional<File> fileOpt;
        try {
            fileOpt = downloadService.resolveFile(identifier, token);
        } catch (AccessDeniedException e) {
            resp.sendError(401, "Access denied");
            return;
        }
        if (!fileOpt.isPresent()) {
            resp.sendError(404, "File not found: " + identifier);
            return;
        }
        File file = fileOpt.get();
        String fileName = downloadService.resolveFileName(identifier, token);

        resp.setHeader("Accept-Ranges", "bytes");
        setDisposition(resp, fileName);
        resp.setContentType("application/octet-stream");

        long total = file.length();
        String rangeHeader = req.getHeader("Range");
        if (rangeHeader == null) {
            // No Range header: stream the whole file with a 200 response.
            resp.setStatus(HttpServletResponse.SC_OK);
            setContentLength(resp, total);
            copyFull(file, resp.getOutputStream());
            return;
        }

        Optional<DownloadRange> rangeOpt = DownloadRange.parse(rangeHeader, total);
        if (!rangeOpt.isPresent()) {
            // The requested range is malformed or unsatisfiable (e.g. start beyond EOF).
            resp.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            resp.setHeader("Content-Range", "bytes */" + total);
            return;
        }
        DownloadRange range = rangeOpt.get();
        // Partial content: return only the requested byte range, enabling resumable downloads.
        resp.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
        resp.setHeader("Content-Range",
                "bytes " + range.getStart() + "-" + range.getEnd() + "/" + total);
        setContentLength(resp, range.getContentLength());
        downloadService.writeRange(file, range.getStart(), range.getContentLength(), resp.getOutputStream());
    }

    /**
     * Uses Servlet 3.0's {@code setContentLength} for files below 2 GB, falling back to Servlet 3.1's
     * {@code setContentLengthLong} for larger files; therefore files above 2 GB require a Servlet 3.1+ container.
     */
    private static void setContentLength(HttpServletResponse resp, long length) {
        if (length <= Integer.MAX_VALUE) {
            resp.setContentLength((int) length);
        } else {
            resp.setContentLengthLong(length);
        }
    }

    private static void copyFull(File file, OutputStream out) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
        }
    }

    private static void setDisposition(HttpServletResponse resp, String fileName) {
        String encoded;
        try {
            encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            encoded = fileName;
        }
        resp.setHeader("Content-Disposition",
                "attachment; filename=\"" + safeAscii(fileName) + "\"; filename*=UTF-8''" + encoded);
    }

    private static String safeAscii(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 0x20 && c < 0x7f && c != '"' && c != '\\') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
