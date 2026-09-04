/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.store.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;

import static org.junit.Assert.assertThrows;

/**
 * Edge coverage for {@link JdbcTaskStore}: the read path must surface a missing table as a
 * stable {@link IllegalStateException} instead of leaking the raw {@code SQLException}.
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
}
