/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import cn.chenxinjie.uploadfile.core.error.UploadErrorRenderer;
import cn.chenxinjie.uploadfile.servlet.UploadServlet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import javax.servlet.MultipartConfigElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * rc.7 unit coverage: multipart safe-default derivation (T37) and host {@link UploadErrorRenderer}
 * bean precedence (T36).
 */
class UploadFileAutoConfigurationRc7UnitTest {

    private static final long MB = 1024 * 1024;

    @Test
    void componentStrategyDerivesRequestLimitFromChunkSize() throws Exception {
        UploadFileProperties properties = new UploadFileProperties();
        properties.setMaxChunkSize(5 * MB);
        // max-request-size left unset (-1): must be derived, not unbounded.
        MultipartConfigElement configElement = multipartConfig(properties, new MockEnvironment());
        assertEquals(6 * MB, configElement.getMaxRequestSize());
    }

    @Test
    void componentStrategyDerivesRequestLimitFromFileSize() throws Exception {
        UploadFileProperties properties = new UploadFileProperties();
        properties.setMaxFileSize(500 * MB);
        MultipartConfigElement configElement = multipartConfig(properties, new MockEnvironment());
        assertEquals(501 * MB, configElement.getMaxRequestSize());
    }

    @Test
    void componentStrategyStaysUnboundedOnlyWhenNothingIsConfigured() throws Exception {
        UploadFileProperties properties = new UploadFileProperties();
        MultipartConfigElement configElement = multipartConfig(properties, new MockEnvironment());
        assertEquals(-1, configElement.getMaxRequestSize());
    }

    @Test
    void explicitRequestLimitIsNotOverridden() throws Exception {
        UploadFileProperties properties = new UploadFileProperties();
        properties.setMaxChunkSize(5 * MB);
        properties.setMaxRequestSize(8 * MB);
        MultipartConfigElement configElement = multipartConfig(properties, new MockEnvironment());
        assertEquals(8 * MB, configElement.getMaxRequestSize());
    }

    @Test
    void hostErrorRendererBeanWinsOverProperty() throws Exception {
        UploadFileAutoConfiguration.EndpointRegistrationConfiguration.UploadServletRegistrationConfiguration cfg =
                new UploadFileAutoConfiguration.EndpointRegistrationConfiguration.UploadServletRegistrationConfiguration();
        UploadFileProperties properties = new UploadFileProperties();
        properties.getHttp().setErrorBody("standard");

        UploadErrorRenderer custom = (action, identifier, status, code, message) -> "CUSTOM";
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("customErrorRenderer", custom);
        ObjectProvider<UploadErrorRenderer> provider = beans.getBeanProvider(UploadErrorRenderer.class);

        UploadServlet servlet = cfg.uploadFileServlet(null, properties, provider);

        Field field = UploadServlet.class.getDeclaredField("errorRenderer");
        field.setAccessible(true);
        assertSame(custom, field.get(servlet));
    }

    private static MultipartConfigElement multipartConfig(UploadFileProperties properties, MockEnvironment environment)
            throws Exception {
        Method method = UploadFileAutoConfiguration.class
                .getDeclaredMethod("multipartConfig", UploadFileProperties.class, Environment.class);
        method.setAccessible(true);
        return (MultipartConfigElement) method.invoke(null, properties, environment);
    }
}
