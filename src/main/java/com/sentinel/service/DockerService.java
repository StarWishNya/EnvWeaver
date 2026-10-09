package com.sentinel.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.HealthState;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.sentinel.config.BusinessException;
import com.sentinel.config.properties.DockerProperties;
import com.sentinel.domain.Container;
import com.sentinel.enums.ErrorCode;
import com.sentinel.mapper.ContainerMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Docker 容器管理服务
 * 
 * @author Sentinel Team
 */
@Slf4j
@Service
public class DockerService {

    private static final String HEALTHY = "HEALTHY";
    private static final String UNHEALTHY = "UNHEALTHY";
    private static final String UNKNOWN = "UNKNOWN";
    private static final String STARTING = "STARTING";

    private final DockerClient dockerClient;
    private final ContainerMapper containerMapper;
    private final DockerProperties dockerProperties;

    public DockerService(DockerClient dockerClient,
                         ContainerMapper containerMapper,
                         DockerProperties dockerProperties) {
        this.dockerClient = dockerClient;
        this.containerMapper = containerMapper;
        this.dockerProperties = dockerProperties;
    }

    /**
     * 创建并启动容器
     * 
     * @param environmentId 环境 ID
     * @param image 镜像名称
     * @param containerName 容器名称
     * @param hostPort 主机端口
     * @param containerPort 容器端口
     * @return 容器对象
     */
    @Transactional
    public Container createAndStartContainer(Long environmentId, String image,
                                            String containerName, int hostPort, int containerPort) {
        String containerId = null;
        try {
            // 创建端口绑定
            ExposedPort exposedPort = ExposedPort.tcp(containerPort);
            Ports portBindings = new Ports();
            portBindings.bind(exposedPort, Ports.Binding.bindPort(hostPort));

            // 创建容器，并应用内存、CPU、网络等资源限制
            CreateContainerResponse containerResponse = dockerClient.createContainerCmd(image)
                    .withName(containerName)
                    .withExposedPorts(exposedPort)
                    .withHostConfig(buildHostConfig(portBindings))
                    .exec();

            containerId = containerResponse.getId();
            log.info("容器创建成功: containerId={}, name={}", containerId, containerName);

            // 启动容器
            dockerClient.startContainerCmd(containerId).exec();
            log.info("容器启动成功: containerId={}", containerId);

            // 保存到数据库
            Container container = new Container();
            container.setEnvironmentId(environmentId);
            container.setContainerId(containerId);
            container.setContainerName(containerName);
            container.setImage(image);
            container.setStatus("RUNNING");
            container.setPortMapping(containerPort + ":" + hostPort);
            container.setHealthStatus(UNKNOWN);
            container.setStartedAt(LocalDateTime.now());

            containerMapper.insert(container);

            return container;

        } catch (Exception e) {
            log.error("容器创建失败: image={}, name={}", image, containerName, e);
            // 创建成功后任一步失败都强制删除容器，清理失败不掩盖原始异常
            removeCreatedContainerQuietly(containerId);
            throw new BusinessException(ErrorCode.DOCKER_ERROR, "容器创建失败: " + e.getMessage());
        }
    }

    /**
     * 停止容器
     * 
     * @param containerId 容器 ID
     */
    @Transactional
    public void stopContainer(String containerId) {
        try {
            dockerClient.stopContainerCmd(containerId)
                    .withTimeout(resolveStopTimeoutSeconds())
                    .exec();
            log.info("容器已停止: containerId={}", containerId);

            Container container = containerMapper.findByContainerId(containerId);
            if (container != null) {
                container.setStatus("STOPPED");
                container.setStoppedAt(LocalDateTime.now());
                containerMapper.updateById(container);
            }

        } catch (Exception e) {
            log.error("容器停止失败: containerId={}", containerId, e);
            throw new BusinessException(ErrorCode.DOCKER_ERROR, "容器停止失败: " + e.getMessage());
        }
    }

    /**
     * 删除容器
     * 
     * @param containerId 容器 ID
     */
    @Transactional
    public void removeContainer(String containerId) {
        try {
            dockerClient.removeContainerCmd(containerId)
                    .withForce(true)
                    .exec();
            log.info("容器已删除: containerId={}", containerId);

            Container container = containerMapper.findByContainerId(containerId);
            if (container != null && container.getId() != null) {
                containerMapper.deleteById(container.getId());
            }

        } catch (Exception e) {
            log.error("容器删除失败: containerId={}", containerId, e);
            throw new BusinessException(ErrorCode.DOCKER_ERROR, "容器删除失败: " + e.getMessage());
        }
    }

    /**
     * 获取容器日志
     *
     * @param containerId 容器 ID
     * @param tail 获取最后 N 行
     * @return 日志内容
     */
    public String getContainerLogs(String containerId, int tail) {
        return getContainerLogs(containerId, tail, null);
    }

    /**
     * 获取容器日志。since 不为空时只返回该时刻之后的输出，单位为 Unix 秒，
     * 并打开 Docker timestamps。每行以 RFC3339 时间戳加一个空格开头，原样返回，由调用方去掉这一层前缀。
     *
     * @param containerId 容器 ID
     * @param tail 获取最后 N 行
     * @param since 起始时间，null 表示不按时间过滤、也不加时间戳
     * @return 日志内容
     */
    public String getContainerLogs(String containerId, int tail, Instant since) {
        StringBuilder logs = new StringBuilder();
        int timeoutSeconds = resolveResponseTimeoutSeconds();
        try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                if (frame == null || frame.getPayload() == null) {
                    return;
                }
                logs.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
            }
        }) {
            LogContainerCmd command = dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTail(tail);
            if (since != null) {
                command = command.withSince((int) since.getEpochSecond())
                        .withTimestamps(true);
            }
            boolean completed = command.exec(callback)
                    .awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                log.error("获取容器日志超时: containerId={}, timeoutSeconds={}", containerId, timeoutSeconds);
                throw new BusinessException(ErrorCode.DOCKER_ERROR,
                        "获取容器日志超时: " + timeoutSeconds + "秒");
            }
            return logs.toString();

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("获取容器日志失败: containerId={}", containerId, e);
            throw new BusinessException(ErrorCode.DOCKER_ERROR, "获取容器日志失败: " + e.getMessage());
        }
    }

    /**
     * 检查容器健康状态
     * 返回值与 t_container.health_status 对齐：HEALTHY、UNHEALTHY、UNKNOWN。
     * 无健康检查返回 UNKNOWN；Docker 的 healthy/unhealthy/starting 映射为大写；
     * 容器未运行（含 running 为空）返回 UNHEALTHY。
     * 
     * @param containerId 容器 ID
     * @return 健康状态
     */
    public String checkContainerHealth(String containerId) {
        try {
            InspectContainerResponse response = dockerClient.inspectContainerCmd(containerId).exec();
            if (response == null || response.getState() == null) {
                return UNKNOWN;
            }

            InspectContainerResponse.ContainerState state = response.getState();
            if (!Boolean.TRUE.equals(state.getRunning())) {
                return UNHEALTHY;
            }

            HealthState health = state.getHealth();
            if (health == null) {
                return UNKNOWN;
            }
            return mapDockerHealthStatus(health.getStatus());

        } catch (Exception e) {
            log.error("检查容器健康状态失败: containerId={}", containerId, e);
            return UNKNOWN;
        }
    }

    /**
     * 根据环境 ID 获取容器列表
     * 
     * @param environmentId 环境 ID
     * @return 容器列表
     */
    public List<Container> getContainersByEnvironmentId(Long environmentId) {
        return containerMapper.findByEnvironmentId(environmentId);
    }

    /**
     * 确认 Docker 守护进程可连通。不可用时抛出 DOCKER_ERROR。
     */
    public void assertDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
        } catch (Exception ex) {
            log.error("Docker 服务不可用", ex);
            throw new BusinessException(ErrorCode.DOCKER_ERROR, "Docker 服务不可用");
        }
    }

    /**
     * 停止并强制删除容器，不修改 t_container。
     * 容器不存在或已经停止时继续删除；容器已删除视为成功。
     * 删除失败时抛出 DOCKER_ERROR，由调用方按容器粒度决定是否继续。
     *
     * @param containerId Docker 容器 ID，空值直接返回
     */
    public void stopAndRemoveContainer(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            return;
        }
        try {
            dockerClient.stopContainerCmd(containerId)
                    .withTimeout(resolveStopTimeoutSeconds())
                    .exec();
            log.info("容器已停止: containerId={}", containerId);
        } catch (NotFoundException ex) {
            log.info("容器已不存在，跳过停止: containerId={}", containerId);
        } catch (NotModifiedException ex) {
            log.info("容器已经停止，继续删除: containerId={}", containerId);
        } catch (Exception ex) {
            log.warn("停止容器失败，将继续尝试删除: containerId={}", containerId, ex);
        }

        try {
            dockerClient.removeContainerCmd(containerId)
                    .withForce(true)
                    .exec();
            log.info("容器已删除: containerId={}", containerId);
        } catch (NotFoundException ex) {
            log.info("容器已不存在，跳过删除: containerId={}", containerId);
        } catch (Exception ex) {
            log.warn("删除容器失败: containerId={}", containerId, ex);
            throw new BusinessException(ErrorCode.DOCKER_ERROR, "删除容器失败");
        }
    }

    /**
     * 按 DockerProperties.container 组装 HostConfig。
     * memoryLimit 单位为 MB，cpuLimit 为核心数，nanoCPUs = cpuLimit * 1e9。
     */
    private HostConfig buildHostConfig(Ports portBindings) {
        DockerProperties.ContainerConfig containerConfig = dockerProperties.getContainer();
        if (containerConfig == null) {
            containerConfig = new DockerProperties.ContainerConfig();
        }

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withPortBindings(portBindings)
                .withAutoRemove(Boolean.TRUE.equals(containerConfig.getAutoRemove()));

        Integer memoryLimitMb = containerConfig.getMemoryLimit();
        if (memoryLimitMb != null && memoryLimitMb > 0) {
            hostConfig.withMemory(memoryLimitMb.longValue() * 1024L * 1024L);
        }

        Double cpuLimit = containerConfig.getCpuLimit();
        if (cpuLimit != null && cpuLimit > 0) {
            hostConfig.withNanoCPUs(Math.round(cpuLimit * 1_000_000_000D));
        }

        String network = containerConfig.getDefaultNetwork();
        if (network != null && !network.isBlank()) {
            hostConfig.withNetworkMode(network);
        }

        String restartPolicy = containerConfig.getRestartPolicy();
        if (restartPolicy != null && !restartPolicy.isBlank()) {
            hostConfig.withRestartPolicy(RestartPolicy.parse(restartPolicy));
        }
        return hostConfig;
    }

    /**
     * 创建后续步骤失败时强制删除已创建的容器。
     * 清理异常只记录日志，不向外抛出。
     */
    private void removeCreatedContainerQuietly(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            return;
        }
        try {
            dockerClient.removeContainerCmd(containerId)
                    .withForce(true)
                    .exec();
            log.warn("容器创建未完成，已强制删除残留容器: containerId={}", containerId);
        } catch (Exception cleanupError) {
            log.warn("清理残留容器失败: containerId={}", containerId, cleanupError);
        }
    }

    /**
     * 将 Docker 健康检查状态映射为大写值。无法识别时返回 UNKNOWN。
     */
    private String mapDockerHealthStatus(String dockerStatus) {
        if (dockerStatus == null || dockerStatus.isBlank()) {
            return UNKNOWN;
        }
        String normalized = dockerStatus.trim().toUpperCase(Locale.ROOT);
        if (HEALTHY.equals(normalized) || UNHEALTHY.equals(normalized)
                || STARTING.equals(normalized) || UNKNOWN.equals(normalized)) {
            return normalized;
        }
        return UNKNOWN;
    }

    /**
     * 日志等待超时（秒），取自 DockerProperties.responseTimeout。
     */
    private int resolveResponseTimeoutSeconds() {
        Integer timeout = dockerProperties.getResponseTimeout();
        if (timeout == null || timeout < 0) {
            return 60;
        }
        return timeout;
    }

    /**
     * 停止容器等待退出的秒数，取自 DockerProperties.stopTimeoutSeconds。
     * 未配置或为负数时使用 3 秒。
     */
    private int resolveStopTimeoutSeconds() {
        Integer timeout = dockerProperties.getStopTimeoutSeconds();
        if (timeout == null || timeout < 0) {
            return 3;
        }
        return timeout;
    }
}
