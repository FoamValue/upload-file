/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.store;

import cn.chenxinjie.uploadfile.core.model.UploadTask;

import java.util.Objects;

/**
 * Default {@link QuotaStore} that recomputes usage from the {@link TaskStore} on every call,
 * reproducing the rc.6 quota behaviour exactly (approximate, lock-free). {@link #release(String)}
 * is a no-op because usage is derived from the store rather than tracked.
 */
public class TaskStoreQuotaStore implements QuotaStore {

    private final TaskStore taskStore;

    public TaskStoreQuotaStore(TaskStore taskStore) {
        this.taskStore = Objects.requireNonNull(taskStore, "taskStore");
    }

    @Override
    public boolean tryReserve(String identifier, long bytes, long limitBytes) {
        if (limitBytes <= 0) {
            return true;
        }
        long used = 0;
        for (UploadTask task : taskStore.list()) {
            if (identifier != null && identifier.equals(task.getIdentifier())) {
                continue;
            }
            used += sizeOf(task);
        }
        return used + Math.max(0, bytes) <= limitBytes;
    }

    @Override
    public void release(String identifier) {
        // Usage is derived from the task store; nothing to release.
    }

    @Override
    public long usedBytes() {
        long used = 0;
        for (UploadTask task : taskStore.list()) {
            used += sizeOf(task);
        }
        return used;
    }

    private static long sizeOf(UploadTask task) {
        return Math.max(0, task.isMerged() ? task.getFinalFileSize() : task.getFileSize());
    }
}
