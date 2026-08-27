/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.servlet;

import cn.chenxinjie.uploadfile.core.model.MergeStatus;
import cn.chenxinjie.uploadfile.core.model.UploadProgress;
import cn.chenxinjie.uploadfile.core.model.UploadResult;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import com.google.gson.Gson;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link UploadServlet} using Spring's servlet mocks.
 */
public class UploadServletTest {

    private static final String IDENTIFIER = "servlet-upload-001";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ResumableUploadService uploadService;
    private UploadServlet servlet;

    @Before
    public void setUp() {
        UploadFileContext context = UploadFileContext.build(folder.getRoot().getAbsolutePath(), null);
        uploadService = context.getUploadService();
        servlet = new UploadServlet();
        servlet.setUploadService(uploadService);
    }

    private MockHttpServletRequest multipartRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("multipart/form-data");
        request.setParameter("identifier", IDENTIFIER);
        request.setParameter("fileName", "demo.bin");
        request.setParameter("fileSize", "5");
        request.setParameter("chunkSize", "5");
        request.setParameter("chunkTotal", "1");
        request.setParameter("chunkIndex", "0");
        return request;
    }

    @Test
    public void uploadChunkReturnsProgress() throws Exception {
        MockHttpServletRequest request = multipartRequest();
        request.addPart(new MockPart("file", "demo.bin", "hello".getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.doPost(request, response);

        assertEquals(200, response.getStatus());
        UploadProgress progress = new Gson().fromJson(response.getContentAsString(), UploadProgress.class);
        assertEquals(1, progress.getUploadedCount());
        assertEquals(100, progress.getProgressPercent());
        assertTrue(uploadService.isChunkUploaded(IDENTIFIER, 0));
    }

    @Test
    public void mergeAfterUploadSucceeds() throws Exception {
        MockHttpServletRequest request = multipartRequest();
        request.addPart(new MockPart("file", "demo.bin", "hello".getBytes(StandardCharsets.UTF_8)));
        servlet.doPost(request, new MockHttpServletResponse());

        MockHttpServletRequest mergeRequest = new MockHttpServletRequest();
        mergeRequest.setParameter("action", "merge");
        mergeRequest.setParameter("identifier", IDENTIFIER);
        MockHttpServletResponse mergeResponse = new MockHttpServletResponse();

        servlet.doPost(mergeRequest, mergeResponse);

        assertEquals(200, mergeResponse.getStatus());
        UploadResult result = new Gson().fromJson(mergeResponse.getContentAsString(), UploadResult.class);
        assertTrue(result.isSuccess());
        assertTrue(result.isMerged());
    }

    @Test
    public void progressEndpointReportsUploadedChunks() throws Exception {
        MockHttpServletRequest request = multipartRequest();
        request.addPart(new MockPart("file", "demo.bin", "hello".getBytes(StandardCharsets.UTF_8)));
        servlet.doPost(request, new MockHttpServletResponse());

        MockHttpServletRequest progressRequest = new MockHttpServletRequest();
        progressRequest.setParameter("identifier", IDENTIFIER);
        MockHttpServletResponse progressResponse = new MockHttpServletResponse();

        servlet.doGet(progressRequest, progressResponse);

        assertEquals(200, progressResponse.getStatus());
        UploadProgress progress = new Gson().fromJson(progressResponse.getContentAsString(), UploadProgress.class);
        assertEquals(1, progress.getUploadedCount());
    }

    @Test
    public void missingPartReturns400() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.doPost(multipartRequest(), response);
        assertEquals(400, response.getStatus());
    }

    @Test
    public void missingIdentifierOnProgressReturns400() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.doGet(new MockHttpServletRequest(), response);
        assertEquals(400, response.getStatus());
    }

    @Test
    public void checksumMismatchRejectsChunk() throws Exception {
        MockHttpServletRequest request = multipartRequest();
        request.setParameter("chunkMd5", "00000000000000000000000000000000");
        request.addPart(new MockPart("file", "demo.bin", "hello".getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.doPost(request, response);

        assertEquals(400, response.getStatus());
        assertFalse(uploadService.isChunkUploaded(IDENTIFIER, 0));
    }

    @Test
    public void mergeAsyncWithoutAsyncMergeReturns400() throws Exception {
        MockHttpServletRequest request = multipartRequest();
        request.addPart(new MockPart("file", "demo.bin", "hello".getBytes(StandardCharsets.UTF_8)));
        servlet.doPost(request, new MockHttpServletResponse());

        MockHttpServletRequest mergeRequest = new MockHttpServletRequest();
        mergeRequest.setParameter("action", "mergeAsync");
        mergeRequest.setParameter("identifier", IDENTIFIER);
        MockHttpServletResponse mergeResponse = new MockHttpServletResponse();

        servlet.doPost(mergeRequest, mergeResponse);

        assertEquals(400, mergeResponse.getStatus());
    }

    @Test
    public void mergeAsyncReachesSucceeded() throws Exception {
        UploadFileContext.Config config = new UploadFileContext.Config();
        config.asyncMergeEnabled = true;
        UploadFileContext context = UploadFileContext.build(folder.getRoot().getAbsolutePath(), null, config);
        UploadServlet asyncServlet = new UploadServlet();
        asyncServlet.setUploadService(context.getUploadService());

        MockHttpServletRequest request = multipartRequest();
        request.addPart(new MockPart("file", "demo.bin", "hello".getBytes(StandardCharsets.UTF_8)));
        asyncServlet.doPost(request, new MockHttpServletResponse());

        MockHttpServletRequest submit = new MockHttpServletRequest();
        submit.setParameter("action", "mergeAsync");
        submit.setParameter("identifier", IDENTIFIER);
        MockHttpServletResponse submitResponse = new MockHttpServletResponse();
        asyncServlet.doPost(submit, submitResponse);
        assertEquals(202, submitResponse.getStatus());
        MergeStatus submitted = new Gson().fromJson(submitResponse.getContentAsString(), MergeStatus.class);
        assertEquals("PENDING", submitted.getState());

        MergeStatus terminal = null;
        for (int i = 0; i < 200 && terminal == null; i++) {
            MockHttpServletRequest statusRequest = new MockHttpServletRequest();
            statusRequest.setParameter("action", "mergeStatus");
            statusRequest.setParameter("identifier", IDENTIFIER);
            MockHttpServletResponse statusResponse = new MockHttpServletResponse();
            asyncServlet.doGet(statusRequest, statusResponse);
            assertEquals(200, statusResponse.getStatus());
            MergeStatus s = new Gson().fromJson(statusResponse.getContentAsString(), MergeStatus.class);
            if ("SUCCEEDED".equals(s.getState()) || "FAILED".equals(s.getState())) {
                terminal = s;
            } else {
                Thread.sleep(20);
            }
        }
        assertNotNull(terminal);
        assertEquals("SUCCEEDED", terminal.getState());
        assertTrue(terminal.isMerged());
    }

    @Test
    public void mergeStatusForUnknownIdentifierReturnsNone() throws Exception {
        MockHttpServletRequest statusRequest = new MockHttpServletRequest();
        statusRequest.setParameter("action", "mergeStatus");
        statusRequest.setParameter("identifier", "nope");
        MockHttpServletResponse statusResponse = new MockHttpServletResponse();

        servlet.doGet(statusRequest, statusResponse);

        assertEquals(200, statusResponse.getStatus());
        MergeStatus status = new Gson().fromJson(statusResponse.getContentAsString(), MergeStatus.class);
        assertEquals("NONE", status.getState());
    }

    @Test
    public void mergeFailureDoesNotLeakInternalMessage() throws Exception {
        // Upload a chunk, then corrupt the metadata so the merge fails.
        MockHttpServletRequest request = multipartRequest();
        request.addPart(new MockPart("file", "demo.bin", "hello".getBytes(StandardCharsets.UTF_8)));
        servlet.doPost(request, new MockHttpServletResponse());

        cn.chenxinjie.uploadfile.core.model.UploadTask task =
                uploadService.getTaskStore().get(IDENTIFIER).get();
        task.setFileSize(9999);
        uploadService.getTaskStore().save(task);

        MockHttpServletRequest mergeRequest = new MockHttpServletRequest();
        mergeRequest.setParameter("action", "merge");
        mergeRequest.setParameter("identifier", IDENTIFIER);
        MockHttpServletResponse mergeResponse = new MockHttpServletResponse();

        servlet.doPost(mergeRequest, mergeResponse);

        assertEquals(400, mergeResponse.getStatus());
        UploadResult result = new Gson().fromJson(mergeResponse.getContentAsString(), UploadResult.class);
        assertFalse(result.isSuccess());
        assertEquals("Merge failed", result.getMessage());
        // Internal details (e.g. the absolute final path) must not leak to the client.
        assertFalse(result.getMessage().contains(folder.getRoot().getAbsolutePath()));
    }
}
