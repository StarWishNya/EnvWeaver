package com.sentinel.controller;

import com.sentinel.dto.ApiResponse;
import com.sentinel.dto.DiagnosisAcceptedResponse;
import com.sentinel.dto.DiagnosisRequest;
import com.sentinel.dto.DiagnosisResultDTO;
import com.sentinel.dto.DiagnosisSubmission;
import com.sentinel.service.DiagnosisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 诊断接口。认证由默认安全配置要求，本控制器不再单独放行。
 *
 * @author Sentinel Team
 */
@Slf4j
@RestController
@RequestMapping("/api/diagnosis")
@Tag(name = "AI 诊断", description = "提交并查询任务诊断结果")
public class DiagnosisController {

    private final DiagnosisService diagnosisService;

    public DiagnosisController(DiagnosisService diagnosisService) {
        this.diagnosisService = diagnosisService;
    }

    /**
     * 请求诊断。HTTP 202 的 data 只有 diagnosisId 和 status。
     * 是否来自缓存写在响应体外层的 fromCache，避免 data 里再出现一个同名字段。
     */
    @PostMapping("/{taskId}")
    @Operation(summary = "请求 AI 诊断", description = "异步诊断任务日志。缓存命中时 status 为 COMPLETED")
    public ResponseEntity<ApiResponse<DiagnosisAcceptedResponse>> requestDiagnosis(
            @Parameter(description = "任务 ID") @PathVariable Long taskId,
            @RequestBody(required = false) DiagnosisRequest request) {
        log.info("请求诊断: taskId={}", taskId);
        DiagnosisSubmission submission = diagnosisService.requestDiagnosis(taskId, request);
        ApiResponse<DiagnosisAcceptedResponse> body = ApiResponse.success(submission.getResponse())
                .fromCache(submission.isFromCache());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    /**
     * 查询诊断。data.fromCache 与外层 fromCache 写成同一个值。
     */
    @GetMapping("/{taskId}")
    @Operation(summary = "获取任务诊断结果", description = "返回最近一次诊断的状态和结果")
    public ApiResponse<DiagnosisResultDTO> getDiagnosis(
            @Parameter(description = "任务 ID") @PathVariable Long taskId) {
        DiagnosisResultDTO result = diagnosisService.getDiagnosis(taskId);
        boolean fromCache = Boolean.TRUE.equals(result.getFromCache());
        return ApiResponse.success(result).fromCache(fromCache);
    }
}
