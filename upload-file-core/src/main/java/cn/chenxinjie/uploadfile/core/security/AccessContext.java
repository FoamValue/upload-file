/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.security;

import java.util.Objects;

/**
 * Immutable request context carried alongside an access-control decision (rc.8, G21).
 *
 * <p>Before rc.8 an {@link AccessControlListener} only saw the identifier/action/decision, so an
 * audit row could not record the HTTP method, URI, client address or user agent — unlike a login
 * audit. The servlet layer now fills an {@link AccessContext} for the current request (via
 * {@link AccessContextHolder}) and the core services pass it to the listener, so an audit hook can
 * persist the same fields as any other audit trail.</p>
 *
 * <p>For pure-core / MVC callers there is no HTTP request; {@link #EMPTY} (all fields {@code null})
 * is used and no exception is raised. Instances are immutable and safe to share.</p>
 */
public final class AccessContext {

    /** The empty context used when no HTTP request is available. */
    public static final AccessContext EMPTY = new AccessContext(null, null, null, null);

    private final String method;
    private final String uri;
    private final String remoteAddr;
    private final String userAgent;

    public AccessContext(String method, String uri, String remoteAddr, String userAgent) {
        this.method = method;
        this.uri = uri;
        this.remoteAddr = remoteAddr;
        this.userAgent = userAgent;
    }

    /** HTTP method (e.g. {@code POST}); {@code null} outside an HTTP request. */
    public String getMethod() {
        return method;
    }

    /** Request URI (e.g. {@code /upload}); {@code null} outside an HTTP request. */
    public String getUri() {
        return uri;
    }

    /** Client address as seen by the container; {@code null} outside an HTTP request. */
    public String getRemoteAddr() {
        return remoteAddr;
    }

    /** {@code User-Agent} header; {@code null} when absent or outside an HTTP request. */
    public String getUserAgent() {
        return userAgent;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AccessContext)) {
            return false;
        }
        AccessContext other = (AccessContext) o;
        return Objects.equals(method, other.method)
                && Objects.equals(uri, other.uri)
                && Objects.equals(remoteAddr, other.remoteAddr)
                && Objects.equals(userAgent, other.userAgent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, uri, remoteAddr, userAgent);
    }

    @Override
    public String toString() {
        return "AccessContext{method=" + method + ", uri=" + uri
                + ", remoteAddr=" + remoteAddr + ", userAgent=" + userAgent + '}';
    }
}
