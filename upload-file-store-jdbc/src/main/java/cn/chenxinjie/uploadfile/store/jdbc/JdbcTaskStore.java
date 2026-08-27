/*
 * Copyright (c) 2026 chenxinjie
 *
 * SPDX-License-Identifier: MIT
 */

package cn.chenxinjie.uploadfile.store.jdbc;

import cn.chenxinjie.uploadfile.core.model.UploadTask;
import cn.chenxinjie.uploadfile.core.store.TaskStore;
import cn.chenxinjie.uploadfile.core.util.StringUtil;
import com.google.gson.Gson;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * JDBC-backed {@link TaskStore} on a table
 * {@code upload_task(identifier, data, create_time, update_time)} with JSON serialization (reusing Gson).
 *
 * <p>The table is created automatically when an {@code initSql} is supplied. {@link #DEFAULT_INIT_SQL}
 * uses portable DDL (H2-compatible); for MySQL use {@code data TEXT}, for PostgreSQL {@code data TEXT}.</p>
 */
public class JdbcTaskStore implements TaskStore {

    public static final String DEFAULT_INIT_SQL =
            "CREATE TABLE IF NOT EXISTS %s (identifier VARCHAR(255) PRIMARY KEY, "
                    + "data CLOB, create_time BIGINT, update_time BIGINT)";

    private final DataSource dataSource;
    private final String tableName;
    private final Gson gson = new Gson();

    public JdbcTaskStore(DataSource dataSource) {
        this(dataSource, "upload_task", null);
    }

    public JdbcTaskStore(DataSource dataSource, String tableName) {
        this(dataSource, tableName, null);
    }

    public JdbcTaskStore(DataSource dataSource, String tableName, String initSql) {
        this.dataSource = dataSource;
        this.tableName = tableName;
        if (initSql != null && !initSql.trim().isEmpty()) {
            executeInit(initSql);
        }
    }

    private void executeInit(String initSql) {
        String sql;
        if (initSql == null || initSql.trim().isEmpty()) {
            sql = String.format(DEFAULT_INIT_SQL, tableName);
        } else if (initSql.contains("%s")) {
            sql = String.format(initSql, tableName);
        } else {
            // A fully specified statement needs no table-name substitution.
            sql = initSql;
        }
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize task table: " + sql, e);
        }
    }

    @Override
    public Optional<UploadTask> get(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return Optional.empty();
        }
        StringUtil.requireSafeIdentifier(identifier);
        String sql = "SELECT data FROM " + tableName + " WHERE identifier = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, identifier);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String json = rs.getString("data");
                UploadTask task = gson.fromJson(json, UploadTask.class);
                if (task == null) {
                    return Optional.empty();
                }
                if (task.getUploadedChunks() == null) {
                    task.setUploadedChunks(new TreeSet<>());
                }
                return Optional.of(task);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read task metadata: " + identifier, e);
        }
    }

    @Override
    public void save(UploadTask task) {
        StringUtil.requireSafeIdentifier(task.getIdentifier());
        String json = gson.toJson(task);
        String delete = "DELETE FROM " + tableName + " WHERE identifier = ?";
        String insert = "INSERT INTO " + tableName
                + " (identifier, data, create_time, update_time) VALUES (?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection()) {
            // Delete + insert in one transaction keeps the upsert portable across databases.
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(delete)) {
                    ps.setString(1, task.getIdentifier());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(insert)) {
                    ps.setString(1, task.getIdentifier());
                    ps.setString(2, json);
                    ps.setLong(3, task.getCreateTime());
                    ps.setLong(4, task.getUpdateTime());
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save task metadata: " + task.getIdentifier(), e);
        }
    }

    @Override
    public boolean remove(String identifier) {
        StringUtil.requireSafeIdentifier(identifier);
        String sql = "DELETE FROM " + tableName + " WHERE identifier = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, identifier);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete task metadata: " + identifier, e);
        }
    }

    @Override
    public Collection<UploadTask> list() {
        String sql = "SELECT data FROM " + tableName;
        List<UploadTask> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    UploadTask task = gson.fromJson(rs.getString("data"), UploadTask.class);
                    if (task != null) {
                        if (task.getUploadedChunks() == null) {
                            task.setUploadedChunks(new TreeSet<>());
                        }
                        result.add(task);
                    }
                } catch (RuntimeException ignored) {
                    // Skip a corrupt record without failing the whole list operation.
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list task metadata: " + tableName, e);
        }
        return result;
    }

    public String getTableName() {
        return tableName;
    }
}
