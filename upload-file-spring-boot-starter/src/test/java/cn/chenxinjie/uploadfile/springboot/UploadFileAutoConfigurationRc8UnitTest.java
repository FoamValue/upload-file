/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import cn.chenxinjie.uploadfile.core.security.AccessContext;
import cn.chenxinjie.uploadfile.core.security.AccessControl;
import cn.chenxinjie.uploadfile.core.security.AccessDecision;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.core.service.TrustedUploadService;
import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rc.8 unit coverage: {@code access-log-scope} filtering (T48) and the {@code TrustedUploadService}
 * auto-wiring (T49).
 */
class UploadFileAutoConfigurationRc8UnitTest {

    @Test
    void accessLogScopeTaskSkipsChunkAllowsButKeepsDeniesAndTaskLevelAllows() {
        // Default scope: the per-chunk upload allow is skipped, everything else is kept.
        assertFalse(UploadFileAutoConfiguration.shouldLogAccess("task", AccessControl.ACTION_UPLOAD, true));
        assertTrue(UploadFileAutoConfiguration.shouldLogAccess("task", AccessControl.ACTION_MERGE, true));
        assertTrue(UploadFileAutoConfiguration.shouldLogAccess("task", AccessControl.ACTION_DOWNLOAD, true));
        assertTrue(UploadFileAutoConfiguration.shouldLogAccess("task", AccessControl.ACTION_CANCEL, true));
        assertTrue(UploadFileAutoConfiguration.shouldLogAccess("task", AccessControl.ACTION_UPLOAD, false));
    }

    @Test
    void accessLogScopeDenyOnlyLogsDenies() {
        assertTrue(UploadFileAutoConfiguration.shouldLogAccess("deny", AccessControl.ACTION_MERGE, false));
        assertFalse(UploadFileAutoConfiguration.shouldLogAccess("deny", AccessControl.ACTION_MERGE, true));
        assertFalse(UploadFileAutoConfiguration.shouldLogAccess("deny", AccessControl.ACTION_UPLOAD, true));
    }

    @Test
    void accessLogScopeAllLogsEverything() {
        assertTrue(UploadFileAutoConfiguration.shouldLogAccess("all", AccessControl.ACTION_UPLOAD, true));
        assertTrue(UploadFileAutoConfiguration.shouldLogAccess("all", AccessControl.ACTION_MERGE, false));
    }

    @Test
    void accessLogScopeDefaultsToTask() {
        UploadFileProperties properties = new UploadFileProperties();
        assertEquals("task", properties.getObservability().getAccessLogScope());
    }

    @Test
    void accessLogListenerHandlesBothOverloadsWithoutError() {
        UploadFileProperties properties = new UploadFileProperties();
        properties.getObservability().setAccessLog(true);
        UploadFileAutoConfiguration configuration = new UploadFileAutoConfiguration();
        configuration.setAccessLogProperties(properties);
        cn.chenxinjie.uploadfile.core.security.AccessControlListener listener =
                configuration.uploadFileAccessLogListener();

        // Both the 5-arg (no context) and 6-arg (with context) forms must be safe to call.
        listener.onDecision("id", AccessControl.ACTION_MERGE, AccessDecision.allow(), 1L);
        listener.onDecision(new AccessContext("POST", "/upload", "127.0.0.1", "junit"),
                "id", AccessControl.ACTION_MERGE, AccessDecision.allow(), 1L);
        listener.onDecision(new AccessContext("POST", "/upload", "127.0.0.1", "junit"),
                "id", AccessControl.ACTION_UPLOAD, AccessDecision.deny(403, "nope"), 1L);
    }

    @Test
    void trustedUploadServiceBeanWrapsTheUploadService(@TempDir Path tempDir) {
        ResumableUploadService uploadService = new ResumableUploadService(
                new MemoryTaskStore(), new LocalFileChunkStorage(tempDir.resolve("chunks")),
                new File(tempDir.toFile(), "files"));

        TrustedUploadService trusted = new UploadFileAutoConfiguration().trustedUploadService(uploadService);

        assertNotNull(trusted);
        assertFalse(trusted.getTask("missing").isPresent());
    }
}
