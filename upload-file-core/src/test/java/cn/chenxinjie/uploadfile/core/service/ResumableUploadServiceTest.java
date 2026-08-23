/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.service;

import cn.chenxinjie.uploadfile.core.exception.ChecksumMismatchException;
import cn.chenxinjie.uploadfile.core.model.ChunkUploadRequest;
import cn.chenxinjie.uploadfile.core.model.UploadProgress;
import cn.chenxinjie.uploadfile.core.model.UploadResult;
import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.storage.LocalFileChunkStorage;
import cn.chenxinjie.uploadfile.core.store.FileTaskStore;
import cn.chenxinjie.uploadfile.core.util.ChecksumUtil;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ResumableUploadServiceTest {

    private static final int CHUNK_SIZE = 1024;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ResumableUploadService service;
    private File mergedDir;

    @Before
    public void setUp() throws IOException {
        mergedDir = new File(folder.getRoot(), "files");
        service = new ResumableUploadService(
                new FileTaskStore(new File(folder.getRoot(), "meta").toPath()),
                new LocalFileChunkStorage(new File(folder.getRoot(), "chunks").toPath()),
                mergedDir);
    }

    private ChunkUploadRequest request(String id, int index, int total) {
        ChunkUploadRequest req = new ChunkUploadRequest();
        req.setIdentifier(id);
        req.setFileName("demo.bin");
        req.setFileSize(CHUNK_SIZE * total);
        req.setChunkSize(CHUNK_SIZE);
        req.setChunkTotal(total);
        req.setChunkIndex(index);
        return req;
    }

    @Test
    public void uploadAllChunksAndMerge() throws Exception {
        int total = 4;
        byte[] data = new byte[CHUNK_SIZE * total];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 251);
        }

        for (int i = 0; i < total; i++) {
            byte[] chunk = Arrays.copyOfRange(data, i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE);
            UploadProgress p = service.uploadChunk(request("f1", i, total), new ByteArrayInputStream(chunk));
            assertEquals(i + 1, p.getUploadedCount());
            assertTrue(service.isChunkUploaded("f1", i));
        }

        assertEquals(100, service.getProgress("f1").getProgressPercent());

        UploadResult result = service.merge("f1");
        assertTrue(result.isSuccess());
        assertTrue(result.isMerged());
        File finalFile = new File(result.getFinalPath());
        assertTrue(finalFile.isFile());
        assertArrayEquals(data, Files.readAllBytes(finalFile.toPath()));
        assertEquals(CHUNK_SIZE * total, result.getFinalFileSize());
    }

    @Test
    public void reuploadSameChunkIsIdempotent() throws Exception {
        byte[] chunk = "data".getBytes(StandardCharsets.UTF_8);
        service.uploadChunk(request("f2", 0, 2), new ByteArrayInputStream(chunk));
        UploadProgress p = service.uploadChunk(request("f2", 0, 2), new ByteArrayInputStream(chunk));
        assertEquals(1, p.getUploadedCount());
    }

    @Test
    public void mergeWithoutAllChunksFails() throws Exception {
        service.uploadChunk(request("f3", 0, 4), new ByteArrayInputStream("a".getBytes()));
        service.uploadChunk(request("f3", 1, 4), new ByteArrayInputStream("b".getBytes()));
        assertThrows(IllegalStateException.class, () -> service.merge("f3"));
    }

    @Test
    public void checksumMismatchRejectsChunk() throws Exception {
        ChunkUploadRequest req = request("f4", 0, 2);
        req.setChunkMd5("00000000000000000000000000000000");
        assertThrows(ChecksumMismatchException.class,
                () -> service.uploadChunk(req, new ByteArrayInputStream("bad".getBytes())));
        assertFalse(service.isChunkUploaded("f4", 0));
        assertEquals(0, service.getProgress("f4").getUploadedCount());
    }

    @Test
    public void validReuploadAfterMismatchSucceeds() throws Exception {
        byte[] chunk = "ok-data".getBytes(StandardCharsets.UTF_8);
        ChunkUploadRequest bad = request("f7", 0, 2);
        bad.setChunkMd5("00000000000000000000000000000000");
        assertThrows(ChecksumMismatchException.class,
                () -> service.uploadChunk(bad, new ByteArrayInputStream(chunk)));

        ChunkUploadRequest good = request("f7", 0, 2);
        good.setChunkMd5(ChecksumUtil.md5(chunk));
        UploadProgress p = service.uploadChunk(good, new ByteArrayInputStream(chunk));
        assertEquals(1, p.getUploadedCount());
        assertTrue(service.isChunkUploaded("f7", 0));
    }

    @Test
    public void pathTraversalFileNameRejected() {
        ChunkUploadRequest req = request("f8", 0, 1);
        req.setFileName("../evil.txt");
        assertThrows(IllegalArgumentException.class,
                () -> service.uploadChunk(req, new ByteArrayInputStream("x".getBytes())));
    }

    @Test
    public void blankFileNameRejected() {
        ChunkUploadRequest req = request("f9", 0, 1);
        req.setFileName("");
        assertThrows(IllegalArgumentException.class,
                () -> service.uploadChunk(req, new ByteArrayInputStream("x".getBytes())));
    }

    @Test
    public void mergeRejectsUnsafeFileName() throws Exception {
        byte[] chunk = "data".getBytes(StandardCharsets.UTF_8);
        service.uploadChunk(request("f10", 0, 1), new ByteArrayInputStream(chunk));

        UploadTask task = service.getTaskStore().get("f10").get();
        task.setFileName("../../escape.bin");
        service.getTaskStore().save(task);

        assertThrows(IllegalArgumentException.class, () -> service.merge("f10"));
    }

    @Test
    public void checksumMatchAcceptsChunk() throws Exception {
        byte[] chunk = "ok-data".getBytes(StandardCharsets.UTF_8);
        ChunkUploadRequest req = request("f5", 0, 2);
        req.setChunkMd5(ChecksumUtil.md5(chunk));
        UploadProgress p = service.uploadChunk(req, new ByteArrayInputStream(chunk));
        assertEquals(1, p.getUploadedCount());
    }

    @Test
    public void missingIdentifierRejected() {
        ChunkUploadRequest req = new ChunkUploadRequest();
        req.setChunkIndex(0);
        req.setChunkTotal(1);
        assertThrows(IllegalArgumentException.class,
                () -> service.uploadChunk(req, new ByteArrayInputStream("x".getBytes())));
    }

    @Test
    public void pathTraversalIdentifierRejected() {
        ChunkUploadRequest req = request("../evil", 0, 1);
        assertThrows(IllegalArgumentException.class,
                () -> service.uploadChunk(req, new ByteArrayInputStream("x".getBytes())));
    }

    @Test
    public void outOfRangeChunkIndexRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.uploadChunk(request("f6", 5, 2), new ByteArrayInputStream("x".getBytes())));
    }

    @Test
    public void progressForUnknownIdentifierIsEmpty() {
        UploadProgress p = service.getProgress("nope");
        assertEquals(0, p.getUploadedCount());
        assertFalse(p.isMerged());
    }

    @Test
    public void mergeUnknownIdentifierFails() {
        assertThrows(java.util.NoSuchElementException.class, () -> service.merge("nope"));
    }
}
