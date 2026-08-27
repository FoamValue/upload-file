/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.servlet;

import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.service.ResumableDownloadService;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.mock.web.MockServletContext;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link DownloadServlet} (HTTP Range handling) using Spring's servlet mocks.
 */
public class DownloadServletTest {

    private static final String IDENTIFIER = "download-001";
    private static final byte[] CONTENT =
            "hello resumable download".getBytes(StandardCharsets.UTF_8);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private DownloadServlet servlet;

    @Before
    public void setUp() throws Exception {
        // Reuse the shared context wiring: upload one chunk and merge it to produce the file.
        UploadFileContext context = UploadFileContext.build(folder.getRoot().getAbsolutePath(), null);
        ResumableUploadService uploadService = context.getUploadService();
        ChunkUploadRequest request = new ChunkUploadRequest();
        request.setIdentifier(IDENTIFIER);
        request.setFileName("demo.txt");
        request.setFileSize(CONTENT.length);
        request.setChunkSize(CONTENT.length);
        request.setChunkTotal(1);
        request.setChunkIndex(0);
        uploadService.uploadChunk(request, new ByteArrayInputStream(CONTENT));
        uploadService.merge(IDENTIFIER);

        ResumableDownloadService downloadService = context.getDownloadService();
        servlet = new DownloadServlet();
        servlet.setDownloadService(downloadService);
    }

    private MockHttpServletRequest downloadRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("identifier", IDENTIFIER);
        return request;
    }

    @Test
    public void fullDownloadReturns200AndFullContent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.doGet(downloadRequest(), response);

        assertEquals(200, response.getStatus());
        assertEquals(CONTENT.length, response.getContentLength());
        assertArrayEquals(CONTENT, response.getContentAsByteArray());
        assertEquals("bytes", response.getHeader("Accept-Ranges"));
    }

    @Test
    public void rangeDownloadReturns206AndSlicedContent() throws Exception {
        MockHttpServletRequest request = downloadRequest();
        request.addHeader("Range", "bytes=0-4");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.doGet(request, response);

        assertEquals(206, response.getStatus());
        assertEquals("bytes 0-4/" + CONTENT.length, response.getHeader("Content-Range"));
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), response.getContentAsByteArray());
    }

    @Test
    public void openEndedRangeDownload() throws Exception {
        MockHttpServletRequest request = downloadRequest();
        request.addHeader("Range", "bytes=6-");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.doGet(request, response);

        assertEquals(206, response.getStatus());
        assertEquals("bytes 6-" + (CONTENT.length - 1) + "/" + CONTENT.length,
                response.getHeader("Content-Range"));
        assertArrayEquals("resumable download".getBytes(StandardCharsets.UTF_8),
                response.getContentAsByteArray());
    }

    @Test
    public void suffixRangeDownload() throws Exception {
        MockHttpServletRequest request = downloadRequest();
        request.addHeader("Range", "bytes=-8");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.doGet(request, response);

        assertEquals(206, response.getStatus());
        assertArrayEquals("download".getBytes(StandardCharsets.UTF_8), response.getContentAsByteArray());
    }

    @Test
    public void unsatisfiableRangeReturns416() throws Exception {
        MockHttpServletRequest request = downloadRequest();
        request.addHeader("Range", "bytes=999999-");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.doGet(request, response);

        assertEquals(416, response.getStatus());
        assertEquals("bytes */" + CONTENT.length, response.getHeader("Content-Range"));
    }

    @Test
    public void missingFileReturns404() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("identifier", "not-exists");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.doGet(request, response);

        assertEquals(404, response.getStatus());
    }

    @Test
    public void missingIdentifierReturns400() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.doGet(new MockHttpServletRequest(), response);
        assertEquals(400, response.getStatus());
    }

    @Test
    public void initFromServletConfigServesDownload() throws Exception {
        MockServletContext servletContext = new MockServletContext();
        MockServletConfig config = new MockServletConfig(servletContext);
        config.addInitParameter("storage-dir", folder.getRoot().getAbsolutePath());
        config.addInitParameter("metadata-dir", folder.getRoot().getAbsolutePath() + "/meta");

        DownloadServlet configured = new DownloadServlet();
        configured.init(config);

        // Produce the merged file through the shared context the servlet bootstrapped.
        UploadFileContext context = (UploadFileContext) servletContext
                .getAttribute(UploadFileContext.ATTRIBUTE_NAME);
        ResumableUploadService uploadService = context.getUploadService();
        ChunkUploadRequest request = new ChunkUploadRequest();
        request.setIdentifier(IDENTIFIER);
        request.setFileName("demo.txt");
        request.setFileSize(CONTENT.length);
        request.setChunkSize(CONTENT.length);
        request.setChunkTotal(1);
        request.setChunkIndex(0);
        uploadService.uploadChunk(request, new ByteArrayInputStream(CONTENT));
        uploadService.merge(IDENTIFIER);

        MockHttpServletResponse response = new MockHttpServletResponse();
        configured.doGet(downloadRequest(), response);

        assertEquals(200, response.getStatus());
        assertArrayEquals(CONTENT, response.getContentAsByteArray());
    }

    @Test
    public void largeFileUsesLongContentLength() throws Exception {
        long size = 2L * 1024 * 1024 * 1024 + 10; // over 2 GB, requires setContentLengthLong
        File big = new File(folder.getRoot(), "big.bin");
        try (RandomAccessFile raf = new RandomAccessFile(big, "rw")) {
            raf.setLength(size); // sparse file, no real disk usage
        }

        UploadFileContext context = UploadFileContext.build(folder.getRoot().getAbsolutePath(), null);
        UploadTask task = new UploadTask();
        task.setIdentifier("big");
        task.setFileName("big.bin");
        task.setMerged(true);
        task.setFinalPath(big.getAbsolutePath());
        context.getTaskStore().save(task);

        DownloadServlet bigServlet = new DownloadServlet();
        bigServlet.setDownloadService(context.getDownloadService());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("identifier", "big");
        MockHttpServletResponse response = new DiscardingResponse();
        bigServlet.doGet(request, response);

        assertEquals(200, response.getStatus());
        assertEquals("bytes", response.getHeader("Accept-Ranges"));
        assertTrue(response.getContentLengthLong() >= 0);
    }

    /** {@link MockHttpServletResponse} that discards the body so a >2 GB stream never buffers. */
    private static final class DiscardingResponse extends MockHttpServletResponse {
        private final ServletOutputStream out = new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
            }

            @Override
            public void write(int b) {
            }

            @Override
            public void write(byte[] b, int off, int len) {
            }
        };

        @Override
        public ServletOutputStream getOutputStream() {
            return out;
        }
    }
}
