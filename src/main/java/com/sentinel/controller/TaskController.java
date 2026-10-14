package com.sentinel.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sentinel.domain.Task;
import com.sentinel.dto.ApiResponse;
import com.sentinel.dto.CreateTaskRequest;
import com.sentinel.dto.PageResponse;
import com.sentinel.dto.TaskLogsResponse;
import com.sentinel.dto.TaskResponse;
import com.sentinel.dto.TaskStatusResponse;
import com.sentinel.service.ContainerLogWindow;
import com.sentinel.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
     * 创建任务。HTTP 201，响应体仍使用统一的业务码。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "创建任务", description = "创建一个新的测试任务")
    public ApiResponse<TaskResponse> createTask(@Valid @RequestBody CreateTaskRequest request) {
        log.info("创建任务请求: taskCode={}", request.getTaskCode());
        Task task = taskService.createTask(request);
        return ApiResponse.success(toResponse(task));
    }

    /**
     * 获取任务详情
     */
    @GetMapping("/{taskId}")
    @Operation(summary = "获取任务详情", description = "根据任务 ID 获取任务详细信息")
    public ApiResponse<TaskResponse> getTask(
            @Parameter(description = "任务 ID") @PathVariable Long taskId) {
        Task task = taskService.getTaskById(taskId);
        return ApiResponse.success(toResponse(task));
    }

    /**
     * 分页查询任务列表。page 从 0 开始，size 默认 20，最大 500。
     */
    @GetMapping
    @Operation(summary = "分页查询任务列表", description = "分页查询任务列表，支持按状态和测试类型过滤")
    public ApiResponse<PageResponse<TaskResponse>> listTasks(
            @Parameter(description = "页码，从 0 开始") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页大小，1 到 500") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "任务状态") @RequestParam(required = false) String status,
            @Parameter(description = "测试类型") @RequestParam(required = false) String testType) {
        TaskService.checkListQuery(page, size, status, testType);
        IPage<Task> result = taskService.listTasks(page, size, status, testType);
        Map<Long, Integer> positions = queuePositions();
        PageResponse<TaskResponse> body = new PageResponse<>();
        body.setContent(result.getRecords().stream().map(task -> TaskResponse.from(task, positions)).toList());
        body.setPage(page);
        body.setSize(size);
        body.setTotalElements(result.getTotal());
        body.setTotalPages(totalPages(result.getTotal(), size));
        return ApiResponse.success(body);
    }

    private TaskResponse toResponse(Task task) {
        return TaskResponse.from(task, queuePositions());
    }

    private Map<Long, Integer> queuePositions() {
        Map<Long, Integer> positions = taskService.queuePositions();
        return positions == null ? Map.of() : positions;
    }

    private static long totalPages(long totalElements, int size) {
        if (totalElements <= 0) {
            return 0;
        }
        return (totalElements + size - 1) / size;
    }

    /**
     * 根据状态查询任务列表
     */
    @GetMapping("/status/{status}")
    @Operation(summary = "根据状态查询任务", description = "查询指定状态的所有任务")
    public ApiResponse<List<TaskResponse>> listTasksByStatus(
            @Parameter(description = "任务状态") @PathVariable String status) {
        Map<Long, Integer> positions = queuePositions();
        List<TaskResponse> tasks = taskService.listTasksByStatus(status).stream()
                .map(task -> TaskResponse.from(task, positions))
                .toList();
        return ApiResponse.success(tasks);
    }

    /**
     * 获取单个任务的状态。路径限定为数字 ID，避免和 GET /status/{status} 冲突。
     */
    @GetMapping("/{taskId:[0-9]+}/status")
    @Operation(summary = "获取任务状态", description = "返回任务状态和进度")
    public ApiResponse<TaskStatusResponse> getTaskStatus(
            @Parameter(description = "任务 ID") @PathVariable Long taskId) {
        log.info("查询任务状态: taskId={}", taskId);
        return ApiResponse.success(taskService.getTaskStatus(taskId));
    }

    /**
     * 获取任务日志。tail 默认 100，合法范围 1..5000；since 为 ISO-8601 日期时间。
     */
    @GetMapping("/{taskId:[0-9]+}/logs")
    @Operation(summary = "获取任务日志", description = "返回环境容器日志，可按行数和时间过滤")
    public ApiResponse<TaskLogsResponse> getTaskLogs(
            @Parameter(description = "任务 ID") @PathVariable Long taskId,
            @Parameter(description = "返回最后 N 行") @RequestParam(defaultValue = "100") int tail,
            @Parameter(description = "从指定时间开始") @RequestParam(required = false) String since) {
        ContainerLogWindow.checkTail(tail);
        ContainerLogWindow.parseSince(since);
        log.info("查询任务日志: taskId={}, tail={}", taskId, tail);
        return ApiResponse.success(taskService.getTaskLogs(taskId, tail, since));
    }

    /**
     * 取消任务。已结束返回 400，并发冲突返回 409，不存在返回 404。
     */
    @DeleteMapping("/{taskId}")
    @Operation(summary = "取消任务", description = "取消指定的任务")
    public ApiResponse<Void> deleteTask(
            @Parameter(description = "任务 ID") @PathVariable Long taskId) {
        log.info("取消任务请求: taskId={}", taskId);
        taskService.cancelTask(taskId);
        return ApiResponse.success("任务已取消", null);
    }

    /**
     * 旧的取消入口，行为与 DELETE /api/tasks/{taskId} 相同。
     */
    @Deprecated
    @PostMapping("/{taskId}/cancel")
    @Operation(summary = "取消任务（已废弃）", description = "请改用 DELETE /api/tasks/{taskId}", deprecated = true)
    public ApiResponse<Void> cancelTask(
            @Parameter(description = "任务 ID") @PathVariable Long taskId) {
        log.info("取消任务请求(已废弃): taskId={}", taskId);
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
