/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.core.security;

import cn.chenxinjie.uploadfile.core.exception.AccessDeniedException;
import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class AccessControlTest {

    @Test
    public void permitAllNeverDenies() {
        PermitAllAccessControl control = PermitAllAccessControl.INSTANCE;
        control.check("id1", AccessControl.ACTION_UPLOAD, null);
        control.check(null, AccessControl.ACTION_DOWNLOAD, "whatever");
        control.check("id2", AccessControl.ACTION_MERGE, "");
    }

    @Test
    public void tokenControlAcceptsMatchingToken() {
        TokenAccessControl control = new TokenAccessControl("secret-token");
        control.check("id1", AccessControl.ACTION_UPLOAD, "secret-token");
        control.check("id2", AccessControl.ACTION_DOWNLOAD, " secret-token "); // trimmed
    }

    @Test
    public void tokenControlRejectsWrongToken() {
        TokenAccessControl control = new TokenAccessControl("secret-token");
        assertThrows(AccessDeniedException.class,
                () -> control.check("id1", AccessControl.ACTION_UPLOAD, "wrong"));
    }

    @Test
    public void tokenControlRejectsMissingToken() {
        TokenAccessControl control = new TokenAccessControl("secret-token");
        assertThrows(AccessDeniedException.class,
                () -> control.check("id1", AccessControl.ACTION_UPLOAD, null));
        assertThrows(AccessDeniedException.class,
                () -> control.check("id1", AccessControl.ACTION_UPLOAD, ""));
    }

    @Test
    public void tokenControlRejectsMissingIdentifier() {
        TokenAccessControl control = new TokenAccessControl("secret-token");
        assertThrows(AccessDeniedException.class,
                () -> control.check(null, AccessControl.ACTION_UPLOAD, "secret-token"));
        assertThrows(AccessDeniedException.class,
                () -> control.check("", AccessControl.ACTION_UPLOAD, "secret-token"));
    }

    @Test
    public void tokenControlRejectsBlankTokenAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new TokenAccessControl(""));
        assertThrows(IllegalArgumentException.class, () -> new TokenAccessControl(null));
    }

    @Test
    public void ifConfiguredReturnsPermitAllForBlankToken() {
        assertSame(PermitAllAccessControl.INSTANCE, TokenAccessControl.ifConfigured(null));
        assertSame(PermitAllAccessControl.INSTANCE, TokenAccessControl.ifConfigured("  "));
    }

    @Test
    public void ifConfiguredReturnsTokenControlForConfiguredToken() {
        assertSame(TokenAccessControl.class, TokenAccessControl.ifConfigured("t").getClass());
    }
}
