package com.sentinel.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.BusinessException;
import com.sentinel.config.properties.DockerProperties;
import com.sentinel.config.properties.ProvisioningProperties;
import com.sentinel.domain.Container;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Task;
import com.sentinel.enums.ErrorCode;
import com.sentinel.mapper.ContainerMapper;
import com.sentinel.mapper.EnvironmentMapper;
import com.sentinel.service.provision.ContainerLauncher;
import com.sentinel.service.provision.DependencyCatalog;
import com.sentinel.service.provision.DependencySpec;
import com.sentinel.service.provision.DockerMessages;
import com.sentinel.service.provision.PortAllocator;
import com.sentinel.service.provision.ProvisioningFailedException;
import com.sentinel.service.provision.ReadinessPause;
import com.sentinel.service.provision.TcpProbe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 按任务依赖创建测试环境并启动容器。
 */
@Slf4j
@Service
public class DockerEnvironmentProvisioner implements EnvironmentProvisioner {

    private static final String STATUS_CREATING = "CREATING";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_IDLE = "IDLE";
    private static final String STATUS_ERROR = "ERROR";
    private static final String STATUS_RETAINED = "RETAINED";
    private static final String CONTAINER_RUNNING = "RUNNING";
    private static final String HEALTH_UNKNOWN = "UNKNOWN";
    private static final String HEALTH_HEALTHY = "HEALTHY";
    private static final long POLL_MILLIS = 200L;
    private static final int ENV_CODE_ATTEMPTS = 5;
    private static final int LOG_TAIL_LINES = 20;

    private final EnvironmentMapper environmentMapper;
    private final ContainerMapper containerMapper;
    private final DependencyCatalog dependencyCatalog;
    private final PortAllocator portAllocator;
    private final ContainerLauncher containerLauncher;
    private final TcpProbe tcpProbe;
    private final ReadinessPause readinessPause;
    private final ProvisioningProperties provisioningProperties;
    private final DockerProperties dockerProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DockerEnvironmentProvisioner(EnvironmentMapper environmentMapper,
                                        ContainerMapper containerMapper,
                                        DependencyCatalog dependencyCatalog,
                                        PortAllocator portAllocator,
                                        ContainerLauncher containerLauncher,
                                        TcpProbe tcpProbe,
                                        ReadinessPause readinessPause,
                                        ProvisioningProperties provisioningProperties,
                                        DockerProperties dockerProperties) {
        this.environmentMapper = environmentMapper;
        this.containerMapper = containerMapper;
        this.dependencyCatalog = dependencyCatalog;
        this.portAllocator = portAllocator;
        this.containerLauncher = containerLauncher;
        this.tcpProbe = tcpProbe;
        this.readinessPause = readinessPause;
        this.provisioningProperties = provisioningProperties;
        this.dockerProperties = dockerProperties;
    }

    @Override
    public Environment provision(Task task) {
        if (task == null || task.getId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "任务不能为空");
        }
        Environment existing = findReusable(task);
        if (existing != null) {
            log.info("任务已有未释放环境，跳过创建: taskId={}, envId={}, status={}",
                    task.getId(), existing.getId(), existing.getStatus());
            return existing;
        }

        List<DependencySpec> specs = dependencyCatalog.resolve(task.getDependencies());
        if (specs.isEmpty()) {
            return createEmptyEnvironment(task);
        }

        List<Integer> ports = portAllocator.allocate(specs.size());
        Environment environment = null;
        List<RunningContainer> started = new ArrayList<>();
        try {
            environment = insertCreating(task, specs, ports);
            for (DependencySpec spec : specs) {
                containerLauncher.ensureImage(spec.getImage());
            }
            for (int i = 0; i < specs.size(); i++) {
                startContainer(environment, task, specs.get(i), ports.get(i), started);
            }
            for (RunningContainer running : started) {
                awaitReady(running);
                markHealthy(running);
            }
            markReady(environment);
            log.info("环境已就绪: taskId={}, envId={}, envCode={}",
                    task.getId(), environment.getId(), environment.getEnvCode());
            return environment;
        } catch (BusinessException ex) {
            abort(environment, started);
            throw attachEnvironment(environment, ex);
        } catch (RuntimeException ex) {
            log.error("环境创建出现未预期错误: taskId={}", task.getId(), ex);
            abort(environment, started);
            throw attachEnvironment(environment,
                    new BusinessException(ErrorCode.ENVIRONMENT_CREATE_FAILED, "环境创建失败"));
        } finally {
            portAllocator.release(ports);
        }
    }

    private Environment findReusable(Task task) {
        Long environmentId = task.getEnvironmentId();
        if (environmentId == null) {
            return null;
        }
        Environment environment = environmentMapper.selectById(environmentId);
        if (environment == null) {
            log.info("任务关联的环境不存在，将重新创建: taskId={}, environmentId={}", task.getId(), environmentId);
            return null;
        }
        // IDLE、ERROR、RETAINED 不能复用。保留中的容器仍占端口，但现场属于失败任务。
        String status = environment.getStatus() == null ? "" : environment.getStatus().trim();
        if (STATUS_IDLE.equalsIgnoreCase(status)
                || STATUS_ERROR.equalsIgnoreCase(status)
                || STATUS_RETAINED.equalsIgnoreCase(status)
                || status.isEmpty()) {
            return null;
        }
        return environment;
    }

    private Environment createEmptyEnvironment(Task task) {
        Environment environment = insertCreating(task, List.of(), List.of());
        try {
            environment.setStatus(STATUS_READY);
            environment.setPortMappings("{}");
            environment.setAccessUrl(resolveHost());
            environment.setResourceUsage(null);
            environmentMapper.updateById(environment);
            log.info("任务没有依赖，环境已就绪: taskId={}, envId={}", task.getId(), environment.getId());
            return environment;
        } catch (RuntimeException ex) {
            log.error("空环境标记就绪失败: taskId={}, envId={}", task.getId(), environment.getId(), ex);
            abort(environment, List.of());
            throw attachEnvironment(environment,
                    new BusinessException(ErrorCode.ENVIRONMENT_CREATE_FAILED, "环境创建失败"));
        }
    }

    /**
     * 环境行已经落库时，带上环境 ID，方便编排器把 ERROR 环境挂到任务上。
     */
    private BusinessException attachEnvironment(Environment environment, BusinessException failure) {
        if (environment == null || environment.getId() == null) {
            return failure;
        }
        if (failure instanceof ProvisioningFailedException provisioningFailed
                && environment.getId().equals(provisioningFailed.getEnvironmentId())) {
            return provisioningFailed;
        }
        return new ProvisioningFailedException(failure.getCode(), failure.getMessage(), environment.getId());
    }

    private Environment insertCreating(Task task, List<DependencySpec> specs, List<Integer> ports) {
        String portMappings = specs.isEmpty() ? null : writePortMappings(specs, ports);
        DuplicateKeyException duplicate = null;
        for (int attempt = 0; attempt < ENV_CODE_ATTEMPTS; attempt++) {
            String envCode = newEnvCode();
            Environment environment = new Environment();
            environment.setEnvCode(envCode);
            environment.setStatus(STATUS_CREATING);
            environment.setTaskId(task.getId());
            environment.setComposeContent(renderCompose(envCode, task.getId(), specs, ports));
            environment.setAccessUrl(resolveHost());
            environment.setPortMappings(portMappings);
            environment.setExpiresAt(LocalDateTime.now().plus(ttl()));
            try {
                environmentMapper.insert(environment);
            } catch (DuplicateKeyException ex) {
                duplicate = ex;
                log.warn("环境编码冲突，准备重试: envCode={}", envCode);
                continue;
            }
            if (environment.getId() == null) {
                throw new BusinessException(ErrorCode.ENVIRONMENT_CREATE_FAILED, "环境创建失败");
            }
            return environment;
        }
        log.error("环境编码连续冲突", duplicate);
        throw new BusinessException(ErrorCode.ENVIRONMENT_CREATE_FAILED, "环境创建失败");
    }

    private void startContainer(Environment environment,
                                Task task,
                                DependencySpec spec,
                                int hostPort,
                                List<RunningContainer> started) {
        String name = containerName(environment.getEnvCode(), spec.getName());
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("sentinel.env", environment.getEnvCode());
        labels.put("sentinel.task", String.valueOf(task.getId()));
        ContainerLauncher.Launch launch = new ContainerLauncher.Launch(
                spec.getImage(),
                name,
                spec.getContainerPort(),
                hostPort,
                spec.getEnvironment(),
                labels);
        ContainerLauncher.StartedContainer startedContainer;
        try {
            startedContainer = containerLauncher.start(launch);
        } catch (BusinessException ex) {
            throw new BusinessException(ex.getCode() == null ? ErrorCode.DOCKER_ERROR.getCode() : ex.getCode(),
                    startFailureMessage(spec.getName(), ex.getMessage()));
        }

        Container row = new Container();
        row.setEnvironmentId(environment.getId());
        row.setContainerId(startedContainer.containerId());
        row.setContainerName(name);
        row.setImage(spec.getImage());
        row.setStatus(CONTAINER_RUNNING);
        row.setPortMapping(spec.getContainerPort() + ":" + hostPort);
        row.setHealthStatus(HEALTH_UNKNOWN);
        row.setStartedAt(LocalDateTime.now());

        // 先记入已启动列表，入库失败时也能按容器 ID 清理。
        started.add(new RunningContainer(
                spec, hostPort, startedContainer.containerId(), startedContainer.healthcheckDefined(), row));
        try {
            containerMapper.insert(row);
        } catch (RuntimeException ex) {
            log.error("保存容器记录失败: envId={}, name={}", environment.getId(), name, ex);
            throw new BusinessException(ErrorCode.ENVIRONMENT_CREATE_FAILED, "保存容器记录失败");
        }
    }

    private void awaitReady(RunningContainer running) {
        Duration timeout = provisioningProperties.getReadyTimeout();
        if (timeout == null || timeout.isNegative()) {
            timeout = Duration.ofSeconds(90);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        String host = resolveHost();
        while (true) {
            if (isReady(running, host)) {
                return;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new BusinessException(ErrorCode.ENVIRONMENT_CREATE_FAILED, readinessFailureMessage(running));
            }
            long sleepMillis = Math.min(POLL_MILLIS, Math.max(1L, remainingNanos / 1_000_000L));
            readinessPause.pause(Duration.ofMillis(sleepMillis));
        }
    }

    private boolean isReady(RunningContainer running, String host) {
        ContainerLauncher.Snapshot snapshot = containerLauncher.inspect(running.containerId);
        if (!snapshot.running()) {
            return false;
        }
        if (running.healthcheckDefined) {
            String health = snapshot.healthStatus();
            if (health == null || !"healthy".equalsIgnoreCase(health)) {
                return false;
            }
        }
        return tcpProbe.accepts(host, running.hostPort);
    }

    private String readinessFailureMessage(RunningContainer running) {
        String logs = "";
        try {
            logs = containerLauncher.recentLogs(running.containerId, LOG_TAIL_LINES);
        } catch (RuntimeException ex) {
            log.warn("读取未就绪容器日志失败: containerId={}", running.containerId);
        }
        String tail = DockerMessages.tailLines(logs, LOG_TAIL_LINES);
        String base = "依赖 " + running.spec.getName() + " 未在超时时间内就绪";
        if (tail.isEmpty()) {
            return base;
        }
        return base + "\n" + tail;
    }

    private static String startFailureMessage(String dependency, String detail) {
        String cause = detail == null ? "" : detail.trim();
        String prefix = "启动容器失败";
        if (cause.startsWith(prefix)) {
            cause = cause.substring(prefix.length()).trim();
            if (cause.startsWith(":")) {
                cause = cause.substring(1).trim();
            }
        }
        cause = DockerMessages.sanitize(cause);
        if (cause == null || cause.isBlank()) {
            return "启动 " + dependency + " 失败";
        }
        return "启动 " + dependency + " 失败: " + cause;
    }

    private void markHealthy(RunningContainer running) {
        running.row.setHealthStatus(HEALTH_HEALTHY);
        containerMapper.updateById(running.row);
    }

    private void markReady(Environment environment) {
        environment.setStatus(STATUS_READY);
        environment.setAccessUrl(resolveHost());
        environment.setResourceUsage(null);
        environmentMapper.updateById(environment);
    }

    private void abort(Environment environment, List<RunningContainer> started) {
        for (int i = started.size() - 1; i >= 0; i--) {
            RunningContainer running = started.get(i);
            try {
                containerLauncher.remove(running.containerId);
            } catch (RuntimeException ex) {
                log.warn("清理容器失败: containerId={}", running.containerId, ex);
            }
            if (running.row.getId() != null) {
                try {
                    running.row.setStatus(STATUS_ERROR);
                    containerMapper.updateById(running.row);
                } catch (RuntimeException ex) {
                    log.warn("更新容器状态失败: containerId={}", running.containerId, ex);
                }
            }
        }
        if (environment != null && environment.getId() != null) {
            try {
                environment.setStatus(STATUS_ERROR);
                environment.setPortMappings(null);
                environmentMapper.markProvisionFailed(environment.getId());
                log.error("环境创建失败，已标记为 ERROR: envId={}, envCode={}",
                        environment.getId(), environment.getEnvCode());
            } catch (RuntimeException ex) {
                log.error("环境创建失败且无法标记 ERROR: envId={}", environment.getId(), ex);
            }
        }
    }

    private String writePortMappings(List<DependencySpec> specs, List<Integer> ports) {
        Map<String, Integer> mappings = new LinkedHashMap<>();
        for (int i = 0; i < specs.size(); i++) {
            mappings.put(specs.get(i).getName(), ports.get(i));
        }
        try {
            return objectMapper.writeValueAsString(mappings);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.ENVIRONMENT_CREATE_FAILED, "环境创建失败");
        }
    }

    private String renderCompose(String envCode, Long taskId, List<DependencySpec> specs, List<Integer> ports) {
        StringBuilder yaml = new StringBuilder();
        if (specs.isEmpty()) {
            return "services: {}\n";
        }
        yaml.append("services:\n");
        DockerProperties.ContainerConfig containerConfig = dockerProperties.getContainer();
        if (containerConfig == null) {
            containerConfig = new DockerProperties.ContainerConfig();
        }
        for (int i = 0; i < specs.size(); i++) {
            DependencySpec spec = specs.get(i);
            int hostPort = ports.get(i);
            yaml.append("  ").append(yamlQuote(spec.getName())).append(":\n");
            yaml.append("    image: ").append(yamlQuote(spec.getImage())).append('\n');
            yaml.append("    container_name: ").append(yamlQuote(containerName(envCode, spec.getName()))).append('\n');
            yaml.append("    ports:\n");
            yaml.append("      - ").append(yamlQuote(hostPort + ":" + spec.getContainerPort())).append('\n');
            if (!spec.getEnvironment().isEmpty()) {
                yaml.append("    environment:\n");
                for (Map.Entry<String, String> entry : spec.getEnvironment().entrySet()) {
                    yaml.append("      ").append(entry.getKey()).append(": ").append(yamlQuote(entry.getValue())).append('\n');
                }
            }
            if (containerConfig.getMemoryLimit() != null && containerConfig.getMemoryLimit() > 0) {
                yaml.append("    mem_limit: ").append(yamlQuote(containerConfig.getMemoryLimit() + "m")).append('\n');
            }
            if (containerConfig.getCpuLimit() != null && containerConfig.getCpuLimit() > 0) {
                yaml.append("    cpus: ").append(yamlQuote(formatCpu(containerConfig.getCpuLimit()))).append('\n');
            }
            if (containerConfig.getRestartPolicy() != null && !containerConfig.getRestartPolicy().isBlank()) {
                yaml.append("    restart: ").append(yamlQuote(containerConfig.getRestartPolicy().trim())).append('\n');
            }
            if (containerConfig.getDefaultNetwork() != null && !containerConfig.getDefaultNetwork().isBlank()) {
                yaml.append("    network_mode: ").append(yamlQuote(containerConfig.getDefaultNetwork().trim())).append('\n');
            }
            yaml.append("    labels:\n");
            yaml.append("      sentinel.env: ").append(yamlQuote(envCode)).append('\n');
            yaml.append("      sentinel.task: ").append(yamlQuote(String.valueOf(taskId))).append('\n');
        }
        return yaml.toString();
    }

    private String formatCpu(Double cpuLimit) {
        if (cpuLimit == Math.rint(cpuLimit)) {
            return String.valueOf(cpuLimit.intValue());
        }
        return String.valueOf(cpuLimit);
    }

    private String yamlQuote(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String containerName(String envCode, String dependency) {
        String cleaned = dependency.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
        if (cleaned.isBlank()) {
            cleaned = "dep";
        }
        return "sentinel-env-" + envCode + "-" + cleaned;
    }

    private String newEnvCode() {
        byte[] bytes = new byte[4];
        ThreadLocalRandom.current().nextBytes(bytes);
        StringBuilder code = new StringBuilder("env-");
        for (byte value : bytes) {
            code.append(Character.forDigit((value >> 4) & 0xF, 16));
            code.append(Character.forDigit(value & 0xF, 16));
        }
        return code.toString();
    }

    private Duration ttl() {
        Duration configured = provisioningProperties.getTtl();
        if (configured == null || configured.isZero() || configured.isNegative()) {
            return Duration.ofHours(2);
        }
        return configured;
    }

    private String resolveHost() {
        String host = provisioningProperties.getHost();
        if (host == null || host.isBlank()) {
            return "localhost";
        }
        return host.trim();
    }

    private static final class RunningContainer {
        private final DependencySpec spec;
        private final int hostPort;
        private final String containerId;
        private final boolean healthcheckDefined;
        private final Container row;

        private RunningContainer(DependencySpec spec,
                                 int hostPort,
                                 String containerId,
                                 boolean healthcheckDefined,
                                 Container row) {
            this.spec = spec;
            this.hostPort = hostPort;
            this.containerId = containerId;
            this.healthcheckDefined = healthcheckDefined;
            this.row = row;
        }
    }
}
