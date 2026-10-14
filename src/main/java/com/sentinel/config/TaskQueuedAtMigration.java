package com.sentinel.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * 给已有库补上 t_task.queued_at。新库的 schema.sql 已经包含该列，重复执行无效果。
 * 与历史上改 schema.sql 的方式一致：结构以 schema.sql 为准，启动时把缺的列补上，避免开发库被重建。
 */
@Slf4j
@Component
public class TaskQueuedAtMigration {

    static final String TABLE = "t_task";
    static final String COLUMN = "queued_at";

    private final DataSource dataSource;

    public TaskQueuedAtMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void migrate() {
        try (Connection connection = dataSource.getConnection()) {
            ensureQueuedAt(connection);
        } catch (SQLException ex) {
            throw new IllegalStateException("t_task.queued_at 迁移失败", ex);
        }
    }

    static void ensureQueuedAt(Connection connection) throws SQLException {
        if (connection == null || !tableExists(connection, TABLE)) {
            log.info("t_task 不存在，跳过 queued_at 迁移");
            return;
        }
        if (columnExists(connection, TABLE, COLUMN)) {
            return;
        }
        String ddl = mysql(connection)
                ? "ALTER TABLE t_task ADD COLUMN queued_at DATETIME NULL COMMENT '进入排队的时间，出队后清空'"
                : "ALTER TABLE t_task ADD COLUMN queued_at DATETIME NULL";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(ddl);
            log.info("已为 t_task 添加 queued_at");
        } catch (SQLException ex) {
            if (duplicateColumn(ex) || columnExists(connection, TABLE, COLUMN)) {
                log.info("t_task.queued_at 已存在，跳过");
                return;
            }
            throw ex;
        }
    }

    static boolean tableExists(Connection connection, String table) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        if (hasTable(meta, connection.getCatalog(), table)) {
            return true;
        }
        return hasTable(meta, null, table);
    }

    static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        if (hasColumn(meta, connection.getCatalog(), table, column)) {
            return true;
        }
        return hasColumn(meta, null, table, column);
    }

    private static boolean hasTable(DatabaseMetaData meta, String catalog, String table) throws SQLException {
        try (ResultSet rows = meta.getTables(catalog, null, "%", new String[]{"TABLE"})) {
            while (rows.next()) {
                if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasColumn(DatabaseMetaData meta, String catalog, String table, String column)
            throws SQLException {
        try (ResultSet rows = meta.getColumns(catalog, null, "%", "%")) {
            while (rows.next()) {
                if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))
                        && column.equalsIgnoreCase(rows.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean mysql(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        return product != null && product.toLowerCase(Locale.ROOT).contains("mysql");
    }

    static boolean duplicateColumn(SQLException ex) {
        if (ex == null) {
            return false;
        }
        if (ex.getErrorCode() == 1060) {
            return true;
        }
        String message = ex.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("duplicate column") || lower.contains("already exists");
    }
}
