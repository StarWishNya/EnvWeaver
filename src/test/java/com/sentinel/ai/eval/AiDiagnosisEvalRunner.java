package com.sentinel.ai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sentinel.ai.AIProviderClientFactory;
import com.sentinel.ai.AIProviderStatus;
import com.sentinel.ai.AIProtocol;
import com.sentinel.ai.AIRequest;
import com.sentinel.ai.AIResponse;
import com.sentinel.ai.AIRouter;
import com.sentinel.config.properties.AIProperties;
import com.sentinel.service.DiagnosisService;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 用 DiagnosisService 的系统提示词、用户提示词和解析器，对每条样本调用真实 AI。
 * 不启动 Spring、不访问 Docker、不写诊断缓存。
 */
public final class AiDiagnosisEvalRunner {

    private static final Pattern SK_TOKEN = Pattern.compile("sk-[A-Za-z0-9_\\-]{8,}");
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int TEXT_LIMIT = 400;

    private static final String TAXONOMY_NOTES = """
            Assertion failure, business NPE, and the flaky race have no dedicated errorType. \
            Expected value is UNKNOWN.
            Port already in use is NETWORK_ERROR (bind failure). There is no PORT_CONFLICT value.
            MySQL connection refused is NETWORK_ERROR. Authentication failure is CONFIG_ERROR \
            (bad credentials), not a separate AUTH value.
            Class not found is DEPENDENCY_FAILURE. A missing placeholder is CONFIG_ERROR.
            Redis command timeout and JUnit test timeout are TIMEOUT. \
            Downstream HTTP 500 is DEPENDENCY_FAILURE.
            Severity used by the set: CRITICAL only for memory or disk exhaustion; \
            HIGH for a broken startup or a failed live request; MEDIUM for a single test failure, \
            test timeout, or intermittent race.
            """;

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final List<String> secrets;

    private AiDiagnosisEvalRunner(List<String> secrets) {
        this.secrets = secrets;
    }

    public static void main(String[] args) throws Exception {
        String label = System.getProperty("ai.eval.label", "tuned");
        int runs = Integer.parseInt(System.getProperty("ai.eval.runs", "2"));
        for (int i = 0; i < args.length; i++) {
            if ("--label".equals(args[i]) && i + 1 < args.length) {
                label = args[++i];
            } else if ("--runs".equals(args[i]) && i + 1 < args.length) {
                runs = Integer.parseInt(args[++i]);
            }
        }
        run(label, runs);
    }

    /**
     * 连续跑 {@code runs} 次，把每次结果写成 reports/ai-eval-runs，并重写总报告。
     * 一次调用都没有返回时抛出异常。解析失败仍记入报告。
     */
    public static void run(String label, int runs) throws Exception {
        if (label == null || !LABEL.matcher(label).matches()) {
            throw new IllegalArgumentException("label 只能包含字母、数字、下划线和连字符");
        }
        if (runs < 1 || runs > 5) {
            throw new IllegalArgumentException("runs 必须在 1 到 5 之间");
        }
        Map<String, String> env = environment();
        List<String> secrets = secrets(env);
        AiDiagnosisEvalRunner runner = new AiDiagnosisEvalRunner(secrets);
        List<EvalCase> cases = loadCases();
        AIProperties properties = propertiesFromEnv(env);
        AIRouter router = new AIRouter(properties, new AIProviderClientFactory(properties));
        runner.printProviders(router);
        if (router.status().stream().noneMatch(AIProviderStatus::available)) {
            throw new IllegalStateException("provider-order 里没有已配置的 AI 提供商");
        }

        Path runsDir = Path.of("reports", "ai-eval-runs");
        Files.createDirectories(runsDir);
        String systemPrompt = DiagnosisService.diagnosisSystemPrompt();
        boolean anyResponse = false;
        for (int run = 1; run <= runs; run++) {
            RunResult result = runner.evaluateOnce(label, run, cases, router, properties, systemPrompt);
            anyResponse = anyResponse || result.anyResponse();
            Path file = runsDir.resolve(label + "-" + run + ".json");
            String json = runner.redact(runner.mapper.writeValueAsString(result.toJson(runner.mapper)));
            Files.writeString(file, json + "\n", StandardCharsets.UTF_8);
            System.out.println();
            runner.printTable(result);
            System.out.println("wrote " + file);
        }
        String markdown = runner.redact(renderReport(runsDir, runner.mapper));
        Path report = Path.of("reports", "ai-diagnosis-eval.md");
        Files.writeString(report, markdown, StandardCharsets.UTF_8);
        System.out.println("wrote " + report);
        if (!anyResponse) {
            throw new IllegalStateException("全部样本都没有拿到模型响应");
        }
    }

    public static List<EvalCase> loadCases() throws IOException, URISyntaxException {
        var resource = AiDiagnosisEvalRunner.class.getResource("/ai-eval/cases");
        if (resource == null) {
            throw new IllegalStateException("classpath 上没有 ai-eval/cases");
        }
        Path dir = Path.of(resource.toURI());
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        ObjectMapper mapper = new ObjectMapper();
        List<EvalCase> cases = new ArrayList<>();
        for (Path file : files) {
            cases.add(parseCase(mapper.readTree(file.toFile()), file.getFileName().toString()));
        }
        if (cases.isEmpty()) {
            throw new IllegalStateException("ai-eval/cases 下没有样本");
        }
        List<String> ids = cases.stream().map(EvalCase::id).toList();
        if (ids.stream().distinct().count() != ids.size()) {
            throw new IllegalStateException("样本 id 重复");
        }
        return List.copyOf(cases);
    }

    /**
     * errorType、severity 与关键词命中互相独立。parseSuccess 为 false 时仍比较已解析出的枚举。
     */
    public static Score score(List<String> keywords, String expectedType, String expectedSeverity,
                              String actualType, String actualSeverity, String summary, String rootCause,
                              boolean parseSuccess) {
        boolean typeMatch = expectedType != null && expectedType.equals(actualType);
        boolean severityMatch = expectedSeverity != null && expectedSeverity.equals(actualSeverity);
        String haystack = ((summary == null ? "" : summary) + "\n" + (rootCause == null ? "" : rootCause))
                .toLowerCase(Locale.ROOT);
        String matched = null;
        if (keywords != null) {
            for (String keyword : keywords) {
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }
                if (haystack.contains(keyword.toLowerCase(Locale.ROOT))) {
                    matched = keyword;
                    break;
                }
            }
        }
        return new Score(typeMatch, severityMatch, matched != null, matched, parseSuccess);
    }

    public static String redact(String text, Collection<String> secrets) {
        String out = text == null ? "" : text;
        if (secrets != null) {
            for (String secret : secrets) {
                if (secret != null && secret.length() >= 8) {
                    out = out.replace(secret, "***");
                }
            }
        }
        return SK_TOKEN.matcher(out).replaceAll("sk-***");
    }

    private String redact(String text) {
        return redact(text, secrets);
    }

    private RunResult evaluateOnce(String label, int run, List<EvalCase> cases, AIRouter router,
                                   AIProperties properties, String systemPrompt) {
        List<CaseResult> rows = new ArrayList<>();
        boolean anyResponse = false;
        System.out.println();
        System.out.printf("== %s run %d ==%n", label, run);
        for (EvalCase evalCase : cases) {
            String userPrompt = DiagnosisService.diagnosisUserPrompt(evalCase.log());
            long started = System.nanoTime();
            String provider = "";
            String model = "";
            String error = "";
            DiagnosisService.ModelDiagnosis parsed = null;
            try {
                AIResponse response = router.complete(new AIRequest(systemPrompt, userPrompt, true));
                anyResponse = true;
                provider = response.provider() == null ? "" : response.provider();
                model = response.model() == null ? "" : response.model();
                parsed = DiagnosisService.parseModelContent(response.content(), mapper);
            } catch (RuntimeException ex) {
                error = redact(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            }
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            Score score = parsed == null
                    ? new Score(false, false, false, null, false)
                    : score(evalCase.expected().rootCauseKeywords(), evalCase.expected().errorType(),
                    evalCase.expected().severity(), parsed.errorType(), parsed.severity(),
                    parsed.summary(), parsed.rootCause(), !parsed.fallback());
            CaseResult row = new CaseResult(evalCase, parsed, score, latencyMs, provider, model, error);
            rows.add(row);
            System.out.printf("%-22s type=%s sev=%s kw=%s parse=%s %5d ms %s%n",
                    evalCase.id(), mark(score.typeMatch()), mark(score.severityMatch()),
                    mark(score.keywordHit()), mark(score.parseSuccess()), latencyMs,
                    error.isEmpty() ? provider : "ERROR");
            System.out.flush();
        }
        return new RunResult(label, run, LocalDateTime.now().format(STAMP),
                properties.getProviderOrder(), systemPrompt, providerFacts(properties), rows, anyResponse);
    }

    private void printProviders(AIRouter router) {
        System.out.println("AI providers:");
        for (AIProviderStatus status : router.status()) {
            System.out.printf("  %s protocol=%s model=%s available=%s detail=%s%n",
                    status.name(), status.protocol(), status.model(), status.available(), status.detail());
        }
    }

    private void printTable(RunResult result) {
        System.out.printf("%-22s %-6s %-6s %-6s %-6s %8s%n",
                "case", "type", "sev", "kw", "parse", "ms");
        int type = 0;
        int severity = 0;
        int keyword = 0;
        int parse = 0;
        long latency = 0;
        for (CaseResult row : result.rows()) {
            System.out.printf("%-22s %-6s %-6s %-6s %-6s %8d%n",
                    row.id(), mark(row.score().typeMatch()), mark(row.score().severityMatch()),
                    mark(row.score().keywordHit()), mark(row.score().parseSuccess()), row.latencyMs());
            type += row.score().typeMatch() ? 1 : 0;
            severity += row.score().severityMatch() ? 1 : 0;
            keyword += row.score().keywordHit() ? 1 : 0;
            parse += row.score().parseSuccess() ? 1 : 0;
            latency += row.latencyMs();
        }
        int n = result.rows().size();
        long mean = n == 0 ? 0 : latency / n;
        System.out.printf("%-22s %-6s %-6s %-6s %-6s %8d%n",
                "TOTAL " + n, ratio(type, n), ratio(severity, n), ratio(keyword, n), ratio(parse, n), mean);
    }

    private static String renderReport(Path runsDir, ObjectMapper mapper) throws IOException {
        List<RunResult> runs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(runsDir)) {
            List<Path> files = stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path file : files) {
                runs.add(RunResult.fromJson(mapper.readTree(file.toFile())));
            }
        }
        Map<String, List<RunResult>> byLabel = new TreeMap<>();
        for (RunResult run : runs) {
            byLabel.computeIfAbsent(run.label(), key -> new ArrayList<>()).add(run);
        }
        List<String> labels = new ArrayList<>(byLabel.keySet());
        labels.sort(Comparator.comparingInt(AiDiagnosisEvalRunner::labelRank).thenComparing(String::compareTo));

        StringBuilder md = new StringBuilder();
        md.append("# AI diagnosis evaluation\n\n");
        md.append("Calls `DiagnosisService.diagnosisSystemPrompt`, `diagnosisUserPrompt`, and `parseModelContent`, ");
        md.append("then `AIRouter.complete` with JSON mode. No Docker and no diagnosis cache.\n\n");
        md.append("Scores are independent: errorType exact match, severity exact match, ");
        md.append("any rootCause/summary keyword (case-insensitive), parse success (`fallback=false`), latency.\n\n");
        md.append("The 14 logs are shorter than 8000 characters, so trimming does not change the bytes sent for this set. ");
        md.append("Over the cap, the prompt keeps a short preamble, the first exception (bounded stack frames plus every `Caused by` line), and the tail.\n\n");
        md.append("`.env` sets `AI_PROVIDER=ollama` and the DeepSeek key. The owner's process also sets ");
        md.append("`AI_PROVIDER_ORDER=deepseek,ollama`. When that variable is unset and the DeepSeek key is present, ");
        md.append("this runner uses `deepseek,ollama`. Ollama is only a fallback.\n\n");
        md.append("## Taxonomy notes\n\n");
        md.append(TAXONOMY_NOTES.trim()).append("\n\n");
        if (runs.isEmpty()) {
            md.append("No runs recorded.\n");
            return md.toString();
        }
        for (String label : labels) {
            List<RunResult> group = byLabel.get(label);
            group.sort(Comparator.comparingInt(RunResult::run));
            md.append("## ").append(label).append("\n\n");
            RunResult first = group.get(0);
            md.append("- provider-order: `").append(cell(first.providerOrder())).append("`\n");
            md.append("- recorded: ").append(group.stream().map(run -> "run " + run.run() + " @ " + run.startedAt())
                    .reduce((a, b) -> a + "; " + b).orElse("")).append("\n\n");
            if (!first.providers().isEmpty()) {
                md.append("| provider | protocol | model | timeout | temperature | maxTokens | thinking | available |\n");
                md.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
                for (ProviderFact fact : first.providers()) {
                    md.append("| ").append(cell(fact.name())).append(" | ")
                            .append(cell(fact.protocol())).append(" | ")
                            .append(cell(fact.model())).append(" | ")
                            .append(fact.timeoutSeconds()).append(" | ")
                            .append(fact.temperature()).append(" | ")
                            .append(fact.maxTokens()).append(" | ")
                            .append(cell(fact.thinking())).append(" | ")
                            .append(fact.available()).append(" |\n");
                }
                md.append("\n");
            }
            for (RunResult run : group) {
                md.append("### Run ").append(run.run()).append("\n\n");
                appendScoreTable(md, run.rows());
                md.append("\n");
            }
            md.append("### ").append(label).append(" totals\n\n");
            appendAggregate(md, group);
            md.append("\nVariance: cases whose runs disagree on errorType: ");
            md.append(disagreements(group, true)).append(". On severity: ");
            md.append(disagreements(group, false)).append(".\n\n");
            md.append("<details><summary>System prompt (").append(label).append(")</summary>\n\n````\n");
            md.append(first.systemPrompt() == null ? "" : first.systemPrompt()).append("\n````\n\n</details>\n\n");
        }
        if (labels.size() >= 2) {
            md.append("## Before / after\n\n");
            appendComparison(md, byLabel, labels);
        }
        return md.toString();
    }

    private static void appendScoreTable(StringBuilder md, List<CaseResult> rows) {
        md.append("| case | expected type | actual type | type | expected sev | actual sev | sev | keyword | parse | ms | provider | model |\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (CaseResult row : rows) {
            md.append("| ").append(cell(row.id()))
                    .append(" | ").append(cell(row.expectedType()))
                    .append(" | ").append(cell(row.actualType()))
                    .append(" | ").append(mark(row.score().typeMatch()))
                    .append(" | ").append(cell(row.expectedSeverity()))
                    .append(" | ").append(cell(row.actualSeverity()))
                    .append(" | ").append(mark(row.score().severityMatch()))
                    .append(" | ").append(mark(row.score().keywordHit()))
                    .append(" | ").append(mark(row.score().parseSuccess()))
                    .append(" | ").append(row.latencyMs())
                    .append(" | ").append(cell(row.provider()))
                    .append(" | ").append(cell(row.model()))
                    .append(" |\n");
        }
        md.append("\n");
        for (CaseResult row : rows) {
            md.append("- **").append(cell(row.id())).append("** ");
            if (!row.error().isEmpty()) {
                md.append("error: ").append(cell(clip(row.error()))).append("\n");
                continue;
            }
            md.append("summary: ").append(cell(clip(row.summary())));
            md.append(" rootCause: ").append(cell(clip(row.rootCause())));
            if (row.score().keywordMatched() != null) {
                md.append(" keyword: ").append(cell(row.score().keywordMatched()));
            }
            md.append("\n");
        }
    }

    private static void appendAggregate(StringBuilder md, List<RunResult> group) {
        int cases = group.stream().mapToInt(run -> run.rows().size()).sum();
        int type = 0;
        int severity = 0;
        int keyword = 0;
        int parse = 0;
        long latency = 0;
        for (RunResult run : group) {
            for (CaseResult row : run.rows()) {
                type += row.score().typeMatch() ? 1 : 0;
                severity += row.score().severityMatch() ? 1 : 0;
                keyword += row.score().keywordHit() ? 1 : 0;
                parse += row.score().parseSuccess() ? 1 : 0;
                latency += row.latencyMs();
            }
        }
        long mean = cases == 0 ? 0 : latency / cases;
        md.append("| metric | hits | rate | mean latency ms |\n");
        md.append("| --- | --- | --- | --- |\n");
        md.append("| errorType | ").append(ratio(type, cases)).append(" | ").append(pct(type, cases))
                .append(" | ").append(mean).append(" |\n");
        md.append("| severity | ").append(ratio(severity, cases)).append(" | ").append(pct(severity, cases))
                .append(" | ").append(mean).append(" |\n");
        md.append("| keyword | ").append(ratio(keyword, cases)).append(" | ").append(pct(keyword, cases))
                .append(" | ").append(mean).append(" |\n");
        md.append("| parse | ").append(ratio(parse, cases)).append(" | ").append(pct(parse, cases))
                .append(" | ").append(mean).append(" |\n");
    }

    private static void appendComparison(StringBuilder md, Map<String, List<RunResult>> byLabel, List<String> labels) {
        List<String> ids = new ArrayList<>();
        for (RunResult run : byLabel.get(labels.get(0))) {
            for (CaseResult row : run.rows()) {
                if (!ids.contains(row.id())) {
                    ids.add(row.id());
                }
            }
        }
        md.append("| case |");
        for (String label : labels) {
            md.append(" ").append(cell(label)).append(" type | ")
                    .append(cell(label)).append(" sev | ")
                    .append(cell(label)).append(" kw | ")
                    .append(cell(label)).append(" parse |");
        }
        md.append("\n| --- |");
        for (int i = 0; i < labels.size(); i++) {
            md.append(" --- | --- | --- | --- |");
        }
        md.append("\n");
        for (String id : ids) {
            md.append("| ").append(cell(id)).append(" |");
            for (String label : labels) {
                Hits hits = hitsFor(byLabel.get(label), id);
                md.append(" ").append(ratio(hits.type(), hits.n()))
                        .append(" | ").append(ratio(hits.severity(), hits.n()))
                        .append(" | ").append(ratio(hits.keyword(), hits.n()))
                        .append(" | ").append(ratio(hits.parse(), hits.n()))
                        .append(" |");
            }
            md.append("\n");
        }
        md.append("\n| total |");
        for (String label : labels) {
            Hits hits = hitsFor(byLabel.get(label), null);
            md.append(" ").append(ratio(hits.type(), hits.n()))
                    .append(" | ").append(ratio(hits.severity(), hits.n()))
                    .append(" | ").append(ratio(hits.keyword(), hits.n()))
                    .append(" | ").append(ratio(hits.parse(), hits.n()))
                    .append(" |");
        }
        md.append("\n");
    }

    private static Hits hitsFor(List<RunResult> runs, String id) {
        int n = 0;
        int type = 0;
        int severity = 0;
        int keyword = 0;
        int parse = 0;
        for (RunResult run : runs) {
            for (CaseResult row : run.rows()) {
                if (id != null && !id.equals(row.id())) {
                    continue;
                }
                n++;
                type += row.score().typeMatch() ? 1 : 0;
                severity += row.score().severityMatch() ? 1 : 0;
                keyword += row.score().keywordHit() ? 1 : 0;
                parse += row.score().parseSuccess() ? 1 : 0;
            }
        }
        return new Hits(n, type, severity, keyword, parse);
    }

    private static String disagreements(List<RunResult> group, boolean errorType) {
        if (group.size() < 2) {
            return "n/a (single run)";
        }
        Map<String, String> first = new LinkedHashMap<>();
        for (CaseResult row : group.get(0).rows()) {
            first.put(row.id(), errorType ? row.actualType() : row.actualSeverity());
        }
        List<String> changed = new ArrayList<>();
        for (int i = 1; i < group.size(); i++) {
            for (CaseResult row : group.get(i).rows()) {
                String before = first.get(row.id());
                String after = errorType ? row.actualType() : row.actualSeverity();
                if (before != null && !before.equals(after) && !changed.contains(row.id())) {
                    changed.add(row.id());
                }
            }
        }
        return changed.isEmpty() ? "none" : String.join(", ", changed);
    }

    private static int labelRank(String label) {
        if ("baseline".equals(label)) {
            return 0;
        }
        if ("tuned".equals(label)) {
            return 1;
        }
        return 2;
    }

    private static List<ProviderFact> providerFacts(AIProperties properties) {
        List<ProviderFact> facts = new ArrayList<>();
        Map<String, AIProperties.Provider> providers = properties.getProviders();
        if (providers == null) {
            return facts;
        }
        for (String name : properties.orderedProviderNames()) {
            AIProperties.Provider provider = providers.get(name);
            if (provider == null) {
                continue;
            }
            boolean available = provider.getEnabled() == null || provider.getEnabled();
            if (provider.getProtocol() == AIProtocol.OLLAMA) {
                available = available && hasText(provider.getBaseUrl());
            } else {
                available = available && hasText(provider.getBaseUrl()) && hasText(provider.getApiKey());
            }
            facts.add(new ProviderFact(
                    name,
                    provider.getProtocol() == null ? "" : provider.getProtocol().name(),
                    provider.getModel() == null ? "" : provider.getModel(),
                    provider.getTimeout() == null ? 60 : provider.getTimeout(),
                    provider.getTemperature() == null ? 0.3d : provider.getTemperature(),
                    provider.getMaxTokens() == null ? 2000 : provider.getMaxTokens(),
                    provider.getThinking() == null ? "" : provider.getThinking(),
                    available));
        }
        return facts;
    }

    private static EvalCase parseCase(JsonNode node, String fileName) {
        String id = text(node, "id");
        String description = text(node, "description");
        String log = text(node, "log");
        JsonNode expected = node.get("expected");
        if (id.isEmpty() || description.isEmpty() || log.isEmpty() || expected == null || !expected.isObject()) {
            throw new IllegalStateException(fileName + " 缺少 id、description、log 或 expected");
        }
        String errorType = text(expected, "errorType");
        String severity = text(expected, "severity");
        if (!DiagnosisService.diagnosisErrorTypes().contains(errorType)) {
            throw new IllegalStateException(fileName + " 的 errorType 不在代码枚举中: " + errorType);
        }
        if (!DiagnosisService.diagnosisSeverities().contains(severity)) {
            throw new IllegalStateException(fileName + " 的 severity 不在代码枚举中: " + severity);
        }
        JsonNode keywords = expected.get("rootCauseKeywords");
        if (keywords == null || !keywords.isArray() || keywords.size() < 2 || keywords.size() > 4) {
            throw new IllegalStateException(fileName + " 的 rootCauseKeywords 需要 2 到 4 个");
        }
        List<String> words = new ArrayList<>();
        for (JsonNode keyword : keywords) {
            String word = keyword.asText("").trim();
            if (word.isEmpty()) {
                throw new IllegalStateException(fileName + " 有空关键词");
            }
            words.add(word);
        }
        int lineCount = log.split("\n", -1).length;
        if (lineCount < 20 || lineCount > 80) {
            throw new IllegalStateException(fileName + " 日志行数 " + lineCount + " 不在 20 到 80");
        }
        return new EvalCase(id, description, log, new Expect(errorType, severity, List.copyOf(words)));
    }

    static AIProperties propertiesFromEnv(Map<String, String> env) {
        AIProperties properties = new AIProperties();
        // application.yml 优先 AI_PROVIDER_ORDER。仓库 .env 只写了 AI_PROVIDER=ollama，
        // 所有者进程另设 AI_PROVIDER_ORDER=deepseek,ollama。有 DeepSeek 密钥且未指定顺序时按该顺序调用。
        String order = firstNonBlank(env.get("AI_PROVIDER_ORDER"));
        if (order.isEmpty() && hasText(env.get("DEEPSEEK_API_KEY"))) {
            order = "deepseek,ollama";
        }
        if (order.isEmpty()) {
            order = firstNonBlank(env.get("AI_PROVIDER"), "ollama");
        }
        properties.setProviderOrder(order);
        Map<String, AIProperties.Provider> providers = new LinkedHashMap<>();
        for (String name : properties.orderedProviderNames()) {
            AIProperties.Provider provider = provider(name, env);
            if (provider != null) {
                providers.put(name, provider);
            }
        }
        properties.setProviders(providers);
        return properties;
    }

    private static AIProperties.Provider provider(String name, Map<String, String> env) {
        return switch (name) {
            case "ollama" -> ollama(env);
            case "openai" -> openAiStyle(AIProtocol.OPENAI,
                    firstNonBlank(env.get("OPENAI_BASE_URL"), "https://api.openai.com/v1"),
                    env.get("OPENAI_API_KEY"),
                    firstNonBlank(env.get("OPENAI_MODEL"), "gpt-4o-mini"),
                    intOrNull(env.get("OPENAI_TIMEOUT")),
                    null);
            case "anthropic" -> openAiStyle(AIProtocol.ANTHROPIC,
                    firstNonBlank(env.get("ANTHROPIC_BASE_URL"), "https://api.anthropic.com"),
                    env.get("ANTHROPIC_API_KEY"),
                    firstNonBlank(env.get("ANTHROPIC_MODEL"), "claude-sonnet-4-5"),
                    intOrNull(env.get("ANTHROPIC_TIMEOUT")),
                    null);
            case "deepseek" -> openAiStyle(AIProtocol.OPENAI,
                    firstNonBlank(env.get("DEEPSEEK_BASE_URL"), "https://api.deepseek.com"),
                    env.get("DEEPSEEK_API_KEY"),
                    firstNonBlank(env.get("DEEPSEEK_MODEL"), "deepseek-flash"),
                    intOrNull(env.get("DEEPSEEK_TIMEOUT")),
                    firstNonBlank(env.get("DEEPSEEK_THINKING"), "disabled"));
            case "deepseek-anthropic" -> openAiStyle(AIProtocol.ANTHROPIC,
                    "https://api.deepseek.com/anthropic",
                    env.get("DEEPSEEK_API_KEY"),
                    firstNonBlank(env.get("DEEPSEEK_MODEL"), "deepseek-flash"),
                    intOrNull(env.get("DEEPSEEK_TIMEOUT")),
                    firstNonBlank(env.get("DEEPSEEK_THINKING"), "disabled"));
            case "custom" -> custom(env);
            default -> null;
        };
    }

    private static AIProperties.Provider ollama(Map<String, String> env) {
        AIProperties.Provider provider = new AIProperties.Provider();
        provider.setProtocol(AIProtocol.OLLAMA);
        provider.setBaseUrl(firstNonBlank(env.get("OLLAMA_BASE_URL"), "http://localhost:11434"));
        provider.setModel(firstNonBlank(env.get("OLLAMA_MODEL"), "qwen2.5:7b"));
        String key = env.get("OLLAMA_API_KEY");
        if (hasText(key)) {
            provider.setApiKey(key.trim());
        }
        Integer timeout = intOrNull(env.get("OLLAMA_TIMEOUT"));
        provider.setTimeout(timeout == null ? 120 : timeout);
        provider.setEnabled(true);
        return provider;
    }

    private static AIProperties.Provider openAiStyle(AIProtocol protocol, String baseUrl,
                                                     String apiKey, String model, Integer timeout, String thinking) {
        AIProperties.Provider provider = new AIProperties.Provider();
        provider.setProtocol(protocol);
        provider.setBaseUrl(baseUrl);
        if (hasText(apiKey)) {
            provider.setApiKey(apiKey.trim());
        }
        provider.setModel(model);
        if (timeout != null) {
            provider.setTimeout(timeout);
        }
        if (hasText(thinking)) {
            provider.setThinking(thinking);
        }
        provider.setEnabled(true);
        return provider;
    }

    private static AIProperties.Provider custom(Map<String, String> env) {
        String protocolName = firstNonBlank(env.get("CUSTOM_AI_PROTOCOL"), "openai");
        AIProtocol protocol;
        try {
            protocol = AIProtocol.valueOf(protocolName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            System.out.println("skip custom provider: unknown protocol");
            return null;
        }
        return openAiStyle(protocol,
                env.get("CUSTOM_AI_BASE_URL"),
                env.get("CUSTOM_AI_API_KEY"),
                env.get("CUSTOM_AI_MODEL"),
                intOrNull(env.get("CUSTOM_AI_TIMEOUT")),
                null);
    }

    static Map<String, String> environment() throws IOException {
        Map<String, String> merged = new LinkedHashMap<>();
        Path file = Path.of(".env");
        if (Files.isRegularFile(file)) {
            merged.putAll(parseEnv(file));
        }
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            merged.put(entry.getKey(), entry.getValue().trim());
        }
        return merged;
    }

    static Map<String, String> parseEnv(Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("export ")) {
                trimmed = trimmed.substring("export ".length()).trim();
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            if (value.length() >= 2) {
                char first = value.charAt(0);
                char last = value.charAt(value.length() - 1);
                if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                    value = value.substring(1, value.length() - 1);
                }
            }
            values.put(key, value);
        }
        return values;
    }

    static List<String> secrets(Map<String, String> env) {
        List<String> secrets = new ArrayList<>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toUpperCase(Locale.ROOT);
            String value = entry.getValue();
            if (value == null || value.length() < 8) {
                continue;
            }
            if (key.contains("KEY") || key.contains("SECRET") || key.contains("PASSWORD") || key.contains("TOKEN")) {
                secrets.add(value);
            }
        }
        return secrets;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.asText("").trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static Integer intOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String mark(boolean value) {
        return value ? "Y" : "N";
    }

    private static String ratio(int hits, int total) {
        return hits + "/" + total;
    }

    private static String pct(int hits, int total) {
        if (total == 0) {
            return "0%";
        }
        return Math.round((hits * 1000.0) / total) / 10.0 + "%";
    }

    private static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("|", "/").replace("\r", " ").replace("\n", " ").trim();
    }

    private static String clip(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.replace("\r", " ").replace("\n", " ").trim();
        if (trimmed.length() <= TEXT_LIMIT) {
            return trimmed;
        }
        return trimmed.substring(0, TEXT_LIMIT);
    }

    public record EvalCase(String id, String description, String log, Expect expected) {
    }

    public record Expect(String errorType, String severity, List<String> rootCauseKeywords) {
    }

    public record Score(boolean typeMatch, boolean severityMatch, boolean keywordHit, String keywordMatched,
                        boolean parseSuccess) {
    }

    private record ProviderFact(String name, String protocol, String model, int timeoutSeconds, double temperature,
                                int maxTokens, String thinking, boolean available) {
    }

    private record Hits(int n, int type, int severity, int keyword, int parse) {
    }

    private record CaseResult(String id, String expectedType, String actualType, String expectedSeverity,
                              String actualSeverity, String summary, String rootCause, Score score,
                              long latencyMs, String provider, String model, String error) {

        CaseResult(EvalCase evalCase, DiagnosisService.ModelDiagnosis parsed, Score score, long latencyMs,
                   String provider, String model, String error) {
            this(evalCase.id(),
                    evalCase.expected().errorType(),
                    parsed == null ? "" : parsed.errorType(),
                    evalCase.expected().severity(),
                    parsed == null ? "" : parsed.severity(),
                    parsed == null ? "" : parsed.summary(),
                    parsed == null ? "" : parsed.rootCause(),
                    score,
                    latencyMs,
                    provider,
                    model,
                    error == null ? "" : error);
        }

        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode node = mapper.createObjectNode();
            node.put("id", id);
            node.put("expectedErrorType", expectedType);
            node.put("actualErrorType", actualType);
            node.put("expectedSeverity", expectedSeverity);
            node.put("actualSeverity", actualSeverity);
            node.put("typeMatch", score.typeMatch());
            node.put("severityMatch", score.severityMatch());
            node.put("keywordHit", score.keywordHit());
            node.put("keywordMatched", score.keywordMatched());
            node.put("parseSuccess", score.parseSuccess());
            node.put("latencyMs", latencyMs);
            node.put("provider", provider);
            node.put("model", model);
            node.put("summary", clip(summary));
            node.put("rootCause", clip(rootCause));
            node.put("error", clip(error));
            return node;
        }

        static CaseResult fromJson(JsonNode node) {
            Score score = new Score(
                    node.path("typeMatch").asBoolean(false),
                    node.path("severityMatch").asBoolean(false),
                    node.path("keywordHit").asBoolean(false),
                    text(node, "keywordMatched"),
                    node.path("parseSuccess").asBoolean(false));
            return new CaseResult(
                    text(node, "id"),
                    text(node, "expectedErrorType"),
                    text(node, "actualErrorType"),
                    text(node, "expectedSeverity"),
                    text(node, "actualSeverity"),
                    text(node, "summary"),
                    text(node, "rootCause"),
                    score,
                    node.path("latencyMs").asLong(0),
                    text(node, "provider"),
                    text(node, "model"),
                    text(node, "error"));
        }
    }

    private record RunResult(String label, int run, String startedAt, String providerOrder, String systemPrompt,
                             List<ProviderFact> providers, List<CaseResult> rows, boolean anyResponse) {

        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode node = mapper.createObjectNode();
            node.put("label", label);
            node.put("run", run);
            node.put("startedAt", startedAt);
            node.put("providerOrder", providerOrder);
            node.put("systemPrompt", systemPrompt);
            ArrayNode providerNodes = node.putArray("providers");
            for (ProviderFact fact : providers) {
                ObjectNode item = providerNodes.addObject();
                item.put("name", fact.name());
                item.put("protocol", fact.protocol());
                item.put("model", fact.model());
                item.put("timeoutSeconds", fact.timeoutSeconds());
                item.put("temperature", fact.temperature());
                item.put("maxTokens", fact.maxTokens());
                item.put("thinking", fact.thinking());
                item.put("available", fact.available());
            }
            ArrayNode caseNodes = node.putArray("cases");
            for (CaseResult row : rows) {
                caseNodes.add(row.toJson(mapper));
            }
            return node;
        }

        static RunResult fromJson(JsonNode node) {
            List<ProviderFact> providers = new ArrayList<>();
            for (JsonNode item : node.path("providers")) {
                providers.add(new ProviderFact(
                        text(item, "name"),
                        text(item, "protocol"),
                        text(item, "model"),
                        item.path("timeoutSeconds").asInt(60),
                        item.path("temperature").asDouble(0.3d),
                        item.path("maxTokens").asInt(2000),
                        text(item, "thinking"),
                        item.path("available").asBoolean(false)));
            }
            List<CaseResult> rows = new ArrayList<>();
            for (JsonNode item : node.path("cases")) {
                rows.add(CaseResult.fromJson(item));
            }
            return new RunResult(
                    text(node, "label"),
                    node.path("run").asInt(0),
                    text(node, "startedAt"),
                    text(node, "providerOrder"),
                    text(node, "systemPrompt"),
                    providers,
                    rows,
                    false);
        }
    }
}
