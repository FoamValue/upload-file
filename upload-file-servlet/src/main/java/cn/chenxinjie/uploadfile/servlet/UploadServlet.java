/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.servlet;

import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
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
 *   <li>{@code GET /upload?action=progress&identifier=xxx}: query progress; returns {@link UploadProgress} JSON</li>
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

    private final Gson gson = new Gson();
    private ResumableUploadService uploadService;

    public void setUploadService(ResumableUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        if (uploadService == null) {
            UploadFileContext context = UploadFileContext.getOrCreate(config.getServletContext(), config);
            uploadService = context.getUploadService();
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doProgress(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // action=merge triggers the merge endpoint; anything else is treated as a chunk upload.
        if ("merge".equals(req.getParameter("action"))) {
            doMerge(req, resp);
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
            UploadProgress progress = uploadService.uploadChunk(chunkRequest, in);
            writeJson(resp, 200, gson.toJson(progress));
        } catch (Exception e) {
            writeJson(resp, 400, gson.toJson(UploadProgress.empty(chunkRequest.getIdentifier())));
        }
    }

    private void doMerge(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String identifier = param(req, "identifier");
        try {
            UploadResult result = uploadService.merge(identifier);
            writeJson(resp, result.isSuccess() ? 200 : 400, gson.toJson(result));
        } catch (Exception e) {
            writeJson(resp, 400, gson.toJson(UploadResult.error(identifier, "Merge failed: " + e.getMessage())));
        }
    }

    private void doProgress(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String identifier = param(req, "identifier");
        if (identifier == null || identifier.trim().isEmpty()) {
            writeJson(resp, 400, gson.toJson(UploadProgress.empty(null)));
            return;
        }
        try {
            UploadProgress progress = uploadService.getProgress(identifier);
            writeJson(resp, 200, gson.toJson(progress));
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
