package com.sentinel.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sentinel.config.BusinessException;
import com.sentinel.domain.Task;
import com.sentinel.dto.CreateTaskRequest;
import com.sentinel.enums.ErrorCode;
import com.sentinel.mapper.TaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务服务
 * 
 * @author Sentinel Team
 */
@Slf4j
@Service
public class TaskService {

    private final TaskMapper taskMapper;

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
        task.setDependencies(request.getDependencies());
        task.setStatus("PENDING");
        task.setPriority(request.getPriority());
        
        taskMapper.insert(task);
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
}
