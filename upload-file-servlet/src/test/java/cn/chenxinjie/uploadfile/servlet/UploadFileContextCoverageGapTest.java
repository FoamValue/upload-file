/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.servlet;

import cn.chenxinjie.uploadfile.core.security.AccessContext;
import cn.chenxinjie.uploadfile.core.security.AccessControl;
import cn.chenxinjie.uploadfile.core.security.AccessControlListener;
import cn.chenxinjie.uploadfile.core.security.AccessDecision;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Coverage-gap tests for {@link UploadFileContext} branches the behavioural suites leave
 * uncovered: {@link UploadFileContext#sanitizeLog(String)} control-character handling, the
 * two-arg {@code build} fail-fast, the async-merge thread-pool fail-fast, and the
 * access-decision listener (including the legacy 4-arg bridge and the first-chunk-only
 * logging filter in the default {@code task} scope).
 */
public class UploadFileContextCoverageGapTest {

    private static final String ACCESS_LOGGER = "cn.chenxinjie.uploadfile.access";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void sanitizeLogReplacesControlCharactersAndKeepsPrintable() {
        assertEquals("", UploadFileContext.sanitizeLog(null));
        assertEquals("", UploadFileContext.sanitizeLog(""));
        assertEquals("a_b_c", UploadFileContext.sanitizeLog("a\nb\tc"));
        assertEquals("d_e_f_", UploadFileContext.sanitizeLog("d\re\u0001f\u007f"));
        assertEquals("plain text", UploadFileContext.sanitizeLog("plain text"));
    }

    @Test
    public void twoArgBuildFailsFastWhenAllLimitsUnset() {
        try {
            UploadFileContext.build(folder.getRoot().getAbsolutePath(), null);
            throw new AssertionError("expected IllegalStateException for unbounded upload");
        } catch (IllegalStateException expected) {
            // The default Config leaves all limits unset, so the two-arg overload must fail fast.
        }
    }

    @Test
    public void asyncMergeWithNonPositiveThreadPoolFailsFast() {
        UploadFileContext.Config config = new UploadFileContext.Config();
        config.maxFileSize = 100; // satisfy the unbounded-upload gate first
        config.asyncMergeEnabled = true;
        config.asyncMergeThreadPoolSize = 0;

        assertThrows(IllegalStateException.class,
                () -> UploadFileContext.build(folder.getRoot().getAbsolutePath(), null, config));
    }

    private UploadFileContext contextWithAccessLog(String scope) {
        UploadFileContext.Config config = new UploadFileContext.Config();
        config.maxChunkSize = 100;
        config.observabilityAccessLog = true;
        config.observabilityAccessLogScope = scope;
        return UploadFileContext.build(folder.getRoot().getAbsolutePath(), null, config);
    }

    @SuppressWarnings("unchecked")
    private static AccessControlListener firstAccessListener(ResumableUploadService service)
            throws Exception {
        Field field = ResumableUploadService.class.getDeclaredField("accessControlListeners");
        field.setAccessible(true);
        List<AccessControlListener> listeners = (List<AccessControlListener>) field.get(service);
        assertEquals(1, listeners.size());
        return listeners.get(0);
    }

    private static Handler capturingHandler(List<LogRecord> records) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    public void accessLogListenerLogsSanitizedDecisions() throws Exception {
        UploadFileContext ctx = contextWithAccessLog("all");
        AccessControlListener listener = firstAccessListener(ctx.getUploadService());

        List<LogRecord> records = new ArrayList<>();
        Logger access = Logger.getLogger(ACCESS_LOGGER);
        access.setLevel(Level.ALL);
        access.setUseParentHandlers(false);
        Handler handler = capturingHandler(records);
        access.addHandler(handler);
        try {
            // Legacy 4-arg overload bridges into the same log line with an empty context.
            listener.onDecision("legacy-id", AccessControl.ACTION_MERGE, AccessDecision.allow(), 1_000_000L);
            String line = new SimpleFormatter().format(records.get(0));
            assertTrue(line, line.contains("decision=ALLOW"));
            assertTrue(line, line.contains("identifier=legacy-id"));

            // 5-arg overload with control characters in the context -> sanitized fields.
            AccessContext dirty = new AccessContext("POST", "/upload\n", "1.2.3.4\r", "UA\u0001\u007f");
            listener.onDecision(dirty, "ctx-id", AccessControl.ACTION_DOWNLOAD, AccessDecision.allow(), 2_000_000L);
            String line2 = new SimpleFormatter().format(records.get(1));
            assertTrue(line2, line2.contains("method=POST"));
            assertTrue(line2, line2.contains("uri=/upload_"));
            assertTrue(line2, line2.contains("remoteAddr=1.2.3.4_"));
            assertTrue(line2, line2.contains("userAgent=UA__"));

            // Deny decisions log a WARNING carrying the status and reason.
            listener.onDecision(dirty, "deny-id", AccessControl.ACTION_UPLOAD,
                    AccessDecision.deny(403, "forbidden\n"), 3_000_000L);
            assertEquals(3, records.size());
            assertEquals(Level.WARNING, records.get(2).getLevel());
            String line3 = new SimpleFormatter().format(records.get(2));
            assertTrue(line3, line3.contains("decision=DENY"));
            assertTrue(line3, line3.contains("status=403"));
        } finally {
            access.removeHandler(handler);
        }
    }

    @Test
    public void accessLogSkipsRepeatedChunkAllowInTaskScope() throws Exception {
        UploadFileContext ctx = contextWithAccessLog("task");
        AccessControlListener listener = firstAccessListener(ctx.getUploadService());

        List<LogRecord> records = new ArrayList<>();
        Logger access = Logger.getLogger(ACCESS_LOGGER);
        access.setLevel(Level.ALL);
        access.setUseParentHandlers(false);
        Handler handler = capturingHandler(records);
        access.addHandler(handler);
        try {
            // First chunk allow of an identifier is a task-level event and is logged.
            listener.onDecision("id1", AccessControl.ACTION_UPLOAD, AccessDecision.allow(), 1L);
            // The second chunk allow of the same identifier is filtered out.
            listener.onDecision("id1", AccessControl.ACTION_UPLOAD, AccessDecision.allow(), 1L);
            // Deny decisions are always logged.
            listener.onDecision("id1", AccessControl.ACTION_UPLOAD, AccessDecision.deny(403, "x"), 1L);
            assertEquals(2, records.size());
            assertEquals(Level.INFO, records.get(0).getLevel());
            assertEquals(Level.WARNING, records.get(1).getLevel());
        } finally {
            access.removeHandler(handler);
        }
    }
}
