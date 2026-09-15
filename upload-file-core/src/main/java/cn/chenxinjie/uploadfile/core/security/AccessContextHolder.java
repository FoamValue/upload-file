/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.security;

/**
 * Thread-local holder of the current {@link AccessContext} (rc.8, G21).
 *
 * <p>The integration layer (the upload/download servlets) sets the context when a request enters and
 * clears it in a {@code finally} block; the core services read it while notifying
 * {@link AccessControlListener}s. When nothing is set (pure-core/MVC usage), {@link #current()}
 * returns {@link AccessContext#EMPTY} so listeners never see {@code null}.</p>
 *
 * <p>Callers that set a context must clear it in a {@code finally} block to avoid leaking it onto a
 * pooled request thread.</p>
 */
public final class AccessContextHolder {

    private static final ThreadLocal<AccessContext> CURRENT = new ThreadLocal<>();

    private AccessContextHolder() {
    }

    /** Returns the context for the current thread, or {@link AccessContext#EMPTY} when none is set. */
    public static AccessContext current() {
        AccessContext context = CURRENT.get();
        return context == null ? AccessContext.EMPTY : context;
    }

    /** Sets the context for the current thread; {@code null} clears it. */
    public static void set(AccessContext context) {
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
    }

    /** Clears the context for the current thread. */
    public static void clear() {
        CURRENT.remove();
    }
}
