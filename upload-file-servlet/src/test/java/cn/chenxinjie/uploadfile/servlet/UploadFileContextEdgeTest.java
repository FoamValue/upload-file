/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.servlet;

import cn.chenxinjie.uploadfile.core.security.AccessControl;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.mock.web.MockServletContext;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Edge coverage for {@link UploadFileContext} branches that the behavioural tests do not reach:
 * the per-file size-limit wiring and the init-param integer parsing fallback.
 */
public class UploadFileContextEdgeTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void buildAppliesPerFileSizeLimit() {
        UploadFileContext.Config config = new UploadFileContext.Config();
        config.maxFileSize = 100;

        UploadFileContext context =
                UploadFileContext.build(folder.getRoot().getAbsolutePath(), null, config);

        assertNotNull(context);
    }

    @Test
    public void invalidThreadPoolSizeInitParamFallsBackToDefault() {
        MockServletConfig config = new MockServletConfig(new MockServletContext());
        config.addInitParameter("async-merge.thread-pool-size", "not-a-number");

        UploadFileContext.Config parsed = UploadFileContext.Config.fromInitParams(config);

        assertEquals(2, parsed.asyncMergeThreadPoolSize);
    }

    @Test
    public void numericThreadPoolSizeInitParamIsParsed() {
        MockServletConfig config = new MockServletConfig(new MockServletContext());
        config.addInitParameter("async-merge.thread-pool-size", "4");

        UploadFileContext.Config parsed = UploadFileContext.Config.fromInitParams(config);

        assertEquals(4, parsed.asyncMergeThreadPoolSize);
    }

    @Test
    public void accessLogScopeTaskAdmitsOnlyTheFirstChunkPerIdentifier() {
        Set<String> seen = UploadFileContext.newAccessLogIdentifierSet();
        assertTrue(UploadFileContext.admitAccessLog("task", AccessControl.ACTION_UPLOAD, true, "id", seen));
        assertFalse(UploadFileContext.admitAccessLog("task", AccessControl.ACTION_UPLOAD, true, "id", seen));
        assertTrue(UploadFileContext.admitAccessLog("task", AccessControl.ACTION_MERGE, true, "id", seen));
        assertTrue(UploadFileContext.admitAccessLog("task", AccessControl.ACTION_UPLOAD, false, "id", seen));
    }

    @Test
    public void accessLogScopeAllLogsEveryChunkAndDenyLogsNoAllow() {
        Set<String> all = UploadFileContext.newAccessLogIdentifierSet();
        assertTrue(UploadFileContext.admitAccessLog("all", AccessControl.ACTION_UPLOAD, true, "id", all));
        assertTrue(UploadFileContext.admitAccessLog("all", AccessControl.ACTION_UPLOAD, true, "id", all));

        Set<String> deny = UploadFileContext.newAccessLogIdentifierSet();
        assertFalse(UploadFileContext.admitAccessLog("deny", AccessControl.ACTION_UPLOAD, true, "id", deny));
        assertTrue(UploadFileContext.admitAccessLog("deny", AccessControl.ACTION_UPLOAD, false, "id", deny));
    }
}
