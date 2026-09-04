/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.core.service.StorageCleanupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies the rc.2 features are off by default (rc.1 compatibility): the cleanup scheduler
 * must not run and async merge must be disabled unless explicitly enabled.
 */
@SpringBootTest(classes = UploadFileAutoConfigurationDefaultsTest.TestConfig.class, properties = {
        "upload-file.storage-dir=target/upload-file-feature-test-default"
})
class UploadFileAutoConfigurationDefaultsTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestConfig {
    }

    @Autowired
    private StorageCleanupService cleanupService;

    @Autowired
    private ResumableUploadService uploadService;

    @Test
    void cleanupSchedulerIsNotStartedByDefault() {
        assertFalse(cleanupService.isRunning());
    }

    @Test
    void asyncMergeIsOffByDefault() {
        assertFalse(uploadService.isAsyncMergeEnabled());
    }
}
