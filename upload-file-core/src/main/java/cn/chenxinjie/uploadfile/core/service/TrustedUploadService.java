/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.service;

import cn.chenxinjie.uploadfile.core.model.MergeStatus;
import cn.chenxinjie.uploadfile.core.model.UploadProgress;
import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.store.TaskStore;

import java.util.Objects;
import java.util.Optional;

/**
 * Read-only facade exposing the <b>un-gated</b> (trusted) reads of {@link ResumableUploadService}
 * (rc.7).
 *
 * <p>{@link ResumableUploadService} exposes both token-gated reads (for HTTP boundaries) and
 * un-gated reads (for the trusted server-side confirm flow). The un-gated variants are intentionally
 * isolated here so that using them is explicit and compile-time visible, instead of accidentally
 * calling an un-gated method at a boundary. Inject this facade in trusted application code and use
 * the token overloads on the service at HTTP boundaries.</p>
 */
public final class TrustedUploadService {

    private final ResumableUploadService delegate;

    public TrustedUploadService(ResumableUploadService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** Un-gated progress read. */
    public UploadProgress getProgress(String identifier) {
        return delegate.getProgressTrusted(identifier);
    }

    /** Un-gated task read (the stable confirm-phase read). */
    public Optional<UploadTask> getTask(String identifier) {
        return delegate.getTaskTrusted(identifier);
    }

    /** Un-gated chunk-presence read. */
    public boolean isChunkUploaded(String identifier, int chunkIndex) {
        return delegate.isChunkUploadedTrusted(identifier, chunkIndex);
    }

    /** Un-gated merge-status read. */
    public MergeStatus getMergeStatus(String identifier) {
        return delegate.getMergeStatusTrusted(identifier);
    }

    /** The underlying task store. */
    public TaskStore getTaskStore() {
        return delegate.getTaskStore();
    }
}
