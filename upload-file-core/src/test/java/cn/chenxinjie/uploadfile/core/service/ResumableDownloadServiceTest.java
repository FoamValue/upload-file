/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.service;

import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ResumableDownloadServiceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File mergedDir;
    private TaskStore store;
    private ResumableDownloadService service;

    @Before
    public void setUp() throws Exception {
        mergedDir = new File(folder.getRoot(), "files");
        store = new MemoryTaskStore();
        service = new ResumableDownloadService(store, mergedDir);
    }

    private UploadTask mergedTask(String id, String fileName, String finalPath) {
        UploadTask task = new UploadTask();
        task.setIdentifier(id);
        task.setFileName(fileName);
        task.setMerged(true);
        task.setFinalPath(finalPath);
        return task;
    }

    @Test
    public void resolveFileByFinalPath() throws Exception {
        File file = new File(mergedDir, "f1");
        Files.createDirectories(file.toPath());
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        File target = new File(file, "demo.txt");
        Files.write(target.toPath(), data);
        store.save(mergedTask("f1", "demo.txt", target.getAbsolutePath()));

        Optional<File> resolved = service.resolveFile("f1");
        assertTrue(resolved.isPresent());
        assertArrayEquals(data, Files.readAllBytes(resolved.get().toPath()));
    }

    @Test
    public void resolveFileByFallbackPath() throws Exception {
        File file = new File(mergedDir, "f2");
        Files.createDirectories(file.toPath());
        byte[] data = "fallback".getBytes(StandardCharsets.UTF_8);
        Files.write(new File(file, "demo.txt").toPath(), data);
        store.save(mergedTask("f2", "demo.txt", null));

        assertTrue(service.resolveFile("f2").isPresent());
    }

    @Test
    public void resolveFileUnknownIdentifierIsEmpty() {
        assertFalse(service.resolveFile("nope").isPresent());
    }

    @Test
    public void writeRangeWritesCorrectBytes() throws Exception {
        File file = new File(mergedDir, "f3");
        Files.createDirectories(file.toPath());
        File target = new File(file, "demo.txt");
        Files.write(target.toPath(), "hello world".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long written = service.writeRange(target, 6, 5, out);
        assertEquals(5, written);
        assertEquals("world", new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void resolveFileNameFallsBackToIdentifier() {
        assertEquals("f4", service.resolveFileName("f4"));

        store.save(mergedTask("f5", "demo.txt", null));
        assertEquals("demo.txt", service.resolveFileName("f5"));
    }
}
