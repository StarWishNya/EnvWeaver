package com.sentinel.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PingCmd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据库、Redis、Docker 健康检查器。失败只返回 false。
 */
@DisplayName("依赖健康检查器")
class ComponentHealthCheckerTest {

    @Test
    @DisplayName("数据库 isValid 为真时 UP")
    void databaseUpWhenConnectionIsValid() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(DbHealthChecker.VALIDATION_TIMEOUT_SECONDS)).thenReturn(true);

        DbHealthChecker checker = new DbHealthChecker(dataSource);

        assertThat(checker.name()).isEqualTo("db");
        assertThat(checker.up()).isTrue();
        verify(connection).close();
    }

    @Test
    @DisplayName("数据库无效、无连接或抛错时 DOWN")
    void databaseDownWhenCheckFails() throws Exception {
        DataSource invalid = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(invalid.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(false);
        assertThat(new DbHealthChecker(invalid).up()).isFalse();

        DataSource missing = mock(DataSource.class);
        when(missing.getConnection()).thenReturn(null);
        assertThat(new DbHealthChecker(missing).up()).isFalse();

        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("jdbc:mysql://db.internal:3306"));
        assertThat(new DbHealthChecker(broken).up()).isFalse();

        DataSource closesBadly = mock(DataSource.class);
        Connection noisy = mock(Connection.class);
        when(closesBadly.getConnection()).thenReturn(noisy);
        when(noisy.isValid(2)).thenReturn(true);
        org.mockito.Mockito.doThrow(new SQLException("close")).when(noisy).close();
        assertThat(new DbHealthChecker(closesBadly).up()).isTrue();
    }

    @Test
    @DisplayName("Redis PING 返回 PONG 时 UP")
    void redisUpOnPong() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn(" pong ");

        RedisHealthChecker checker = new RedisHealthChecker(factory);

        assertThat(checker.name()).isEqualTo("redis");
        assertThat(checker.up()).isTrue();
        verify(connection).close();
    }

    @Test
    @DisplayName("Redis 无 PONG、无连接或抛错时 DOWN")
    void redisDownWhenPingFails() {
        RedisConnectionFactory weird = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(weird.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("LOADING");
        assertThat(new RedisHealthChecker(weird).up()).isFalse();

        RedisConnectionFactory missing = mock(RedisConnectionFactory.class);
        when(missing.getConnection()).thenReturn(null);
        assertThat(new RedisHealthChecker(missing).up()).isFalse();

        RedisConnectionFactory broken = mock(RedisConnectionFactory.class);
        when(broken.getConnection()).thenThrow(new IllegalStateException("redis.internal:6379"));
        assertThat(new RedisHealthChecker(broken).up()).isFalse();

        RedisConnectionFactory closesBadly = mock(RedisConnectionFactory.class);
        RedisConnection noisy = mock(RedisConnection.class);
        when(closesBadly.getConnection()).thenReturn(noisy);
        when(noisy.ping()).thenReturn("PONG");
        org.mockito.Mockito.doThrow(new IllegalStateException("close")).when(noisy).close();
        assertThat(new RedisHealthChecker(closesBadly).up()).isTrue();
    }

    @Test
    @DisplayName("Docker ping 成功时 UP")
    void dockerUpWhenPingSucceeds() {
        DockerClient dockerClient = mock(DockerClient.class);
        PingCmd ping = mock(PingCmd.class);
        when(dockerClient.pingCmd()).thenReturn(ping);

        DockerHealthChecker checker = new DockerHealthChecker(dockerClient);

        assertThat(checker.name()).isEqualTo("docker");
        assertThat(checker.up()).isTrue();
    }

    @Test
    @DisplayName("Docker ping 缺失或抛错时 DOWN")
    void dockerDownWhenPingFails() {
        DockerClient noCommand = mock(DockerClient.class);
        when(noCommand.pingCmd()).thenReturn(null);
        assertThat(new DockerHealthChecker(noCommand).up()).isFalse();

        DockerClient broken = mock(DockerClient.class);
        PingCmd ping = mock(PingCmd.class);
        when(broken.pingCmd()).thenReturn(ping);
        doThrow(new IllegalStateException("unix:///var/run/docker.sock")).when(ping).exec();
        assertThat(new DockerHealthChecker(broken).up()).isFalse();
    }
}
