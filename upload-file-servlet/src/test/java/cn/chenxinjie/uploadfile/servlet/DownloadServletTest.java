/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.servlet;

import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.service.ResumableDownloadService;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

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
}
