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

    /**
     * Called after an access decision has been made, carrying the request context (rc.8, G21).
     *
     * <p>This is the method the core services invoke. Its default implementation bridges to the
     * legacy {@link #onDecision(String, String, AccessDecision, long)} so an existing listener that
     * only implements the 5-argument form keeps compiling and behaving exactly as before; a listener
     * that wants method/URI/IP/User-Agent overrides this overload instead.</p>
     *
     * @param context     the request context, never {@code null} ({@link AccessContext#EMPTY} for
     *                    pure-core/MVC callers with no HTTP request)
     * @param identifier  the file identifier under check (may be null)
     * @param action      one of the {@code AccessControl.ACTION_*} constants
     * @param decision    the decision (allow or deny with status/reason)
     * @param elapsedNanos duration of the decision itself
     */
    default void onDecision(AccessContext context, String identifier, String action,
                            AccessDecision decision, long elapsedNanos) {
        onDecision(identifier, action, decision, elapsedNanos);
    }
}
