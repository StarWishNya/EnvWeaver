package com.sentinel.controller;

import com.sentinel.dto.ComponentHealth;
import com.sentinel.service.MetricsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查控制器。匿名可访问。
 * 数据库或 Redis 为 DOWN 时返回 503；仅 Docker 为 DOWN 时仍返回 200。
 *
 * @author Sentinel Team
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final MetricsService metricsService;

    public HealthController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        ComponentHealth.Components components = metricsService.checkHealth().getComponents();
        boolean coreDown = !"UP".equals(components.getDb()) || !"UP".equals(components.getRedis());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", coreDown ? "DOWN" : "UP");
        response.put("timestamp", LocalDateTime.now());
        response.put("application", "Sentinel");
        response.put("version", "1.0.0-SNAPSHOT");
        response.put("components", components);

        HttpStatus httpStatus = coreDown ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
        return ResponseEntity.status(httpStatus).body(response);
    }
}
