/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.servlet;

import cn.chenxinjie.uploadfile.core.error.UploadErrorRenderer;
import cn.chenxinjie.uploadfile.core.error.UploadErrorRenderers;
import cn.chenxinjie.uploadfile.core.exception.AccessDeniedException;
import cn.chenxinjie.uploadfile.core.exception.QuotaExceededException;
import cn.chenxinjie.uploadfile.core.exception.UploadErrorCodes;
import cn.chenxinjie.uploadfile.core.exception.UploadMergeConflictException;
import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.model.MergeStatus;
import cn.chenxinjie.uploadfile.core.model.UploadProgress;
import cn.chenxinjie.uploadfile.core.model.UploadResult;
import cn.chenxinjie.uploadfile.core.security.AccessControl;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import com.google.gson.Gson;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
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
 *   <li>{@code GET /upload?action=progress&identifier=xxx}: query progress; returns {@link UploadProgress} JSON</li>
 *   <li>{@code GET /upload?action=mergeStatus&identifier=xxx}: query the async merge status; returns {@link MergeStatus} JSON</li>
 * </ul>
 *
 * <p>Behaviour notes since rc.6:</p>
 * <ul>
 *   <li>{@code GET /upload} requires a known {@code action}; a missing or unknown action returns {@code 400};</li>
 *   <li>failures whose exception is not an {@code UploadErrorCode} (and not a raw
 *       {@code IllegalArgumentException}) are reported as {@code 500} instead of being collapsed to {@code 400};</li>
 *   <li>the failure body is chosen by the {@code http.error-body} setting: {@code legacy} (default, the
 *       rc.5 per-endpoint models) or {@code standard} ({@code UploadHttpError} + symbolic code);</li>
 *   <li>{@code cancel} on a missing task returns {@code 404} by default, or {@code 200} when
 *       {@code cancel-not-found-status=200} is configured (idempotent reclaim).</li>
 * </ul>
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

    /** Failure-body renderer (rc.6); {@code legacy} by default. */
    private volatile UploadErrorRenderer errorRenderer = UploadErrorRenderers.legacy();

    /** HTTP status for canceling a missing task; {@code 404} by default, {@code 200} = idempotent. */
    private volatile int cancelNotFoundStatus = 404;

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

    public void setErrorRenderer(UploadErrorRenderer errorRenderer) {
        if (errorRenderer != null) {
            this.errorRenderer = errorRenderer;
        }
    }

    public void setCancelNotFoundStatus(int cancelNotFoundStatus) {
        this.cancelNotFoundStatus = cancelNotFoundStatus;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        if (uploadService == null) {
            UploadFileContext context = UploadFileContext.getOrCreate(config.getServletContext(), config);
            uploadService = context.getUploadService();
            accessTokenHeader = context.getAccessTokenHeader();
            setErrorRenderer(UploadErrorRenderers.from(context.getHttpErrorBody()));
            setCancelNotFoundStatus(context.getCancelNotFoundStatus());
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
        String action = param(req, "action");
        if (action == null || action.trim().isEmpty()) {
            writeError(resp, AccessControl.ACTION_PROGRESS, param(req, "identifier"), 400,
                    UploadErrorCodes.MISSING_ACTION, "action is required");
            return;
        }
        if ("mergeStatus".equals(action)) {
            doMergeStatus(req, resp);
        } else if ("progress".equals(action)) {
            doProgress(req, resp);
        } else {
            writeError(resp, AccessControl.ACTION_PROGRESS, param(req, "identifier"), 400,
                    UploadErrorCodes.UPLOAD_UNKNOWN_ACTION, "unsupported action: " + action);
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
            writeError(resp, AccessControl.ACTION_UPLOAD, chunkRequest.getIdentifier(), 400,
                    UploadErrorCodes.UPLOAD_VALIDATION, "file part is required");
            return;
        }
        if (part == null) {
            writeError(resp, AccessControl.ACTION_UPLOAD, chunkRequest.getIdentifier(), 400,
                    UploadErrorCodes.UPLOAD_VALIDATION, "file part is required");
            return;
        }
        // Stream the chunk body straight into the storage layer, then return the current progress.
        try (InputStream in = part.getInputStream()) {
            UploadProgress progress = uploadService.uploadChunk(chunkRequest, token(req), in);
            writeJson(resp, 200, gson.toJson(progress));
        } catch (Exception e) {
            writeError(resp, AccessControl.ACTION_UPLOAD, chunkRequest.getIdentifier(),
                    UploadErrorRenderers.statusOf(e), UploadErrorRenderers.codeOf(e), "Upload failed");
        }
    }

    private void doMerge(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String identifier = param(req, "identifier");
        try {
            UploadResult result = uploadService.merge(identifier, token(req));
            writeJson(resp, result.isSuccess() ? 200 : 400, gson.toJson(result));
        } catch (Exception e) {
            // Log the details server-side but return a generic message so internal paths
            // and implementation details are never exposed to the client.
            LOG.log(java.util.logging.Level.WARNING, "Merge failed for identifier: " + identifier, e);
            writeError(resp, AccessControl.ACTION_MERGE, identifier,
                    UploadErrorRenderers.statusOf(e), UploadErrorRenderers.codeOf(e), mergeMessage(e));
        }
    }

    private static String mergeMessage(Exception e) {
        if (e instanceof AccessDeniedException) {
            return "Access denied";
        }
        if (e instanceof QuotaExceededException) {
            return "Storage quota exceeded";
        }
        return "Merge failed";
    }

    private void doCancel(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String identifier = param(req, "identifier");
        if (identifier == null || identifier.trim().isEmpty()) {
            writeError(resp, AccessControl.ACTION_CANCEL, null, 400,
                    UploadErrorCodes.MISSING_IDENTIFIER, "identifier is required");
            return;
        }
        try {
            boolean removed = uploadService.cancelUpload(identifier, token(req));
            if (!removed) {
                // Default: strict 404. Optional 200 = idempotent reclaim (nothing to cancel is fine).
                int status = cancelNotFoundStatus == 200 ? 200 : 404;
                writeError(resp, AccessControl.ACTION_CANCEL, identifier, status,
                        UploadErrorCodes.UPLOAD_NOT_FOUND, "Upload task not found");
                return;
            }
            UploadResult result = new UploadResult();
            result.setSuccess(true);
            result.setMessage("Upload task cancelled");
            result.setIdentifier(identifier);
            writeJson(resp, 200, gson.toJson(result));
        } catch (AccessDeniedException e) {
            writeError(resp, AccessControl.ACTION_CANCEL, identifier, e.getStatusCode(),
                    UploadErrorCodes.ACCESS_DENIED, "Access denied");
        } catch (UploadMergeConflictException e) {
            // Generic message: an async merge is pending/running; internal state is not exposed.
            writeError(resp, AccessControl.ACTION_CANCEL, identifier, 409,
                    UploadErrorCodes.UPLOAD_MERGE_CONFLICT, "Async merge in progress");
        } catch (Exception e) {
            writeError(resp, AccessControl.ACTION_CANCEL, identifier,
                    UploadErrorRenderers.statusOf(e), UploadErrorRenderers.codeOf(e), "Cancel failed");
        }
    }

    private void doMergeAsync(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String identifier = param(req, "identifier");
        try {
            MergeStatus status = uploadService.submitMerge(identifier, token(req));
            writeJson(resp, 202, gson.toJson(status));
        } catch (AccessDeniedException e) {
            writeError(resp, AccessControl.ACTION_MERGE_ASYNC, identifier, e.getStatusCode(),
                    UploadErrorCodes.ACCESS_DENIED, "Access denied");
        } catch (Exception e) {
            writeError(resp, AccessControl.ACTION_MERGE_ASYNC, identifier,
                    UploadErrorRenderers.statusOf(e), UploadErrorRenderers.codeOf(e), "Async merge failed");
        }
    }

    private void doMergeStatus(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String identifier = param(req, "identifier");
        if (identifier == null || identifier.trim().isEmpty()) {
            writeError(resp, AccessControl.ACTION_MERGE_STATUS, null, 400,
                    UploadErrorCodes.MISSING_IDENTIFIER, "identifier is required");
            return;
        }
        try {
            MergeStatus status = uploadService.getMergeStatus(identifier, token(req));
            writeJson(resp, 200, gson.toJson(status));
        } catch (AccessDeniedException e) {
            writeError(resp, AccessControl.ACTION_MERGE_STATUS, identifier, e.getStatusCode(),
                    UploadErrorCodes.ACCESS_DENIED, "Access denied");
        } catch (Exception e) {
            writeError(resp, AccessControl.ACTION_MERGE_STATUS, identifier,
                    UploadErrorRenderers.statusOf(e), UploadErrorRenderers.codeOf(e), "Query merge status failed");
        }
    }

    private void doProgress(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String identifier = param(req, "identifier");
        if (identifier == null || identifier.trim().isEmpty()) {
            writeError(resp, AccessControl.ACTION_PROGRESS, null, 400,
                    UploadErrorCodes.MISSING_IDENTIFIER, "identifier is required");
            return;
        }
        try {
            UploadProgress progress = uploadService.getProgress(identifier, token(req));
            writeJson(resp, 200, gson.toJson(progress));
        } catch (AccessDeniedException e) {
            writeError(resp, AccessControl.ACTION_PROGRESS, identifier, e.getStatusCode(),
                    UploadErrorCodes.ACCESS_DENIED, "Access denied");
        } catch (Exception e) {
            writeError(resp, AccessControl.ACTION_PROGRESS, identifier,
                    UploadErrorRenderers.statusOf(e), UploadErrorRenderers.codeOf(e), "Query progress failed");
        }
    }

    private void writeError(HttpServletResponse resp, String action, String identifier,
                            int status, String code, String message) throws IOException {
        Object body = errorRenderer.render(action, identifier, status, code, message);
        writeJson(resp, status, gson.toJson(body));
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
