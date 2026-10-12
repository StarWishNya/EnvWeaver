package com.sentinel.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.BusinessException;
import com.sentinel.domain.Container;
import com.sentinel.domain.Task;
import com.sentinel.dto.CreateTaskRequest;
import com.sentinel.dto.TaskLogsResponse;
import com.sentinel.dto.TaskStatusResponse;
import com.sentinel.enums.ErrorCode;
import com.sentinel.event.TaskCreatedEvent;
import com.sentinel.event.TaskStatusChangedEvent;
import com.sentinel.mapper.TaskMapper;
import com.sentinel.service.provision.DependencyCatalog;
import com.sentinel.service.provision.TemplateCatalog;
import com.sentinel.service.provision.TemplateExpander;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 任务服务
 * 
 * @author Sentinel Team
 */
@Slf4j
@Service
public class TaskService {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 500;

    public static final String PAGE_NEGATIVE_MESSAGE = "page 不能小于 0";
    public static final String PAGE_SIZE_MESSAGE = "size 必须在 1 到 500 之间，大于 500 不会被静默截断";
    public static final String STATUS_FILTER_MESSAGE = "任务状态不合法";
    public static final String TEST_TYPE_FILTER_MESSAGE = "测试类型不合法";

    private static final Set<String> TASK_STATUSES = Set.of(
            "PENDING", "QUEUED", "RUNNING", "SUCCESS", "FAILED", "CANCELLED");

    private static final Set<String> TEST_TYPES = Set.of(
            "UNIT_TEST", "INTEGRATION_TEST", "E2E_TEST");

    private static final int DEFAULT_PRIORITY = 5;

    private final TaskMapper taskMapper;
    private final DockerService dockerService;
    private final ApplicationEventPublisher eventPublisher;
    private final TemplateCatalog templateCatalog;
    private final DependencyCatalog dependencyCatalog;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TaskService(TaskMapper taskMapper,
                       DockerService dockerService,
                       ApplicationEventPublisher eventPublisher,
                       TemplateCatalog templateCatalog,
                       DependencyCatalog dependencyCatalog) {
        this.taskMapper = taskMapper;
        this.dockerService = dockerService;
        this.eventPublisher = eventPublisher;
        this.templateCatalog = templateCatalog;
        this.dependencyCatalog = dependencyCatalog;
    }

    /**
     * 校验列表查询参数。空的 status / testType 表示不过滤。
     */
    public static void checkListQuery(int page, int size, String status, String testType) {
        if (page < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, PAGE_NEGATIVE_MESSAGE);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, PAGE_SIZE_MESSAGE);
        }
        if (StringUtils.hasText(status) && !TASK_STATUSES.contains(status)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, STATUS_FILTER_MESSAGE);
        }
        if (StringUtils.hasText(testType) && !TEST_TYPES.contains(testType)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, TEST_TYPE_FILTER_MESSAGE);
        }
    }

    /**
     * 创建任务。事务提交前发布 TaskCreatedEvent，由监听器在提交后处理。
     * 
     * @param request 创建任务请求
     * @return 任务对象
     */
    @Transactional
    public Task createTask(CreateTaskRequest request) {
        List<String> dependencies = TemplateExpander.expand(
                templateCatalog, request.getTemplate(), request.getDependencies());
        dependencyCatalog.resolveTokens(dependencies);
        Task task = new Task();
        task.setTaskCode(request.getTaskCode());
        task.setServiceId(request.getServiceId());
        task.setTestType(request.getTestType());
        task.setDependencies(normalizeDependencies(dependencies));
        task.setStatus("PENDING");
        task.setPriority(request.getPriority() == null ? DEFAULT_PRIORITY : request.getPriority());

        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.RESOURCE_ALREADY_EXISTS, "任务编码已存在");
        }
        eventPublisher.publishEvent(new TaskCreatedEvent(task.getId()));
        log.info("任务创建成功: taskId={}, taskCode={}", task.getId(), task.getTaskCode());
        
        return task;
    }

    /**
     * 根据 ID 获取任务
     * 
     * @param taskId 任务 ID
     * @return 任务对象
     */
    public Task getTaskById(Long taskId) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }
        return task;
    }

    /**
     * 分页查询任务列表。page 从 0 开始，传入 MyBatis-Plus 时加 1。
     *
     * @param page 页码（从 0 开始）
     * @param size 每页大小
     * @param status 任务状态（可选）
     * @param testType 测试类型（可选）
     * @return 分页结果
     */
    public IPage<Task> listTasks(int page, int size, String status, String testType) {
        checkListQuery(page, size, status, testType);
        Page<Task> mpPage = new Page<>(page + 1L, size);
        return taskMapper.selectTaskPage(mpPage, emptyToNull(status), emptyToNull(testType));
    }

    /**
     * 根据状态查询任务列表
     * 
     * @param status 任务状态
     * @return 任务列表
     */
    public List<Task> listTasksByStatus(String status) {
        return taskMapper.findByStatus(status);
    }

    /**
     * 更新任务状态。状态没变时不写库、也不发事件。
     * 
     * @param taskId 任务 ID
     * @param status 新状态
     */
    @Transactional
    public void updateTaskStatus(Long taskId, String status) {
        if (!TASK_STATUSES.contains(status)) {
            throw new BusinessException(ErrorCode.TASK_STATUS_INVALID);
        }
        Task task = getTaskById(taskId);
        String oldStatus = task.getStatus();
        if (Objects.equals(oldStatus, status)) {
            log.info("任务状态未变化，跳过: taskId={}, status={}", taskId, status);
            return;
        }
        if ("QUEUED".equals(oldStatus) && !"CANCELLED".equals(status)) {
            throw new BusinessException(ErrorCode.OPERATION_NOT_ALLOWED, "排队中的任务只能取消");
        }
        task.setStatus(status);
        applyStatusTimestamps(task, status);
        
        taskMapper.updateById(task);
        eventPublisher.publishEvent(new TaskStatusChangedEvent(taskId, oldStatus, status));
        log.info("任务状态更新: taskId={}, {} -> {}", taskId, oldStatus, status);
    }

    /**
     * 比较并设置状态。当前状态不是 expected 时返回 false，不发事件。
     * 进入 RUNNING 写 startedAt，进入终态写 completedAt，与 updateTaskStatus 一致。
     */
    @Transactional
    public boolean transitionStatus(Long taskId, String expected, String next) {
        if (!TASK_STATUSES.contains(expected) || !TASK_STATUSES.contains(next)) {
            throw new BusinessException(ErrorCode.TASK_STATUS_INVALID);
        }
        LocalDateTime startedAt = "RUNNING".equals(next) ? LocalDateTime.now() : null;
        LocalDateTime completedAt = isTerminal(next) ? LocalDateTime.now() : null;
        int updated = taskMapper.compareAndSetStatus(taskId, expected, next, startedAt, completedAt);
        if (updated == 0) {
            log.info("任务状态切换未命中: taskId={}, expected={}, next={}", taskId, expected, next);
            return false;
        }
        if (!Objects.equals(expected, next)) {
            eventPublisher.publishEvent(new TaskStatusChangedEvent(taskId, expected, next));
        }
        log.info("任务状态切换: taskId={}, {} -> {}", taskId, expected, next);
        return true;
    }

    /**
     * 比较并设置 PENDING→QUEUED，并在首次排队时写入 queuedAt。不是 PENDING 时返回 false，不发事件。
     */
    @Transactional
    public boolean markQueued(Long taskId, LocalDateTime queuedAt) {
        if (taskId == null || queuedAt == null) {
            return false;
        }
        int updated = taskMapper.markQueued(taskId, queuedAt);
        if (updated == 0) {
            log.info("任务未能进入排队: taskId={}", taskId);
            return false;
        }
        eventPublisher.publishEvent(new TaskStatusChangedEvent(taskId, "PENDING", "QUEUED"));
        log.info("任务进入排队: taskId={}, queuedAt={}", taskId, queuedAt);
        return true;
    }

    /**
     * 出队认领：QUEUED 且已有 queuedAt 时改回 PENDING 并清空 queuedAt。
     * 条件更新保证并发出队只有一个成功。供给中的 QUEUED 不匹配。
     */
    @Transactional
    public boolean claimQueued(Long taskId) {
        if (taskId == null) {
            return false;
        }
        int updated = taskMapper.claimQueued(taskId);
        if (updated == 0) {
            log.info("出队未命中: taskId={}", taskId);
            return false;
        }
        eventPublisher.publishEvent(new TaskStatusChangedEvent(taskId, "QUEUED", "PENDING"));
        log.info("任务出队: taskId={}", taskId);
        return true;
    }

    /**
     * 正在排队的任务，已按出队顺序排好。没有则返回空列表。
     */
    public List<Task> listWaitingQueued() {
        List<Task> rows = taskMapper.findWaitingQueued();
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Task> ordered = new ArrayList<>();
        for (Task task : rows) {
            if (task != null) {
                ordered.add(task);
            }
        }
        ordered.sort(QueueOrder.COMPARATOR);
        return ordered;
    }

    /**
     * 排队位次，从 1 开始。只包含正在排队的任务。
     */
    public Map<Long, Integer> queuePositions() {
        List<Task> waiting = listWaitingQueued();
        Map<Long, Integer> positions = new LinkedHashMap<>();
        int position = 1;
        for (Task task : waiting) {
            if (task.getId() != null) {
                positions.put(task.getId(), position++);
            }
        }
        return positions;
    }

    /**
     * 读取任务。不存在时返回 null，不抛 404。
     */
    public Task lookupTask(Long taskId) {
        if (taskId == null) {
            return null;
        }
        return taskMapper.selectById(taskId);
    }

    /**
     * 把环境挂到任务上。任务不存在时 404。
     */
    @Transactional
    public void attachEnvironment(Long taskId, Long environmentId) {
        int updated = taskMapper.updateEnvironmentId(taskId, environmentId);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }
        log.info("任务已关联环境: taskId={}, environmentId={}", taskId, environmentId);
    }

    /**
     * 取消任务
     * 
     * @param taskId 任务 ID
     */
    @Transactional
    public void cancelTask(Long taskId) {
        Task task = getTaskById(taskId);
        
        if (isTerminal(task.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_NOT_ALLOWED, "任务已完成，无法取消");
        }
        
        updateTaskStatus(taskId, "CANCELLED");
        log.info("任务已取消: taskId={}", taskId);
    }

    /**
     * 设置任务错误信息。已经是 FAILED 时仍更新错误信息，但不重复发状态事件。
     * 
     * @param taskId 任务 ID
     * @param errorMessage 错误信息
     */
    @Transactional
    public void setTaskError(Long taskId, String errorMessage) {
        Task task = getTaskById(taskId);
        String oldStatus = task.getStatus();
        task.setErrorMessage(errorMessage);
        task.setStatus("FAILED");
        task.setCompletedAt(LocalDateTime.now());
        
        taskMapper.updateById(task);
        if (!"FAILED".equals(oldStatus)) {
            eventPublisher.publishEvent(new TaskStatusChangedEvent(taskId, oldStatus, "FAILED"));
        }
        log.error("任务执行失败: taskId={}, error={}", taskId, errorMessage);
    }

    /**
     * 任务状态与进度。没有进度列时按状态估算。
     */
    public TaskStatusResponse getTaskStatus(Long taskId) {
        Task task = getTaskById(taskId);
        TaskStatusResponse response = new TaskStatusResponse();
        response.setTaskId(task.getId());
        response.setStatus(task.getStatus());
        response.setProgress(progressOf(task.getStatus()));
        return response;
    }

    /**
     * 任务日志。tail 必须在 1..5000；since 非法时 400。
     * 传了 since 时交给 Docker 的 since 过滤；行首仍带 Docker 时间戳的，再用窗口兜底并去掉前缀。
     */
    public TaskLogsResponse getTaskLogs(Long taskId, int tail, String since) {
        ContainerLogWindow.checkTail(tail);
        OffsetDateTime sinceTime = ContainerLogWindow.parseSince(since);
        Task task = getTaskById(taskId);
        return collectLogs(task, tail, sinceTime);
    }

    /**
     * PENDING 0、QUEUED 10、RUNNING 50，终态 100。未知状态记 0。
     */
    static int progressOf(String status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case "PENDING" -> 0;
            case "QUEUED" -> 10;
            case "RUNNING" -> 50;
            case "SUCCESS", "FAILED", "CANCELLED" -> 100;
            default -> 0;
        };
    }

    private TaskLogsResponse collectLogs(Task task, int tail, OffsetDateTime sinceTime) {
        TaskLogsResponse response = new TaskLogsResponse();
        Long environmentId = task.getEnvironmentId();
        if (environmentId == null) {
            response.setLogs(fallbackLogs(task));
            response.setHasMore(false);
            return response;
        }

        List<Container> containers;
        try {
            containers = dockerService.getContainersByEnvironmentId(environmentId);
        } catch (Exception ex) {
            log.warn("查询环境容器失败，已跳过: environmentId={}, reason={}", environmentId, ex.getMessage());
            response.setLogs(fallbackLogs(task));
            response.setHasMore(false);
            return response;
        }
        if (containers == null || containers.isEmpty()) {
            response.setLogs(fallbackLogs(task));
            response.setHasMore(false);
            return response;
        }

        StringBuilder body = new StringBuilder();
        boolean hasMore = false;
        boolean sawContainer = false;
        for (Container container : containers) {
            if (container == null || !StringUtils.hasText(container.getContainerId())) {
                continue;
            }
            sawContainer = true;
            String raw;
            try {
                raw = readContainerLogs(container.getContainerId(), tail, sinceTime);
            } catch (Exception ex) {
                log.warn("读取容器日志失败，已跳过: containerId={}, reason={}",
                        container.getContainerId(), ex.getMessage());
                continue;
            }
            if (ContainerLogWindow.countLines(raw) >= tail) {
                hasMore = true;
            }
            String filtered = ContainerLogWindow.filterSince(raw, sinceTime);
            if (sinceTime != null && ContainerLogWindow.uniformDockerTimestampPrefixes(raw)) {
                filtered = ContainerLogWindow.stripDockerTimestampPrefixes(filtered);
            }
            if (!StringUtils.hasText(filtered)) {
                continue;
            }
            if (body.length() > 0) {
                body.append('\n');
            }
            String name = StringUtils.hasText(container.getContainerName())
                    ? container.getContainerName() : container.getContainerId();
            body.append("--- container ").append(name).append(" ---\n");
            body.append(filtered.trim());
        }

        if (!sawContainer) {
            response.setLogs(fallbackLogs(task));
            response.setHasMore(false);
            return response;
        }
        response.setLogs(body.toString());
        response.setHasMore(hasMore);
        return response;
    }

    /**
     * 没传 since 时走原来的 tail 接口。传了 since 时把 Instant 交给 Docker。
     */
    private String readContainerLogs(String containerId, int tail, OffsetDateTime sinceTime) {
        if (sinceTime == null) {
            return dockerService.getContainerLogs(containerId, tail);
        }
        return dockerService.getContainerLogs(containerId, tail, sinceTime.toInstant());
    }

    private static void applyStatusTimestamps(Task task, String status) {
        if ("RUNNING".equals(status)) {
            task.setStartedAt(LocalDateTime.now());
        } else if (isTerminal(status)) {
            task.setCompletedAt(LocalDateTime.now());
        }
    }

    private static boolean isTerminal(String status) {
        return "SUCCESS".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private static String fallbackLogs(Task task) {
        if (StringUtils.hasText(task.getErrorMessage())) {
            return task.getErrorMessage();
        }
        return "";
    }

    private static String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    /**
     * 依赖列是 MySQL JSON。空列表写成 null；非空序列化成 JSON 数组。
     */
    private String normalizeDependencies(List<String> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(dependencies);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "依赖列表必须是合法 JSON");
        }
    }
}
