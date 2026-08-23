/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalFileChunkStorageTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void saveExistsAndRead() throws Exception {
        LocalFileChunkStorage storage = new LocalFileChunkStorage(folder.getRoot().toPath());
        byte[] data = "hello-chunk".getBytes(StandardCharsets.UTF_8);
        storage.saveChunk("file1", 0, new ByteArrayInputStream(data));

        assertTrue(storage.chunkExists("file1", 0));
        assertFalse(storage.chunkExists("file1", 1));
        assertTrue(storage.getChunkFile("file1", 0).isFile());
        assertEquals("hello-chunk", new String(
                java.nio.file.Files.readAllBytes(storage.getChunkFile("file1", 0).toPath()),
                StandardCharsets.UTF_8));
    }

    @Test
    public void listChunksSorted() throws Exception {
        LocalFileChunkStorage storage = new LocalFileChunkStorage(folder.getRoot().toPath());
        storage.saveChunk("file1", 3, new ByteArrayInputStream("3".getBytes()));
        storage.saveChunk("file1", 0, new ByteArrayInputStream("0".getBytes()));
        storage.saveChunk("file1", 1, new ByteArrayInputStream("1".getBytes()));

        List<Integer> chunks = storage.listChunks("file1");
        assertEquals(Arrays.asList(0, 1, 3), chunks);
    }

    @Test
    public void deleteChunkAndAll() throws Exception {
        LocalFileChunkStorage storage = new LocalFileChunkStorage(folder.getRoot().toPath());
        storage.saveChunk("file1", 0, new ByteArrayInputStream("0".getBytes()));
        storage.saveChunk("file1", 1, new ByteArrayInputStream("1".getBytes()));

        storage.deleteChunk("file1", 0);
        assertFalse(storage.chunkExists("file1", 0));
        assertTrue(storage.chunkExists("file1", 1));

        storage.deleteChunks("file1");
        assertFalse(storage.chunkExists("file1", 1));
    }

    @Test
    public void deleteNonExistentChunkIsNoOp() {
        LocalFileChunkStorage storage = new LocalFileChunkStorage(folder.getRoot().toPath());
        storage.deleteChunk("file1", 0); // must not throw
        assertFalse(storage.chunkExists("file1", 0));
    }

    @Test
    public void deleteChunksOnMissingDirIsNoOp() {
        LocalFileChunkStorage storage = new LocalFileChunkStorage(folder.getRoot().toPath());
        storage.deleteChunks("never-created"); // must not throw
    }

    @Test
    public void listChunksUnknownIdentifierIsEmpty() {
        LocalFileChunkStorage storage = new LocalFileChunkStorage(folder.getRoot().toPath());
        assertTrue(storage.listChunks("nope").isEmpty());
    }
}
