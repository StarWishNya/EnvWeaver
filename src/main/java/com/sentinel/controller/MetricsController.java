package com.sentinel.controller;

import com.sentinel.dto.ApiResponse;
import com.sentinel.dto.ComponentHealth;
import com.sentinel.dto.SystemMetrics;
import com.sentinel.service.MetricsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统指标与依赖健康检查。
 * /api/metrics 走默认认证；/api/metrics/health 在安全配置中匿名放行。
 *
 * @author Sentinel Team
 */
@RestController
@RequestMapping("/api/metrics")
@Tag(name = "监控指标", description = "系统资源指标与依赖健康检查")
public class MetricsController {

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    /**
     * 系统指标。data 为 SystemMetrics。
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "获取系统指标", description = "返回 CPU、内存、磁盘和容器数量")
    public ApiResponse<SystemMetrics> getSystemMetrics() {
        return ApiResponse.success(metricsService.getSystemMetrics());
    }

    /**
     * 依赖健康检查。正文只有 status 和 components，不包 ApiResponse。
     * 任一依赖不是 UP 时 HTTP 503。
     */
    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "健康检查", description = "匿名检查数据库、Redis 和 Docker")
    public ResponseEntity<ComponentHealth> health() {
        ComponentHealth health = metricsService.checkHealth();
        HttpStatus httpStatus = "UP".equals(health.getStatus())
                ? HttpStatus.OK
                : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(httpStatus).body(health);
    }
}
