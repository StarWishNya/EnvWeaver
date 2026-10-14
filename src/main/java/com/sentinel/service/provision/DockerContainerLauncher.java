package com.sentinel.service.provision;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HealthCheck;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.RestartPolicy;
import com.sentinel.config.BusinessException;
import com.sentinel.config.properties.DockerProperties;
import com.sentinel.config.properties.ProvisioningProperties;
import com.sentinel.enums.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 直接使用 DockerClient 启动供给容器，不经过 DockerService。
 */
@Slf4j
@Component
public class DockerContainerLauncher implements ContainerLauncher {

    private static final int LOG_READ_SECONDS = 5;
    private static final Set<String> BUILTIN_NETWORKS = Set.of("bridge", "host", "none", "default");

    private final DockerClient dockerClient;
    private final DockerProperties dockerProperties;
    private final ProvisioningProperties provisioningProperties;
    private final Set<String> ensuredNetworks = ConcurrentHashMap.newKeySet();

    public DockerContainerLauncher(DockerClient dockerClient,
                                   DockerProperties dockerProperties,
                                   ProvisioningProperties provisioningProperties) {
        this.dockerClient = dockerClient;
        this.dockerProperties = dockerProperties;
        this.provisioningProperties = provisioningProperties;
    }

    @Override
    public void ensureImage(String image) {
        if (image == null || image.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "镜像名称不能为空");
        }
        if (imageExists(image)) {
            log.info("本地已有镜像，跳过拉取: image={}", image);
            return;
        }
        if (!provisioningProperties.isPullMissing()) {
            throw new BusinessException(ErrorCode.DOCKER_ERROR, "本地缺少镜像 " + image);
        }
        pullImage(image.trim());
    }

    @Override
    public StartedContainer start(Launch launch) {
        if (launch == null || launch.image() == null || launch.image().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "镜像名称不能为空");
        }
        ExposedPort exposedPort = ExposedPort.tcp(launch.containerPort());
        Ports portBindings = new Ports();
        portBindings.bind(exposedPort, Ports.Binding.bindIpAndPort(provisioningProperties.getBindAddress(), launch.hostPort()));

        CreateContainerCmd create = dockerClient.createContainerCmd(launch.image())
                .withName(launch.name())
                .withExposedPorts(exposedPort)
                .withHostConfig(buildHostConfig(portBindings));
        applyEnv(create, launch.env());
        if (launch.labels() != null && !launch.labels().isEmpty()) {
            create.withLabels(launch.labels());
        }

        String containerId = null;
        try {
            ensureNetwork(containerConfig().getDefaultNetwork());
            CreateContainerResponse response = create.exec();
            containerId = response == null ? null : response.getId();
            if (containerId == null || containerId.isBlank()) {
                throw new BusinessException(ErrorCode.DOCKER_ERROR, "启动容器失败");
            }
            dockerClient.startContainerCmd(containerId).exec();
            boolean healthcheckDefined = imageDefinesHealthcheck(launch.image());
            log.info("容器已启动: name={}, containerId={}", launch.name(), containerId);
            return new StartedContainer(containerId, healthcheckDefined);
        } catch (BusinessException ex) {
            removeQuietly(containerId);
            throw ex;
        } catch (RuntimeException ex) {
            removeQuietly(containerId);
            log.error("容器启动失败: name={}", launch.name(), ex);
            throw new BusinessException(ErrorCode.DOCKER_ERROR, containerStartMessage(ex));
        }
    }

    private static String containerStartMessage(RuntimeException error) {
        String cause = DockerMessages.causeOf(error);
        if (cause == null) {
            return "启动容器失败";
        }
        return "启动容器失败: " + cause;
    }

    @Override
    public void remove(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            return;
        }
        try {
            dockerClient.removeContainerCmd(containerId)
                    .withForce(true)
                    .exec();
            log.info("已强制删除容器: containerId={}", containerId);
        } catch (NotFoundException ex) {
            log.info("容器已不存在，跳过删除: containerId={}", containerId);
        } catch (RuntimeException ex) {
            log.warn("强制删除容器失败: containerId={}", containerId, ex);
            throw new BusinessException(ErrorCode.DOCKER_ERROR, "删除容器失败");
        }
    }

    @Override
    public Snapshot inspect(String containerId) {
        try {
            InspectContainerResponse response = dockerClient.inspectContainerCmd(containerId).exec();
            InspectContainerResponse.ContainerState state = response == null ? null : response.getState();
            boolean running = state != null && Boolean.TRUE.equals(state.getRunning());
            String healthStatus = null;
            if (state != null && state.getHealth() != null) {
                healthStatus = state.getHealth().getStatus();
            }
            return new Snapshot(running, healthStatus);
        } catch (NotFoundException ex) {
            throw new BusinessException(ErrorCode.DOCKER_ERROR, "容器不存在");
        } catch (RuntimeException ex) {
            log.error("检查容器状态失败: containerId={}", containerId, ex);
            throw new BusinessException(ErrorCode.DOCKER_ERROR, "检查容器状态失败");
        }
    }

    @Override
    public boolean exists(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            return false;
        }
        try {
            dockerClient.inspectContainerCmd(containerId).exec();
            return true;
        } catch (RuntimeException ex) {
            if (isNotFound(ex)) {
                return false;
            }
            throw ex;
        }
    }

    private static boolean isNotFound(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof NotFoundException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @Override
    public String recentLogs(String containerId, int maxLines) {
        if (containerId == null || containerId.isBlank() || maxLines <= 0) {
            return "";
        }
        StringBuilder logs = new StringBuilder();
        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                if (frame == null || frame.getPayload() == null || frame.getPayload().length == 0) {
                    return;
                }
                logs.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
            }
        };
        try {
            LogContainerCmd command = dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTail(maxLines);
            boolean completed = command.exec(callback).awaitCompletion(LOG_READ_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("读取容器日志超时: containerId={}", containerId);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("读取容器日志被中断: containerId={}", containerId);
        } catch (RuntimeException ex) {
            log.warn("读取容器日志失败: containerId={}", containerId);
            return "";
        } finally {
            try {
                callback.close();
            } catch (IOException ex) {
                log.debug("关闭容器日志回调失败: containerId={}", containerId);
            }
        }
        return DockerMessages.tailLines(logs.toString(), maxLines);
    }

    /**
     * 拆出仓库和标签。registry:port/name:tag 只把最后一个斜杠之后的冒号当作标签。
     */
    static ImageRef parseImageRef(String image) {
        String name = image.trim();
        int digest = name.lastIndexOf('@');
        if (digest > 0) {
            name = name.substring(0, digest);
        }
        int slash = name.lastIndexOf('/');
        int colon = name.lastIndexOf(':');
        if (colon > slash) {
            String repository = name.substring(0, colon);
            String tag = name.substring(colon + 1);
            if (!repository.isBlank() && !tag.isBlank()) {
                return new ImageRef(repository, tag);
            }
        }
        return new ImageRef(name, null);
    }

    private boolean imageExists(String image) {
        try {
            dockerClient.inspectImageCmd(image).exec();
            return true;
        } catch (NotFoundException ex) {
            return false;
        } catch (RuntimeException ex) {
            log.error("检查镜像失败: image={}", image, ex);
            throw new BusinessException(ErrorCode.DOCKER_ERROR, "检查镜像失败");
        }
    }

    private void pullImage(String image) {
        ImageRef ref = parseImageRef(image);
        log.info("开始拉取镜像: image={}", image);
        PullImageResultCallback callback = new PullImageResultCallback();
        try {
            PullImageCmd command = dockerClient.pullImageCmd(ref.repository());
            if (ref.tag() != null) {
                command = command.withTag(ref.tag());
            }
            command.exec(callback);
            long timeoutMillis = pullTimeoutMillis();
            boolean finished = callback.awaitCompletion(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                throw new BusinessException(ErrorCode.DOCKER_ERROR, "拉取镜像超时: " + image);
            }
            log.info("镜像拉取完成: image={}", image);
        } catch (BusinessException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.DOCKER_ERROR, "拉取镜像被中断: " + image);
        } catch (RuntimeException ex) {
            log.error("拉取镜像失败: image={}", image, ex);
            throw new BusinessException(ErrorCode.DOCKER_ERROR, "拉取镜像失败: " + image);
        } finally {
            try {
                callback.close();
            } catch (IOException ex) {
                log.debug("关闭镜像拉取回调失败: image={}", image);
            }
        }
    }

    private boolean imageDefinesHealthcheck(String image) {
        try {
            InspectImageResponse response = dockerClient.inspectImageCmd(image).exec();
            if (response == null || response.getConfig() == null) {
                return false;
            }
            return definesHealthcheck(response.getConfig().getHealthcheck());
        } catch (NotFoundException ex) {
            throw new BusinessException(ErrorCode.DOCKER_ERROR, "本地缺少镜像 " + image);
        } catch (RuntimeException ex) {
            log.error("检查镜像健康检查失败: image={}", image, ex);
            throw new BusinessException(ErrorCode.DOCKER_ERROR, "检查镜像失败");
        }
    }

    private boolean definesHealthcheck(HealthCheck healthCheck) {
        if (healthCheck == null || healthCheck.getTest() == null || healthCheck.getTest().isEmpty()) {
            return false;
        }
        List<String> test = healthCheck.getTest();
        return !(test.size() == 1 && "NONE".equalsIgnoreCase(test.get(0)));
    }

    private void applyEnv(CreateContainerCmd create, Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return;
        }
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            pairs.add(entry.getKey() + "=" + entry.getValue());
        }
        if (!pairs.isEmpty()) {
            create.withEnv(pairs);
        }
    }

    /**
     * 与 DockerService 的 HostConfig 组装保持一致：内存按 MB 转字节，CPU 核心数转 nanoCPUs。
     */
    private HostConfig buildHostConfig(Ports portBindings) {
        DockerProperties.ContainerConfig containerConfig = containerConfig();

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
            hostConfig.withNetworkMode(network.trim());
        }

        String restartPolicy = containerConfig.getRestartPolicy();
        if (restartPolicy != null && !restartPolicy.isBlank()) {
            hostConfig.withRestartPolicy(RestartPolicy.parse(restartPolicy));
        }
        return hostConfig;
    }

    private long pullTimeoutMillis() {
        if (provisioningProperties.getPullTimeout() == null || provisioningProperties.getPullTimeout().isNegative()) {
            return TimeUnit.MINUTES.toMillis(5);
        }
        return provisioningProperties.getPullTimeout().toMillis();
    }

    private DockerProperties.ContainerConfig containerConfig() {
        DockerProperties.ContainerConfig containerConfig = dockerProperties.getContainer();
        if (containerConfig == null) {
            return new DockerProperties.ContainerConfig();
        }
        return containerConfig;
    }

    /**
     * 用户自定义网络不存在就创建。bridge/host/none/default 是 Docker 内置网络，不创建。
     * 同一个网络名只检查一次。并发创建撞上 409 时视为已经存在。
     */
    private void ensureNetwork(String network) {
        if (!isUserDefinedNetwork(network)) {
            return;
        }
        String name = network.trim();
        if (ensuredNetworks.contains(name)) {
            return;
        }
        synchronized (ensuredNetworks) {
            if (ensuredNetworks.contains(name)) {
                return;
            }
            try {
                dockerClient.inspectNetworkCmd().withNetworkId(name).exec();
            } catch (NotFoundException notFound) {
                createUserNetwork(name);
            }
            ensuredNetworks.add(name);
        }
    }

    private void createUserNetwork(String name) {
        try {
            dockerClient.createNetworkCmd()
                    .withName(name)
                    .withDriver("bridge")
                    .withLabels(Map.of("sentinel.managed", "true"))
                    .exec();
            log.info("已创建 Docker 网络: name={}", name);
        } catch (ConflictException conflict) {
            log.info("Docker 网络已存在，忽略创建冲突: name={}", name);
        } catch (DockerException ex) {
            if (ex.getHttpStatus() == 409) {
                log.info("Docker 网络已存在，忽略创建冲突: name={}", name);
                return;
            }
            throw ex;
        }
    }

    private static boolean isUserDefinedNetwork(String network) {
        if (network == null || network.isBlank()) {
            return false;
        }
        return !BUILTIN_NETWORKS.contains(network.trim().toLowerCase(Locale.ROOT));
    }

    private void removeQuietly(String containerId) {
        try {
            remove(containerId);
        } catch (RuntimeException ex) {
            log.warn("启动失败后清理容器未成功: containerId={}", containerId);
        }
    }

    record ImageRef(String repository, String tag) {
    }
}
