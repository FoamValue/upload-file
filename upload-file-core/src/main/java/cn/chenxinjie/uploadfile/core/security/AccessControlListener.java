/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.security;

/**
 * Listener notified after every {@link AccessControl} decision (rc.6).
 *
 * <p>Hooks let an integration (e.g. an MVC app that already has a session and an audit log)
 * observe allow/deny on every entry point — upload/progress/merge/async-merge/status/cancel
 * and download — through one path, instead of hand-wiring an auditor at each deny point.
 * The core services notify all registered listeners before a denial is raised, so the MVC
 * and Servlet paths see identical events.</p>
 */
public interface AccessControlListener {

    /**
     * Called after an access decision has been made.
     *
     * @param identifier  the file identifier under check (may be null)
     * @param action      one of the {@code AccessControl.ACTION_*} constants
     * @param decision    the decision (allow or deny with status/reason)
     * @param elapsedNanos duration of the decision itself
     */
    void onDecision(String identifier, String action, AccessDecision decision, long elapsedNanos);
}
