/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.store;

import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.util.StringUtil;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory metadata store. Data is lost on restart; suitable for single-node, tests,
 * or scenarios that do not require persistence.
 */
public class MemoryTaskStore implements TaskStore {

    private final Map<String, UploadTask> tasks = new ConcurrentHashMap<>();

    @Override
    public Optional<UploadTask> get(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return Optional.empty();
        }
        StringUtil.requireSafeIdentifier(identifier);
        return Optional.ofNullable(tasks.get(identifier));
    }

    @Override
    public void save(UploadTask task) {
        StringUtil.requireSafeIdentifier(task.getIdentifier());
        tasks.put(task.getIdentifier(), task);
    }

    @Override
    public boolean remove(String identifier) {
        StringUtil.requireSafeIdentifier(identifier);
        return tasks.remove(identifier) != null;
    }

    @Override
    public Collection<UploadTask> list() {
        return tasks.values();
    }
}
