/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.model;

/**
 * Status of an async merge, used as the response of the {@code mergeStatus} endpoint.
 *
 * <p>The state follows the machine {@code NONE -> PENDING -> RUNNING -> SUCCEEDED/FAILED};
 * old metadata without the merge fields is reported as {@code NONE}.</p>
 */
public class MergeStatus {

    private String identifier;
    private String state;
    private String message;
    private boolean merged;
    private String finalPath;
    private long finalFileSize;

    public static MergeStatus from(UploadTask task) {
        MergeStatus s = new MergeStatus();
        s.setIdentifier(task.getIdentifier());
        s.setState(task.mergeState());
        if (task.isMerged()) {
            s.setMerged(true);
            s.setFinalPath(task.getFinalPath());
            s.setFinalFileSize(task.getFinalFileSize());
            // A synchronously merged task has no merge-state fields; report it as SUCCEEDED.
            if (UploadTask.MERGE_STATE_NONE.equals(task.mergeState())) {
                s.setState(UploadTask.MERGE_STATE_SUCCEEDED);
            }
        }
        s.setMessage(task.getMergeError());
        return s;
    }

    public static MergeStatus none(String identifier) {
        MergeStatus s = new MergeStatus();
        s.setIdentifier(identifier);
        s.setState(UploadTask.MERGE_STATE_NONE);
        return s;
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isMerged() {
        return merged;
    }

    public void setMerged(boolean merged) {
        this.merged = merged;
    }

    public String getFinalPath() {
        return finalPath;
    }

    public void setFinalPath(String finalPath) {
        this.finalPath = finalPath;
    }

    public long getFinalFileSize() {
        return finalFileSize;
    }

    public void setFinalFileSize(long finalFileSize) {
        this.finalFileSize = finalFileSize;
    }
}
