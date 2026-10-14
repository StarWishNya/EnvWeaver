package com.sentinel.config;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * queued_at 启动迁移。用内存库，不碰共享 MySQL。
 */
@DisplayName("t_task.queued_at 迁移")
class TaskQueuedAtMigrationTest {

    @Test
    @DisplayName("没有 t_task 时跳过")
    void skipsWhenTableMissing() throws Exception {
        try (Connection connection = open("missing").getConnection()) {
            assertThat(TaskQueuedAtMigration.tableExists(connection, "t_task")).isFalse();
            assertThatCode(() -> TaskQueuedAtMigration.ensureQueuedAt(connection)).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("缺列时补上，再执行一次不报错")
    void addsColumnAndIsIdempotent() throws Exception {
        JdbcDataSource dataSource = open("add-column");
        try (Connection connection = dataSource.getConnection()) {
            execute(connection, "CREATE TABLE t_task (id BIGINT PRIMARY KEY)");
            assertThat(TaskQueuedAtMigration.columnExists(connection, "t_task", "queued_at")).isFalse();

            TaskQueuedAtMigration.ensureQueuedAt(connection);
            assertThat(TaskQueuedAtMigration.columnExists(connection, "t_task", "queued_at")).isTrue();

            TaskQueuedAtMigration.ensureQueuedAt(connection);
            assertThat(TaskQueuedAtMigration.columnExists(connection, "t_task", "queued_at")).isTrue();
        }
    }

    @Test
    @DisplayName("建表时已经有 queued_at 则不再改表")
    void skipsWhenColumnAlreadyPresent() throws Exception {
        try (Connection connection = open("present").getConnection()) {
            execute(connection, "CREATE TABLE t_task (id BIGINT PRIMARY KEY, queued_at TIMESTAMP)");
            assertThatCode(() -> TaskQueuedAtMigration.ensureQueuedAt(connection)).doesNotThrowAnyException();
            assertThat(TaskQueuedAtMigration.columnExists(connection, "t_task", "queued_at")).isTrue();
        }
    }

    @Test
    @DisplayName("重复列错误码和文案都视为已存在")
    void detectsDuplicateColumn() {
        assertThat(TaskQueuedAtMigration.duplicateColumn(new SQLException("Duplicate column name", "42S21", 1060)))
                .isTrue();
        assertThat(TaskQueuedAtMigration.duplicateColumn(new SQLException("column already exists"))).isTrue();
        assertThat(TaskQueuedAtMigration.duplicateColumn(new SQLException("syntax error"))).isFalse();
        assertThat(TaskQueuedAtMigration.duplicateColumn(new SQLException((String) null))).isFalse();
        assertThat(TaskQueuedAtMigration.duplicateColumn(null)).isFalse();
    }

    private static JdbcDataSource open(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
