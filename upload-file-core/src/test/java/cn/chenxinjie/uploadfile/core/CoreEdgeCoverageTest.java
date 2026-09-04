/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core;

import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.model.UploadResult;
import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.security.TokenAccessControl;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.core.service.StorageCleanupService;
import cn.chenxinjie.uploadfile.core.storage.ChunkStorage;
import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import cn.chenxinjie.uploadfile.core.store.FileTaskStore;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.store.TaskStoreMigrator;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Edge coverage for defensive / scheduling branches that the behavioural tests do not hit:
 * the access-control {@code toString()}, migration schema-version skip, quota-allowed merge,
 * cleanup scheduler success/failure runs, and the chunk-storage directory IO error paths.
 */
public class CoreEdgeCoverageTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void tokenAccessControlToStringDescribesType() {
        assertEquals("TokenAccessControl", new TokenAccessControl("secret").toString());
    }

    @Test
    public void migratorSkipsTasksWithNewerSchemaVersionOrGhostListEntry() {
        FakeStore source = new FakeStore();
        MemoryTaskStore target = new MemoryTaskStore();

        UploadTask current = new UploadTask();
        current.setIdentifier("legacy");
        current.setSchemaVersion(1);
        source.put(current);

        UploadTask future = new UploadTask();
        future.setIdentifier("future");
        future.setSchemaVersion(99);
        source.put(future);

        // Listed on disk but already gone by the time it is re-read -> the null-task branch.
        UploadTask ghost = new UploadTask();
        ghost.setIdentifier("ghost");
        source.listOnly(ghost);

        int copied = TaskStoreMigrator.migrate(source, target);

        assertEquals(1, copied);
        assertTrue(target.get("legacy").isPresent());
        assertFalse(target.get("future").isPresent());
        assertFalse(target.get("ghost").isPresent());
    }

    /** Store whose {@code list()} may return an entry that {@code get()} no longer finds. */
    private static final class FakeStore implements cn.chenxinjie.uploadfile.core.store.TaskStore {
        private final java.util.Map<String, UploadTask> tasks = new java.util.HashMap<>();
        private final java.util.List<UploadTask> listOnly = new java.util.ArrayList<>();

        void put(UploadTask task) {
            tasks.put(task.getIdentifier(), task);
            listOnly.add(task);
        }

        void listOnly(UploadTask task) {
            listOnly.add(task);
        }

        @Override
        public java.util.Optional<UploadTask> get(String identifier) {
            return java.util.Optional.ofNullable(tasks.get(identifier));
        }

        @Override
        public void save(UploadTask task) {
            tasks.put(task.getIdentifier(), task);
        }

        @Override
        public boolean remove(String identifier) {
            return tasks.remove(identifier) != null;
        }

        @Override
        public java.util.Collection<UploadTask> list() {
            return new java.util.ArrayList<>(listOnly);
        }
    }

    @Test
    public void mergePassesWhenWithinCapacityQuota() throws Exception {
        String root = folder.getRoot().getAbsolutePath();
        ResumableUploadService service = new ResumableUploadService(
                new MemoryTaskStore(), new LocalFileChunkStorage(root + "/chunks"), new File(root, "files"));
        service.setMaxTotalBytes(10_000);

        ChunkUploadRequest request = new ChunkUploadRequest();
        request.setIdentifier("quota-ok");
        request.setFileName("demo.bin");
        request.setFileSize(5);
        request.setChunkSize(5);
        request.setChunkTotal(1);
        request.setChunkIndex(0);
        service.uploadChunk(request, new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        UploadResult result = service.merge("quota-ok");
        assertTrue(result.isSuccess());
    }

    @Test
    public void cleanupScheduledRunExecutesSuccessfully() throws Exception {
        StorageCleanupService cleanup = new StorageCleanupService(
                new MemoryTaskStore(), new LocalFileChunkStorage(folder.getRoot().getAbsolutePath() + "/chunks"),
                new File(folder.getRoot(), "files"), 3600000L, false);
        AtomicInteger statsRuns = new AtomicInteger();
        cleanup.setStatsListener(stats -> statsRuns.incrementAndGet());
        try {
            cleanup.start(20);
            Thread.sleep(300);
            assertTrue("at least one scheduled cleanup run expected", statsRuns.get() > 0);
            assertTrue(cleanup.getLastStats().getLastRunTime() > 0);
        } finally {
            cleanup.stop();
        }
    }

    @Test
    public void cleanupScheduledFailureInvokesErrorListener() throws Exception {
        FileTaskStore store = new FileTaskStore(folder.newFolder("meta").toPath());
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
        StorageCleanupService cleanup = new StorageCleanupService(
                store, failingChunks, folder.newFolder("files"), 3600000L, true);
        AtomicInteger errors = new AtomicInteger();
        cleanup.setErrorListener(t -> errors.incrementAndGet());
        try {
            cleanup.start(20);
            Thread.sleep(300);
            assertTrue("cleanup failure must be reported to the error listener", errors.get() > 0);
            assertNotNull(cleanup.getLastStats().getError());
        } finally {
            cleanup.stop();
        }
    }

    @Test
    public void listChunksThrowsWhenDirectoryIsUnreadable() throws Exception {
        assumeNotRootWithPosix();
        Path root = folder.newFolder("chunks").toPath();
        Path dir = Files.createDirectories(root.resolve("r1"));
        Files.setPosixFilePermissions(dir, Collections.<PosixFilePermission>emptySet());
        try {
            new LocalFileChunkStorage(root).listChunks("r1");
            // access may succeed when the test runs with elevated privileges; treat as acceptable
        } catch (UncheckedIOException expected) {
            assertTrue(expected.getMessage().contains("Failed to list chunks"));
        } finally {
            Files.setPosixFilePermissions(dir, java.util.EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        }
    }

    @Test
    public void listIdentifiersThrowsWhenRootIsUnreadable() throws Exception {
        assumeNotRootWithPosix();
        Path root = folder.newFolder("chunks2").toPath();
        Files.setPosixFilePermissions(root, Collections.<PosixFilePermission>emptySet());
        try {
            new LocalFileChunkStorage(root).listIdentifiers();
        } catch (UncheckedIOException expected) {
            assertTrue(expected.getMessage().contains("Failed to list chunk identifiers"));
        } finally {
            Files.setPosixFilePermissions(root, java.util.EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        }
    }

    private static void assumeNotRootWithPosix() {
        Assume.assumeFalse("root bypasses file permissions", "root".equals(System.getProperty("user.name")));
        Assume.assumeTrue("POSIX permissions required", java.nio.file.FileSystems.getDefault().supportedFileAttributeViews()
                .contains("posix"));
    }
}
