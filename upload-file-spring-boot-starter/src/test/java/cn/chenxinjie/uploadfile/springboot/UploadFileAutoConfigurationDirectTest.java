/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.springboot;

import cn.chenxinjie.uploadfile.core.service.StorageCleanupService;
import cn.chenxinjie.uploadfile.core.storage.ChunkStorage;
import cn.chenxinjie.uploadfile.core.store.FileTaskStore;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import cn.chenxinjie.uploadfile.core.util.IdentifierLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.io.File;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct (bean-method level) coverage for wiring branches that need a failing startup or an
 * external backend, which full {@code @SpringBootTest} contexts cannot assert cheaply:
 * security fail-fast, {@code metadata-store=file} without a dir, the Redis cleanup-lock bean
 * and the migration-helper bean gating.
 */
class UploadFileAutoConfigurationDirectTest {

    private final UploadFileAutoConfiguration config = new UploadFileAutoConfiguration();

    @Test
    void securityEnabledWithoutTokenFailsFast() {
        UploadFileProperties properties = new UploadFileProperties();
        properties.getSecurity().setEnabled(true);
        assertThrows(IllegalStateException.class, () -> config.uploadFileAccessControl(properties));
    }

    @Test
    void fileMetadataStoreWithoutDirFails() {
        UploadFileProperties properties = new UploadFileProperties();
        properties.setMetadataStore("file");
        assertThrows(IllegalArgumentException.class, () -> config.uploadFileTaskStore(properties, null));
    }

    @Test
    void redisCleanupLockBeanIsWiredWhenRequested() {
        UploadFileProperties properties = new UploadFileProperties();
        assertNotNull(config.uploadFileRedisCleanupLock(properties));
    }

    @Test
    void migrationMigratorBeanIsExposed() {
        assertNotNull(config.uploadFileTaskStoreMigrator(new MemoryTaskStore()));
    }

    @Test
    void jdbcStoreWithoutDataSourceFallsBackToAutoStore() {
        UploadFileProperties properties = new UploadFileProperties();
        properties.setMetadataStore("jdbc");
        ObjectProvider<DataSource> noDataSource = new ObjectProvider<DataSource>() {
            @Override
            public DataSource getObject() {
                return null;
            }

            @Override
            public DataSource getObject(Object... args) {
                return null;
            }

            @Override
            public DataSource getIfAvailable() {
                return null;
            }

            @Override
            public DataSource getIfUnique() {
                return null;
            }
        };
        TaskStore store = config.uploadFileTaskStore(properties, noDataSource);
        assertNotNull(store);
    }

    @Test
    void isClassPresentReturnsFalseForUnknownType() throws Exception {
        Method isClassPresent = UploadFileAutoConfiguration.class
                .getDeclaredMethod("isClassPresent", String.class);
        isClassPresent.setAccessible(true);
        assertFalse((Boolean) isClassPresent.invoke(null, "cn.chenxinjie.does.not.Exist"));
    }

    @Test
    void startupCleanupFailureIsCaughtAndIgnored() throws Exception {
        File root = java.nio.file.Files.createTempDirectory("upload-file-cleanup-fail").toFile();
        try {
            UploadFileProperties properties = new UploadFileProperties();
            properties.getCleanup().setEnabled(true);
            properties.getCleanup().setRunOnStartup(true);
            properties.getCleanup().setOrphanEnabled(true);

            FileTaskStore store = new FileTaskStore(root.toPath().resolve("meta"));
            ChunkStorage failingChunks = new ChunkStorage() {
                @Override
                public void saveChunk(String identifier, int chunkIndex, java.io.InputStream in) {
                }

                @Override
                public boolean chunkExists(String identifier, int chunkIndex) {
                    return false;
                }

                @Override
                public File getChunkFile(String identifier, int chunkIndex) {
                    return new File("none");
                }

                @Override
                public List<Integer> listChunks(String identifier) {
                    return Collections.emptyList();
                }

                @Override
                public void deleteChunk(String identifier, int chunkIndex) {
                }

                @Override
                public void deleteChunks(String identifier) {
                }

                @Override
                public Set<String> listIdentifiers() {
                    throw new UncheckedIOException(new java.io.IOException("simulated IO failure"));
                }
            };
            IdentifierLock lock = new IdentifierLock();
            StorageCleanupService cleanup = config.storageCleanupService(
                    store, failingChunks, properties, lock,
                    new cn.chenxinjie.uploadfile.core.util.StripedIdentifierLockProvider(lock),
                    new cn.chenxinjie.uploadfile.core.store.TaskStoreQuotaStore(store), null);
            assertNotNull(cleanup);
            cleanup.stop();
        } finally {
            deleteTree(root.toPath());
        }
    }

    private static void deleteTree(Path dir) {
        if (!java.nio.file.Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> p.toFile().delete());
        } catch (java.io.IOException ignored) {
            // best effort
        }
    }
}
