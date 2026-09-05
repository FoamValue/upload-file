/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.servlet.DownloadServlet;
import cn.chenxinjie.uploadfile.servlet.UploadServlet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rc.6 endpoint-registration control: {@code endpoint.download-enabled=true} registers the download
 * servlet (off by default); {@code endpoint.enabled=false} is a beans-only mode.
 */
@SpringBootTest(classes = UploadFileAutoConfigurationTest.TestConfig.class)
@TestPropertySource(properties = {
        "upload-file.storage-dir=target/upload-file-starter-test",
        "upload-file.endpoint.download-enabled=true"
})
class UploadFileAutoConfigurationDownloadEndpointTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void downloadServletRegisteredWhenEnabled() {
        ServletRegistrationBean<?> download =
                (ServletRegistrationBean<?>) context.getBean("downloadFileServletRegistration");
        assertNotNull(download);
        assertTrue(download.getServlet() instanceof DownloadServlet);
        assertTrue(download.getUrlMappings().contains("/download"));
        assertTrue(context.containsBean("uploadFileServletRegistration"));
        assertTrue(context.getBean("uploadFileServletRegistration") instanceof ServletRegistrationBean);
    }
}

@SpringBootTest(classes = UploadFileAutoConfigurationTest.TestConfig.class)
@TestPropertySource(properties = {
        "upload-file.storage-dir=target/upload-file-starter-test",
        "upload-file.endpoint.enabled=false"
})
class UploadFileAutoConfigurationBeansOnlyTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ResumableUploadService uploadService;

    @Test
    void noServletButServicesWired() {
        assertNotNull(uploadService);
        assertFalse(context.containsBean("uploadFileServletRegistration"));
        assertFalse(context.containsBean("downloadFileServletRegistration"));
    }
}
