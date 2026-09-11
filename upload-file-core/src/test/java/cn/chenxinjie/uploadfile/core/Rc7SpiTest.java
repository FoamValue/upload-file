/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core;

import cn.chenxinjie.uploadfile.core.exception.AccessDeniedException;
import cn.chenxinjie.uploadfile.core.security.AbstractAccessControl;
import cn.chenxinjie.uploadfile.core.security.AccessControl;
import cn.chenxinjie.uploadfile.core.security.AccessDecision;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.core.service.TrustedUploadService;
import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.store.TaskStoreQuotaStore;
import cn.chenxinjie.uploadfile.core.util.IdentifierLock;
import cn.chenxinjie.uploadfile.core.util.IdentifierLockHandle;
import cn.chenxinjie.uploadfile.core.util.IdentifierLockProvider;
import cn.chenxinjie.uploadfile.core.util.StripedIdentifierLockProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * rc.7 SPI coverage: {@link AbstractAccessControl}, {@link AccessControl#ofDecide}, {@link AccessControl#ofCheck},
 * {@link TrustedUploadService}, {@link TaskStoreQuotaStore} and {@link StripedIdentifierLockProvider}.
 */
public class Rc7SpiTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ResumableUploadService service() {
        return new ResumableUploadService(
                new MemoryTaskStore(),
                new LocalFileChunkStorage(folder.getRoot().toPath().resolve("chunks")),
                new File(folder.getRoot(), "files"));
    }

    @Test
    public void abstractAccessControlForcesDecideAndBridgesCheck() {
        AccessControl ac = new AbstractAccessControl() {
            @Override
            public AccessDecision decide(String identifier, String action, String token) {
                return "no".equals(token) ? AccessDecision.deny(403, "nope") : AccessDecision.allow();
            }
        };
        assertTrue(ac.decide("x", AccessControl.ACTION_UPLOAD, "ok").allowed());
        assertFalse(ac.decide("x", AccessControl.ACTION_UPLOAD, "no").allowed());
        ac.check("x", AccessControl.ACTION_UPLOAD, "ok");
        AccessDeniedException denied = assertThrows(AccessDeniedException.class,
                () -> ac.check("x", AccessControl.ACTION_UPLOAD, "no"));
        assertEquals(403, denied.getStatusCode());
    }

    @Test
    public void ofDecideAndOfCheckFactories() {
        AccessControl decider = AccessControl.ofDecide((id, action, token) -> AccessDecision.deny(401, "x"));
        assertEquals(401, decider.decide("a", AccessControl.ACTION_UPLOAD, null).statusCode());

        AccessControl checker = AccessControl.ofCheck((id, action, token) -> {
            throw new AccessDeniedException(403, "forbidden");
        });
        assertEquals(403, checker.decide("a", AccessControl.ACTION_UPLOAD, null).statusCode());
    }

    @Test
    public void trustedFacadeReadsWithoutGating() {
        ResumableUploadService svc = service();
        svc.setAccessControl(AccessControl.ofDecide((id, action, token) -> AccessDecision.deny(403, "always")));
        // gated reads are denied
        assertThrows(AccessDeniedException.class, () -> svc.getTask("t1", "tk"));
        assertThrows(AccessDeniedException.class, () -> svc.getProgress("t1", "tk"));
        // the trusted facade bypasses the gate
        TrustedUploadService trusted = new TrustedUploadService(svc);
        assertFalse(trusted.getTask("t1").isPresent());
        assertEquals(0, trusted.getProgress("t1").getUploadedCount());
        assertFalse(trusted.isChunkUploaded("t1", 0));
    }

    @Test
    public void taskStoreQuotaStoreReserveSemantics() {
        MemoryTaskStore store = new MemoryTaskStore();
        TaskStoreQuotaStore quota = new TaskStoreQuotaStore(store);
        assertTrue(quota.tryReserve("a", 100, 0));    // quota disabled
        assertTrue(quota.tryReserve("a", 100, 150));  // 100 <= 150
        assertFalse(quota.tryReserve("a", 200, 150)); // 200 > 150
        assertEquals(0, quota.usedBytes());           // derived from the (empty) store
        quota.release("a");                           // no-op
    }

    @Test
    public void stripedProviderIsReentrantAndReleasable() {
        IdentifierLock lock = new IdentifierLock();
        IdentifierLockProvider provider = new StripedIdentifierLockProvider(lock);
        try (IdentifierLockHandle outer = provider.lock("id")) {
            assertNotNull(outer);
            try (IdentifierLockHandle inner = provider.lock("id")) {
                assertNotNull(inner); // reentrant on the same thread
            }
        }
        // after release the lock is available again
        try (IdentifierLockHandle again = provider.lock("id")) {
            assertNotNull(again);
        }
    }
}
