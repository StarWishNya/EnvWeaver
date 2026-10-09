package com.sentinel.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.sentinel.config.properties.DockerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Docker 配置类
 * 
 * @author Sentinel Team
 */
@Slf4j
@Configuration
public class DockerConfig {

    private final DockerProperties dockerProperties;

    public DockerConfig(DockerProperties dockerProperties) {
        this.dockerProperties = dockerProperties;
    }

    /**
     * 配置 Docker 客户端
     * 连接超时与响应超时取自 DockerProperties，单位为秒
     */
    @Bean
    public DockerClient dockerClient() {
        DefaultDockerClientConfig.Builder configBuilder = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerProperties.getHost())
                .withDockerTlsVerify(Boolean.TRUE.equals(dockerProperties.getTlsVerify()))
                .withApiVersion(dockerProperties.getApiVersion());

        String certPath = dockerProperties.getCertPath();
        if (certPath != null && !certPath.isBlank()) {
            configBuilder.withDockerCertPath(certPath);
        }

        DockerClientConfig config = configBuilder.build();

        DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(timeoutSeconds(dockerProperties.getConnectionTimeout(), 30)))
                .responseTimeout(Duration.ofSeconds(timeoutSeconds(dockerProperties.getResponseTimeout(), 60)))
                .build();

        DockerClient dockerClient = DockerClientImpl.getInstance(config, httpClient);

        log.info("Docker 客户端初始化成功: host={}, apiVersion={}, connectionTimeout={}s, responseTimeout={}s",
                dockerProperties.getHost(),
                dockerProperties.getApiVersion(),
                dockerProperties.getConnectionTimeout(),
                dockerProperties.getResponseTimeout());

        return dockerClient;
    }

    private int timeoutSeconds(Integer configured, int defaultSeconds) {
        return configured == null ? defaultSeconds : configured;
    }
}
