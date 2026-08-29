/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.store;

import cn.chenxinjie.uploadfile.core.model.UploadTask;

import java.util.Objects;

/**
 * Copies upload-task metadata from one {@link TaskStore} to another (e.g. {@link FileTaskStore} →
 * {@link JdbcTaskStore} / {@link RedisTaskStore}) so a store switch does not lose in-flight tasks.
 *
 * <p>Migration is an explicit, idempotent operation: records are re-saved into the target, so running
 * it twice yields the same result. Records whose {@code schemaVersion} is newer than the current
 * format are skipped to avoid writing data the current reader cannot interpret.</p>
 */
public final class TaskStoreMigrator {

    /** The maximum schema version the current code understands. */
    public static final int MAX_SUPPORTED_SCHEMA_VERSION = UploadTask.CURRENT_SCHEMA_VERSION;

    private final TaskStore target;

    /**
     * Creates a migrator that writes into the given target store (e.g. the store currently selected
     * by {@code upload-file.metadata-store}).
     */
    public TaskStoreMigrator(TaskStore target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    /**
     * Migrates all tasks from the given source store into this migrator's target.
     *
     * @return the number of tasks copied
     */
    public int migrate(TaskStore source) {
        return migrate(source, target);
    }

    /**
     * Migrates all tasks from {@code source} to {@code target} (upsert semantics).
     *
     * @return the number of tasks copied
     */
    public static int migrate(TaskStore source, TaskStore target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        int copied = 0;
        for (UploadTask task : source.list()) {
            String identifier = task.getIdentifier();
            UploadTask current = source.get(identifier).orElse(null);
            if (current == null) {
                continue;
            }
            if (current.getSchemaVersion() > MAX_SUPPORTED_SCHEMA_VERSION) {
                continue;
            }
            target.save(current);
            copied++;
        }
        return copied;
    }
}
