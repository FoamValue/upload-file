/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Verifies {@code upload-file.metadata-store=memory} selects the in-memory store.
 */
@SpringBootTest(classes = UploadFileAutoConfigurationMemoryStoreTest.TestConfig.class, properties = {
        "upload-file.storage-dir=target/upload-file-feature-test-memory",
        "upload-file.metadata-store=memory"
})
class UploadFileAutoConfigurationMemoryStoreTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestConfig {
    }

    @Autowired
    private TaskStore taskStore;

    @Test
    void memoryStoreIsSelected() {
        assertInstanceOf(MemoryTaskStore.class, taskStore);
    }
}
