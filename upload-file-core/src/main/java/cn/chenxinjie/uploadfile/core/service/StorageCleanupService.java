/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.service;

import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.storage.ChunkStorage;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.store.TaskStore;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Background governance service implementing:
 * <ul>
 *   <li><b>Expired-task cleanup (TTL/GC)</b>: removes incomplete tasks (and their chunks) that have not
 *       been updated for longer than the TTL; {@code taskTtlMillis <= 0} means never clean;</li>
 *   <li><b>Orphan-data GC</b> (opt-in): under the chunk root and the merged-file dir, directories with no
 *       matching task record are treated as orphans and removed.</li>
 * </ul>
 *
 * <p>The core stays free of framework dependencies; an optional {@link Consumer} can be registered to
 * observe cleanup errors (e.g. wired to a logger by the integration module).</p>
 */
public class StorageCleanupService {

    private final TaskStore taskStore;
    private final ChunkStorage chunkStorage;
    private final File mergedFileDir;
    private final long taskTtlMillis;
    private final boolean orphanEnabled;

    private ScheduledExecutorService scheduler = newScheduler();

    private volatile boolean started;
    private volatile Consumer<Throwable> errorListener;

    private static ScheduledExecutorService newScheduler() {
        return new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "upload-file-storage-cleanup");
            t.setDaemon(true);
            return t;
        });
    }

    public StorageCleanupService(TaskStore taskStore, ChunkStorage chunkStorage, File mergedFileDir,
                                 long taskTtlMillis, boolean orphanEnabled) {
        this.taskStore = Objects.requireNonNull(taskStore, "taskStore");
        this.chunkStorage = Objects.requireNonNull(chunkStorage, "chunkStorage");
        this.mergedFileDir = Objects.requireNonNull(mergedFileDir, "mergedFileDir");
        this.taskTtlMillis = taskTtlMillis;
        this.orphanEnabled = orphanEnabled;
    }

    /**
     * Registers a listener invoked (in the scheduler thread) whenever a cleanup run fails.
     */
    public void setErrorListener(Consumer<Throwable> errorListener) {
        this.errorListener = errorListener;
    }

    /**
     * Schedules periodic cleanup runs; safe to call multiple times (idempotent).
     *
     * @param intervalMillis period between runs; must be &gt; 0 to start
     */
    public synchronized void start(long intervalMillis) {
        if (started || intervalMillis <= 0) {
            return;
        }
        if (scheduler.isShutdown()) {
            // A previous stop() shut the scheduler down; recreate it so the service is restartable.
            scheduler = newScheduler();
        }
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                cleanup();
            } catch (Throwable t) {
                Consumer<Throwable> listener = errorListener;
                if (listener != null) {
                    listener.accept(t);
                }
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        started = true;
    }

    /**
     * Shuts the scheduler down; no further runs are executed.
     */
    public synchronized void stop() {
        scheduler.shutdownNow();
        started = false;
    }

    public boolean isRunning() {
        return started;
    }

    /**
     * Runs a single cleanup pass synchronously (expired tasks, then orphans when enabled).
     */
    public void cleanup() {
        cleanupExpiredTasks();
        if (orphanEnabled) {
            cleanupOrphans();
        }
    }

    private void cleanupExpiredTasks() {
        if (taskTtlMillis <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        Collection<UploadTask> tasks = taskStore.list();
        for (UploadTask task : tasks) {
            if (task.isMerged()) {
                continue;
            }
            long updateTime = task.getUpdateTime();
            if (updateTime > 0 && now - updateTime > taskTtlMillis) {
                String identifier = task.getIdentifier();
                // Remove the chunks first so no orphan chunk data is left behind.
                chunkStorage.deleteChunks(identifier);
                taskStore.remove(identifier);
            }
        }
    }

    private void cleanupOrphans() {
        if (taskStore instanceof MemoryTaskStore) {
            // With an in-memory store all task records are lost on restart, so every on-disk dir
            // would look like an orphan and be deleted; never run the orphan scan against it.
            return;
        }
        Set<String> known = new HashSet<>();
        for (UploadTask task : taskStore.list()) {
            known.add(task.getIdentifier());
        }
        // Orphan chunks: on-disk chunk dirs with no task record.
        for (String identifier : chunkStorage.listIdentifiers()) {
            if (!known.contains(identifier)) {
                chunkStorage.deleteChunks(identifier);
            }
        }
        // Orphan merged files: merged-file dirs with no task record.
        if (mergedFileDir.isDirectory()) {
            File[] children = mergedFileDir.listFiles(File::isDirectory);
            if (children != null) {
                for (File child : children) {
                    if (!known.contains(child.getName())) {
                        deleteDirectory(child.toPath());
                    }
                }
            }
        }
    }

    private static void deleteDirectory(java.nio.file.Path dir) {
        try (Stream<java.nio.file.Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete directory: " + dir, e);
        }
    }
}
