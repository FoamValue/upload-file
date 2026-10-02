/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.demo;

import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demo-level smoke test: boots the real application on a random port and verifies that the
 * static page is served and the core beans are wired. It runs as part of the normal
 * {@code mvn test} build, so the demo modules are covered by CI too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "upload-file.storage-dir=target/demo-test/upload",
                "upload-file.metadata-dir=target/demo-test/upload/meta"
        })
class DemoApplicationSmokeTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ResumableUploadService uploadService;

    @Autowired
    private TaskStore taskStore;

    @Test
    void servesTheDemoPage() {
        ResponseEntity<String> response = rest.getForEntity("/", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Large-File Chunked Upload");
    }

    @Test
    void coreBeansAreWired() {
        assertThat(uploadService).isNotNull();
        assertThat(taskStore).isNotNull();
    }
}
