/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import cn.chenxinjie.uploadfile.core.service.ResumableDownloadService;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.core.storage.ChunkStorage;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import cn.chenxinjie.uploadfile.servlet.DownloadServlet;
import cn.chenxinjie.uploadfile.servlet.UploadServlet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link UploadFileAutoConfiguration} wires the core beans and registers
 * the upload/download servlets when the starter is on the classpath.
 */
@SpringBootTest(classes = UploadFileAutoConfigurationTest.TestConfig.class)
@TestPropertySource(properties = "upload-file.storage-dir=target/upload-file-starter-test")
class UploadFileAutoConfigurationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestConfig {
    }

    @Autowired
    private TaskStore taskStore;

    @Autowired
    private ChunkStorage chunkStorage;

    @Autowired
    private ResumableUploadService uploadService;

    @Autowired
    private ResumableDownloadService downloadService;

    @Autowired
    private ApplicationContext context;

    @Test
    void coreBeansAreWired() {
        assertNotNull(taskStore);
        assertNotNull(chunkStorage);
        assertNotNull(uploadService);
        assertNotNull(downloadService);
    }

    @Test
    void servletRegistrationsExist() {
        ServletRegistrationBean<?> upload = (ServletRegistrationBean<?>) context.getBean("uploadFileServletRegistration");
        ServletRegistrationBean<?> download = (ServletRegistrationBean<?>) context.getBean("downloadFileServletRegistration");
        assertNotNull(upload);
        assertNotNull(download);
        assertTrue(upload.getServlet() instanceof UploadServlet);
        assertTrue(download.getServlet() instanceof DownloadServlet);
        assertTrue(upload.getUrlMappings().contains("/upload"));
        assertTrue(download.getUrlMappings().contains("/download"));
    }
}
