/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.service;

import cn.chenxinjie.uploadfile.core.exception.AccessDeniedException;
import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.security.AccessControl;
import cn.chenxinjie.uploadfile.core.security.AccessControlListener;
import cn.chenxinjie.uploadfile.core.security.AccessDecision;
import cn.chenxinjie.uploadfile.core.security.PermitAllAccessControl;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import cn.chenxinjie.uploadfile.core.util.StringUtil;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Core resumable-download service, reading byte ranges based on HTTP Range.
 */
public class ResumableDownloadService {

    private static final int BUFFER_SIZE = 8192;

    private final TaskStore taskStore;
    private final File mergedFileDir;
    private volatile AccessControl accessControl = PermitAllAccessControl.INSTANCE;

    /** Access-decision observers (rc.6); notified before a denial is raised. */
    private final CopyOnWriteArrayList<AccessControlListener> accessControlListeners =
            new CopyOnWriteArrayList<>();

    public ResumableDownloadService(TaskStore taskStore, File mergedFileDir) {
        this.taskStore = Objects.requireNonNull(taskStore, "taskStore");
        this.mergedFileDir = Objects.requireNonNull(mergedFileDir, "mergedFileDir");
    }

    /**
     * Replaces the access-control policy; defaults to {@link PermitAllAccessControl} (no-op).
     */
    public void setAccessControl(AccessControl accessControl) {
        this.accessControl = Objects.requireNonNull(accessControl, "accessControl");
    }

    /**
     * Registers an access-decision listener (rc.6); see {@code ResumableUploadService#addAccessControlListener}.
     */
    public void addAccessControlListener(AccessControlListener listener) {
        accessControlListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Evaluates the access-control policy and notifies the registered listeners; throws
     * {@link AccessDeniedException} carrying the decision status when the operation is denied.
     */
    private void gate(String identifier, String action, String token) {
        long start = System.nanoTime();
        AccessDecision decision = accessControl.decide(identifier, action, token);
        long elapsed = System.nanoTime() - start;
        for (AccessControlListener listener : accessControlListeners) {
            listener.onDecision(identifier, action, decision, elapsed);
        }
        if (!decision.allowed()) {
            throw new AccessDeniedException(decision.statusCode(), decision.reason());
        }
    }

    /**
     * Locates the merged complete file.
     */
    public Optional<File> resolveFile(String identifier) {
        return resolveFile(identifier, null);
    }

    /**
     * Locates the merged complete file with an access token (see {@link AccessControl}).
     */
    public Optional<File> resolveFile(String identifier, String token) {
        if (StringUtil.isBlank(identifier)) {
            return Optional.empty();
        }
        gate(identifier, AccessControl.ACTION_DOWNLOAD, token);
        UploadTask task = taskStore.get(identifier).orElse(null);
        if (task == null) {
            return Optional.empty();
        }
        // Prefer the recorded final path, then fall back to the standard layout
        // ({mergedFileDir}/{identifier}/{fileName}) for robustness.
        if (StringUtil.isNotBlank(task.getFinalPath())) {
            File file = new File(task.getFinalPath());
            if (file.isFile()) {
                return Optional.of(file);
            }
        }
        if (task.isMerged() && StringUtil.isNotBlank(task.getFileName())) {
            File file = new File(mergedFileDir, identifier + File.separator + task.getFileName());
            if (file.isFile()) {
                return Optional.of(file);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves the file name used for the download (for Content-Disposition).
     */
    public String resolveFileName(String identifier) {
        return resolveFileName(identifier, null);
    }

    /**
     * Resolves the file name used for the download with an access token (see {@link AccessControl}).
     */
    public String resolveFileName(String identifier, String token) {
        gate(identifier, AccessControl.ACTION_DOWNLOAD, token);
        UploadTask task = taskStore.get(identifier).orElse(null);
        return task != null && StringUtil.isNotBlank(task.getFileName()) ? task.getFileName() : identifier;
    }

    /**
     * Writes the bytes of the file in the range {@code [start, start + length)} to the output stream.
     *
     * @return the number of bytes actually written
     */
    public long writeRange(File file, long start, long length, OutputStream out) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            skipFully(in, start); // jump to the requested offset
            byte[] buffer = new byte[BUFFER_SIZE];
            long remaining = length;
            while (remaining > 0) {
                // Copy exactly `length` bytes (the requested range) to the output stream.
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (n < 0) {
                    break;
                }
                out.write(buffer, 0, n);
                remaining -= n;
            }
            return length - remaining;
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long skipped = 0;
        while (skipped < n) {
            long s = in.skip(n - skipped);
            if (s <= 0) {
                // skip() may return 0; fall back to reading a single byte until the target offset is reached.
                if (in.read() == -1) {
                    throw new EOFException("Unexpected end of file");
                }
                skipped++;
            } else {
                skipped += s;
            }
        }
    }
}
