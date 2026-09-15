/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import cn.chenxinjie.uploadfile.core.security.AccessControlListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * rc.8 context coverage: proves Spring actually injects {@link UploadFileProperties} into
 * {@link UploadFileAutoConfiguration} through the package-private setter, so the access-log listener
 * reads the configured {@code access-log-scope} (not just the built-in default).
 */
@SpringBootTest(classes = UploadFileAccessLogScopeContextTest.TestConfig.class)
@TestPropertySource(properties = {
        "upload-file.storage-dir=target/upload-file-accesslog-test",
        "upload-file.observability.access-log=true",
        "upload-file.observability.access-log-scope=all"
})
class UploadFileAccessLogScopeContextTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestConfig {
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void accessLogListenerIsWiredAndPropertiesAreInjected() throws Exception {
        assertNotNull(context.getBean(AccessControlListener.class));

        UploadFileAutoConfiguration configuration = context.getBean(UploadFileAutoConfiguration.class);
        Field field = UploadFileAutoConfiguration.class.getDeclaredField("accessLogProperties");
        field.setAccessible(true);
        Object injected = field.get(configuration);
        assertNotNull(injected, "Spring must inject UploadFileProperties into the configuration");
        assertSame(context.getBean(UploadFileProperties.class), injected);
    }
}
