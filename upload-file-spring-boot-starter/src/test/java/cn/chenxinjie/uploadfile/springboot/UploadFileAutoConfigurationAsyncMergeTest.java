/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.model.MergeStatus;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void asyncMergeCompletesOnWiredExecutor() throws Exception {
        ChunkUploadRequest request = new ChunkUploadRequest();
        request.setIdentifier("async-wired-1");
        request.setFileName("demo.bin");
        request.setChunkSize(5);
        request.setChunkTotal(1);
        request.setChunkIndex(0);
        uploadService.uploadChunk(request,
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        MergeStatus submitted = uploadService.submitMerge("async-wired-1");
        assertEquals("PENDING", submitted.getState());

        MergeStatus terminal = null;
        for (int i = 0; i < 200 && terminal == null; i++) {
            MergeStatus status = uploadService.getMergeStatus("async-wired-1");
            if ("SUCCEEDED".equals(status.getState()) || "FAILED".equals(status.getState())) {
                terminal = status;
            } else {
                Thread.sleep(20);
            }
        }
        assertEquals("SUCCEEDED", terminal.getState());
        assertTrue(terminal.isMerged());
    }
}
