/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.demo;

import cn.chenxinjie.uploadfile.core.store.TaskStore;
import cn.chenxinjie.uploadfile.store.jdbc.JdbcTaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code jdbc} store profile: the app boots with {@code upload-file-store-jdbc}
 * on an embedded H2 datasource (no external service needed), so CI covers the JDBC store wiring
 * without a database server. The {@code redis} profile needs a real Redis and is exercised
 * manually (see README).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "upload-file.storage-dir=target/demo-test/jdbc-upload",
                "upload-file.metadata-dir=target/demo-test/jdbc-upload/meta"
        })
@ActiveProfiles("jdbc")
class JdbcStoreProfileSmokeTest {

    @LocalServerPort
    private int port;

    private final RestTemplate rest = new RestTemplate();

    @Autowired
    private TaskStore taskStore;

    @Test
    void usesTheJdbcStore() {
        assertThat(taskStore).isInstanceOf(JdbcTaskStore.class);
    }

    @Test
    void servesTheDemoPage() {
        ResponseEntity<String> response = rest.getForEntity("http://localhost:" + port + "/", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
