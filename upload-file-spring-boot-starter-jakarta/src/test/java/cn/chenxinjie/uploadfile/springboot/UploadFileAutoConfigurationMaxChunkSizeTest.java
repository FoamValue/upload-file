/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@code upload-file.max-chunk-size} is enforced by the wired upload service.
 */
@SpringBootTest(classes = UploadFileAutoConfigurationMaxChunkSizeTest.TestConfig.class, properties = {
        "upload-file.storage-dir=target/upload-file-feature-test-max-size",
        "upload-file.max-chunk-size=4"
})
class UploadFileAutoConfigurationMaxChunkSizeTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestConfig {
    }

    @Autowired
    private ResumableUploadService uploadService;

    @Test
    void oversizedChunkIsRejected() {
        ChunkUploadRequest request = new ChunkUploadRequest();
        request.setIdentifier("max-size-1");
        request.setFileName("demo.bin");
        request.setChunkSize(4);
        request.setChunkTotal(1);
        request.setChunkIndex(0);

        assertThrows(IllegalArgumentException.class,
                () -> uploadService.uploadChunk(request,
                        new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))));
    }
}
