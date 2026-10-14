package com.sentinel.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 用 JDBC isValid 检查数据库，超时 2 秒。
 */
@Slf4j
@Component
public class DbHealthChecker implements ComponentHealthChecker {

    static final int VALIDATION_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    public DbHealthChecker(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String name() {
        return DB;
    }

    @Override
    public boolean up() {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            if (connection == null) {
                return false;
            }
            return connection.isValid(VALIDATION_TIMEOUT_SECONDS);
        } catch (Exception e) {
            log.warn("Database health check failed: {}", e.getClass().getSimpleName());
            return false;
        } finally {
            close(connection);
        }
    }

    private static void close(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (Exception e) {
            log.warn("Database connection close failed: {}", e.getClass().getSimpleName());
        }
    }
}
