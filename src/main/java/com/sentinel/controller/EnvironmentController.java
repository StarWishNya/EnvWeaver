package com.sentinel.controller;

import com.sentinel.dto.ApiResponse;
import com.sentinel.dto.EnvironmentDTO;
import com.sentinel.service.EnvironmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 环境管理控制器
 *
 * @author Sentinel Team
 */
@RestController
@RequestMapping("/api/environments")
@Tag(name = "环境管理", description = "环境查询与释放")
public class EnvironmentController {

    private final EnvironmentService environmentService;

    public EnvironmentController(EnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    /**
     * 获取环境列表
     */
    @GetMapping
    @Operation(summary = "获取环境列表", description = "查询环境列表，可按状态过滤")
    public ApiResponse<List<EnvironmentDTO>> listEnvironments(
            @Parameter(description = "环境状态：CREATING、READY、BUSY、RELEASING、IDLE、ERROR")
            @RequestParam(required = false) String status) {
        return ApiResponse.success(environmentService.listEnvironments(status));
    }

    /**
     * 获取环境详情
     */
    @GetMapping("/{envId}")
    @Operation(summary = "获取环境详情", description = "根据环境 ID 获取环境信息")
    public ApiResponse<EnvironmentDTO> getEnvironment(
            @Parameter(description = "环境 ID") @PathVariable Long envId) {
        return ApiResponse.success(environmentService.getEnvironment(envId));
    }

    /**
     * 释放环境
     */
    @PostMapping("/{envId}/release")
    @Operation(summary = "释放环境", description = "停止并删除环境中的容器。全部成功则环境置为空闲；任一容器失败则环境置为 ERROR 并返回 503")
    public ApiResponse<Void> releaseEnvironment(
            @Parameter(description = "环境 ID") @PathVariable Long envId) {
        environmentService.releaseEnvironment(envId);
        return ApiResponse.success();
    }
}
