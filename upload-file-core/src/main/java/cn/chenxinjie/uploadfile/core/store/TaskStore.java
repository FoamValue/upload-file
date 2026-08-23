/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.store;

import cn.chenxinjie.uploadfile.core.model.UploadTask;

import java.util.Collection;
import java.util.Optional;

/**
 * SPI for storing upload-task metadata.
 *
 * <p>Built-in implementations: {@link MemoryTaskStore} (in-memory),
 * {@link FileTaskStore} (local files + JSON).</p>
 * <p>Implement this interface to back the store with Redis, a database, or other storage.</p>
 */
public interface TaskStore {

    Optional<UploadTask> get(String identifier);

    void save(UploadTask task);

    boolean remove(String identifier);

    Collection<UploadTask> list();
}
