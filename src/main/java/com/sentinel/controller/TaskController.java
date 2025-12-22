package com.sentinel.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sentinel.domain.Task;
import com.sentinel.dto.ApiResponse;
import com.sentinel.dto.CreateTaskRequest;
import com.sentinel.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 任务管理控制器
 * 
 * @author Sentinel Team
 */
@Slf4j
@RestController
@RequestMapping("/api/tasks")
@Tag(name = "任务管理", description = "任务的创建、查询、取消等操作")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 创建任务
     */
    @PostMapping
    @Operation(summary = "创建任务", description = "创建一个新的测试任务")
    public ApiResponse<Task> createTask(@Valid @RequestBody CreateTaskRequest request) {
        log.info("创建任务请求: taskCode={}", request.getTaskCode());
        Task task = taskService.createTask(request);
        return ApiResponse.success(task);
    }

    /**
     * 获取任务详情
     */
    @GetMapping("/{taskId}")
    @Operation(summary = "获取任务详情", description = "根据任务 ID 获取任务详细信息")
    public ApiResponse<Task> getTask(
            @Parameter(description = "任务 ID") @PathVariable Long taskId) {
        Task task = taskService.getTaskById(taskId);
        return ApiResponse.success(task);
    }

    /**
     * 分页查询任务列表
     */
    @GetMapping
    @Operation(summary = "分页查询任务列表", description = "分页查询任务列表，支持按状态过滤")
    public ApiResponse<IPage<Task>> listTasks(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "10") int pageSize,
            @Parameter(description = "任务状态") @RequestParam(required = false) String status) {
        IPage<Task> page = taskService.listTasks(pageNum, pageSize, status);
        return ApiResponse.success(page);
    }

    /**
     * 根据状态查询任务列表
     */
    @GetMapping("/status/{status}")
    @Operation(summary = "根据状态查询任务", description = "查询指定状态的所有任务")
    public ApiResponse<List<Task>> listTasksByStatus(
            @Parameter(description = "任务状态") @PathVariable String status) {
        List<Task> tasks = taskService.listTasksByStatus(status);
        return ApiResponse.success(tasks);
    }

    /**
     * 取消任务
     */
    @PostMapping("/{taskId}/cancel")
    @Operation(summary = "取消任务", description = "取消指定的任务")
    public ApiResponse<Void> cancelTask(
            @Parameter(description = "任务 ID") @PathVariable Long taskId) {
        log.info("取消任务请求: taskId={}", taskId);
        taskService.cancelTask(taskId);
        return ApiResponse.success("任务已取消", null);
    }

    /**
     * 更新任务状态（管理员）
     */
    @PutMapping("/{taskId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "更新任务状态", description = "更新任务状态（仅管理员）")
    public ApiResponse<Void> updateTaskStatus(
            @Parameter(description = "任务 ID") @PathVariable Long taskId,
            @Parameter(description = "新状态") @RequestParam String status) {
        log.info("更新任务状态: taskId={}, status={}", taskId, status);
        taskService.updateTaskStatus(taskId, status);
        return ApiResponse.success("任务状态已更新", null);
    }
}
