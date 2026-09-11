/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.error;

import cn.chenxinjie.uploadfile.core.exception.AccessDeniedException;
import cn.chenxinjie.uploadfile.core.exception.ChecksumMismatchException;
import cn.chenxinjie.uploadfile.core.exception.QuotaExceededException;
import cn.chenxinjie.uploadfile.core.exception.UploadErrorCode;
import cn.chenxinjie.uploadfile.core.exception.UploadErrorCodes;
import cn.chenxinjie.uploadfile.core.exception.UploadMergeConflictException;
import cn.chenxinjie.uploadfile.core.exception.UploadTaskNotFoundException;
import cn.chenxinjie.uploadfile.core.exception.UploadValidationException;
import cn.chenxinjie.uploadfile.core.model.MergeStatus;
import cn.chenxinjie.uploadfile.core.model.UploadHttpError;
import cn.chenxinjie.uploadfile.core.model.UploadProgress;
import cn.chenxinjie.uploadfile.core.model.UploadResult;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * rc.6: symbolic error codes and the legacy/standard failure-body renderers.
 */
public class UploadErrorRenderersRc6Test {

    @Test
    public void typedExceptionsCarryStableCatalogCodes() {
        assertEquals(UploadErrorCodes.UPLOAD_VALIDATION, new UploadValidationException("x").code());
        assertEquals(UploadErrorCodes.UPLOAD_CHECKSUM, new ChecksumMismatchException("x").code());
        assertEquals(UploadErrorCodes.UPLOAD_NOT_FOUND, new UploadTaskNotFoundException("x").code());
        assertEquals(UploadErrorCodes.UPLOAD_MERGE_CONFLICT, new UploadMergeConflictException("x").code());
        assertEquals(UploadErrorCodes.ACCESS_DENIED, new AccessDeniedException("x").code());
        assertEquals(UploadErrorCodes.QUOTA_EXCEEDED, new QuotaExceededException("x").code());
        // codeOf(Throwable) resolves typed failures through the catalog, everything else is a server fault.
        assertEquals(UploadErrorCodes.UPLOAD_VALIDATION, UploadErrorRenderers.codeOf(new UploadValidationException("x")));
        assertEquals(UploadErrorCodes.QUOTA_EXCEEDED, UploadErrorRenderers.codeOf(new QuotaExceededException("x")));
        assertEquals(UploadErrorCodes.UPLOAD_SERVER_ERROR, UploadErrorRenderers.codeOf(new IllegalStateException("boom")));
    }

    @Test
    public void unknownErrorCodeImplementorFallsBackToServerError() {
        UploadErrorCode custom = new UploadErrorCode() {
            @Override
            public int getHttpStatusCode() {
                return 418;
            }
        };
        assertEquals(UploadErrorCodes.UPLOAD_SERVER_ERROR, custom.code());
    }

    @Test
    public void statusClassification() {
        assertEquals(400, UploadErrorRenderers.statusOf(new UploadValidationException("x")));
        assertEquals(404, UploadErrorRenderers.statusOf(new UploadTaskNotFoundException("x")));
        assertEquals(403, UploadErrorRenderers.statusOf(new AccessDeniedException(403, "no")));
        assertEquals(401, UploadErrorRenderers.statusOf(new AccessDeniedException("no")));
        // raw IllegalArgumentException = client error; everything else = server fault (rc.6)
        assertEquals(400, UploadErrorRenderers.statusOf(new IllegalArgumentException("blank")));
        assertEquals(500, UploadErrorRenderers.statusOf(new IllegalStateException("boom")));
    }

    @Test
    public void legacyRendererPreservesPerEndpointShapes() {
        UploadErrorRenderer legacy = UploadErrorRenderers.legacy();
        assertTrue(legacy.render("upload", "id1", 401, UploadErrorCodes.ACCESS_DENIED, "denied")
                instanceof UploadProgress);
        assertTrue(legacy.render("merge", "id1", 409, UploadErrorCodes.UPLOAD_MERGE_CONFLICT, "Merge failed")
                instanceof UploadResult);
        assertTrue(legacy.render("cancel", "id1", 404, UploadErrorCodes.UPLOAD_NOT_FOUND, "nope")
                instanceof UploadResult);
        // rc.6: the download endpoint shares the legacy UploadResult error body.
        assertTrue(legacy.render("download", "id1", 416, UploadErrorCodes.RANGE_NOT_SATISFIABLE, "range")
                instanceof UploadResult);
        assertTrue(legacy.render("mergeStatus", "id1", 400, UploadErrorCodes.UPLOAD_NOT_FOUND, "nope")
                instanceof MergeStatus);
        assertTrue(legacy.render("mergeAsync", "id1", 409, UploadErrorCodes.UPLOAD_MERGE_CONFLICT, "busy")
                instanceof MergeStatus);
    }

    @Test
    public void standardRendererBuildsUploadHttpError() {
        UploadErrorRenderer standard = UploadErrorRenderers.standard();
        Object body = standard.render("merge", "id1", 507, UploadErrorCodes.QUOTA_EXCEEDED, "no space");
        assertTrue(body instanceof UploadHttpError);
        UploadHttpError error = (UploadHttpError) body;
        assertEquals(UploadErrorCodes.QUOTA_EXCEEDED, error.getCode());
        assertEquals(507, error.getStatus());
        assertEquals("no space", error.getMessage());
        assertEquals("id1", error.getIdentifier());
        assertEquals("merge", error.getAction());
    }

    @Test
    public void selectionHonorsStandardMode() {
        assertNotNull(UploadErrorRenderers.from("standard"));
        assertTrue(UploadErrorRenderers.from("legacy").render("progress", "x", 400, "C", "m")
                instanceof UploadProgress);
        assertTrue(UploadErrorRenderers.from(null).render("progress", "x", 400, "C", "m")
                instanceof UploadProgress);
        assertTrue(UploadErrorRenderers.from("standard").render("progress", "x", 400, "C", "m")
                instanceof UploadHttpError);
    }
}
