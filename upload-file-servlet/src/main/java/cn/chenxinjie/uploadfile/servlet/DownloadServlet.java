package cn.chenxinjie.uploadfile.servlet;

import cn.chenxinjie.uploadfile.core.model.DownloadRange;
import cn.chenxinjie.uploadfile.core.service.ResumableDownloadService;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.Optional;

/**
 * 断点续传下载 Servlet，支持 HTTP Range 请求。
 *
 * <p>接口约定：</p>
 * <ul>
 *   <li>{@code GET /download?identifier=xxx}：下载完整文件（200）</li>
 *   <li>{@code GET /download?identifier=xxx}，携带 {@code Range: bytes=start-end}：区间下载（206）</li>
 *   <li>不可满足的 Range 返回 416，文件不存在返回 404</li>
 * </ul>
 */
@WebServlet(name = "downloadFileServlet", urlPatterns = "/download", loadOnStartup = 1)
public class DownloadServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final int BUFFER_SIZE = 8192;

    private ResumableDownloadService downloadService;

    public void setDownloadService(ResumableDownloadService downloadService) {
        this.downloadService = downloadService;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        if (downloadService == null) {
            UploadFileContext context = UploadFileContext.getOrCreate(config.getServletContext(), config);
            downloadService = context.getDownloadService();
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String identifier = req.getParameter("identifier");
        if (identifier == null || identifier.trim().isEmpty()) {
            resp.sendError(400, "缺少 identifier");
            return;
        }
        Optional<File> fileOpt = downloadService.resolveFile(identifier);
        if (!fileOpt.isPresent()) {
            resp.sendError(404, "文件不存在: " + identifier);
            return;
        }
        File file = fileOpt.get();
        String fileName = downloadService.resolveFileName(identifier);

        resp.setHeader("Accept-Ranges", "bytes");
        setDisposition(resp, fileName);
        resp.setContentType("application/octet-stream");

        long total = file.length();
        String rangeHeader = req.getHeader("Range");
        if (rangeHeader == null) {
            resp.setStatus(HttpServletResponse.SC_OK);
            setContentLength(resp, total);
            copyFull(file, resp.getOutputStream());
            return;
        }

        Optional<DownloadRange> rangeOpt = DownloadRange.parse(rangeHeader, total);
        if (!rangeOpt.isPresent()) {
            resp.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            resp.setHeader("Content-Range", "bytes */" + total);
            return;
        }
        DownloadRange range = rangeOpt.get();
        resp.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
        resp.setHeader("Content-Range",
                "bytes " + range.getStart() + "-" + range.getEnd() + "/" + total);
        setContentLength(resp, range.getContentLength());
        downloadService.writeRange(file, range.getStart(), range.getContentLength(), resp.getOutputStream());
    }

    /**
     * 小于 2GB 时使用 Servlet 3.0 的 {@code setContentLength}；更大文件回退到 Servlet 3.1 的
     * {@code setContentLengthLong}，因此超过 2GB 的文件需要 Servlet 3.1+ 容器。
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
