/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.servlet;

import cn.chenxinjie.uploadfile.core.exception.QuotaExceededException;
import cn.chenxinjie.uploadfile.core.model.MergeStatus;
import cn.chenxinjie.uploadfile.core.model.UploadProgress;
import cn.chenxinjie.uploadfile.core.model.UploadResult;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import com.google.gson.Gson;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPart;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.mock.web.MockServletContext;

import jakarta.servlet.http.Part;
import java.io.File;
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

    @Test
    public void initFromServletConfigBuildsContext() throws Exception {
        MockServletContext servletContext = new MockServletContext();
        MockServletConfig config = new MockServletConfig(servletContext);
        config.addInitParameter("storage-dir", folder.getRoot().getAbsolutePath());
        config.addInitParameter("metadata-dir", folder.getRoot().getAbsolutePath() + "/meta");
        UploadServlet configured = new UploadServlet();
        configured.init(config);

        MockHttpServletRequest request = multipartRequest();
        request.addPart(new MockPart("file", "demo.bin", "hello".getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        configured.doPost(request, response);

        assertEquals(200, response.getStatus());
        UploadFileContext context = (UploadFileContext) servletContext
                .getAttribute(UploadFileContext.ATTRIBUTE_NAME);
        assertNotNull(context);
        assertTrue(context.getUploadService().isChunkUploaded(IDENTIFIER, 0));
    }

    @Test
    public void nonMultipartPostReturns400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(); // no multipart content type
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.doPost(request, response);
        assertEquals(400, response.getStatus());
    }

    @Test
    public void mergeStatusBlankIdentifierReturns400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("action", "mergeStatus");
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.doGet(request, response);
        assertEquals(400, response.getStatus());
    }

    @Test
    public void mergeStatusUnsafeIdentifierReturns400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("action", "mergeStatus");
        request.setParameter("identifier", "../evil");
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.doGet(request, response);
        assertEquals(400, response.getStatus());
    }

    @Test
    public void progressUnsafeIdentifierReturns400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("identifier", "../evil");
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.doGet(request, response);
        assertEquals(400, response.getStatus());
    }

    @Test
    public void emptyChunkTotalFallsBackToInvalidDefault() throws Exception {
        MockHttpServletRequest request = multipartRequest();
        request.setParameter("chunkTotal", "");
        request.addPart(new MockPart("file", "demo.bin", "hello".getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.doPost(request, response);
        assertEquals(400, response.getStatus());
    }

    @Test
    public void nonNumericChunkTotalReturns400() throws Exception {
        MockHttpServletRequest request = multipartRequest();
        request.setParameter("chunkTotal", "abc");
        request.addPart(new MockPart("file", "demo.bin", "hello".getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.doPost(request, response);
        assertEquals(400, response.getStatus());
    }

    @Test
    public void nonNumericSizesFallBackToDefaults() throws Exception {
        MockHttpServletRequest request = multipartRequest();
        request.setParameter("fileSize", "abc");
        request.setParameter("chunkSize", "");
        request.addPart(new MockPart("file", "demo.bin", "hello".getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.doPost(request, response);

        assertEquals(200, response.getStatus());
        UploadProgress progress = new Gson().fromJson(response.getContentAsString(), UploadProgress.class);
        assertEquals(1, progress.getUploadedCount());
    }

    @Test
    public void securityRejectsChunkWithoutToken() throws Exception {
        UploadServlet secured = securedServlet("srv-secret");

        MockHttpServletRequest request = multipartRequest("sec1");
        request.addPart(new MockPart("file", "demo.bin", "hello".getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        secured.doPost(request, response);

        assertEquals(401, response.getStatus());
        assertFalse(uploadServiceOf(secured).isChunkUploaded("sec1", 0));
    }

    @Test
    public void securityAcceptsChunkWithHeaderToken() throws Exception {
        UploadServlet secured = securedServlet("srv-secret");

        MockHttpServletRequest request = multipartRequest("sec2");
        request.addPart(new MockPart("file", "demo.bin", "hello".getBytes(StandardCharsets.UTF_8)));
        request.addHeader("X-Access-Token", "srv-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        secured.doPost(request, response);

        assertEquals(200, response.getStatus());
        assertTrue(uploadServiceOf(secured).isChunkUploaded("sec2", 0));
    }

    @Test
    public void securityAcceptsChunkWithQueryToken() throws Exception {
        UploadServlet secured = securedServlet("srv-secret");

        MockHttpServletRequest request = multipartRequest("sec3");
        request.setParameter("token", "srv-secret");
        request.addPart(new MockPart("file", "demo.bin", "hello".getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        secured.doPost(request, response);

        assertEquals(200, response.getStatus());
    }

    @Test
    public void securityRejectsProgressWithoutToken() throws Exception {
        UploadServlet secured = securedServlet("srv-secret");
        MockHttpServletRequest progress = new MockHttpServletRequest();
        progress.setParameter("identifier", "sec4");
        MockHttpServletResponse response = new MockHttpServletResponse();

        secured.doGet(progress, response);

        assertEquals(401, response.getStatus());
    }

    @Test
    public void quotaExceededReturns507() throws Exception {
        UploadFileContext.Config config = new UploadFileContext.Config();
        config.quotaMaxBytes = 4;
        UploadFileContext context = UploadFileContext.build(folder.getRoot().getAbsolutePath(), null, config);
        UploadServlet quotaServlet = new UploadServlet();
        quotaServlet.setUploadService(context.getUploadService());

        MockHttpServletRequest request = multipartRequest("quota1");
        request.setParameter("fileSize", "100");
        request.addPart(new MockPart("file", "demo.bin", "hello".getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        quotaServlet.doPost(request, response);

        assertEquals(507, response.getStatus());
        assertFalse(context.getUploadService().isChunkUploaded("quota1", 0));
    }

    @Test
    public void cancelUnknownIdentifierReturns404() throws Exception {
        MockHttpServletRequest cancelRequest = new MockHttpServletRequest();
        cancelRequest.setParameter("action", "cancel");
        cancelRequest.setParameter("identifier", "no-such-task");
        MockHttpServletResponse cancelResponse = new MockHttpServletResponse();

        servlet.doPost(cancelRequest, cancelResponse);

        assertEquals(404, cancelResponse.getStatus());
    }

    @Test
    public void cancelRemovesTaskChunksAndMergedFile() throws Exception {
        MockHttpServletRequest request = multipartRequest();
        request.addPart(new MockPart("file", "demo.bin", "hello".getBytes(StandardCharsets.UTF_8)));
        servlet.doPost(request, new MockHttpServletResponse());

        MockHttpServletRequest mergeRequest = new MockHttpServletRequest();
        mergeRequest.setParameter("action", "merge");
        mergeRequest.setParameter("identifier", IDENTIFIER);
        servlet.doPost(mergeRequest, new MockHttpServletResponse());

        java.io.File mergedFile = new java.io.File(folder.getRoot(), "files/" + IDENTIFIER);
        assertTrue(mergedFile.isDirectory());

        MockHttpServletRequest cancelRequest = new MockHttpServletRequest();
        cancelRequest.setParameter("action", "cancel");
        cancelRequest.setParameter("identifier", IDENTIFIER);
        MockHttpServletResponse cancelResponse = new MockHttpServletResponse();
        servlet.doPost(cancelRequest, cancelResponse);

        assertEquals(200, cancelResponse.getStatus());
        UploadResult result = new Gson().fromJson(cancelResponse.getContentAsString(), UploadResult.class);
        assertTrue(result.isSuccess());
        assertFalse(uploadService.getTaskStore().get(IDENTIFIER).isPresent());
        assertFalse(mergedFile.exists());
    }

    @Test
    public void mergeOnMissingTaskReturns404() throws Exception {
        MockHttpServletRequest mergeRequest = new MockHttpServletRequest();
        mergeRequest.setParameter("action", "merge");
        mergeRequest.setParameter("identifier", "no-such-task");
        MockHttpServletResponse mergeResponse = new MockHttpServletResponse();

        servlet.doPost(mergeRequest, mergeResponse);

        assertEquals(404, mergeResponse.getStatus());
    }

    @Test
    public void mergeWithoutAllChunksReturns409() throws Exception {
        MockHttpServletRequest request = multipartRequest();
        request.setParameter("chunkTotal", "4");
        request.setParameter("chunkIndex", "0");
        request.addPart(new MockPart("file", "demo.bin", "hello".getBytes(StandardCharsets.UTF_8)));
        servlet.doPost(request, new MockHttpServletResponse());

        MockHttpServletRequest mergeRequest = new MockHttpServletRequest();
        mergeRequest.setParameter("action", "merge");
        mergeRequest.setParameter("identifier", IDENTIFIER);
        MockHttpServletResponse mergeResponse = new MockHttpServletResponse();

        servlet.doPost(mergeRequest, mergeResponse);

        assertEquals(409, mergeResponse.getStatus());
    }

    @Test
    public void cancelWhileAsyncMergeRunningReturns409() throws Exception {
        MockHttpServletRequest request = multipartRequest();
        request.addPart(new MockPart("file", "demo.bin", "hello".getBytes(StandardCharsets.UTF_8)));
        servlet.doPost(request, new MockHttpServletResponse());

        cn.chenxinjie.uploadfile.core.model.UploadTask task =
                uploadService.getTaskStore().get(IDENTIFIER).get();
        task.setMergeState(cn.chenxinjie.uploadfile.core.model.UploadTask.MERGE_STATE_RUNNING);
        uploadService.getTaskStore().save(task);

        MockHttpServletRequest cancelRequest = new MockHttpServletRequest();
        cancelRequest.setParameter("action", "cancel");
        cancelRequest.setParameter("identifier", IDENTIFIER);
        MockHttpServletResponse cancelResponse = new MockHttpServletResponse();

        servlet.doPost(cancelRequest, cancelResponse);

        assertEquals(409, cancelResponse.getStatus());
        assertTrue(uploadService.getTaskStore().get(IDENTIFIER).isPresent());    }

    @Test
    public void cancelRejectsWithoutTokenWhenSecurityEnabled() throws Exception {
        UploadServlet secured = securedServlet("srv-secret");

        MockHttpServletRequest cancelRequest = new MockHttpServletRequest();
        cancelRequest.setParameter("action", "cancel");
        cancelRequest.setParameter("identifier", "sec-cancel");
        MockHttpServletResponse cancelResponse = new MockHttpServletResponse();

        secured.doPost(cancelRequest, cancelResponse);

        assertEquals(401, cancelResponse.getStatus());
    }

    @Test
    public void mergeRejectsWithoutTokenWhenSecurityEnabled() throws Exception {
        UploadServlet secured = securedServlet("srv-secret");
        MockHttpServletRequest mergeRequest = new MockHttpServletRequest();
        mergeRequest.setParameter("action", "merge");
        mergeRequest.setParameter("identifier", IDENTIFIER);
        MockHttpServletResponse response = new MockHttpServletResponse();

        secured.doPost(mergeRequest, response);

        assertEquals(401, response.getStatus());
    }

    @Test
    public void mergeAsyncRejectsWithoutTokenWhenSecurityEnabled() throws Exception {
        UploadServlet secured = securedServlet("srv-secret");
        MockHttpServletRequest submit = new MockHttpServletRequest();
        submit.setParameter("action", "mergeAsync");
        submit.setParameter("identifier", IDENTIFIER);
        MockHttpServletResponse response = new MockHttpServletResponse();

        secured.doPost(submit, response);

        assertEquals(401, response.getStatus());
    }

    @Test
    public void mergeStatusRejectsWithoutTokenWhenSecurityEnabled() throws Exception {
        UploadServlet secured = securedServlet("srv-secret");
        MockHttpServletRequest statusRequest = new MockHttpServletRequest();
        statusRequest.setParameter("action", "mergeStatus");
        statusRequest.setParameter("identifier", IDENTIFIER);
        MockHttpServletResponse response = new MockHttpServletResponse();

        secured.doGet(statusRequest, response);

        assertEquals(401, response.getStatus());
    }

    @Test
    public void mergeQuotaExceededReturns507() throws Exception {
        UploadServlet failing = new UploadServlet();
        failing.setUploadService(new ErroringUploadService(folder.getRoot().getAbsolutePath()));

        MockHttpServletRequest mergeRequest = new MockHttpServletRequest();
        mergeRequest.setParameter("action", "merge");
        mergeRequest.setParameter("identifier", IDENTIFIER);
        MockHttpServletResponse response = new MockHttpServletResponse();

        failing.doPost(mergeRequest, response);

        assertEquals(507, response.getStatus());
        UploadResult result = new Gson().fromJson(response.getContentAsString(), UploadResult.class);
        assertEquals("Storage quota exceeded", result.getMessage());
    }

    @Test
    public void cancelBlankIdentifierReturns400() throws Exception {
        MockHttpServletRequest cancelRequest = new MockHttpServletRequest();
        cancelRequest.setParameter("action", "cancel");
        cancelRequest.setParameter("identifier", "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.doPost(cancelRequest, response);

        assertEquals(400, response.getStatus());
    }

    @Test
    public void cancelServiceFailureReturnsGeneric400() throws Exception {
        UploadServlet failing = new UploadServlet();
        failing.setUploadService(new ErroringUploadService(folder.getRoot().getAbsolutePath()));

        MockHttpServletRequest cancelRequest = new MockHttpServletRequest();
        cancelRequest.setParameter("action", "cancel");
        cancelRequest.setParameter("identifier", IDENTIFIER);
        MockHttpServletResponse response = new MockHttpServletResponse();

        failing.doPost(cancelRequest, response);

        assertEquals(400, response.getStatus());
        UploadResult result = new Gson().fromJson(response.getContentAsString(), UploadResult.class);
        assertEquals("Cancel failed", result.getMessage());
    }

    @Test
    public void partReadFailureReturns400() throws Exception {
        MockHttpServletRequest request = new ThrowingPartRequest();
        request.setParameter("identifier", IDENTIFIER);
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.doPost(request, response);

        assertEquals(400, response.getStatus());
    }

    @Test
    public void accessTokenHeaderIsCustomizable() throws Exception {
        UploadServlet secured = securedServlet("srv-secret");
        secured.setAccessTokenHeader("X-Custom-Token");

        MockHttpServletRequest request = multipartRequest("sec5");
        request.addPart(new MockPart("file", "demo.bin", "hello".getBytes(StandardCharsets.UTF_8)));
        request.addHeader("X-Custom-Token", "srv-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        secured.doPost(request, response);

        assertEquals(200, response.getStatus());
        assertTrue(secured.getUploadService().isChunkUploaded("sec5", 0));
    }

    @Test
    public void blankAccessTokenHeaderKeepsDefault() throws Exception {
        UploadServlet secured = securedServlet("srv-secret");
        secured.setAccessTokenHeader("   ");

        MockHttpServletRequest request = multipartRequest("sec6");
        request.addPart(new MockPart("file", "demo.bin", "hello".getBytes(StandardCharsets.UTF_8)));
        request.addHeader("X-Access-Token", "srv-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        secured.doPost(request, response);

        assertEquals(200, response.getStatus());
    }

    /**
     * Service stub that fails deterministically on merge/cancel, so the servlet's 507 and generic
     * 400 catch blocks can be exercised without building real storage state.
     */
    private static final class ErroringUploadService extends ResumableUploadService {
        ErroringUploadService(String root) {
            super(new MemoryTaskStore(), new LocalFileChunkStorage(root + "/chunks"), new File(root, "files"));
        }

        @Override
        public UploadResult merge(String identifier, String token) {
            throw new QuotaExceededException("capacity exceeded");
        }

        @Override
        public boolean cancelUpload(String identifier, String token) {
            throw new IllegalStateException("simulated failure");
        }
    }

    /** Request whose {@code getPart} blows up, covering the servlet's multipart-read failure path. */
    private static final class ThrowingPartRequest extends MockHttpServletRequest {
        @Override
        public Part getPart(String name) {
            throw new IllegalStateException("multipart failure");
        }
    }

    private UploadServlet securedServlet(String token) {
        UploadFileContext.Config config = new UploadFileContext.Config();
        config.securityEnabled = true;
        config.securityToken = token;
        UploadFileContext context = UploadFileContext.build(folder.getRoot().getAbsolutePath(), null, config);
        UploadServlet servlet = new UploadServlet();
        servlet.setUploadService(context.getUploadService());
        return servlet;
    }

    private ResumableUploadService uploadServiceOf(UploadServlet servlet) {
        return servlet.getUploadService();
    }

    private MockHttpServletRequest multipartRequest(String identifier) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("multipart/form-data");
        request.setParameter("identifier", identifier);
        request.setParameter("fileName", "demo.bin");
        request.setParameter("fileSize", "5");
        request.setParameter("chunkSize", "5");
        request.setParameter("chunkTotal", "1");
        request.setParameter("chunkIndex", "0");
        return request;
    }
}
