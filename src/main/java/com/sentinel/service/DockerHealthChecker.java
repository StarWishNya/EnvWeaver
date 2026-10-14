package com.sentinel.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PingCmd;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 用 Docker ping 检查引擎是否可达。
 */
@Slf4j
@Component
public class DockerHealthChecker implements ComponentHealthChecker {

    private final DockerClient dockerClient;

    public DockerHealthChecker(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    @Override
    public String name() {
        return DOCKER;
    }

    @Override
    public boolean up() {
        try {
            PingCmd ping = dockerClient.pingCmd();
            if (ping == null) {
                return false;
            }
            ping.exec();
            return true;
        } catch (Exception e) {
            log.warn("Docker health check failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
