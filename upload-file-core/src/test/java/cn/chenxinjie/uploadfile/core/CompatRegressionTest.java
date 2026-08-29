/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core;

import cn.chenxinjie.uploadfile.core.model.UploadProgress;
import cn.chenxinjie.uploadfile.core.model.UploadResult;
import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.service.ResumableDownloadService;
import cn.chenxinjie.uploadfile.core.service.ResumableUploadService;
import cn.chenxinjie.uploadfile.core.service.StorageCleanupService;
import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import cn.chenxinjie.uploadfile.core.store.FileTaskStore;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.util.IdentifierLock;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Compat regression suite (T12): boots the current code against rc.2-style metadata JSON and
 * directory layout and verifies the behavior under the default configuration is unchanged, and
 * that the rc.2 high-risk cleanup combinations are safe by default.
 */
public class CompatRegressionTest {

    private static final long RC2_TIME = 1_750_000_000_000L;

    /** rc.2 JSON: no {@code schemaVersion}, no merge-state fields (Gson omitted the nulls). */
    private static final String RC2_JSON = "{\n"
            + "  \"identifier\": \"rc2file\",\n"
            + "  \"fileName\": \"demo.bin\",\n"
            + "  \"fileSize\": 10,\n"
            + "  \"chunkSize\": 10,\n"
            + "  \"chunkTotal\": 1,\n"
            + "  \"uploadedChunks\": [0],\n"
            + "  \"merged\": false,\n"
            + "  \"finalFileSize\": 0,\n"
            + "  \"createTime\": " + RC2_TIME + ",\n"
            + "  \"updateTime\": " + RC2_TIME + "\n"
            + "}";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** Writes the rc.2 directory layout: chunks/<id>/<i>.part and meta/<id>.json. */
    private File writeRc2Layout(String data) throws Exception {
        File root = folder.getRoot();
        File meta = new File(root, "meta");
        assertTrue(meta.mkdirs());
        Files.write(new File(meta, "rc2file.json").toPath(), RC2_JSON.getBytes(StandardCharsets.UTF_8));
        File chunkDir = new File(root, "chunks/rc2file");
        assertTrue(chunkDir.mkdirs());
        Files.write(new File(chunkDir, "0.part").toPath(), data.getBytes(StandardCharsets.UTF_8));
        return root;
    }

    @Test
    public void rc2MetadataLoadsWithSchemaVersionNormalizedAndMergeNone() throws Exception {
        File root = writeRc2Layout("0123456789");
        FileTaskStore store = new FileTaskStore(new File(root, "meta").toPath());

        UploadTask task = store.get("rc2file").get();

        // The rc.2 record carries no schemaVersion -> normalized to the current version on load.
        assertEquals(UploadTask.CURRENT_SCHEMA_VERSION, task.getSchemaVersion());
        assertEquals(UploadTask.MERGE_STATE_NONE, task.mergeState());
        assertEquals(1, task.uploadedCount());
    }

    @Test
    public void rc2LayoutProgressMergeAndDownloadWorkUnderDefaults() throws Exception {
        File root = writeRc2Layout("0123456789");
        FileTaskStore store = new FileTaskStore(new File(root, "meta").toPath());
        LocalFileChunkStorage chunks = new LocalFileChunkStorage(new File(root, "chunks").toPath());
        File mergedDir = new File(root, "files");

        // Defaults: verify checksum on, atomic+fsync merge on, no access control.
        ResumableUploadService upload = new ResumableUploadService(store, chunks, mergedDir, true, true, true,
                new IdentifierLock());

        UploadProgress progress = upload.getProgress("rc2file");
        assertEquals(1, progress.getUploadedCount());
        assertEquals(100, progress.getProgressPercent());

        UploadResult result = upload.merge("rc2file");
        assertTrue(result.isSuccess());
        assertTrue(result.isMerged());
        assertEquals(10, result.getFinalFileSize());

        ResumableDownloadService download = new ResumableDownloadService(store, mergedDir);
        Optional<File> file = download.resolveFile("rc2file");
        assertTrue(file.isPresent());
        assertEquals("demo.bin", download.resolveFileName("rc2file"));
    }

    @Test
    public void defaultConfigDoesNotDeleteLongIdleTasks() throws Exception {
        // C1: TTL cleanup is off by default, so a long-idle rc.2 in-flight task survives a restart.
        File root = writeRc2Layout("0123456789");
        FileTaskStore store = new FileTaskStore(new File(root, "meta").toPath());
        LocalFileChunkStorage chunks = new LocalFileChunkStorage(new File(root, "chunks").toPath());

        // cleanup disabled (default): no scheduler is started, so the task can never be silently deleted.
        StorageCleanupService cleanup = new StorageCleanupService(store, chunks, new File(root, "files"),
                1, false, new IdentifierLock());
        assertFalse(cleanup.isRunning());

        assertTrue(store.get("rc2file").isPresent());
        assertTrue(chunks.chunkExists("rc2file", 0));
    }

    @Test
    public void memoryStoreSkipsOrphanGcSoRc2DiskDataSurvives() throws Exception {
        // C2: with a MemoryTaskStore (the default when no metadata-dir is set), the orphan GC must
        // never delete on-disk data, otherwise a restart would wipe every upload.
        File root = folder.getRoot();
        File meta = new File(root, "meta");
        assertTrue(meta.mkdirs());
        Files.write(new File(meta, "orphan.json").toPath(), RC2_JSON.getBytes(StandardCharsets.UTF_8));
        File chunkDir = new File(root, "chunks/orphan");
        assertTrue(chunkDir.mkdirs());
        Files.write(new File(chunkDir, "0.part").toPath(), "x".getBytes(StandardCharsets.UTF_8));

        StorageCleanupService cleanup = new StorageCleanupService(
                new MemoryTaskStore(), new LocalFileChunkStorage(new File(root, "chunks").toPath()),
                new File(root, "files"), 3600_000L, true, new IdentifierLock());
        cleanup.cleanup();

        assertTrue(Files.exists(new File(chunkDir, "0.part").toPath()));
    }

    @Test
    public void uploadChunkRoundTripWithDefaultChunkSizeUsesRc2Defaults() throws Exception {
        FileTaskStore store = new FileTaskStore(new File(folder.getRoot(), "meta5").toPath());
        LocalFileChunkStorage chunks = new LocalFileChunkStorage(new File(folder.getRoot(), "chunks5").toPath());
        ResumableUploadService upload = new ResumableUploadService(
                store, chunks, new File(folder.getRoot(), "files5"), true, true, true);

        UploadProgress p = upload.uploadChunk(req("new1", 0, 1), new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, p.getUploadedCount());
        // schemaVersion is stamped on newly created tasks.
        assertEquals(UploadTask.CURRENT_SCHEMA_VERSION, store.get("new1").get().getSchemaVersion());
    }

    private static cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest req(String id, int index, int total) {
        cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest req =
                new cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest();
        req.setIdentifier(id);
        req.setFileName("demo.bin");
        req.setFileSize(3);
        req.setChunkSize(3);
        req.setChunkTotal(total);
        req.setChunkIndex(index);
        return req;
    }
}
