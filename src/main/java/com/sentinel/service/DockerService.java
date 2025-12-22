package com.sentinel.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.*;
import com.sentinel.config.BusinessException;
import com.sentinel.domain.Container;
import com.sentinel.enums.ErrorCode;
import com.sentinel.mapper.ContainerMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Docker 容器管理服务
 * 
 * @author Sentinel Team
 */
@Slf4j
@Service
public class DockerService {

    private final DockerClient dockerClient;
    private final ContainerMapper containerMapper;

    public DockerService(DockerClient dockerClient, ContainerMapper containerMapper) {
        this.dockerClient = dockerClient;
        this.containerMapper = containerMapper;
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
        try {
            // 创建端口绑定
            ExposedPort exposedPort = ExposedPort.tcp(containerPort);
            Ports portBindings = new Ports();
            portBindings.bind(exposedPort, Ports.Binding.bindPort(hostPort));

            // 创建容器
            CreateContainerResponse containerResponse = dockerClient.createContainerCmd(image)
                    .withName(containerName)
                    .withExposedPorts(exposedPort)
                    .withHostConfig(HostConfig.newHostConfig()
                            .withPortBindings(portBindings)
                            .withAutoRemove(false))
                    .exec();

            String containerId = containerResponse.getId();
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
            container.setHealthStatus("UNKNOWN");
            container.setStartedAt(LocalDateTime.now());

            containerMapper.insert(container);

            return container;

        } catch (Exception e) {
            log.error("容器创建失败: image={}, name={}", image, containerName, e);
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
            dockerClient.stopContainerCmd(containerId).exec();
            log.info("容器已停止: containerId={}", containerId);

            // 更新数据库
            List<Container> containers = containerMapper.selectList(null);
            for (Container container : containers) {
                if (container.getContainerId().equals(containerId)) {
                    container.setStatus("STOPPED");
                    container.setStoppedAt(LocalDateTime.now());
                    containerMapper.updateById(container);
                    break;
                }
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

            // 从数据库删除
            List<Container> containers = containerMapper.selectList(null);
            for (Container container : containers) {
                if (container.getContainerId().equals(containerId)) {
                    containerMapper.deleteById(container.getId());
                    break;
                }
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
        try {
            StringBuilder logs = new StringBuilder();
            
            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTail(tail)
                    .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            logs.append(new String(frame.getPayload()));
                        }
                    })
                    .awaitCompletion();

            return logs.toString();

        } catch (Exception e) {
            log.error("获取容器日志失败: containerId={}", containerId, e);
            throw new BusinessException(ErrorCode.DOCKER_ERROR, "获取容器日志失败: " + e.getMessage());
        }
    }

    /**
     * 检查容器健康状态
     * 
     * @param containerId 容器 ID
     * @return 健康状态
     */
    public String checkContainerHealth(String containerId) {
        try {
            InspectContainerResponse response = dockerClient.inspectContainerCmd(containerId).exec();
            
            InspectContainerResponse.ContainerState state = response.getState();
            if (state.getRunning()) {
                // 检查健康检查结果
                if (state.getHealth() != null) {
                    return state.getHealth().getStatus();
                }
                return "HEALTHY";
            } else {
                return "UNHEALTHY";
            }

        } catch (Exception e) {
            log.error("检查容器健康状态失败: containerId={}", containerId, e);
            return "UNKNOWN";
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
}
