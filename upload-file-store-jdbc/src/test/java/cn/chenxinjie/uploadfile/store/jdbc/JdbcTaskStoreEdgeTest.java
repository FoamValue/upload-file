/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.store.jdbc;

import cn.chenxinjie.uploadfile.core.model.UploadTask;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Edge coverage for {@link JdbcTaskStore}: the read path must surface a missing table as a
 * stable {@link IllegalStateException} instead of leaking the raw {@code SQLException}, and the
 * save path must never let an auto-commit restoration failure mask the original error (L1).
 */
public class JdbcTaskStoreEdgeTest {

    @Test
    public void getThrowsWhenTableDoesNotExist() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:jdbc-edge-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        // initSql = null skips table creation, so the metadata table does not exist.
        JdbcTaskStore store = new JdbcTaskStore(dataSource, "missing_upload_task", null);

        assertThrows(IllegalStateException.class, () -> store.get("any-id"));
    }

    @Test
    public void saveSurfacesOriginalErrorWhenAutoCommitRestoreFails() {
        // A statement failure is the "original" error; the auto-commit restore in the finally
        // block also fails. The original error must propagate, never be masked (L1).
        AtomicReference<Boolean> autoCommit = new AtomicReference<>(true);
        Connection conn = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAutoCommit":
                            return autoCommit.get();
                        case "setAutoCommit":
                            autoCommit.set((Boolean) args[0]);
                            if (args[0].equals(Boolean.TRUE)) {
                                // Restoring auto-commit fails; this must not mask the original error.
                                throw new SQLException("auto-commit restore failed");
                            }
                            return null;
                        case "prepareStatement":
                            // The original failure the caller must observe.
                            throw new SQLException("statement failed");
                        case "rollback":
                        case "commit":
                        case "close":
                            return null;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
        DataSource ds = (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> method.getName().equals("getConnection") ? conn : null);

        JdbcTaskStore store = new JdbcTaskStore(ds, "upload_task", null);
        UploadTask task = new UploadTask();
        task.setIdentifier("l1");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> store.save(task));
        assertTrue(ex.getCause() instanceof SQLException);
        assertEquals("statement failed", ex.getCause().getMessage());
    }
}
