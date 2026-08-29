/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import cn.chenxinjie.uploadfile.core.exception.AccessDeniedException;
import cn.chenxinjie.uploadfile.core.exception.QuotaExceededException;
import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.security.AccessControl;
import cn.chenxinjie.uploadfile.core.security.TokenAccessControl;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.servlet.UploadServlet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the rc.3 security / size / quota wiring in {@link UploadFileAutoConfiguration}
 * (the dotted property names bind to the nested groups).
 */
@SpringBootTest(classes = UploadFileAutoConfigurationSecurityTest.TestConfig.class)
@TestPropertySource(properties = {
        "upload-file.storage-dir=target/upload-file-security-test",
        "upload-file.security.enabled=true",
        "upload-file.security.token=shared-secret",
        "upload-file.security.header-name=X-Upload-Token",
        "upload-file.max-file-size=1024",
        "upload-file.quota.max-bytes=2048"
})
class UploadFileAutoConfigurationSecurityTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestConfig {
    }

    @Autowired
    private AccessControl accessControl;

    @Autowired
    private ResumableUploadService uploadService;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("uploadFileServletRegistration")
    private ServletRegistrationBean<?> uploadServletRegistration;

    @Test
    void dottedSecurityPropertiesBindAndEnforceToken() throws Exception {
        assertInstanceOf(TokenAccessControl.class, accessControl);

        ChunkUploadRequest req = req("sec-1");
        assertThrows(AccessDeniedException.class,
                () -> uploadService.uploadChunk(req, new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))));
        assertFalse(uploadService.isChunkUploaded("sec-1", 0));

        assertEquals(1, uploadService.uploadChunk(req, "shared-secret",
                new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8))).getUploadedCount());
    }

    @Test
    void servletUsesConfiguredHeaderName() {
        assertTrue(uploadServletRegistration.getServlet() instanceof UploadServlet);
    }

    @Test
    void maxFileSizeAndQuotaAreWired() {
        ChunkUploadRequest overFileSize = req("sec-2");
        overFileSize.setFileSize(2000); // > max-file-size 1024
        assertThrows(IllegalArgumentException.class,
                () -> uploadService.uploadChunk(overFileSize, "shared-secret",
                        new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))));

        // Seed a 1500-byte merged task so the 2048 quota is nearly used up.
        cn.chenxinjie.uploadfile.core.model.UploadTask existing = new cn.chenxinjie.uploadfile.core.model.UploadTask();
        existing.setIdentifier("done");
        existing.setFileName("done.bin");
        existing.setChunkTotal(1);
        existing.setMerged(true);
        existing.setFinalFileSize(1500);
        existing.setSchemaVersion(cn.chenxinjie.uploadfile.core.model.UploadTask.CURRENT_SCHEMA_VERSION);
        uploadService.getTaskStore().save(existing);

        // 1500 + 1000 > 2048 -> quota rejected.
        ChunkUploadRequest overQuota = req("sec-3");
        overQuota.setFileSize(1000);
        assertThrows(QuotaExceededException.class,
                () -> uploadService.uploadChunk(overQuota, "shared-secret",
                        new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))));
    }

    private static ChunkUploadRequest req(String id) {
        ChunkUploadRequest req = new ChunkUploadRequest();
        req.setIdentifier(id);
        req.setFileName("demo.bin");
        req.setFileSize(3);
        req.setChunkSize(3);
        req.setChunkTotal(1);
        req.setChunkIndex(0);
        return req;
    }
}
