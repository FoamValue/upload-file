/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadFilePropertiesTest {

    private final UploadFileProperties properties = new UploadFileProperties();

    @Test
    void defaultsMatchDocumentation() {
        assertEquals("./upload-file-data", properties.getStorageDir());
        assertNull(properties.getMetadataDir());
        assertTrue(properties.isVerifyChecksum());
        assertEquals("/upload", properties.getUploadUrl());
        assertEquals("/download", properties.getDownloadUrl());
        assertEquals(-1L, properties.getMaxChunkSize());
        assertEquals(-1L, properties.getMaxRequestSize());
        assertEquals("auto", properties.getMetadataStore());

        assertTrue(properties.getMerge().isFsync());
        assertTrue(properties.getMerge().isAtomic());

        assertFalse(properties.getCleanup().isEnabled());
        assertFalse(properties.getCleanup().isRunOnStartup());
        assertEquals(Duration.ofHours(1), properties.getCleanup().getInterval());
        assertEquals(Duration.ofHours(24), properties.getCleanup().getTaskTtl());
        assertFalse(properties.getCleanup().isOrphanEnabled());

        assertFalse(properties.getAsyncMerge().isEnabled());
        assertEquals(2, properties.getAsyncMerge().getThreadPoolSize());

        assertEquals("upload_task", properties.getJdbc().getTableName());
        assertTrue(properties.getJdbc().getInitSql().contains("%s"));

        assertEquals("localhost", properties.getRedis().getHost());
        assertEquals(6379, properties.getRedis().getPort());
        assertNull(properties.getRedis().getPassword());
        assertEquals("upload:task:", properties.getRedis().getKeyPrefix());
        assertEquals(0, properties.getRedis().getTtlSeconds());

        // rc.3 additions
        assertEquals(-1L, properties.getMaxFileSize());
        assertFalse(properties.getSecurity().isEnabled());
        assertEquals("", properties.getSecurity().getToken());
        assertEquals("X-Access-Token", properties.getSecurity().getHeaderName());
        assertEquals(0L, properties.getQuota().getMaxBytes());
        assertFalse(properties.getCleanup().isUseRedisLock());
        assertTrue(properties.getObservability().isLogStats());
        assertFalse(properties.getMigration().isEnabled());

        // rc.6 additions
        assertTrue(properties.getEndpoint().isEnabled());
        assertTrue(properties.getEndpoint().isUploadEnabled());
        assertFalse(properties.getEndpoint().isDownloadEnabled());
        assertEquals("legacy", properties.getHttp().getErrorBody());
        assertEquals(404, properties.getHttp().getCancelNotFoundStatus());
        assertEquals("component", properties.getMultipart().getStrategy());
        assertFalse(properties.getObservability().isAccessLog());
    }

    @Test
    void settersRoundTripEveryProperty() {
        properties.setStorageDir("/tmp/store");
        properties.setMetadataDir("/tmp/meta");
        properties.setVerifyChecksum(false);
        properties.setUploadUrl("/u");
        properties.setDownloadUrl("/d");
        properties.setMaxChunkSize(1024L);
        properties.setMaxRequestSize(2048L);
        properties.setMetadataStore("redis");

        properties.getMerge().setFsync(false);
        properties.getMerge().setAtomic(false);

        properties.getCleanup().setEnabled(true);
        properties.getCleanup().setRunOnStartup(true);
        properties.getCleanup().setInterval(Duration.ofMinutes(5));
        properties.getCleanup().setTaskTtl(Duration.ofMinutes(30));
        properties.getCleanup().setOrphanEnabled(true);

        properties.getAsyncMerge().setEnabled(true);
        properties.getAsyncMerge().setThreadPoolSize(4);

        properties.getJdbc().setTableName("my_task");
        properties.getJdbc().setInitSql("CREATE TABLE t (id INT)");

        properties.getRedis().setHost("redis.example");
        properties.getRedis().setPort(6380);
        properties.getRedis().setPassword("secret");
        properties.getRedis().setKeyPrefix("up:");
        properties.getRedis().setTtlSeconds(3600);

        assertEquals("/tmp/store", properties.getStorageDir());
        assertEquals("/tmp/meta", properties.getMetadataDir());
        assertFalse(properties.isVerifyChecksum());
        assertEquals("/u", properties.getUploadUrl());
        assertEquals("/d", properties.getDownloadUrl());
        assertEquals(1024L, properties.getMaxChunkSize());
        assertEquals(2048L, properties.getMaxRequestSize());
        assertEquals("redis", properties.getMetadataStore());

        assertFalse(properties.getMerge().isFsync());
        assertFalse(properties.getMerge().isAtomic());

        assertTrue(properties.getCleanup().isEnabled());
        assertTrue(properties.getCleanup().isRunOnStartup());
        assertEquals(Duration.ofMinutes(5), properties.getCleanup().getInterval());
        assertEquals(Duration.ofMinutes(30), properties.getCleanup().getTaskTtl());
        assertTrue(properties.getCleanup().isOrphanEnabled());

        assertTrue(properties.getAsyncMerge().isEnabled());
        assertEquals(4, properties.getAsyncMerge().getThreadPoolSize());

        assertEquals("my_task", properties.getJdbc().getTableName());
        assertEquals("CREATE TABLE t (id INT)", properties.getJdbc().getInitSql());

        assertEquals("redis.example", properties.getRedis().getHost());
        assertEquals(6380, properties.getRedis().getPort());
        assertEquals("secret", properties.getRedis().getPassword());
        assertEquals("up:", properties.getRedis().getKeyPrefix());
        assertEquals(3600, properties.getRedis().getTtlSeconds());

        // rc.3 additions
        properties.setMaxFileSize(4096L);
        properties.getSecurity().setEnabled(true);
        properties.getSecurity().setToken("t0k3n");
        properties.getSecurity().setHeaderName("X-Custom");
        properties.getQuota().setMaxBytes(1_000_000L);
        properties.getCleanup().setUseRedisLock(true);
        properties.getObservability().setLogStats(false);
        properties.getMigration().setEnabled(true);

        assertEquals(4096L, properties.getMaxFileSize());
        assertTrue(properties.getSecurity().isEnabled());
        assertEquals("t0k3n", properties.getSecurity().getToken());
        assertEquals("X-Custom", properties.getSecurity().getHeaderName());
        assertEquals(1_000_000L, properties.getQuota().getMaxBytes());
        assertTrue(properties.getCleanup().isUseRedisLock());
        assertFalse(properties.getObservability().isLogStats());
        assertTrue(properties.getMigration().isEnabled());

        // rc.6 additions
        properties.getEndpoint().setEnabled(false);
        properties.getEndpoint().setUploadEnabled(false);
        properties.getEndpoint().setDownloadEnabled(true);
        properties.getHttp().setErrorBody("standard");
        properties.getHttp().setCancelNotFoundStatus(200);
        properties.getMultipart().setStrategy("unlimited");
        properties.getObservability().setAccessLog(true);

        assertFalse(properties.getEndpoint().isEnabled());
        assertFalse(properties.getEndpoint().isUploadEnabled());
        assertTrue(properties.getEndpoint().isDownloadEnabled());
        assertEquals("standard", properties.getHttp().getErrorBody());
        assertEquals(200, properties.getHttp().getCancelNotFoundStatus());
        assertEquals("unlimited", properties.getMultipart().getStrategy());
        assertTrue(properties.getObservability().isAccessLog());
    }
}
