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
 * 分片上传 Servlet。
 *
 * <p>接口约定（{@code @MultipartConfig}，文件字段名固定为 {@code file}）：</p>
 * <ul>
 *   <li>{@code POST /upload}：上传一个分片（multipart，携带 identifier/fileName/fileSize/chunkSize/chunkTotal/chunkIndex/chunkMd5 与分片文件），返回 {@link UploadProgress} JSON</li>
 *   <li>{@code POST /upload?action=merge&identifier=xxx}：合并分片，返回 {@link UploadResult} JSON</li>
 *   <li>{@code GET /upload?action=progress&identifier=xxx}：查询进度，返回 {@link UploadProgress} JSON</li>
 * </ul>
 *
 * <p>可通过 setter 注入服务，也可在 web.xml / @WebInitParam 中通过 {@code storage-dir}、{@code metadata-dir}
 * 初始化参数使用默认实现（与 {@link DownloadServlet} 共享同一上下文）。</p>
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
        if ("merge".equals(req.getParameter("action"))) {
            doMerge(req, resp);
        } else {
            doChunkUpload(req, resp);
        }
    }

    private void doChunkUpload(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        ChunkUploadRequest chunkRequest = new ChunkUploadRequest();
        chunkRequest.setIdentifier(param(req, "identifier"));
        chunkRequest.setFileName(param(req, "fileName"));
        chunkRequest.setFileSize(longParam(req, "fileSize", 0));
        chunkRequest.setChunkSize(longParam(req, "chunkSize", 0));
        chunkRequest.setChunkTotal(intParam(req, "chunkTotal", -1));
        chunkRequest.setChunkIndex(intParam(req, "chunkIndex", -1));
        chunkRequest.setChunkMd5(param(req, "chunkMd5"));

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
            writeJson(resp, 400, gson.toJson(UploadResult.error(identifier, "合并失败: " + e.getMessage())));
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
