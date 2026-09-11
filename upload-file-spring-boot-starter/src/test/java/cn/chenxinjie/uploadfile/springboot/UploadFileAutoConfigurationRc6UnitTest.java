/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import cn.chenxinjie.uploadfile.core.security.AccessControlListener;
import cn.chenxinjie.uploadfile.core.security.AccessDecision;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import javax.servlet.MultipartConfigElement;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * rc.6 unit coverage for the multipart strategy resolution and the access-log listener that are
 * awkward to assert through a full Spring context.
 */
class UploadFileAutoConfigurationRc6UnitTest {

    private final UploadFileAutoConfiguration config = new UploadFileAutoConfiguration();

    @Test
    void springMultipartStrategyFollowsSpringProperties() throws Exception {
        UploadFileProperties properties = new UploadFileProperties();
        properties.getMultipart().setStrategy("spring");
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.servlet.multipart.max-file-size", "2MB");
        environment.setProperty("spring.servlet.multipart.max-request-size", "3MB");
        environment.setProperty("spring.servlet.multipart.file-size-threshold", "4KB");

        MultipartConfigElement configElement = multipartConfig(properties, environment);

        assertEquals(2L * 1024 * 1024, configElement.getMaxFileSize());
        assertEquals(3L * 1024 * 1024, configElement.getMaxRequestSize());
        assertEquals(4L * 1024, configElement.getFileSizeThreshold());
    }

    @Test
    void springMultipartStrategyFallsBackOnInvalidValue() throws Exception {
        UploadFileProperties properties = new UploadFileProperties();
        properties.getMultipart().setStrategy("spring");
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.servlet.multipart.max-file-size", "not-a-size");

        MultipartConfigElement configElement = multipartConfig(properties, environment);

        // The unparsable value is ignored and the documented 1 MB Boot default is used.
        assertEquals(1024L * 1024, configElement.getMaxFileSize());
    }

    @Test
    void unlimitedMultipartStrategyDisablesContainerLimits() throws Exception {
        UploadFileProperties properties = new UploadFileProperties();
        properties.getMultipart().setStrategy("unlimited");

        MultipartConfigElement configElement = multipartConfig(properties, new MockEnvironment());

        assertEquals(-1, configElement.getMaxFileSize());
        assertEquals(-1, configElement.getMaxRequestSize());
    }

    @Test
    void componentMultipartStrategyUsesComponentLimits() throws Exception {
        UploadFileProperties properties = new UploadFileProperties();
        properties.getMultipart().setStrategy("component");
        properties.setMaxChunkSize(1024);
        properties.setMaxRequestSize(2048);

        MultipartConfigElement configElement = multipartConfig(properties, new MockEnvironment());

        assertEquals(1024, configElement.getMaxFileSize());
        assertEquals(2048, configElement.getMaxRequestSize());
    }

    @Test
    void accessLogListenerHandlesAllowAndDeny() {
        AccessControlListener listener = config.uploadFileAccessLogListener();
        listener.onDecision("id", "upload", AccessDecision.allow(), 1_000_000L);
        listener.onDecision("id", "upload", AccessDecision.deny(403, "owner mismatch"), 1_000_000L);
    }

    private static MultipartConfigElement multipartConfig(UploadFileProperties properties, MockEnvironment environment)
            throws Exception {
        Method method = UploadFileAutoConfiguration.class
                .getDeclaredMethod("multipartConfig", UploadFileProperties.class, Environment.class);
        method.setAccessible(true);
        return (MultipartConfigElement) method.invoke(null, properties, environment);
    }
}
