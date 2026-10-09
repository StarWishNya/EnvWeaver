package com.sentinel.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.config.BusinessException;
import com.sentinel.domain.Task;
import com.sentinel.dto.CreateTaskRequest;
import com.sentinel.enums.ErrorCode;
import com.sentinel.mapper.TaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 任务服务
 * 
 * @author Sentinel Team
 */
@Slf4j
@Service
public class TaskService {

    private static final Set<String> TASK_STATUSES = Set.of(
            "PENDING", "QUEUED", "RUNNING", "SUCCESS", "FAILED", "CANCELLED");

    private final TaskMapper taskMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TaskService(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    /**
     * 创建任务
     * 
     * @param request 创建任务请求
     * @return 任务对象
     */
    @Transactional
    public Task createTask(CreateTaskRequest request) {
        Task task = new Task();
        task.setTaskCode(request.getTaskCode());
        task.setServiceId(request.getServiceId());
        task.setTestType(request.getTestType());
        task.setDependencies(normalizeDependencies(request.getDependencies()));
        task.setStatus("PENDING");
        task.setPriority(request.getPriority());

        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.RESOURCE_ALREADY_EXISTS, "任务编码已存在");
        }
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
     * 分页查询任务列表
     * 
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @param status 任务状态（可选）
     * @return 分页结果
     */
    public IPage<Task> listTasks(int pageNum, int pageSize, String status) {
        Page<Task> page = new Page<>(pageNum, pageSize);
        return taskMapper.selectTaskPage(page, status);
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
     * 更新任务状态
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
        task.setStatus(status);
        
        if ("RUNNING".equals(status)) {
            task.setStartedAt(LocalDateTime.now());
        } else if ("SUCCESS".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
            task.setCompletedAt(LocalDateTime.now());
        }
        
        taskMapper.updateById(task);
        log.info("任务状态更新: taskId={}, status={}", taskId, status);
    }

    /**
     * 取消任务
     * 
     * @param taskId 任务 ID
     */
    @Transactional
    public void cancelTask(Long taskId) {
        Task task = getTaskById(taskId);
        
        if ("SUCCESS".equals(task.getStatus()) || "FAILED".equals(task.getStatus()) || "CANCELLED".equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_NOT_ALLOWED, "任务已完成，无法取消");
        }
        
        updateTaskStatus(taskId, "CANCELLED");
        log.info("任务已取消: taskId={}", taskId);
    }

    /**
     * 设置任务错误信息
     * 
     * @param taskId 任务 ID
     * @param errorMessage 错误信息
     */
    @Transactional
    public void setTaskError(Long taskId, String errorMessage) {
        Task task = getTaskById(taskId);
        task.setErrorMessage(errorMessage);
        task.setStatus("FAILED");
        task.setCompletedAt(LocalDateTime.now());
        
        taskMapper.updateById(task);
        log.error("任务执行失败: taskId={}, error={}", taskId, errorMessage);
    }

    /**
     * 依赖列是 MySQL JSON。空值写成 null；非法 JSON 在入库前拒绝，避免 500。
     */
    private String normalizeDependencies(String dependencies) {
        if (!StringUtils.hasText(dependencies)) {
            return null;
        }
        try {
            objectMapper.readTree(dependencies);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "依赖列表必须是合法 JSON");
        }
        return dependencies;
    }
}
