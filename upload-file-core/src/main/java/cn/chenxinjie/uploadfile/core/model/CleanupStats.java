/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.model;

/**
 * Statistics of a single storage-cleanup pass, exposed for observability (queryable bean) and
 * passed to the optional stats listener for structured logging.
 */
public class CleanupStats {

    private long lastRunTime;
    private long cleanedTasks;
    private long cleanedOrphans;
    private long elapsedMillis;
    private String error;

    public long getLastRunTime() {
        return lastRunTime;
    }

    public void setLastRunTime(long lastRunTime) {
        this.lastRunTime = lastRunTime;
    }

    public long getCleanedTasks() {
        return cleanedTasks;
    }

    public void setCleanedTasks(long cleanedTasks) {
        this.cleanedTasks = cleanedTasks;
    }

    public long getCleanedOrphans() {
        return cleanedOrphans;
    }

    public void setCleanedOrphans(long cleanedOrphans) {
        this.cleanedOrphans = cleanedOrphans;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public void setElapsedMillis(long elapsedMillis) {
        this.elapsedMillis = elapsedMillis;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public boolean isFailed() {
        return error != null;
    }
}
