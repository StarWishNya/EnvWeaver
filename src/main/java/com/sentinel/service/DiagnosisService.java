package com.sentinel.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sentinel.ai.AIRequest;
import com.sentinel.ai.AIResponse;
import com.sentinel.ai.AIRouter;
import com.sentinel.config.BusinessException;
import com.sentinel.config.DiagnosisAsyncConfig;
import com.sentinel.domain.Container;
import com.sentinel.domain.DiagnosisCache;
import com.sentinel.domain.Task;
import com.sentinel.dto.DiagnosisAcceptedResponse;
import com.sentinel.dto.DiagnosisRequest;
import com.sentinel.dto.DiagnosisResultDTO;
import com.sentinel.dto.DiagnosisSubmission;
import com.sentinel.dto.TaskDiagnosisState;
import com.sentinel.enums.ErrorCode;
import com.sentinel.mapper.DiagnosisCacheMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AI 诊断服务。
 * 相同日志以 MD5 存在 t_diagnosis_cache：root_cause 截断到 500 字；solutions 为 suggestions 的 JSON 数组；
 * possible_reasons 写入对象 {"errorType","severity","summary","confidence","provider","model"}。
 * confidence 缺失时写入 JSON null，不写 0；读取时也接受数组 [errorType, severity, summary, confidence]。
 * 每个任务的状态、诊断 ID、结果、provider、model、error 放在 Redis，TTL 为 cache.diagnosis-ttl。
 * 模型调用在诊断线程池中执行，不包在数据库事务里。
 *
 * @author Sentinel Team
 */
@Slf4j
@Service
public class DiagnosisService {

    static final int ROOT_CAUSE_MAX = 500;
    static final int LOG_SAMPLE_MAX = 500;
    static final int SUMMARY_FALLBACK_MAX = 200;

    private static final int PROMPT_LOG_MAX = 8000;
    private static final Set<String> ERROR_TYPES = Set.of(
            "RESOURCE_EXHAUSTED", "NETWORK_ERROR", "DEPENDENCY_FAILURE", "TIMEOUT", "CONFIG_ERROR", "UNKNOWN");
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final List<String> RECOVERABLE_FIELDS = List.of(
            "errorType", "severity", "summary", "rootCause", "suggestions", "confidence");
    private static final Pattern QUOTED_NUMBER = Pattern.compile(
            "\\s*\"(-?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][+-]?\\d+)?)\"");
    private static final Pattern BARE_NUMBER = Pattern.compile(
            "\\s*(-?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][+-]?\\d+)?)");
    private static final String SYSTEM_PROMPT = """
            你是容器与 Spring 应用故障诊断专家。
            只输出一个 JSON 对象，不要使用 Markdown，不要输出解释。
            文本字段使用中文。字段必须是：
            errorType（RESOURCE_EXHAUSTED、NETWORK_ERROR、DEPENDENCY_FAILURE、TIMEOUT、CONFIG_ERROR、UNKNOWN 之一），
            severity（LOW、MEDIUM、HIGH、CRITICAL 之一），
            summary（问题摘要），
            rootCause（根因），
            suggestions（修复建议字符串数组），
            confidence（0 到 1 的小数）。
            """;

    private final AIRouter aiRouter;
    private final DiagnosisCacheMapper diagnosisCacheMapper;
    private final CacheService cacheService;
    private final TaskService taskService;
    private final DockerService dockerService;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor diagnosisExecutor;

    public DiagnosisService(AIRouter aiRouter,
                            DiagnosisCacheMapper diagnosisCacheMapper,
                            CacheService cacheService,
                            TaskService taskService,
                            DockerService dockerService,
                            ObjectMapper objectMapper,
                            @Qualifier(DiagnosisAsyncConfig.DIAGNOSIS_EXECUTOR)
                            ThreadPoolTaskExecutor diagnosisExecutor) {
        this.aiRouter = aiRouter;
        this.diagnosisCacheMapper = diagnosisCacheMapper;
        this.cacheService = cacheService;
        this.taskService = taskService;
        this.dockerService = dockerService;
        this.objectMapper = objectMapper;
        this.diagnosisExecutor = diagnosisExecutor;
    }

    /**
     * 受理诊断。缓存命中时 status 为 COMPLETED；否则先写入 PROCESSING 再异步调用模型。
     * 返回的 status 是受理时刻的状态，不会改成 FAILED。
     *
     * @param taskId 任务 ID
     * @param request 可选请求体
     * @return 受理结果。fromCache 为 true 表示本次没有调用模型
     */
    public DiagnosisSubmission requestDiagnosis(Long taskId, DiagnosisRequest request) {
        Task task = taskService.getTaskById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }

        DiagnosisRequest body = request == null ? new DiagnosisRequest() : request;
        String input = collectInput(task, body.getLog());
        if (!StringUtils.hasText(input)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "没有可诊断的内容：请在请求体提供 log，或确保任务有错误信息或容器日志");
        }

        String logHash = DigestUtils.md5Hex(input);
        boolean forceRefresh = body.isForceRefresh();
        DiagnosisCache cached = diagnosisCacheMapper.findByLogHash(logHash);
        if (cached != null && !forceRefresh) {
            touchHit(cached);
            long diagnosisId = cached.getId() == null ? newDiagnosisId() : cached.getId();
            TaskDiagnosisState state = stateFromCache(task, cached, logHash, diagnosisId);
            cacheService.setTaskDiagnosis(taskId, state);
            log.info("诊断命中日志缓存: taskId={}, diagnosisId={}, logHash={}", taskId, diagnosisId, logHash);
            return submission(diagnosisId, "COMPLETED", true);
        }

        DiagnosisSubmission inFlight = reuseInFlight(taskId, logHash, forceRefresh);
        if (inFlight != null) {
            log.info("诊断仍在进行，复用已受理任务: taskId={}, diagnosisId={}",
                    taskId, inFlight.getResponse().getDiagnosisId());
            return inFlight;
        }

        long diagnosisId = cached != null && cached.getId() != null ? cached.getId() : newDiagnosisId();
        TaskDiagnosisState accepted = newTaskState(taskId, diagnosisId, logHash);
        accepted.setStatus("PROCESSING");
        cacheService.setTaskDiagnosis(taskId, accepted);

        try {
            diagnosisExecutor.execute(() -> diagnoseAsync(taskId, diagnosisId, logHash, input));
        } catch (RejectedExecutionException ex) {
            accepted.setStatus("FAILED");
            accepted.setError("诊断任务队列已满");
            cacheService.setTaskDiagnosis(taskId, accepted);
            log.warn("诊断线程池已满: taskId={}", taskId);
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "诊断任务队列已满");
        }

        log.info("诊断已受理: taskId={}, diagnosisId={}, logHash={}", taskId, diagnosisId, logHash);
        return submission(diagnosisId, "PROCESSING", false);
    }

    /**
     * 查询任务最近一次诊断。没有受理记录时抛出 DIAGNOSIS_NOT_FOUND。
     *
     * @param taskId 任务 ID
     * @return 诊断结果
     */
    public DiagnosisResultDTO getDiagnosis(Long taskId) {
        Object raw = cacheService.getTaskDiagnosis(taskId);
        if (!(raw instanceof TaskDiagnosisState state) || state.getDiagnosisId() == null) {
            throw new BusinessException(ErrorCode.DIAGNOSIS_NOT_FOUND);
        }
        return toDto(state);
    }

    /**
     * 在诊断线程中调用模型，返回后再写数据库。本方法不加事务。
     */
    private void diagnoseAsync(Long taskId, long diagnosisId, String logHash, String input) {
        try {
            AIResponse response = aiRouter.complete(
                    new AIRequest(SYSTEM_PROMPT, buildUserPrompt(input), true));
            if (response == null) {
                markFailedIfCurrent(taskId, diagnosisId, logHash, "AI 未返回结果");
                return;
            }
            ModelDiagnosis parsed = parseModelContent(response.content(), objectMapper);
            if (parsed.fallback()) {
                log.warn("AI 诊断结果不是合法 JSON，已回退为截断文本: taskId={}", taskId);
            }
            long storedId = saveCache(diagnosisId, logHash, input, parsed, response.provider(), response.model());
            if (!ownsSlot(taskId, logHash)) {
                log.info("忽略过期诊断结果: taskId={}, logHash={}", taskId, logHash);
                return;
            }
            TaskDiagnosisState state = loadState(taskId, storedId, logHash);
            state.setDiagnosisId(storedId);
            state.setStatus("COMPLETED");
            state.setFromCache(false);
            state.setError(null);
            state.setProvider(response.provider());
            state.setModel(response.model());
            applyParsed(state, parsed);
            cacheService.setTaskDiagnosis(taskId, state);
            log.info("诊断完成: taskId={}, diagnosisId={}, provider={}, model={}",
                    taskId, storedId, response.provider(), response.model());
        } catch (Exception ex) {
            log.warn("AI 诊断失败: taskId={}, reason={}", taskId, ex.getMessage());
            markFailedIfCurrent(taskId, diagnosisId, logHash, safeError(ex));
        }
    }

    /**
     * 同一任务若已改诊断另一段日志，则丢弃这次回调，避免旧结果覆盖新状态。
     */
    private boolean ownsSlot(Long taskId, String logHash) {
        Object raw = cacheService.getTaskDiagnosis(taskId);
        if (!(raw instanceof TaskDiagnosisState state) || !StringUtils.hasText(state.getLogHash())) {
            return true;
        }
        return logHash.equals(state.getLogHash());
    }

    private void markFailedIfCurrent(Long taskId, long diagnosisId, String logHash, String error) {
        if (!ownsSlot(taskId, logHash)) {
            log.info("忽略过期诊断失败: taskId={}, logHash={}", taskId, logHash);
            return;
        }
        markFailed(taskId, diagnosisId, error);
    }

    private String collectInput(Task task, String bodyLog) {
        if (StringUtils.hasText(bodyLog)) {
            return bodyLog.trim();
        }
        StringBuilder input = new StringBuilder();
        if (StringUtils.hasText(task.getErrorMessage())) {
            input.append(task.getErrorMessage().trim());
        }
        appendContainerLogs(task.getEnvironmentId(), input);
        return input.toString().trim();
    }

    private void appendContainerLogs(Long environmentId, StringBuilder input) {
        if (environmentId == null) {
            return;
        }
        List<Container> containers;
        try {
            containers = dockerService.getContainersByEnvironmentId(environmentId);
        } catch (Exception ex) {
            log.warn("查询环境容器失败，已跳过: environmentId={}, reason={}", environmentId, ex.getMessage());
            return;
        }
        if (containers == null) {
            return;
        }
        for (Container container : containers) {
            if (container == null || !StringUtils.hasText(container.getContainerId())) {
                continue;
            }
            try {
                String logs = dockerService.getContainerLogs(container.getContainerId(), 200);
                if (!StringUtils.hasText(logs)) {
                    continue;
                }
                if (input.length() > 0) {
                    input.append('\n');
                }
                String name = StringUtils.hasText(container.getContainerName())
                        ? container.getContainerName() : container.getContainerId();
                input.append("--- container ").append(name).append(" ---\n");
                input.append(logs.trim());
            } catch (Exception ex) {
                log.warn("读取容器日志失败，已跳过: containerId={}, reason={}",
                        container.getContainerId(), ex.getMessage());
            }
        }
    }

    private DiagnosisSubmission reuseInFlight(Long taskId, String logHash, boolean forceRefresh) {
        if (forceRefresh) {
            return null;
        }
        Object raw = cacheService.getTaskDiagnosis(taskId);
        if (!(raw instanceof TaskDiagnosisState current) || !logHash.equals(current.getLogHash())) {
            return null;
        }
        if (!"PENDING".equals(current.getStatus()) && !"PROCESSING".equals(current.getStatus())) {
            return null;
        }
        long diagnosisId = current.getDiagnosisId() == null ? newDiagnosisId() : current.getDiagnosisId();
        return submission(diagnosisId, current.getStatus(), false);
    }

    private long saveCache(long diagnosisId, String logHash, String input, ModelDiagnosis parsed,
                           String provider, String model) {
        String rootCause = blankToUnknown(truncate(parsed.rootCause(), ROOT_CAUSE_MAX));
        String sample = truncate(input, LOG_SAMPLE_MAX);
        if (sample.isEmpty()) {
            sample = "-";
        }
        String reasons = reasonsJson(parsed, provider, model);
        String solutions = solutionsJson(parsed.suggestions());
        LocalDateTime now = LocalDateTime.now();

        DiagnosisCache existing = diagnosisCacheMapper.findByLogHash(logHash);
        if (existing == null) {
            DiagnosisCache row = new DiagnosisCache();
            row.setId(diagnosisId);
            row.setLogHash(logHash);
            row.setLogSample(sample);
            row.setRootCause(rootCause);
            row.setPossibleReasons(reasons);
            row.setSolutions(solutions);
            row.setHitCount(1);
            row.setLastHitAt(now);
            try {
                diagnosisCacheMapper.insert(row);
                return row.getId() == null ? diagnosisId : row.getId();
            } catch (DuplicateKeyException ex) {
                existing = diagnosisCacheMapper.findByLogHash(logHash);
                if (existing == null) {
                    throw ex;
                }
            }
        }
        existing.setLogSample(sample);
        existing.setRootCause(rootCause);
        existing.setPossibleReasons(reasons);
        existing.setSolutions(solutions);
        existing.setLastHitAt(now);
        diagnosisCacheMapper.updateById(existing);
        return existing.getId() == null ? diagnosisId : existing.getId();
    }

    private void touchHit(DiagnosisCache row) {
        int hits = row.getHitCount() == null ? 0 : row.getHitCount();
        row.setHitCount(hits + 1);
        row.setLastHitAt(LocalDateTime.now());
        diagnosisCacheMapper.updateById(row);
    }

    private TaskDiagnosisState stateFromCache(Task task, DiagnosisCache row, String logHash, long diagnosisId) {
        TaskDiagnosisState state = newTaskState(task.getId(), diagnosisId, logHash);
        state.setStatus("COMPLETED");
        state.setFromCache(true);
        if (row.getCreatedAt() != null) {
            state.setCreatedAt(row.getCreatedAt().toString());
        }
        applyStoredMetadata(state, row);
        return state;
    }

    private void applyStoredMetadata(TaskDiagnosisState state, DiagnosisCache row) {
        state.setRootCause(row.getRootCause() == null ? "" : row.getRootCause());
        state.setSuggestions(readSolutions(row.getSolutions()));
        state.setErrorType("UNKNOWN");
        state.setSeverity("MEDIUM");
        state.setSummary("");
        if (StringUtils.hasText(row.getPossibleReasons())) {
            try {
                JsonNode node = objectMapper.readTree(row.getPossibleReasons());
                if (node.isObject()) {
                    state.setErrorType(clampEnum(text(node, "errorType"), ERROR_TYPES, "UNKNOWN"));
                    state.setSeverity(clampEnum(text(node, "severity"), SEVERITIES, "MEDIUM"));
                    state.setSummary(text(node, "summary"));
                    state.setConfidence(clampConfidence(node.get("confidence")));
                    String provider = text(node, "provider");
                    String model = text(node, "model");
                    if (!provider.isEmpty()) {
                        state.setProvider(provider);
                    }
                    if (!model.isEmpty()) {
                        state.setModel(model);
                    }
                } else if (node.isArray()) {
                    if (node.size() > 0) {
                        state.setErrorType(clampEnum(node.get(0).asText(), ERROR_TYPES, "UNKNOWN"));
                    }
                    if (node.size() > 1) {
                        state.setSeverity(clampEnum(node.get(1).asText(), SEVERITIES, "MEDIUM"));
                    }
                    if (node.size() > 2 && !node.get(2).isNull()) {
                        state.setSummary(node.get(2).asText(""));
                    }
                    if (node.size() > 3) {
                        state.setConfidence(clampConfidence(node.get(3)));
                    }
                }
            } catch (Exception ex) {
                log.warn("诊断缓存 possible_reasons 无法解析: id={}", row.getId());
            }
        }
        if (!StringUtils.hasText(state.getSummary())) {
            state.setSummary(truncate(state.getRootCause(), SUMMARY_FALLBACK_MAX));
        }
    }

    private List<String> readSolutions(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isObject()) {
                return readSuggestions(node.get("suggestions"));
            }
            return readSuggestions(node);
        } catch (Exception ex) {
            log.warn("诊断缓存 solutions 无法解析");
            return List.of();
        }
    }

    private String reasonsJson(ModelDiagnosis parsed, String provider, String model) {
        Map<String, Object> reasons = new LinkedHashMap<>();
        reasons.put("errorType", parsed.errorType());
        reasons.put("severity", parsed.severity());
        reasons.put("summary", parsed.summary());
        reasons.put("confidence", parsed.confidence());
        if (StringUtils.hasText(provider)) {
            reasons.put("provider", provider);
        }
        if (StringUtils.hasText(model)) {
            reasons.put("model", model);
        }
        return writeJson(reasons);
    }

    private String solutionsJson(List<String> suggestions) {
        return writeJson(suggestions == null ? List.of() : suggestions);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("诊断结果无法序列化", ex);
        }
    }

    private void markFailed(Long taskId, long diagnosisId, String error) {
        TaskDiagnosisState state = loadState(taskId, diagnosisId, null);
        state.setStatus("FAILED");
        state.setFromCache(false);
        state.setError(error);
        cacheService.setTaskDiagnosis(taskId, state);
    }

    private TaskDiagnosisState loadState(Long taskId, long diagnosisId, String logHash) {
        Object raw = cacheService.getTaskDiagnosis(taskId);
        if (raw instanceof TaskDiagnosisState state) {
            if (state.getDiagnosisId() == null) {
                state.setDiagnosisId(diagnosisId);
            }
            if (logHash != null) {
                state.setLogHash(logHash);
            }
            return state;
        }
        return newTaskState(taskId, diagnosisId, logHash);
    }

    private static TaskDiagnosisState newTaskState(Long taskId, long diagnosisId, String logHash) {
        TaskDiagnosisState state = new TaskDiagnosisState();
        state.setTaskId(taskId);
        state.setDiagnosisId(diagnosisId);
        state.setLogHash(logHash);
        state.setFromCache(false);
        state.setCreatedAt(LocalDateTime.now().toString());
        return state;
    }

    private static void applyParsed(TaskDiagnosisState state, ModelDiagnosis parsed) {
        state.setErrorType(parsed.errorType());
        state.setSeverity(parsed.severity());
        state.setSummary(parsed.summary());
        state.setRootCause(parsed.rootCause());
        state.setSuggestions(parsed.suggestions());
        state.setConfidence(parsed.confidence());
    }

    private DiagnosisResultDTO toDto(TaskDiagnosisState state) {
        DiagnosisResultDTO dto = new DiagnosisResultDTO();
        dto.setId(state.getDiagnosisId());
        dto.setTaskId(state.getTaskId());
        dto.setStatus(state.getStatus());
        dto.setFromCache(state.isFromCache());
        dto.setProvider(state.getProvider());
        dto.setModel(state.getModel());
        dto.setError(StringUtils.hasText(state.getError()) ? state.getError() : null);
        dto.setCreatedAt(parseTime(state.getCreatedAt()));
        if ("COMPLETED".equals(state.getStatus())) {
            dto.setErrorType(state.getErrorType());
            dto.setSeverity(state.getSeverity());
            dto.setSummary(state.getSummary());
            dto.setRootCause(state.getRootCause());
            dto.setSuggestions(state.getSuggestions() == null ? List.of() : List.copyOf(state.getSuggestions()));
            dto.setConfidence(state.getConfidence());
        }
        return dto;
    }

    private static DiagnosisSubmission submission(long diagnosisId, String status, boolean fromCache) {
        DiagnosisAcceptedResponse response = new DiagnosisAcceptedResponse();
        response.setDiagnosisId(diagnosisId);
        response.setStatus(status);
        return new DiagnosisSubmission(response, fromCache);
    }

    private static String buildUserPrompt(String input) {
        String body = input.length() > PROMPT_LOG_MAX
                ? input.substring(0, PROMPT_LOG_MAX) + "\n...[日志已截断]"
                : input;
        return "请诊断下面的日志，只返回一个 JSON 对象。\n\n" + body;
    }

    private static long newDiagnosisId() {
        long id = System.currentTimeMillis() * 1_000_000L + ThreadLocalRandom.current().nextInt(1_000_000);
        return id > 0 ? id : 1L;
    }

    private static String safeError(Throwable ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            message = ex.getClass().getSimpleName();
        }
        return truncate(message, ROOT_CAUSE_MAX);
    }

    private static String blankToUnknown(String value) {
        return StringUtils.hasText(value) ? value : "未知";
    }

    private static LocalDateTime parseTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeException ex) {
            return null;
        }
    }

    /**
     * 解析模型输出。非法枚举落到 UNKNOWN / MEDIUM。
     * 越界或非数字的 confidence 钳制到 [0,1]；字段缺失时为 null，不写成 0。
     * 对象被提前闭合时，从闭合后的原文补回缺失字段。
     * 找不到 JSON 对象时 summary 取原文前 200 字，rootCause 取原文截断到 500 字，confidence 为 null。
     */
    static ModelDiagnosis parseModelContent(String content, ObjectMapper objectMapper) {
        String raw = content == null ? "" : content;
        String stripped = stripFence(raw.trim());
        String json = extractJsonObject(stripped);
        if (json == null) {
            return fallback(raw);
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                return fallback(raw);
            }
            node = expandEarlyClose(stripped, node, objectMapper);
            node = fillMissingFromSuffix(node, suffixAfter(stripped, json), objectMapper);
            String rawSuffix = suffixAfter(raw, json);
            if (!rawSuffix.equals(suffixAfter(stripped, json))) {
                node = fillMissingFromSuffix(node, rawSuffix, objectMapper);
            }
            String errorType = clampEnum(text(node, "errorType"), ERROR_TYPES, "UNKNOWN");
            String severity = clampEnum(text(node, "severity"), SEVERITIES, "MEDIUM");
            String summary = text(node, "summary");
            String rootCause = text(node, "rootCause");
            if (!StringUtils.hasText(rootCause)) {
                rootCause = StringUtils.hasText(summary) ? summary : "未知";
            }
            rootCause = truncate(rootCause, ROOT_CAUSE_MAX);
            if (!StringUtils.hasText(summary)) {
                summary = truncate(rootCause, SUMMARY_FALLBACK_MAX);
            }
            return new ModelDiagnosis(
                    errorType,
                    severity,
                    summary,
                    rootCause,
                    readSuggestions(node.get("suggestions")),
                    clampConfidence(node.get("confidence")),
                    false);
        } catch (Exception ex) {
            return fallback(raw);
        }
    }

    /**
     * 模型有时在 suggestions 后面多写一个 }，把 confidence 等字段留在对象外面。
     * 只去掉这次抽出的对象的闭合括号，再解析；合并不覆盖对象里已经有的字段。
     */
    private static JsonNode expandEarlyClose(String text, JsonNode original, ObjectMapper mapper) {
        JsonNode best = original;
        String current = text;
        for (int attempt = 0; attempt < RECOVERABLE_FIELDS.size(); attempt++) {
            String extracted = extractJsonObject(current);
            if (extracted == null) {
                break;
            }
            int end = indexOfExtractedEnd(current, extracted);
            if (end < 0 || !hasTrailingField(current.substring(end))) {
                break;
            }
            String expandedText = dropExtractedCloser(current, extracted);
            if (expandedText == null) {
                break;
            }
            String expandedJson = extractJsonObject(expandedText);
            if (expandedJson == null) {
                break;
            }
            JsonNode parsed;
            try {
                parsed = mapper.readTree(expandedJson);
            } catch (Exception ex) {
                break;
            }
            JsonNode merged = mergeMissing(best, parsed);
            if (merged == best) {
                break;
            }
            best = merged;
            current = expandedText;
        }
        return best;
    }

    private static JsonNode mergeMissing(JsonNode base, JsonNode extra) {
        if (!(base instanceof ObjectNode baseObject) || extra == null || !extra.isObject()) {
            return base;
        }
        ObjectNode merged = baseObject.deepCopy();
        boolean changed = false;
        for (String field : RECOVERABLE_FIELDS) {
            if (lacks(merged, field) && !lacks(extra, field)) {
                merged.set(field, extra.get(field).deepCopy());
                changed = true;
            }
        }
        return changed ? merged : base;
    }

    /**
     * 修补后仍然缺字段时，在对象后面的原文里按键名补回。字符串内的花括号和引号不算。
     */
    private static JsonNode fillMissingFromSuffix(JsonNode node, String suffix, ObjectMapper mapper) {
        if (!StringUtils.hasText(suffix) || !(node instanceof ObjectNode base)) {
            return node;
        }
        ObjectNode copy = base.deepCopy();
        boolean changed = false;
        for (String field : List.of("errorType", "severity", "summary", "rootCause")) {
            if (!lacks(copy, field)) {
                continue;
            }
            int valueAt = indexOfJsonKey(suffix, field);
            if (valueAt < 0) {
                continue;
            }
            String value = readJsonStringValue(suffix, valueAt, mapper);
            if (value != null) {
                copy.put(field, value);
                changed = true;
            }
        }
        if (lacks(copy, "suggestions")) {
            int valueAt = indexOfJsonKey(suffix, "suggestions");
            if (valueAt >= 0) {
                JsonNode suggestions = readSuggestionsValue(suffix, valueAt, mapper);
                if (suggestions != null && !suggestions.isNull()) {
                    copy.set("suggestions", suggestions);
                    changed = true;
                }
            }
        }
        if (lacks(copy, "confidence")) {
            int valueAt = indexOfJsonKey(suffix, "confidence");
            if (valueAt >= 0) {
                Double value = readConfidenceValue(suffix, valueAt);
                if (value != null) {
                    copy.put("confidence", value);
                    changed = true;
                }
            }
        }
        return changed ? copy : node;
    }

    private static boolean hasTrailingField(String suffix) {
        int comma = skipWs(suffix, 0);
        if (comma >= suffix.length() || suffix.charAt(comma) != ',') {
            return false;
        }
        int quote = skipWs(suffix, comma + 1);
        if (quote >= suffix.length() || suffix.charAt(quote) != '"') {
            return false;
        }
        int end = suffix.indexOf('"', quote + 1);
        if (end < 0) {
            return false;
        }
        String key = suffix.substring(quote + 1, end);
        if (!RECOVERABLE_FIELDS.contains(key)) {
            return false;
        }
        int colon = skipWs(suffix, end + 1);
        return colon < suffix.length() && suffix.charAt(colon) == ':';
    }

    private static String dropExtractedCloser(String text, String extracted) {
        int start = text.indexOf(extracted);
        if (start < 0 || extracted.isEmpty() || extracted.charAt(extracted.length() - 1) != '}') {
            return null;
        }
        int close = start + extracted.length() - 1;
        return text.substring(0, close) + text.substring(close + 1);
    }

    private static String suffixAfter(String text, String extracted) {
        int end = indexOfExtractedEnd(text, extracted);
        return end < 0 ? "" : text.substring(end);
    }

    private static int indexOfExtractedEnd(String text, String extracted) {
        if (text == null || extracted == null || extracted.isEmpty()) {
            return -1;
        }
        int start = text.indexOf(extracted);
        if (start < 0) {
            return -1;
        }
        return start + extracted.length();
    }

    private static boolean lacks(JsonNode node, String field) {
        if (node == null) {
            return true;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.isMissingNode();
    }

    /**
     * 返回键名后面、冒号之后的下标。忽略字符串里的同名文本。
     */
    private static int indexOfJsonKey(String text, String key) {
        if (text == null || key == null) {
            return -1;
        }
        String needle = "\"" + key + "\"";
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (current == '\\') {
                    escape = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current != '"') {
                continue;
            }
            if (text.startsWith(needle, i)) {
                int afterKey = skipWs(text, i + needle.length());
                if (afterKey < text.length() && text.charAt(afterKey) == ':') {
                    return afterKey + 1;
                }
            }
            inString = true;
        }
        return -1;
    }

    private static String readJsonStringValue(String text, int valueStart, ObjectMapper mapper) {
        int quote = skipWs(text, valueStart);
        if (quote >= text.length() || text.charAt(quote) != '"') {
            return null;
        }
        return readJsonStringLiteral(text, quote, mapper);
    }

    private static String readJsonStringLiteral(String text, int quoteIndex, ObjectMapper mapper) {
        int end = jsonStringEnd(text, quoteIndex);
        if (end < 0) {
            return null;
        }
        try {
            return mapper.readValue(text.substring(quoteIndex, end + 1), String.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private static int jsonStringEnd(String text, int quoteIndex) {
        boolean escape = false;
        for (int i = quoteIndex + 1; i < text.length(); i++) {
            char current = text.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (current == '\\') {
                escape = true;
            } else if (current == '"') {
                return i;
            }
        }
        return -1;
    }

    private static JsonNode readSuggestionsValue(String text, int valueStart, ObjectMapper mapper) {
        int start = skipWs(text, valueStart);
        if (start >= text.length()) {
            return null;
        }
        if (text.charAt(start) == '[') {
            String array = extractBalanced(text, start, '[', ']');
            if (array == null) {
                return null;
            }
            try {
                return mapper.readTree(array);
            } catch (Exception ex) {
                return null;
            }
        }
        if (text.charAt(start) == '"') {
            String value = readJsonStringLiteral(text, start, mapper);
            if (value == null) {
                return null;
            }
            return mapper.getNodeFactory().textNode(value);
        }
        return null;
    }

    private static String extractBalanced(String text, int openIndex, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = openIndex; i < text.length(); i++) {
            char current = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (current == '\\') {
                    escape = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == open) {
                depth++;
            } else if (current == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(openIndex, i + 1);
                }
            }
        }
        return null;
    }

    private static Double readConfidenceValue(String text, int valueStart) {
        String rest = text.substring(Math.max(valueStart, 0));
        Matcher quoted = QUOTED_NUMBER.matcher(rest);
        if (quoted.lookingAt()) {
            return Double.valueOf(quoted.group(1));
        }
        Matcher bare = BARE_NUMBER.matcher(rest);
        if (bare.lookingAt()) {
            return Double.valueOf(bare.group(1));
        }
        return null;
    }

    private static int skipWs(String text, int index) {
        int i = Math.max(index, 0);
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static ModelDiagnosis fallback(String raw) {
        String summary = truncate(raw, SUMMARY_FALLBACK_MAX);
        String root = truncate(raw, ROOT_CAUSE_MAX);
        if (!StringUtils.hasText(root)) {
            root = "未知";
        }
        return new ModelDiagnosis("UNKNOWN", "MEDIUM", summary, root, List.of(), null, true);
    }

    private static String stripFence(String trimmed) {
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline < 0) {
            return trimmed;
        }
        int lastFence = trimmed.lastIndexOf("```");
        if (lastFence <= firstNewline) {
            return trimmed.substring(firstNewline + 1).trim();
        }
        return trimmed.substring(firstNewline + 1, lastFence).trim();
    }

    static String extractJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char current = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (current == '\\') {
                    escape = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static List<String> readSuggestions(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isTextual()) {
            String text = node.asText().trim();
            return text.isEmpty() ? List.of() : List.of(text);
        }
        if (!node.isArray()) {
            return List.of();
        }
        List<String> suggestions = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || item.isNull()) {
                continue;
            }
            String text = item.isTextual() ? item.asText() : item.toString();
            if (StringUtils.hasText(text)) {
                suggestions.add(text.trim());
            }
        }
        return List.copyOf(suggestions);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.asText("").trim();
    }

    private static String clampEnum(String raw, Set<String> allowed, String fallbackValue) {
        if (!StringUtils.hasText(raw)) {
            return fallbackValue;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return allowed.contains(normalized) ? normalized : fallbackValue;
    }

    /**
     * 缺失为 null。非数字视为无效，落到 0。有限数值钳制到 [0, 1]。
     */
    private static Double clampConfidence(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        double value;
        if (node.isNumber()) {
            value = node.asDouble();
        } else if (node.isTextual()) {
            try {
                value = Double.parseDouble(node.asText().trim());
            } catch (NumberFormatException ex) {
                return 0d;
            }
        } else {
            return 0d;
        }
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0d) {
            return 0d;
        }
        return Math.min(value, 1d);
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, max);
    }

    /**
     * 解析后的模型诊断。fallback 为 true 表示原文不是合法 JSON。
     * confidence 缺失时为 null。
     */
    record ModelDiagnosis(String errorType, String severity, String summary, String rootCause,
                          List<String> suggestions, Double confidence, boolean fallback) {
    }
}
