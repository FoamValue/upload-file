/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.security;

import cn.chenxinjie.uploadfile.core.exception.AccessDeniedException;

/**
 * Convenience base class for {@link AccessControl} implementations (rc.7).
 *
 * <p>{@link AccessControl} keeps {@code check(...)} as a {@code @Deprecated} default method that
 * throws {@link UnsupportedOperationException}, so an implementation that overrides neither method
 * compiles but fails at runtime. Extending this class forces {@link #decide(String, String, String)}
 * to be implemented at compile time, and provides the legacy {@code check(...)} bridge for free.</p>
 *
 * <pre>{@code
 * AccessControl ac = new AbstractAccessControl() {
 *     @Override
 *     public AccessDecision decide(String id, String action, String token) {
 *         return ownerOf(token).equals(ownerOf(id))
 *                 ? AccessDecision.allow()
 *                 : AccessDecision.deny(403, "owner mismatch");
 *     }
 * };
 * }</pre>
 */
public abstract class AbstractAccessControl implements AccessControl {

    /**
     * Returns the decision for the operation; must be implemented by subclasses.
     */
    @Override
    public abstract AccessDecision decide(String identifier, String action, String token);

    /**
     * Bridges the legacy entry point to {@link #decide(String, String, String)}: a denial raises an
     * {@link AccessDeniedException} carrying the decision status.
     */
    @Override
    @Deprecated
    public void check(String identifier, String action, String token) throws AccessDeniedException {
        AccessDecision decision = decide(identifier, action, token);
        if (!decision.allowed()) {
            throw new AccessDeniedException(decision.statusCode(), decision.reason());
        }
    }
}
