package com.sentinel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.ai.AIRequest;
import com.sentinel.ai.AIResponse;
import com.sentinel.ai.AIRouter;
import com.sentinel.config.BusinessException;
import com.sentinel.domain.Container;
import com.sentinel.domain.DiagnosisCache;
import com.sentinel.domain.Task;
import com.sentinel.dto.DiagnosisRequest;
import com.sentinel.dto.DiagnosisResultDTO;
import com.sentinel.dto.DiagnosisSubmission;
import com.sentinel.enums.ErrorCode;
import com.sentinel.mapper.DiagnosisCacheMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 诊断服务：解析与钳制、缓存命中、缺输入、模型失败。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DiagnosisService 单元测试")
class DiagnosisServiceTest {

    @Mock
    private AIRouter aiRouter;

    @Mock
    private DiagnosisCacheMapper diagnosisCacheMapper;

    @Mock
    private CacheService cacheService;

    @Mock
    private TaskService taskService;

    @Mock
    private DockerService dockerService;

    @Mock
    private ThreadPoolTaskExecutor diagnosisExecutor;

    private final Map<Long, Object> taskStates = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private DiagnosisService diagnosisService;

    @BeforeEach
    void setUp() {
        lenient().doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(diagnosisExecutor).execute(any(Runnable.class));
        lenient().doAnswer(invocation -> {
            taskStates.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(cacheService).setTaskDiagnosis(anyLong(), any());
        lenient().when(cacheService.getTaskDiagnosis(anyLong()))
                .thenAnswer(invocation -> taskStates.get(invocation.getArgument(0)));

        diagnosisService = new DiagnosisService(
                aiRouter,
                diagnosisCacheMapper,
                cacheService,
                taskService,
                dockerService,
                objectMapper,
                diagnosisExecutor);
    }

    @Test
    @DisplayName("非法枚举和越界置信度会被钳制")
    void shouldClampInvalidEnumsAndConfidence() throws Exception {
        DiagnosisResultDTO high = diagnose(1L, "log-high", modelJson(
                "disk_full", "urgent", "磁盘满了", "日志占满磁盘", List.of("清理日志"), 2.5));
        assertThat(high.getStatus()).isEqualTo("COMPLETED");
        assertThat(high.getErrorType()).isEqualTo("UNKNOWN");
        assertThat(high.getSeverity()).isEqualTo("MEDIUM");
        assertThat(high.getSummary()).isEqualTo("磁盘满了");
        assertThat(high.getRootCause()).isEqualTo("日志占满磁盘");
        assertThat(high.getSuggestions()).containsExactly("清理日志");
        assertThat(high.getConfidence()).isEqualTo(1.0d);
        assertThat(high.getProvider()).isEqualTo("deepseek");
        assertThat(high.getModel()).isEqualTo("deepseek-flash");
        assertThat(high.getFromCache()).isFalse();

        DiagnosisResultDTO low = diagnose(2L, "log-low", modelJson(
                "timeout", "low", "调用超时", "下游超时", List.of(), -0.2));
        assertThat(low.getErrorType()).isEqualTo("TIMEOUT");
        assertThat(low.getSeverity()).isEqualTo("LOW");
        assertThat(low.getConfidence()).isEqualTo(0.0d);
    }

    @Test
    @DisplayName("能去掉 Markdown 围栏并解析前后夹杂文本的 JSON")
    void shouldParseFencedJsonAndSurroundingText() throws Exception {
        String json = modelJson(
                "NETWORK_ERROR", "HIGH", "数据库连不上", "mysql:3306 拒绝连接",
                List.of("检查端口"), 0.5);
        String fenced = "分析结果如下：\n```json\n" + json + "\n```\n以上。";

        DiagnosisResultDTO dto = diagnose(3L, "hikari", fenced);

        assertThat(dto.getErrorType()).isEqualTo("NETWORK_ERROR");
        assertThat(dto.getSeverity()).isEqualTo("HIGH");
        assertThat(dto.getSummary()).isEqualTo("数据库连不上");
        assertThat(dto.getRootCause()).isEqualTo("mysql:3306 拒绝连接");
        assertThat(dto.getConfidence()).isEqualTo(0.5d);
        assertThat(dto.getSuggestions()).containsExactly("检查端口");
    }

    @Test
    @DisplayName("字符串里的花括号不会截断 JSON")
    void shouldKeepBraceInsideString() throws Exception {
        DiagnosisResultDTO dto = diagnose(4L, "brace", modelJson(
                "CONFIG_ERROR", "CRITICAL", "包含}花括号", "配置缺失", List.of("补齐配置"), 0.25));

        assertThat(dto.getSummary()).isEqualTo("包含}花括号");
        assertThat(dto.getErrorType()).isEqualTo("CONFIG_ERROR");
        assertThat(dto.getSeverity()).isEqualTo("CRITICAL");
        assertThat(dto.getConfidence()).isEqualTo(0.25d);
    }

    @Test
    @DisplayName("无法解析时回退为截断文本")
    void shouldFallbackWhenUnparseable() {
        String raw = "错".repeat(700);
        DiagnosisResultDTO dto = diagnose(5L, "raw-log", raw);

        assertThat(dto.getStatus()).isEqualTo("COMPLETED");
        assertThat(dto.getErrorType()).isEqualTo("UNKNOWN");
        assertThat(dto.getSeverity()).isEqualTo("MEDIUM");
        assertThat(dto.getConfidence()).isNull();
        assertThat(dto.getSummary()).isEqualTo("错".repeat(DiagnosisService.SUMMARY_FALLBACK_MAX));
        assertThat(dto.getRootCause()).isEqualTo("错".repeat(DiagnosisService.ROOT_CAUSE_MAX));
        assertThat(dto.getSuggestions()).isEmpty();
        assertThat(dto.getError()).isNull();
    }

    @Test
    @DisplayName("root_cause 写入数据库时截断到 500 字")
    void shouldTruncateRootCauseTo500() throws Exception {
        String logText = "L".repeat(800);
        String content = modelJson(
                "RESOURCE_EXHAUSTED", "HIGH", "磁盘写满", "因".repeat(600), List.of("清理日志"), 0.5);
        when(aiRouter.complete(any(AIRequest.class)))
                .thenReturn(new AIResponse("deepseek", "deepseek-flash", content));
        when(taskService.getTaskById(6L)).thenReturn(task(6L));

        diagnosisService.requestDiagnosis(6L, requestWithLog(logText));

        ArgumentCaptor<DiagnosisCache> captor = ArgumentCaptor.forClass(DiagnosisCache.class);
        verify(diagnosisCacheMapper).insert(captor.capture());
        DiagnosisCache saved = captor.getValue();
        assertThat(saved.getRootCause()).hasSize(DiagnosisService.ROOT_CAUSE_MAX);
        assertThat(saved.getRootCause()).isEqualTo("因".repeat(DiagnosisService.ROOT_CAUSE_MAX));
        assertThat(saved.getLogSample()).hasSize(DiagnosisService.LOG_SAMPLE_MAX);
        assertThat(saved.getPossibleReasons()).contains("RESOURCE_EXHAUSTED").contains("deepseek-flash");
        assertThat(saved.getSolutions()).contains("清理日志");

        DiagnosisResultDTO dto = diagnosisService.getDiagnosis(6L);
        assertThat(dto.getRootCause()).hasSize(DiagnosisService.ROOT_CAUSE_MAX);
        assertThat(dto.getSummary()).isEqualTo("磁盘写满");
    }

    @Test
    @DisplayName("相同日志哈希且未强制刷新时直接返回 COMPLETED")
    void shouldReturnCompletedWhenLogHashCacheHits() {
        Task task = task(7L);
        when(taskService.getTaskById(7L)).thenReturn(task);
        DiagnosisCache row = cachedRow();
        when(diagnosisCacheMapper.findByLogHash(anyString())).thenReturn(row);

        DiagnosisSubmission submission = diagnosisService.requestDiagnosis(7L, requestWithLog("connection refused"));

        assertThat(submission.isFromCache()).isTrue();
        assertThat(submission.getResponse().getStatus()).isEqualTo("COMPLETED");
        assertThat(submission.getResponse().getDiagnosisId()).isEqualTo(42L);
        assertThat(row.getHitCount()).isEqualTo(2);
        verify(aiRouter, never()).complete(any());
        verify(diagnosisExecutor, never()).execute(any(Runnable.class));

        DiagnosisResultDTO dto = diagnosisService.getDiagnosis(7L);
        assertThat(dto.getFromCache()).isTrue();
        assertThat(dto.getTaskId()).isEqualTo(7L);
        assertThat(dto.getErrorType()).isEqualTo("NETWORK_ERROR");
        assertThat(dto.getSeverity()).isEqualTo("HIGH");
        assertThat(dto.getSummary()).isEqualTo("数据库连不上");
        assertThat(dto.getRootCause()).isEqualTo("Hikari 无法连接 MySQL");
        assertThat(dto.getSuggestions()).containsExactly("检查 3306 端口");
        assertThat(dto.getConfidence()).isCloseTo(0.8d, within(0.0001d));
        assertThat(dto.getProvider()).isEqualTo("deepseek");
        assertThat(dto.getModel()).isEqualTo("deepseek-flash");
        assertThat(dto.getStatus()).isEqualTo("COMPLETED");
        assertThat(dto.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 9, 8, 0));
    }

    @Test
    @DisplayName("possible_reasons 为数组时仍能还原枚举和置信度")
    void shouldReadArrayShapedPossibleReasons() {
        when(taskService.getTaskById(8L)).thenReturn(task(8L));
        DiagnosisCache row = new DiagnosisCache();
        row.setId(8L);
        row.setHitCount(3);
        row.setRootCause("调用超时");
        row.setPossibleReasons("[\"TIMEOUT\",\"LOW\",\"下游超时\",0.25]");
        row.setSolutions("[\"增加超时时间\"]");
        when(diagnosisCacheMapper.findByLogHash(anyString())).thenReturn(row);

        diagnosisService.requestDiagnosis(8L, requestWithLog("timeout log"));

        DiagnosisResultDTO dto = diagnosisService.getDiagnosis(8L);
        assertThat(dto.getErrorType()).isEqualTo("TIMEOUT");
        assertThat(dto.getSeverity()).isEqualTo("LOW");
        assertThat(dto.getSummary()).isEqualTo("下游超时");
        assertThat(dto.getConfidence()).isEqualTo(0.25d);
        assertThat(dto.getSuggestions()).containsExactly("增加超时时间");
        assertThat(dto.getFromCache()).isTrue();
    }

    @Test
    @DisplayName("forceRefresh 时即使有缓存也调用模型")
    void shouldCallRouterWhenForceRefresh() throws Exception {
        when(taskService.getTaskById(9L)).thenReturn(task(9L));
        DiagnosisCache row = cachedRow();
        when(diagnosisCacheMapper.findByLogHash(anyString())).thenReturn(row);
        when(aiRouter.complete(any(AIRequest.class))).thenReturn(new AIResponse(
                "deepseek", "deepseek-v4-pro",
                modelJson("DEPENDENCY_FAILURE", "CRITICAL", "依赖不可用", "Redis 未启动", List.of("启动 Redis"), 0.5)));

        DiagnosisRequest request = requestWithLog("connection refused");
        request.setForceRefresh(true);
        DiagnosisSubmission submission = diagnosisService.requestDiagnosis(9L, request);

        assertThat(submission.isFromCache()).isFalse();
        assertThat(submission.getResponse().getStatus()).isEqualTo("PROCESSING");
        assertThat(submission.getResponse().getDiagnosisId()).isEqualTo(42L);
        verify(aiRouter).complete(any(AIRequest.class));
        DiagnosisResultDTO dto = diagnosisService.getDiagnosis(9L);
        assertThat(dto.getFromCache()).isFalse();
        assertThat(dto.getStatus()).isEqualTo("COMPLETED");
        assertThat(dto.getModel()).isEqualTo("deepseek-v4-pro");
        assertThat(dto.getErrorType()).isEqualTo("DEPENDENCY_FAILURE");
        verify(diagnosisCacheMapper).updateById(row);
    }

    @Test
    @DisplayName("任务不存在时返回 2001")
    void shouldThrowWhenTaskMissing() {
        when(taskService.getTaskById(99L)).thenThrow(new BusinessException(ErrorCode.TASK_NOT_FOUND));

        assertThatThrownBy(() -> diagnosisService.requestDiagnosis(99L, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getCode()).isEqualTo(2001);
                    assertThat(business.getMessage()).isEqualTo("任务不存在");
                });
        verify(aiRouter, never()).complete(any());
    }

    @Test
    @DisplayName("没有日志、错误信息和容器日志时返回校验错误")
    void shouldThrowWhenInputMissing() {
        Task task = task(10L);
        task.setErrorMessage("  ");
        task.setEnvironmentId(8L);
        when(taskService.getTaskById(10L)).thenReturn(task);
        when(dockerService.getContainersByEnvironmentId(8L)).thenReturn(List.of());
        DiagnosisRequest request = new DiagnosisRequest();
        request.setLog(" \n ");

        assertThatThrownBy(() -> diagnosisService.requestDiagnosis(10L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR.getCode());
                    assertThat(business.getMessage()).contains("log");
                });
        verify(aiRouter, never()).complete(any());
        verify(dockerService).getContainersByEnvironmentId(8L);
    }

    @Test
    @DisplayName("模型失败时状态为 FAILED 并带错误信息")
    void shouldMarkFailedWhenRouterThrows() {
        when(taskService.getTaskById(11L)).thenReturn(task(11L));
        when(aiRouter.complete(any(AIRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "deepseek: timeout"));

        DiagnosisSubmission submission = diagnosisService.requestDiagnosis(11L, requestWithLog("boom"));

        assertThat(submission.getResponse().getStatus()).isEqualTo("PROCESSING");
        assertThat(submission.isFromCache()).isFalse();
        DiagnosisResultDTO dto = diagnosisService.getDiagnosis(11L);
        assertThat(dto.getStatus()).isEqualTo("FAILED");
        assertThat(dto.getError()).contains("deepseek").doesNotContain("sk-");
        assertThat(dto.getFromCache()).isFalse();
    }

    @Test
    @DisplayName("请求体里的 log 优先，且不读 Docker")
    void shouldUseBodyLogWithoutDocker() throws Exception {
        Task task = task(12L);
        task.setErrorMessage("task error");
        task.setEnvironmentId(3L);
        when(taskService.getTaskById(12L)).thenReturn(task);
        when(aiRouter.complete(any(AIRequest.class))).thenReturn(new AIResponse(
                "deepseek", "deepseek-flash",
                modelJson("UNKNOWN", "MEDIUM", "摘要", "根因", List.of(), 0.5)));

        diagnosisService.requestDiagnosis(12L, requestWithLog("body log line"));

        ArgumentCaptor<AIRequest> captor = ArgumentCaptor.forClass(AIRequest.class);
        verify(aiRouter).complete(captor.capture());
        assertThat(captor.getValue().userPrompt()).contains("body log line").doesNotContain("task error");
        assertThat(captor.getValue().systemPrompt()).contains("JSON");
        assertThat(captor.getValue().jsonOutput()).isTrue();
        verify(dockerService, never()).getContainersByEnvironmentId(anyLong());
    }

    @Test
    @DisplayName("容器日志读取失败时仍诊断任务错误信息")
    void shouldKeepErrorMessageWhenContainerLogsFail() throws Exception {
        Task task = task(13L);
        task.setErrorMessage("Application failed");
        task.setEnvironmentId(9L);
        when(taskService.getTaskById(13L)).thenReturn(task);
        Container ok = new Container();
        ok.setContainerId("c1");
        ok.setContainerName("mysql");
        Container bad = new Container();
        bad.setContainerId("c2");
        bad.setContainerName("redis");
        when(dockerService.getContainersByEnvironmentId(9L)).thenReturn(List.of(ok, bad));
        when(dockerService.getContainerLogs("c1", 200)).thenReturn("Connection refused mysql:3306");
        when(dockerService.getContainerLogs("c2", 200)).thenThrow(new RuntimeException("docker log down"));
        when(aiRouter.complete(any(AIRequest.class))).thenReturn(new AIResponse(
                "deepseek", "deepseek-flash",
                modelJson("NETWORK_ERROR", "HIGH", "连不上", "端口拒绝", List.of("检查 MySQL"), 0.5)));

        DiagnosisResultDTO dto = afterRequest(13L, null);

        ArgumentCaptor<AIRequest> captor = ArgumentCaptor.forClass(AIRequest.class);
        verify(aiRouter).complete(captor.capture());
        assertThat(captor.getValue().userPrompt())
                .contains("Application failed")
                .contains("Connection refused mysql:3306")
                .contains("mysql");
        verify(dockerService).getContainerLogs("c1", 200);
        assertThat(dto.getStatus()).isEqualTo("COMPLETED");
        assertThat(dto.getErrorType()).isEqualTo("NETWORK_ERROR");
    }

    @Test
    @DisplayName("同一日志仍在处理时不重复提交")
    void shouldNotStartSecondCallWhileProcessing() {
        doAnswer(invocation -> null).when(diagnosisExecutor).execute(any(Runnable.class));
        when(taskService.getTaskById(14L)).thenReturn(task(14L));

        DiagnosisSubmission first = diagnosisService.requestDiagnosis(14L, requestWithLog("same-log"));
        DiagnosisSubmission second = diagnosisService.requestDiagnosis(14L, requestWithLog("same-log"));

        assertThat(first.getResponse().getStatus()).isEqualTo("PROCESSING");
        assertThat(second.getResponse().getDiagnosisId()).isEqualTo(first.getResponse().getDiagnosisId());
        assertThat(second.getResponse().getStatus()).isEqualTo("PROCESSING");
        assertThat(second.isFromCache()).isFalse();
        verify(diagnosisExecutor).execute(any(Runnable.class));
        verify(aiRouter, never()).complete(any());
        assertThat(diagnosisService.getDiagnosis(14L).getStatus()).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("日志哈希冲突时改为更新已有缓存行")
    void shouldUpdateExistingRowWhenInsertConflicts() throws Exception {
        when(taskService.getTaskById(15L)).thenReturn(task(15L));
        DiagnosisCache existing = new DiagnosisCache();
        existing.setId(99L);
        existing.setHitCount(4);
        when(diagnosisCacheMapper.findByLogHash(anyString())).thenReturn(null, null, existing);
        doThrow(new DuplicateKeyException("dup")).when(diagnosisCacheMapper).insert(any(DiagnosisCache.class));
        when(aiRouter.complete(any(AIRequest.class))).thenReturn(new AIResponse(
                "deepseek", "deepseek-flash",
                modelJson("TIMEOUT", "MEDIUM", "超时", "锁等待", List.of("重试"), 0.5)));

        DiagnosisSubmission submission = diagnosisService.requestDiagnosis(15L, requestWithLog("conflict"));

        assertThat(submission.getResponse().getDiagnosisId()).isPositive();
        assertThat(submission.getResponse().getDiagnosisId()).isNotEqualTo(99L);
        verify(diagnosisCacheMapper).updateById(existing);
        assertThat(diagnosisService.getDiagnosis(15L).getId()).isEqualTo(99L);
        assertThat(existing.getRootCause()).isEqualTo("锁等待");
    }

    @Test
    @DisplayName("尚未请求诊断时查询返回 4002")
    void shouldThrowWhenDiagnosisWasNotRequested() {
        assertThatThrownBy(() -> diagnosisService.getDiagnosis(16L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(4002));
    }

    @Test
    @DisplayName("对象提前闭合时补回后面的 confidence，并保留 suggestions")
    void shouldRecoverConfidenceAfterEarlyObjectClose() throws Exception {
        String malformed = "{\"errorType\":\"NETWORK_ERROR\",\"severity\":\"HIGH\",\"summary\":\"数据库连接被拒绝\","
                + "\"rootCause\":\"HikariPool 无法连接 mysql:3306\","
                + "\"suggestions\":[\"检查 MySQL 是否监听 3306\",\"确认 JDBC URL\"]},\"confidence\":0.95}";

        DiagnosisService.ModelDiagnosis parsed = DiagnosisService.parseModelContent(malformed, objectMapper);

        assertThat(parsed.fallback()).isFalse();
        assertThat(parsed.confidence()).isEqualTo(0.95d);
        assertThat(parsed.suggestions()).containsExactly("检查 MySQL 是否监听 3306", "确认 JDBC URL");
        assertThat(parsed.errorType()).isEqualTo("NETWORK_ERROR");
        assertThat(parsed.severity()).isEqualTo("HIGH");
        assertThat(parsed.summary()).isEqualTo("数据库连接被拒绝");
        assertThat(parsed.rootCause()).isEqualTo("HikariPool 无法连接 mysql:3306");

        DiagnosisResultDTO dto = diagnose(21L, "early-close", malformed);
        assertThat(dto.getConfidence()).isEqualTo(0.95d);
        assertThat(dto.getSuggestions()).containsExactly("检查 MySQL 是否监听 3306", "确认 JDBC URL");
    }

    @Test
    @DisplayName("提前闭合后的其余字段也会补回")
    void shouldRecoverFieldsThatFollowAnEarlyClose() {
        String malformed = "{\"errorType\":\"NETWORK_ERROR\"},\"severity\":\"HIGH\",\"summary\":\"数据库连不上\","
                + "\"rootCause\":\"mysql:3306 拒绝连接\",\"suggestions\":[\"检查端口\"],\"confidence\":0.92}";

        DiagnosisService.ModelDiagnosis parsed = DiagnosisService.parseModelContent(malformed, objectMapper);

        assertThat(parsed.fallback()).isFalse();
        assertThat(parsed.errorType()).isEqualTo("NETWORK_ERROR");
        assertThat(parsed.severity()).isEqualTo("HIGH");
        assertThat(parsed.summary()).isEqualTo("数据库连不上");
        assertThat(parsed.rootCause()).isEqualTo("mysql:3306 拒绝连接");
        assertThat(parsed.suggestions()).containsExactly("检查端口");
        assertThat(parsed.confidence()).isEqualTo(0.92d);
    }

    @Test
    @DisplayName("补不成完整对象时仍从原文扫出 confidence")
    void shouldScanConfidenceWhenRepairDoesNotParse() {
        String malformed = "{\"summary\":\"只有摘要\",\"suggestions\":[\"看日志\"]},\"confidence\":0.95";

        DiagnosisService.ModelDiagnosis parsed = DiagnosisService.parseModelContent(malformed, objectMapper);

        assertThat(parsed.fallback()).isFalse();
        assertThat(parsed.summary()).isEqualTo("只有摘要");
        assertThat(parsed.suggestions()).containsExactly("看日志");
        assertThat(parsed.confidence()).isEqualTo(0.95d);
        assertThat(parsed.rootCause()).isEqualTo("只有摘要");
    }

    @Test
    @DisplayName("缺少 confidence 时为 null，缓存和 DTO 都写出 null")
    void shouldKeepMissingConfidenceNull() throws Exception {
        String content = "{\"errorType\":\"NETWORK_ERROR\",\"severity\":\"HIGH\",\"summary\":\"数据库连不上\","
                + "\"rootCause\":\"端口拒绝\",\"suggestions\":[\"检查端口\"]}";
        when(taskService.getTaskById(22L)).thenReturn(task(22L));
        when(aiRouter.complete(any(AIRequest.class)))
                .thenReturn(new AIResponse("deepseek", "deepseek-flash", content));

        diagnosisService.requestDiagnosis(22L, requestWithLog("no-confidence"));

        ArgumentCaptor<DiagnosisCache> captor = ArgumentCaptor.forClass(DiagnosisCache.class);
        verify(diagnosisCacheMapper).insert(captor.capture());
        assertThat(captor.getValue().getPossibleReasons()).contains("\"confidence\":null");

        DiagnosisResultDTO dto = diagnosisService.getDiagnosis(22L);
        assertThat(dto.getConfidence()).isNull();

        DiagnosisResultDTO wire = new DiagnosisResultDTO();
        wire.setSummary(dto.getSummary());
        wire.setConfidence(dto.getConfidence());
        String json = objectMapper.writeValueAsString(wire);
        assertThat(json).contains("\"confidence\":null");
        assertThat(json).doesNotContain("rootCause");
    }

    @Test
    @DisplayName("缓存中的 confidence 为 null 或数组缺该元素时不补成 0")
    void shouldReadNullConfidenceFromCache() {
        when(taskService.getTaskById(23L)).thenReturn(task(23L));
        DiagnosisCache objectRow = new DiagnosisCache();
        objectRow.setId(23L);
        objectRow.setHitCount(1);
        objectRow.setRootCause("根因");
        objectRow.setPossibleReasons(
                "{\"errorType\":\"UNKNOWN\",\"severity\":\"MEDIUM\",\"summary\":\"只有摘要\",\"confidence\":null}");
        objectRow.setSolutions("[]");
        when(diagnosisCacheMapper.findByLogHash(anyString())).thenReturn(objectRow);

        diagnosisService.requestDiagnosis(23L, requestWithLog("cached-null-object"));
        assertThat(diagnosisService.getDiagnosis(23L).getConfidence()).isNull();

        when(taskService.getTaskById(24L)).thenReturn(task(24L));
        DiagnosisCache arrayRow = new DiagnosisCache();
        arrayRow.setId(24L);
        arrayRow.setHitCount(1);
        arrayRow.setRootCause("调用超时");
        arrayRow.setPossibleReasons("[\"TIMEOUT\",\"LOW\",\"下游超时\"]");
        arrayRow.setSolutions("[\"增加超时时间\"]");
        when(diagnosisCacheMapper.findByLogHash(anyString())).thenReturn(arrayRow);

        diagnosisService.requestDiagnosis(24L, requestWithLog("cached-null-array"));
        DiagnosisResultDTO dto = diagnosisService.getDiagnosis(24L);
        assertThat(dto.getConfidence()).isNull();
        assertThat(dto.getErrorType()).isEqualTo("TIMEOUT");
        assertThat(dto.getSummary()).isEqualTo("下游超时");
    }

    @Test
    @DisplayName("直接解析：缺失字段使用默认枚举，缺失置信度为 null，非数字置信度为 0")
    void shouldParseMissingFields() {
        DiagnosisService.ModelDiagnosis parsed = DiagnosisService.parseModelContent(
                "{\"summary\":\"只有摘要\"}", objectMapper);

        assertThat(parsed.errorType()).isEqualTo("UNKNOWN");
        assertThat(parsed.severity()).isEqualTo("MEDIUM");
        assertThat(parsed.summary()).isEqualTo("只有摘要");
        assertThat(parsed.rootCause()).isEqualTo("只有摘要");
        assertThat(parsed.confidence()).isNull();
        assertThat(parsed.fallback()).isFalse();

        DiagnosisService.ModelDiagnosis textual = DiagnosisService.parseModelContent(
                "{\"errorType\":\"network_error\",\"severity\":\"high\",\"summary\":\"s\",\"rootCause\":\"r\",\"confidence\":\"nope\"}",
                objectMapper);
        assertThat(textual.errorType()).isEqualTo("NETWORK_ERROR");
        assertThat(textual.severity()).isEqualTo("HIGH");
        assertThat(textual.confidence()).isEqualTo(0d);
    }

    private DiagnosisResultDTO diagnose(Long taskId, String logText, String modelContent) {
        when(taskService.getTaskById(taskId)).thenReturn(task(taskId));
        when(aiRouter.complete(any(AIRequest.class)))
                .thenReturn(new AIResponse("deepseek", "deepseek-flash", modelContent));
        return afterRequest(taskId, requestWithLog(logText));
    }

    private DiagnosisResultDTO afterRequest(Long taskId, DiagnosisRequest request) {
        diagnosisService.requestDiagnosis(taskId, request);
        return diagnosisService.getDiagnosis(taskId);
    }

    private static Task task(Long id) {
        Task task = new Task();
        task.setId(id);
        return task;
    }

    private static DiagnosisRequest requestWithLog(String logText) {
        DiagnosisRequest request = new DiagnosisRequest();
        request.setLog(logText);
        return request;
    }

    private static DiagnosisCache cachedRow() {
        DiagnosisCache row = new DiagnosisCache();
        row.setId(42L);
        row.setHitCount(1);
        row.setRootCause("Hikari 无法连接 MySQL");
        row.setSolutions("[\"检查 3306 端口\"]");
        row.setPossibleReasons("""
                {"errorType":"NETWORK_ERROR","severity":"HIGH","summary":"数据库连不上","confidence":0.8,"provider":"deepseek","model":"deepseek-flash"}
                """);
        row.setCreatedAt(LocalDateTime.of(2026, 10, 9, 8, 0));
        return row;
    }

    private String modelJson(String errorType, String severity, String summary, String rootCause,
                             List<String> suggestions, double confidence) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorType", errorType);
        body.put("severity", severity);
        body.put("summary", summary);
        body.put("rootCause", rootCause);
        body.put("suggestions", suggestions);
        body.put("confidence", confidence);
        return objectMapper.writeValueAsString(body);
    }
}
