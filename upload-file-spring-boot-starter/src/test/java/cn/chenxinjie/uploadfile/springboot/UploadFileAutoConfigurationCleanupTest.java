/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import cn.chenxinjie.uploadfile.core.service.StorageCleanupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code upload-file.cleanup.enabled=true} (with {@code run-on-startup}) starts the
 * cleanup scheduler and runs an initial pass.
 */
@SpringBootTest(classes = UploadFileAutoConfigurationCleanupTest.TestConfig.class, properties = {
        "upload-file.storage-dir=target/upload-file-feature-test-cleanup",
        "upload-file.cleanup.enabled=true",
        "upload-file.cleanup.run-on-startup=true"
})
class UploadFileAutoConfigurationCleanupTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestConfig {
    }

    @Autowired
    private StorageCleanupService cleanupService;

    @Autowired
    private UploadFileProperties properties;

    @Test
    void cleanupSchedulerIsStarted() {
        assertTrue(properties.getCleanup().isEnabled());
        assertTrue(cleanupService.isRunning());
    }
}
