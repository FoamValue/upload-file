/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.servlet;

import cn.chenxinjie.uploadfile.core.exception.AccessDeniedException;
import cn.chenxinjie.uploadfile.core.exception.QuotaExceededException;
import cn.chenxinjie.uploadfile.core.exception.UploadErrorCode;
import cn.chenxinjie.uploadfile.core.exception.UploadMergeConflictException;
import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.model.MergeStatus;
import cn.chenxinjie.uploadfile.core.model.UploadProgress;
import cn.chenxinjie.uploadfile.core.model.UploadResult;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import com.google.gson.Gson;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;

/**
 * Chunk-upload servlet.
 *
 * <p>Endpoint contract ({@code @MultipartConfig}, the file field is fixed as {@code file}):</p>
 * <ul>
 *   <li>{@code POST /upload}: upload one chunk (multipart, carrying identifier/fileName/fileSize/chunkSize/chunkTotal/chunkIndex/chunkMd5 plus the chunk file); returns {@link UploadProgress} JSON</li>
 *   <li>{@code POST /upload?action=merge&identifier=xxx}: merge chunks; returns {@link UploadResult} JSON</li>
 *   <li>{@code POST /upload?action=mergeAsync&identifier=xxx}: submit the merge asynchronously; returns {@link MergeStatus} JSON with HTTP 202</li>
 *   <li>{@code POST /upload?action=cancel&identifier=xxx}: cancel the task and reclaim its chunks/merged artifact; returns {@link UploadResult} JSON</li>
 *   <li>{@code GET /upload?action=mergeStatus&identifier=xxx}: query the async merge status; returns {@link MergeStatus} JSON</li>
 *   <li>{@code GET /upload?action=progress&identifier=xxx}: query progress; returns {@link UploadProgress} JSON</li>
 * </ul>
 *
 * <p>Failures are reported with the status code carried by the {@link cn.chenxinjie.uploadfile.core.exception.UploadErrorCode}
 * exceptions ({@code 400} validation/checksum, {@code 401} access denied, {@code 404} task not found,
 * {@code 409} merge-state conflict, {@code 507} quota) and a JSON body.</p>
 *
 * <p>The service can be injected via a setter, or the default implementation can be used by
 * providing {@code storage-dir} / {@code metadata-dir} init-params in web.xml or
 * {@code @WebInitParam} (shared with {@link DownloadServlet}).</p>
 */
@WebServlet(name = "uploadFileServlet", urlPatterns = "/upload", loadOnStartup = 1)
@MultipartConfig(fileSizeThreshold = 1024 * 1024, maxFileSize = -1, maxRequestSize = -1)
public class UploadServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(UploadServlet.class.getName());

    private final Gson gson = new Gson();
    private ResumableUploadService uploadService;

    /** Name of the header carrying the access token; a {@code token} query param is also accepted. */
    private volatile String accessTokenHeader = "X-Access-Token";

    public void setUploadService(ResumableUploadService uploadService) {
        this.uploadService = uploadService;
    }

    public ResumableUploadService getUploadService() {
        return uploadService;
    }

    public void setAccessTokenHeader(String accessTokenHeader) {
        if (accessTokenHeader != null && !accessTokenHeader.trim().isEmpty()) {
            this.accessTokenHeader = accessTokenHeader.trim();
        }
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        if (uploadService == null) {
            UploadFileContext context = UploadFileContext.getOrCreate(config.getServletContext(), config);
            uploadService = context.getUploadService();
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
        if ("mergeStatus".equals(req.getParameter("action"))) {
            doMergeStatus(req, resp);
        } else {
            doProgress(req, resp);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // action=merge triggers the merge endpoint; action=mergeAsync submits it asynchronously;
        // action=cancel removes the task and its data; anything else is treated as a chunk upload.
        String action = req.getParameter("action");
        if ("merge".equals(action)) {
            doMerge(req, resp);
        } else if ("mergeAsync".equals(action)) {
            doMergeAsync(req, resp);
        } else if ("cancel".equals(action)) {
            doCancel(req, resp);
        } else {
            doChunkUpload(req, resp);
        }
    }

    private void doChunkUpload(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // Build the chunk request from the multipart form fields; the chunk bytes are read below.
        ChunkUploadRequest chunkRequest = new ChunkUploadRequest();
        chunkRequest.setIdentifier(param(req, "identifier"));
        chunkRequest.setFileName(param(req, "fileName"));
        chunkRequest.setFileSize(longParam(req, "fileSize", 0));
        chunkRequest.setChunkSize(longParam(req, "chunkSize", 0));
        chunkRequest.setChunkTotal(intParam(req, "chunkTotal", -1));
        chunkRequest.setChunkIndex(intParam(req, "chunkIndex", -1));
        chunkRequest.setChunkMd5(param(req, "chunkMd5"));

        // The request must be a multipart request and contain a part named "file".
        Part part = null;
        try {
            part = req.getPart("file");
        } catch (ServletException | IllegalStateException e) {
            writeJson(resp, 400, gson.toJson(UploadProgress.empty(chunkRequest.getIdentifier())));
            return;
        }
        if (part == null) {
            writeJson(resp, 400, gson.toJson(UploadProgress.empty(chunkRequest.getIdentifier())));
            return;
        }
        // Stream the chunk body straight into the storage layer, then return the current progress.
        try (InputStream in = part.getInputStream()) {
            UploadProgress progress = uploadService.uploadChunk(chunkRequest, token(req), in);
            writeJson(resp, 200, gson.toJson(progress));
        } catch (AccessDeniedException e) {
            writeJson(resp, 401, gson.toJson(UploadProgress.empty(chunkRequest.getIdentifier())));
        } catch (QuotaExceededException e) {
            writeJson(resp, 507, gson.toJson(UploadProgress.empty(chunkRequest.getIdentifier())));
        } catch (Exception e) {
            writeJson(resp, statusOf(e), gson.toJson(UploadProgress.empty(chunkRequest.getIdentifier())));
        }
    }

    private void doMerge(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String identifier = param(req, "identifier");
        try {
            UploadResult result = uploadService.merge(identifier, token(req));
            writeJson(resp, result.isSuccess() ? 200 : 400, gson.toJson(result));
        } catch (AccessDeniedException e) {
            writeJson(resp, 401, gson.toJson(UploadResult.error(identifier, "Access denied")));
        } catch (QuotaExceededException e) {
            writeJson(resp, 507, gson.toJson(UploadResult.error(identifier, "Storage quota exceeded")));
        } catch (Exception e) {
            // Log the details server-side but return a generic message so internal paths
            // and implementation details are never exposed to the client.
            LOG.log(java.util.logging.Level.WARNING, "Merge failed for identifier: " + identifier, e);
            writeJson(resp, statusOf(e), gson.toJson(UploadResult.error(identifier, "Merge failed")));
        }
    }

    /**
     * Maps a core failure to a stable HTTP status: exceptions implementing
     * {@link UploadErrorCode} report their own code; everything else is a client error
     * ({@code 400}) on these upload endpoints.
     */
    private static int statusOf(Exception e) {
        if (e instanceof UploadErrorCode) {
            return ((UploadErrorCode) e).getHttpStatusCode();
        }
        return 400;
    }

    private void doCancel(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String identifier = param(req, "identifier");
        if (identifier == null || identifier.trim().isEmpty()) {
            writeJson(resp, 400, gson.toJson(UploadResult.error(null, "identifier is required")));
            return;
        }
        try {
            boolean removed = uploadService.cancelUpload(identifier, token(req));
            if (!removed) {
                writeJson(resp, 404, gson.toJson(UploadResult.error(identifier, "Upload task not found")));
                return;
            }
            UploadResult result = new UploadResult();
            result.setSuccess(true);
            result.setMessage("Upload task cancelled");
            result.setIdentifier(identifier);
            writeJson(resp, 200, gson.toJson(result));
        } catch (AccessDeniedException e) {
            writeJson(resp, 401, gson.toJson(UploadResult.error(identifier, "Access denied")));
        } catch (UploadMergeConflictException e) {
            // Generic message: an async merge is pending/running; internal state is not exposed.
            writeJson(resp, 409, gson.toJson(UploadResult.error(identifier, "Async merge in progress")));
        } catch (Exception e) {
            writeJson(resp, 400, gson.toJson(UploadResult.error(identifier, "Cancel failed")));
        }
    }

    private void doMergeAsync(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String identifier = param(req, "identifier");
        try {
            MergeStatus status = uploadService.submitMerge(identifier, token(req));
            writeJson(resp, 202, gson.toJson(status));
        } catch (AccessDeniedException e) {
            writeJson(resp, 401, gson.toJson(MergeStatus.none(identifier)));
        } catch (Exception e) {
            writeJson(resp, statusOf(e), gson.toJson(MergeStatus.none(identifier)));
        }
    }

    private void doMergeStatus(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String identifier = param(req, "identifier");
        if (identifier == null || identifier.trim().isEmpty()) {
            writeJson(resp, 400, gson.toJson(MergeStatus.none(null)));
            return;
        }
        try {
            MergeStatus status = uploadService.getMergeStatus(identifier, token(req));
            writeJson(resp, 200, gson.toJson(status));
        } catch (AccessDeniedException e) {
            writeJson(resp, 401, gson.toJson(MergeStatus.none(identifier)));
        } catch (Exception e) {
            writeJson(resp, 400, gson.toJson(MergeStatus.none(identifier)));
        }
    }

    private void doProgress(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String identifier = param(req, "identifier");
        if (identifier == null || identifier.trim().isEmpty()) {
            writeJson(resp, 400, gson.toJson(UploadProgress.empty(null)));
            return;
        }
        try {
            UploadProgress progress = uploadService.getProgress(identifier, token(req));
            writeJson(resp, 200, gson.toJson(progress));
        } catch (AccessDeniedException e) {
            writeJson(resp, 401, gson.toJson(UploadProgress.empty(identifier)));
        } catch (Exception e) {
            writeJson(resp, 400, gson.toJson(UploadProgress.empty(identifier)));
        }
    }

    private void writeJson(HttpServletResponse resp, int status, String json) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json; charset=UTF-8");
        resp.getWriter().write(json);
    }

    private static String param(HttpServletRequest req, String name) {
        return req.getParameter(name);
    }

    private static long longParam(HttpServletRequest req, String name, long defaultValue) {
        String v = req.getParameter(name);
        if (v == null || v.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int intParam(HttpServletRequest req, String name, int defaultValue) {
        String v = req.getParameter(name);
        if (v == null || v.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
