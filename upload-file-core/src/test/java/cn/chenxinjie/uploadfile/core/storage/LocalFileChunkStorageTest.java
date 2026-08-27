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
import java.io.File;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
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

    @Test
    public void listIdentifiersListsChunkDirs() throws Exception {
        LocalFileChunkStorage storage = new LocalFileChunkStorage(folder.getRoot().toPath());
        storage.saveChunk("f1", 0, new ByteArrayInputStream("a".getBytes()));
        storage.saveChunk("f2", 1, new ByteArrayInputStream("b".getBytes()));

        Set<String> identifiers = storage.listIdentifiers();
        assertEquals(new HashSet<>(Arrays.asList("f1", "f2")), identifiers);
    }

    @Test
    public void stringConstructorWorks() throws Exception {
        LocalFileChunkStorage storage = new LocalFileChunkStorage(folder.getRoot().getAbsolutePath());
        storage.saveChunk("s1", 0, new ByteArrayInputStream("x".getBytes()));
        assertTrue(storage.chunkExists("s1", 0));
    }

    @Test
    public void constructorRejectsPathThatIsAFile() throws Exception {
        File blocker = new File(folder.getRoot(), "blocker");
        Files.write(blocker.toPath(), new byte[1]);
        assertThrows(UncheckedIOException.class, () -> new LocalFileChunkStorage(blocker.toPath()));
    }

    @Test
    public void listChunksIgnoresNonNumericFiles() throws Exception {
        LocalFileChunkStorage storage = new LocalFileChunkStorage(folder.getRoot().toPath());
        storage.saveChunk("f1", 1, new ByteArrayInputStream("1".getBytes()));
        // Leftover temp files or stray files in the chunk dir must be ignored.
        java.nio.file.Path chunkDir = folder.getRoot().toPath().resolve("f1");
        Files.write(chunkDir.resolve("abc.part"), new byte[1]);
        Files.write(chunkDir.resolve(".upload-xyz.part"), new byte[1]);

        List<Integer> chunks = storage.listChunks("f1");
        assertEquals(Arrays.asList(1), chunks);
    }

    @Test
    public void deleteChunkOnNonEmptyDirectoryThrows() throws Exception {
        LocalFileChunkStorage storage = new LocalFileChunkStorage(folder.getRoot().toPath());
        java.nio.file.Path dir = folder.getRoot().toPath().resolve("d1");
        java.nio.file.Path chunk = dir.resolve("0.part");
        Files.createDirectories(chunk);
        Files.write(chunk.resolve("child"), new byte[1]);
        assertThrows(UncheckedIOException.class, () -> storage.deleteChunk("d1", 0));
    }

    @Test
    public void listIdentifiersWhenRootIsAFileReturnsEmpty() throws Exception {
        LocalFileChunkStorage storage = new LocalFileChunkStorage(folder.getRoot().toPath());
        File root = folder.getRoot();
        Files.delete(root.toPath());
        Files.write(root.toPath(), new byte[1]);
        assertTrue(storage.listIdentifiers().isEmpty());
    }

    @Test
    public void deleteChunksFailurePropagates() throws Exception {
        LocalFileChunkStorage storage = new LocalFileChunkStorage(folder.getRoot().toPath());
        java.nio.file.Path dir = folder.getRoot().toPath().resolve("h");
        java.nio.file.Path sub = dir.resolve("sub");
        Files.createDirectories(sub);
        Files.write(sub.resolve("x"), new byte[1]);
        sub.toFile().setWritable(false);
        try {
            assertThrows(UncheckedIOException.class, () -> storage.deleteChunks("h"));
        } finally {
            sub.toFile().setWritable(true);
        }
    }
}
