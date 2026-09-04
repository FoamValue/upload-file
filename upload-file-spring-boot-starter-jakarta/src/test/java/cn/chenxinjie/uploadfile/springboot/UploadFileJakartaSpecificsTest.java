/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import cn.chenxinjie.uploadfile.servlet.UploadServlet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jakarta-specific assertions that do not exist on the {@code javax} line: the auto-configuration must be
 * registered through Spring Boot 3/4 {@code AutoConfiguration.imports} (not the Boot 2 {@code spring.factories}),
 * be annotated {@code @AutoConfiguration}, and the servlet twin must actually extend {@code jakarta.servlet} types.
 */
class UploadFileJakartaSpecificsTest {

    @Test
    void autoConfigurationIsDiscoveredThroughImportsFile() throws IOException {
        URL url = getClass().getClassLoader()
                .getResource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertNotNull(url, "AutoConfiguration.imports must be packaged by the jakarta starter");
        try (InputStream in = url.openStream()) {
            String content = new String(readAllBytes(in), StandardCharsets.UTF_8);
            assertTrue(content.contains("cn.chenxinjie.uploadfile.springboot.UploadFileAutoConfiguration"),
                    "the auto-config class must be listed in AutoConfiguration.imports");
        }
    }

    @Test
    void autoConfigurationIsAnnotatedWithAutoConfiguration() {
        assertNotNull(UploadFileAutoConfiguration.class.getAnnotation(AutoConfiguration.class),
                "jakarta auto-configuration must use @AutoConfiguration (Boot 3/4 style)");
    }

    @Test
    void servletTwinExtendsJakartaServletTypes() {
        assertEquals("jakarta.servlet.http.HttpServlet", UploadServlet.class.getSuperclass().getName(),
                "upload-file-servlet-jakarta must compile against the jakarta servlet API");
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
