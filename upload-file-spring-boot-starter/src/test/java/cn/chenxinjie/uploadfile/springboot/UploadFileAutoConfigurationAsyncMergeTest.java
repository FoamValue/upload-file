/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code upload-file.async-merge.enabled=true} wires the async merge executor.
 */
@SpringBootTest(classes = UploadFileAutoConfigurationAsyncMergeTest.TestConfig.class, properties = {
        "upload-file.storage-dir=target/upload-file-feature-test-async",
        "upload-file.async-merge.enabled=true"
})
class UploadFileAutoConfigurationAsyncMergeTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestConfig {
    }

    @Autowired
    private ResumableUploadService uploadService;

    @Test
    void asyncMergeExecutorIsWiredWhenEnabled() {
        assertTrue(uploadService.isAsyncMergeEnabled());
    }
}
