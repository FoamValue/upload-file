/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import cn.chenxinjie.uploadfile.servlet.DownloadServlet;
import cn.chenxinjie.uploadfile.servlet.UploadServlet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * rc.6: a host-provided servlet bean overrides the auto-configured instance (the registration then
 * wires the host's bean instead), which is the documented override contract.
 */
@SpringBootTest(classes = UploadFileAutoConfigurationServletOverrideTest.TestConfig.class)
@TestPropertySource(properties = {
        "upload-file.storage-dir=target/upload-file-servlet-override-test",
        "upload-file.endpoint.download-enabled=true"
})
class UploadFileAutoConfigurationServletOverrideTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestConfig {

        @Bean
        UploadServlet customUploadServlet() {
            return new UploadServlet();
        }

        @Bean
        DownloadServlet customDownloadServlet() {
            return new DownloadServlet();
        }
    }

    @Autowired
    private UploadServlet customUploadServlet;

    @Autowired
    private DownloadServlet customDownloadServlet;

    @Autowired
    @Qualifier("uploadFileServletRegistration")
    private ServletRegistrationBean<?> uploadRegistration;

    @Autowired
    @Qualifier("downloadFileServletRegistration")
    private ServletRegistrationBean<?> downloadRegistration;

    @Test
    void hostServletBeansOverrideTheAutoConfiguredInstances() {
        assertSame(customUploadServlet, uploadRegistration.getServlet());
        assertSame(customDownloadServlet, downloadRegistration.getServlet());
    }
}
