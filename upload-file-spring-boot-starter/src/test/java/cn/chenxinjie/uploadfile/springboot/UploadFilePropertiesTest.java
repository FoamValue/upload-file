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
    }
}
