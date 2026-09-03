/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.service;

import cn.chenxinjie.uploadfile.core.exception.UploadErrorCode;
import cn.chenxinjie.uploadfile.core.exception.UploadMergeConflictException;
import cn.chenxinjie.uploadfile.core.exception.UploadTaskNotFoundException;
import cn.chenxinjie.uploadfile.core.exception.UploadValidationException;
import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.model.UploadResult;
import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import cn.chenxinjie.uploadfile.core.store.FileTaskStore;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the rc.4 read/cancel contract ({@link ResumableUploadService#getTask} and
 * {@link ResumableUploadService#cancelUpload}) and the stable HTTP-status semantics of the
 * typed core exceptions.
 */
public class ResumableUploadCancelTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ResumableUploadService service;
    private File mergedDir;

    @Before
    public void setUp() throws IOException {
        mergedDir = new File(folder.getRoot(), "files");
        service = new ResumableUploadService(
                new FileTaskStore(new File(folder.getRoot(), "meta").toPath()),
                new LocalFileChunkStorage(new File(folder.getRoot(), "chunks").toPath()),
                mergedDir);
    }

    private ChunkUploadRequest singleChunkRequest(String id) {
        ChunkUploadRequest req = new ChunkUploadRequest();
        req.setIdentifier(id);
        req.setFileName("demo.bin");
        req.setFileSize("hello".length());
        req.setChunkSize("hello".length());
        req.setChunkTotal(1);
        req.setChunkIndex(0);
        return req;
    }

    private void uploadSingleChunk(String id) throws IOException {
        service.uploadChunk(singleChunkRequest(id),
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void getTaskAfterMergeExposesFinalPath() throws Exception {
        uploadSingleChunk("g1");
        UploadResult result = service.merge("g1");

        Optional<UploadTask> task = service.getTask("g1");
        assertTrue(task.isPresent());
        assertEquals(result.getFinalPath(), task.get().getFinalPath());
        assertTrue(task.get().isMerged());
        assertEquals("hello".length(), task.get().getFinalFileSize());
        assertTrue(new File(task.get().getFinalPath()).isFile());
    }

    @Test
    public void getTaskUnknownIdentifierReturnsEmpty() {
        assertTrue(!service.getTask("no-such-task").isPresent());
    }

    @Test
    public void cancelAbandonedUploadRemovesTaskAndChunks() throws Exception {
        uploadSingleChunk("c1");
        assertTrue(service.getTask("c1").isPresent());

        boolean removed = service.cancelUpload("c1");

        assertTrue(removed);
        assertFalse(service.getTask("c1").isPresent());
        assertFalse(service.isChunkUploaded("c1", 0));
        File chunkDir = new File(folder.getRoot(), "chunks/c1");
        assertFalse(chunkDir.exists());
    }

    @Test
    public void cancelUnknownIdentifierReturnsFalse() {
        assertFalse(service.cancelUpload("no-such-task"));
    }

    @Test
    public void cancelAfterMergeRemovesArtifactAndAllowsReuse() throws Exception {
        uploadSingleChunk("c2");
        UploadResult merged = service.merge("c2");
        assertTrue(new File(merged.getFinalPath()).isFile());

        assertTrue(service.cancelUpload("c2"));

        assertFalse(service.getTask("c2").isPresent());
        assertFalse(new File(folder.getRoot(), "files/c2").exists());

        // The identifier can be reused for a brand-new upload after cancellation.
        uploadSingleChunk("c2");
        assertTrue(service.getTask("c2").isPresent());
        assertFalse(service.getTask("c2").get().isMerged());
    }

    @Test
    public void cancelRejectsTaskWithRunningAsyncMerge() throws Exception {
        uploadSingleChunk("c3");
        UploadTask task = service.getTask("c3").get();
        task.setMergeState(UploadTask.MERGE_STATE_RUNNING);
        service.getTaskStore().save(task);

        UploadMergeConflictException ex =
                assertThrows(UploadMergeConflictException.class, () -> service.cancelUpload("c3"));
        assertEquals(409, ex.getHttpStatusCode());
        assertTrue(service.getTask("c3").isPresent());
    }

    @Test
    public void mergeUnknownIdentifierMapsTo404() {
        UploadTaskNotFoundException ex =
                assertThrows(UploadTaskNotFoundException.class, () -> service.merge("no-such-task"));
        assertEquals(404, ex.getHttpStatusCode());
        // Still a NoSuchElementException for callers that catch the broad type.
        assertTrue(ex instanceof NoSuchElementException);
    }

    @Test
    public void invalidChunkParametersMapTo400() throws Exception {
        ChunkUploadRequest req = singleChunkRequest("v1");
        req.setChunkTotal(0);
        UploadValidationException ex = assertThrows(UploadValidationException.class,
                () -> service.uploadChunk(req, new ByteArrayInputStream("x".getBytes())));
        assertEquals(400, ex.getHttpStatusCode());
    }

    @Test
    public void inconsistentMetadataMapsTo400() throws Exception {
        uploadSingleChunk("v2");

        ChunkUploadRequest inconsistent = singleChunkRequest("v2");
        inconsistent.setChunkIndex(1);
        inconsistent.setChunkTotal(2);
        UploadValidationException ex = assertThrows(UploadValidationException.class,
                () -> service.uploadChunk(inconsistent, new ByteArrayInputStream("x".getBytes())));
        assertEquals(400, ex.getHttpStatusCode());
    }

    @Test
    public void mergeWithMissingChunksMapsTo409() throws Exception {
        ChunkUploadRequest req = singleChunkRequest("v3");
        req.setChunkTotal(2);
        req.setFileSize("hello".length() * 2);
        req.setChunkSize("hello".length());
        service.uploadChunk(req, new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        UploadMergeConflictException ex =
                assertThrows(UploadMergeConflictException.class, () -> service.merge("v3"));
        assertEquals(409, ex.getHttpStatusCode());
    }

    @Test
    public void typedExceptionsReportStatusCodes() {
        assertEquals(400, new cn.chenxinjie.uploadfile.core.exception.ChecksumMismatchException("x").getHttpStatusCode());
        assertEquals(401, new cn.chenxinjie.uploadfile.core.exception.AccessDeniedException("x").getHttpStatusCode());
        assertEquals(507, new cn.chenxinjie.uploadfile.core.exception.QuotaExceededException("x").getHttpStatusCode());
        assertEquals(400, new UploadValidationException("x").getHttpStatusCode());
        assertEquals(404, new UploadTaskNotFoundException("x").getHttpStatusCode());
        assertEquals(409, new UploadMergeConflictException("x").getHttpStatusCode());
    }

    @Test
    public void classifierPatternUsedByHttpBoundariesMapsStableStatuses() throws Exception {
        // The documented integration pattern: catch the boundary failure and let the exception's
        // UploadErrorCode drive the HTTP status; everything else is a server-side 500.
        assertEquals(404, httpStatus(() -> service.merge("no-such-task")));

        ChunkUploadRequest bad = singleChunkRequest("v4");
        bad.setChunkTotal(-1);
        assertEquals(400, httpStatus(() -> service.uploadChunk(bad, new ByteArrayInputStream("x".getBytes()))));
    }

    @Test
    public void cancelRespectsAccessControlToken() throws Exception {
        cn.chenxinjie.uploadfile.core.security.TokenAccessControl accessControl =
                new cn.chenxinjie.uploadfile.core.security.TokenAccessControl("srv-secret");
        ResumableUploadService secured = new ResumableUploadService(
                new FileTaskStore(new File(folder.getRoot(), "meta-sec").toPath()),
                new LocalFileChunkStorage(new File(folder.getRoot(), "chunks-sec").toPath()),
                new File(folder.getRoot(), "files-sec"),
                true, true, true, new cn.chenxinjie.uploadfile.core.util.IdentifierLock(), accessControl);

        cn.chenxinjie.uploadfile.core.exception.AccessDeniedException ex =
                assertThrows(cn.chenxinjie.uploadfile.core.exception.AccessDeniedException.class,
                        () -> secured.cancelUpload("sec-id"));
        assertEquals(401, ex.getHttpStatusCode());
    }

    private interface FailingAction {
        void run() throws Exception;
    }

    private static int httpStatus(FailingAction action) {
        try {
            action.run();
            throw new AssertionError("expected the action to fail");
        } catch (Exception e) {
            if (e instanceof UploadErrorCode) {
                return ((UploadErrorCode) e).getHttpStatusCode();
            }
            return 500;
        }
    }
}
