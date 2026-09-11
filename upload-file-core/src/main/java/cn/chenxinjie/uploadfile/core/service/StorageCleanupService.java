/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.service;

import cn.chenxinjie.uploadfile.core.model.CleanupStats;
import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.storage.ChunkStorage;
import cn.chenxinjie.uploadfile.core.store.MemoryTaskStore;
import cn.chenxinjie.uploadfile.core.store.QuotaStore;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import cn.chenxinjie.uploadfile.core.util.CleanupLock;
import cn.chenxinjie.uploadfile.core.util.IdentifierLock;
import cn.chenxinjie.uploadfile.core.util.IdentifierLockHandle;
import cn.chenxinjie.uploadfile.core.util.IdentifierLockProvider;
import cn.chenxinjie.uploadfile.core.util.StripedIdentifierLockProvider;

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
 *
 * <p>When a {@link CleanupLock} is supplied, each scheduled pass first acquires the lease and is skipped
 * when another instance already holds it, so multi-instance deployments do not run duplicate cleanup.</p>
 */
public class StorageCleanupService {

    private final TaskStore taskStore;
    private final ChunkStorage chunkStorage;
    private final File mergedFileDir;
    private final long taskTtlMillis;
    private final boolean orphanEnabled;
    private volatile IdentifierLockProvider identifierLockProvider;
    private final CleanupLock cleanupLock;
    private volatile QuotaStore quotaStore;

    private ScheduledExecutorService scheduler = newScheduler();

    private volatile boolean started;
    private volatile Consumer<Throwable> errorListener;
    private volatile Consumer<CleanupStats> statsListener;
    private volatile CleanupStats lastStats = new CleanupStats();

    private static ScheduledExecutorService newScheduler() {
        return new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "upload-file-storage-cleanup");
            t.setDaemon(true);
            return t;
        });
    }

    public StorageCleanupService(TaskStore taskStore, ChunkStorage chunkStorage, File mergedFileDir,
                                 long taskTtlMillis, boolean orphanEnabled) {
        this(taskStore, chunkStorage, mergedFileDir, taskTtlMillis, orphanEnabled, null, null);
    }

    /**
     * Creates the service with an optional shared lock; pass the same instance used by the
     * {@link ResumableUploadService} so cleanup is mutually exclusive with in-flight uploads.
     */
    public StorageCleanupService(TaskStore taskStore, ChunkStorage chunkStorage, File mergedFileDir,
                                 long taskTtlMillis, boolean orphanEnabled, IdentifierLock identifierLock) {
        this(taskStore, chunkStorage, mergedFileDir, taskTtlMillis, orphanEnabled, identifierLock, null);
    }

    /**
     * Creates the service with a shared identifier lock and an optional distributed {@link CleanupLock}.
     */
    public StorageCleanupService(TaskStore taskStore, ChunkStorage chunkStorage, File mergedFileDir,
                                 long taskTtlMillis, boolean orphanEnabled, IdentifierLock identifierLock,
                                 CleanupLock cleanupLock) {
        this.taskStore = Objects.requireNonNull(taskStore, "taskStore");
        this.chunkStorage = Objects.requireNonNull(chunkStorage, "chunkStorage");
        this.mergedFileDir = Objects.requireNonNull(mergedFileDir, "mergedFileDir");
        this.taskTtlMillis = taskTtlMillis;
        this.orphanEnabled = orphanEnabled;
        this.identifierLockProvider = identifierLock == null
                ? new StripedIdentifierLockProvider()
                : new StripedIdentifierLockProvider(identifierLock);
        this.cleanupLock = cleanupLock;
    }

    /**
     * Replaces the identifier-lock provider (rc.7); pass the same provider used by the
     * {@link ResumableUploadService} so cleanup stays mutually exclusive with in-flight uploads.
     */
    public void setIdentifierLockProvider(IdentifierLockProvider identifierLockProvider) {
        if (identifierLockProvider != null) {
            this.identifierLockProvider = identifierLockProvider;
        }
    }

    /**
     * Optional quota store (rc.7); when set, removing an expired task releases its reservation.
     */
    public void setQuotaStore(QuotaStore quotaStore) {
        this.quotaStore = quotaStore;
    }

    /**
     * Registers a listener invoked (in the scheduler thread) whenever a cleanup run fails.
     */
    public void setErrorListener(Consumer<Throwable> errorListener) {
        this.errorListener = errorListener;
    }

    /**
     * Registers a listener invoked after each completed cleanup pass with its statistics.
     */
    public void setStatsListener(Consumer<CleanupStats> statsListener) {
        this.statsListener = statsListener;
    }

    /**
     * Returns a snapshot of the most recent cleanup statistics (initially all zero).
     */
    public CleanupStats getLastStats() {
        CleanupStats snapshot = lastStats;
        CleanupStats copy = new CleanupStats();
        copy.setLastRunTime(snapshot.getLastRunTime());
        copy.setCleanedTasks(snapshot.getCleanedTasks());
        copy.setCleanedOrphans(snapshot.getCleanedOrphans());
        copy.setElapsedMillis(snapshot.getElapsedMillis());
        copy.setError(snapshot.getError());
        return copy;
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
                recordFailure(t);
                Consumer<Throwable> listener = errorListener;
                if (listener != null) {
                    listener.accept(t);
                }
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        started = true;
    }

    private void recordFailure(Throwable t) {
        CleanupStats failed = new CleanupStats();
        failed.setLastRunTime(System.currentTimeMillis());
        failed.setError(t.toString());
        lastStats = failed;
        Consumer<CleanupStats> listener = statsListener;
        if (listener != null) {
            listener.accept(failed);
        }
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
     *
     * <p>When a distributed {@link CleanupLock} is configured and it cannot be acquired (another
     * instance is cleaning), the pass is skipped without touching any data.</p>
     */
    public void cleanup() {
        CleanupStats run = new CleanupStats();
        run.setLastRunTime(System.currentTimeMillis());
        long startedAt = System.currentTimeMillis();
        if (cleanupLock != null && !cleanupLock.tryAcquire()) {
            // Another instance holds the lease; skip this round entirely.
            return;
        }
        try {
            cleanupExpiredTasks(run);
            if (orphanEnabled) {
                cleanupOrphans(run);
            }
        } finally {
            if (cleanupLock != null) {
                cleanupLock.release();
            }
        }
        run.setElapsedMillis(System.currentTimeMillis() - startedAt);
        lastStats = run;
        Consumer<CleanupStats> listener = statsListener;
        if (listener != null) {
            listener.accept(run);
        }
    }

    private void cleanupExpiredTasks(CleanupStats run) {
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
                try (IdentifierLockHandle lockHandle = identifierLockProvider.lock(identifier)) {
                    // Re-check under the lock so a task that was refreshed by an in-flight upload
                    // while we were scanning is not wrongly deleted.
                    UploadTask current = taskStore.get(identifier).orElse(null);
                    if (current != null && !current.isMerged()
                            && current.getUpdateTime() > 0 && now - current.getUpdateTime() > taskTtlMillis) {
                        // Remove the chunks first so no orphan chunk data is left behind.
                        chunkStorage.deleteChunks(identifier);
                        taskStore.remove(identifier);
                        if (quotaStore != null) {
                            quotaStore.release(identifier);
                        }
                        run.setCleanedTasks(run.getCleanedTasks() + 1);
                    }
                }
            }
        }
    }

    private void cleanupOrphans(CleanupStats run) {
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
            if (known.contains(identifier)) {
                continue;
            }
            try (IdentifierLockHandle lockHandle = identifierLockProvider.lock(identifier)) {
                // Re-check after acquiring the lock: a task may have been created while scanning.
                if (!hasTask(identifier)) {
                    chunkStorage.deleteChunks(identifier);
                    run.setCleanedOrphans(run.getCleanedOrphans() + 1);
                }
            }
        }
        // Orphan merged files: merged-file dirs with no task record.
        if (mergedFileDir.isDirectory()) {
            File[] children = mergedFileDir.listFiles(File::isDirectory);
            if (children != null) {
                for (File child : children) {
                    String identifier = child.getName();
                    if (known.contains(identifier)) {
                        continue;
                    }
                    try (IdentifierLockHandle lockHandle = identifierLockProvider.lock(identifier)) {
                        if (!hasTask(identifier)) {
                            deleteDirectory(child.toPath());
                            run.setCleanedOrphans(run.getCleanedOrphans() + 1);
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns whether a task exists for the identifier; an on-disk name that is not a valid
     * identifier (e.g. contains a path separator) can never be a real task, so it is treated
     * as absent without letting the lookup fail the whole cleanup run.
     */
    private boolean hasTask(String identifier) {
        try {
            return taskStore.get(identifier).isPresent();
        } catch (IllegalArgumentException e) {
            return false;
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
