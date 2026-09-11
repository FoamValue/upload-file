/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.security;

import cn.chenxinjie.uploadfile.core.exception.AccessDeniedException;
import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.util.IdentifierLock;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * rc.6: {@link AccessDecision}, the {@code check()↔decide()} bridge, and access-decision listeners.
 */
public class AccessControlRc6Test {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void accessDecisionAllowAndDeny() {
        assertTrue(AccessDecision.allow().allowed());
        AccessDecision denied = AccessDecision.deny(403, "forbidden");
        assertFalse(denied.allowed());
        assertEquals(403, denied.statusCode());
        assertEquals("forbidden", denied.reason());
        assertThrows(IllegalArgumentException.class, () -> AccessDecision.deny(200, "not an error"));
        assertThrows(IllegalArgumentException.class, () -> AccessDecision.deny(600, "not an error"));
    }

    @Test
    public void legacyCheckImplementationIsBridgedToDecide() {
        AccessControl legacy = new AccessControl() {
            @Override
            @Deprecated
            public void check(String identifier, String action, String token) {
                if ("blocked".equals(identifier)) {
                    throw new AccessDeniedException("blocked by legacy policy");
                }
            }
        };
        AccessDecision allowed = legacy.decide("fine", AccessControl.ACTION_UPLOAD, null);
        assertTrue(allowed.allowed());
        AccessDecision denied = legacy.decide("blocked", AccessControl.ACTION_UPLOAD, null);
        assertFalse(denied.allowed());
        assertEquals(401, denied.statusCode());
    }

    @Test
    public void decideImplementationDrivesDeprecatedCheck() {
        AccessControl rich = new AccessControl() {
            @Override
            public AccessDecision decide(String identifier, String action, String token) {
                return "forbidden".equals(identifier)
                        ? AccessDecision.deny(403, "owner mismatch")
                        : AccessDecision.allow();
            }

            @Override
            @Deprecated
            public void check(String identifier, String action, String token) {
                AccessDecision d = decide(identifier, action, token);
                if (!d.allowed()) {
                    throw new AccessDeniedException(d.statusCode(), d.reason());
                }
            }
        };
        AccessDeniedException e = assertThrows(AccessDeniedException.class,
                () -> rich.check("forbidden", AccessControl.ACTION_MERGE, null));
        assertEquals(403, e.getStatusCode());
        assertEquals(403, e.getHttpStatusCode());
    }

    @Test
    public void serviceNotifiesListenersOnAllowAndDeny() {
        ResumableUploadService allowService = service(PermitAllAccessControl.INSTANCE);
        AtomicInteger allowEvents = new AtomicInteger();
        allowService.addAccessControlListener(
                (identifier, action, decision, elapsed) -> allowEvents.incrementAndGet());
        allowService.getMergeStatus("whatever");
        assertEquals(1, allowEvents.get());

        ResumableUploadService denyService = service(new DenyAlways());
        AtomicInteger denyEvents = new AtomicInteger();
        denyService.addAccessControlListener(
                (identifier, action, decision, elapsed) -> denyEvents.incrementAndGet());
        AccessDeniedException denied = assertThrows(AccessDeniedException.class,
                () -> denyService.getMergeStatus("anything"));
        assertEquals(403, denied.getStatusCode());
        assertEquals(1, denyEvents.get());
    }

    @Test
    public void gatedReadOverloadsEnforceAccessControl() throws Exception {
        ResumableUploadService allowService = service(PermitAllAccessControl.INSTANCE);
        ChunkUploadRequest request = new ChunkUploadRequest();
        request.setIdentifier("read1");
        request.setFileName("demo.bin");
        request.setFileSize(3);
        request.setChunkSize(3);
        request.setChunkTotal(1);
        request.setChunkIndex(0);
        allowService.uploadChunk(request, new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));

        assertTrue(allowService.isChunkUploaded("read1", 0, null));
        assertFalse(allowService.isChunkUploaded("read1", 1, null));       // task exists, chunk not uploaded
        assertFalse(allowService.isChunkUploaded("no-such-task", 0, null)); // task missing
        assertTrue(allowService.getTask("read1", null).isPresent());
        assertFalse(allowService.getTask("no-such-task", null).isPresent());

        ResumableUploadService denyService = service(new DenyAlways());
        assertEquals(403, assertThrows(AccessDeniedException.class,
                () -> denyService.isChunkUploaded("read1", 0, "tk")).getStatusCode());
        assertEquals(403, assertThrows(AccessDeniedException.class,
                () -> denyService.getTask("read1", "tk")).getStatusCode());
    }

    @Test
    public void decideOnlyImplementationRejectsDeprecatedCheck() {
        AccessControl decideOnly = new AccessControl() {
            @Override
            public AccessDecision decide(String identifier, String action, String token) {
                return AccessDecision.allow();
            }
        };
        assertTrue(decideOnly.decide("x", AccessControl.ACTION_UPLOAD, null).allowed());
        // The default check() is a deprecated bridge that must not be called directly.
        assertThrows(UnsupportedOperationException.class,
                () -> decideOnly.check("x", AccessControl.ACTION_UPLOAD, null));
    }

    @Test
    public void accessDecisionToStringDescribesAllowAndDeny() {
        assertEquals("ALLOW", AccessDecision.allow().toString());
        assertEquals("DENY(403, owner mismatch)", AccessDecision.deny(403, "owner mismatch").toString());
        assertEquals("DENY(401)", AccessDecision.deny(401, null).toString());
    }

    private ResumableUploadService service(AccessControl accessControl) {
        return new ResumableUploadService(
                new MemoryTaskStore(),
                new LocalFileChunkStorage(Paths.get(folder.getRoot().getAbsolutePath(), "chunks")),
                new File(folder.getRoot(), "files"),
                true, true, true, new IdentifierLock(), accessControl);
    }

    /**
     * rc.6: a new implementation may override {@link AccessControl#decide} only and need not
     * implement the deprecated {@code check()} at all.
     */
    private static final class DenyAlways implements AccessControl {
        @Override
        public AccessDecision decide(String identifier, String action, String token) {
            return AccessDecision.deny(403, "no access");
        }
    }
}
