package com.sentinel.ai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.service.DiagnosisService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 样本集和计分不访问网络。
 */
class AiEvalOfflineTest {

    @Test
    @DisplayName("14 条样本的枚举、关键词和日志长度符合诊断代码")
    void catalogMatchesTaxonomyAndLength() throws Exception {
        List<AiDiagnosisEvalRunner.EvalCase> cases = AiDiagnosisEvalRunner.loadCases();

        assertThat(cases).hasSize(14);
        assertThat(cases).extracting(AiDiagnosisEvalRunner.EvalCase::id).doesNotHaveDuplicates();
        for (AiDiagnosisEvalRunner.EvalCase evalCase : cases) {
            assertThat(DiagnosisService.diagnosisErrorTypes()).contains(evalCase.expected().errorType());
            assertThat(DiagnosisService.diagnosisSeverities()).contains(evalCase.expected().severity());
            assertThat(evalCase.expected().rootCauseKeywords()).hasSizeBetween(2, 4);
            assertThat(evalCase.description()).isNotBlank();
            int lines = evalCase.log().split("\n", -1).length;
            assertThat(lines).isBetween(20, 80);
        }
        assertThat(DiagnosisService.diagnosisErrorTypes())
                .containsExactlyInAnyOrder(
                        "RESOURCE_EXHAUSTED", "NETWORK_ERROR", "DEPENDENCY_FAILURE",
                        "TIMEOUT", "CONFIG_ERROR", "UNKNOWN");
        assertThat(DiagnosisService.diagnosisSystemPrompt()).contains("JSON");
        assertThat(DiagnosisService.diagnosisUserPrompt("hello-log")).contains("hello-log").contains("JSON");
    }

    @Test
    @DisplayName("关键词大小写不敏感，命中任一即可，枚举按精确值比较")
    void keywordHitIsCaseInsensitiveOr() {
        AiDiagnosisEvalRunner.Score score = AiDiagnosisEvalRunner.score(
                List.of("OutOfMemory", "内存"),
                "RESOURCE_EXHAUSTED", "CRITICAL",
                "RESOURCE_EXHAUSTED", "HIGH",
                "Java heap space", "进程触发 outofmemory",
                true);

        assertThat(score.keywordHit()).isTrue();
        assertThat(score.keywordMatched()).isEqualTo("OutOfMemory");
        assertThat(score.typeMatch()).isTrue();
        assertThat(score.severityMatch()).isFalse();
        assertThat(score.parseSuccess()).isTrue();
    }

    @Test
    @DisplayName("解析失败时关键词和枚举仍按已有文本计分")
    void fallbackStillScoresEnums() {
        AiDiagnosisEvalRunner.Score missed = AiDiagnosisEvalRunner.score(
                List.of("端口", "BindException"),
                "NETWORK_ERROR", "HIGH",
                "UNKNOWN", "MEDIUM",
                "看不懂", "原文",
                false);

        assertThat(missed.keywordHit()).isFalse();
        assertThat(missed.keywordMatched()).isNull();
        assertThat(missed.typeMatch()).isFalse();
        assertThat(missed.parseSuccess()).isFalse();

        AiDiagnosisEvalRunner.Score accidental = AiDiagnosisEvalRunner.score(
                List.of("断言"),
                "UNKNOWN", "MEDIUM",
                "UNKNOWN", "MEDIUM",
                "", "",
                false);
        assertThat(accidental.typeMatch()).isTrue();
        assertThat(accidental.severityMatch()).isTrue();
        assertThat(accidental.parseSuccess()).isFalse();
    }

    @Test
    @DisplayName("报告文本会抹掉密钥和 sk- 令牌")
    void redactStripsSecretValuesAndSkTokens() {
        String redacted = AiDiagnosisEvalRunner.redact(
                "boom sk-abcdefghijklmnopqrstuvwxyz tail SECRETVALUE",
                List.of("SECRETVALUE"));

        assertThat(redacted).isEqualTo("boom sk-*** tail ***");
        assertThat(redacted).doesNotContain("SECRETVALUE").doesNotContain("abcdefghijklmnop");
    }

    @Test
    @DisplayName("acceptable 中的 errorType 算命中，期望值本身也算，列表外不算")
    void acceptableErrorTypeCountsAsTypeMatch() {
        AiDiagnosisEvalRunner.Score alternate = AiDiagnosisEvalRunner.score(
                List.of("PKIX", "证书"),
                "UNKNOWN", List.of("UNKNOWN", "CONFIG_ERROR", "NETWORK_ERROR"),
                "HIGH",
                "CONFIG_ERROR", "HIGH",
                "信任库缺少证书", "PKIX path building failed",
                true);
        assertThat(alternate.typeMatch()).isTrue();
        assertThat(alternate.severityMatch()).isTrue();
        assertThat(alternate.keywordMatched()).isEqualTo("PKIX");

        AiDiagnosisEvalRunner.Score primary = AiDiagnosisEvalRunner.score(
                List.of("死锁"),
                "UNKNOWN", List.of("DEPENDENCY_FAILURE"),
                "HIGH",
                "UNKNOWN", "HIGH",
                "死锁", "事务回滚",
                true);
        assertThat(primary.typeMatch()).isTrue();

        AiDiagnosisEvalRunner.Score miss = AiDiagnosisEvalRunner.score(
                List.of("死锁"),
                "UNKNOWN", List.of("DEPENDENCY_FAILURE"),
                "HIGH",
                "TIMEOUT", "HIGH",
                "死锁", "",
                true);
        assertThat(miss.typeMatch()).isFalse();
        assertThat(miss.keywordHit()).isTrue();
    }

    @Test
    @DisplayName("12 条留出样本与调优集不重叠，歧义类给出 acceptable 和 labelNote")
    void holdoutCasesStayOutOfTheTunedSet() throws Exception {
        List<AiDiagnosisEvalRunner.EvalCase> tuned = AiDiagnosisEvalRunner.loadCases(null);
        List<AiDiagnosisEvalRunner.EvalCase> fromClasspath = AiDiagnosisEvalRunner.loadCases("ai-eval/holdout");
        List<AiDiagnosisEvalRunner.EvalCase> fromFile =
                AiDiagnosisEvalRunner.loadCases("src/test/resources/ai-eval/holdout");

        assertThat(tuned).hasSize(14);
        assertThat(AiDiagnosisEvalRunner.loadCases("  ")).extracting(AiDiagnosisEvalRunner.EvalCase::id)
                .containsExactlyElementsOf(tuned.stream().map(AiDiagnosisEvalRunner.EvalCase::id).toList());
        assertThat(fromClasspath).extracting(AiDiagnosisEvalRunner.EvalCase::id)
                .containsExactlyElementsOf(fromFile.stream().map(AiDiagnosisEvalRunner.EvalCase::id).toList());
        assertThat(fromClasspath).hasSize(12);
        assertThat(fromClasspath).extracting(AiDiagnosisEvalRunner.EvalCase::id)
                .doesNotContainAnyElementsOf(tuned.stream().map(AiDiagnosisEvalRunner.EvalCase::id).toList());

        Map<String, AiDiagnosisEvalRunner.EvalCase> byId = new LinkedHashMap<>();
        for (AiDiagnosisEvalRunner.EvalCase evalCase : fromClasspath) {
            byId.put(evalCase.id(), evalCase);
            assertThat(evalCase.description()).isNotBlank();
            assertThat(evalCase.expected().rootCauseKeywords()).hasSizeBetween(2, 4);
            assertThat(evalCase.log().split("\n", -1)).hasSizeBetween(20, 80);
            assertThat(evalCase.log().length()).isLessThan(8000);
            assertThat(DiagnosisService.diagnosisUserPrompt(evalCase.log())).contains(evalCase.log());
            assertThat(DiagnosisService.diagnosisErrorTypes()).contains(evalCase.expected().errorType());
            assertThat(DiagnosisService.diagnosisSeverities()).contains(evalCase.expected().severity());
            assertThat(evalCase.expected().acceptable())
                    .allSatisfy(type -> assertThat(DiagnosisService.diagnosisErrorTypes()).contains(type));
        }

        expect(byId, "kafka-rebalance-timeout", "TIMEOUT", "HIGH");
        expect(byId, "ssl-pkix-path", "UNKNOWN", "HIGH", "UNKNOWN", "CONFIG_ERROR", "NETWORK_ERROR");
        expect(byId, "liquibase-checksum", "UNKNOWN", "HIGH", "UNKNOWN", "CONFIG_ERROR");
        expect(byId, "too-many-open-files", "RESOURCE_EXHAUSTED", "HIGH");
        expect(byId, "thread-pool-rejected", "RESOURCE_EXHAUSTED", "HIGH");
        expect(byId, "grpc-unavailable", "NETWORK_ERROR", "HIGH", "NETWORK_ERROR", "DEPENDENCY_FAILURE");
        expect(byId, "mysql-deadlock", "UNKNOWN", "HIGH", "UNKNOWN", "DEPENDENCY_FAILURE");
        expect(byId, "json-downstream-body", "DEPENDENCY_FAILURE", "HIGH", "DEPENDENCY_FAILURE", "UNKNOWN");
        expect(byId, "readiness-probe", "TIMEOUT", "HIGH", "TIMEOUT", "DEPENDENCY_FAILURE");
        expect(byId, "selenium-element-missing", "UNKNOWN", "MEDIUM");
        expect(byId, "jar-method-conflict", "DEPENDENCY_FAILURE", "HIGH", "DEPENDENCY_FAILURE", "UNKNOWN");
        expect(byId, "file-permission-denied", "UNKNOWN", "HIGH", "UNKNOWN", "CONFIG_ERROR");
    }

    @Test
    @DisplayName("非法 acceptable 或缺 labelNote 的样本目录会被拒绝")
    void rejectsAmbiguousCaseWithoutExplanation(@TempDir Path dir) throws Exception {
        writeProbe(dir, "NOT_A_TYPE", "explained");
        assertThatThrownBy(() -> AiDiagnosisEvalRunner.loadCases(dir.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT_A_TYPE");

        Path missingNote = Files.createTempDirectory(dir, "note");
        writeProbe(missingNote, "CONFIG_ERROR", null);
        assertThatThrownBy(() -> AiDiagnosisEvalRunner.loadCases(missingNote.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("labelNote");

        assertThatThrownBy(() -> AiDiagnosisEvalRunner.loadCases("ai-eval/no-such-holdout"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("找不到样本目录");
    }

    @Test
    @DisplayName("重写总报告时保留手工追加的 Held-out evaluation")
    void rewriteKeepsHeldOutSection() {
        String generated = "# AI diagnosis evaluation\n\n## tuned\n";
        assertThat(AiDiagnosisEvalRunner.withPreservedHoldout(generated, null)).isEqualTo(generated);
        assertThat(AiDiagnosisEvalRunner.withPreservedHoldout(generated, generated)).isEqualTo(generated);

        String existing = generated + "\n## Held-out evaluation\n\nbody\n";
        String merged = AiDiagnosisEvalRunner.withPreservedHoldout(generated, existing);
        assertThat(merged).contains("## tuned");
        assertThat(merged).endsWith("## Held-out evaluation\n\nbody\n");
        assertThat(merged.indexOf("## Held-out evaluation"))
                .isEqualTo(merged.lastIndexOf("## Held-out evaluation"));
    }

    private static void expect(Map<String, AiDiagnosisEvalRunner.EvalCase> byId, String id, String type,
                               String severity, String... acceptable) {
        AiDiagnosisEvalRunner.EvalCase evalCase = byId.get(id);
        assertThat(evalCase).as(id).isNotNull();
        assertThat(evalCase.expected().errorType()).isEqualTo(type);
        assertThat(evalCase.expected().severity()).isEqualTo(severity);
        if (acceptable.length == 0) {
            assertThat(evalCase.expected().acceptable()).isEmpty();
            assertThat(evalCase.expected().labelNote()).isBlank();
        } else {
            assertThat(evalCase.expected().acceptable()).containsExactly(acceptable);
            assertThat(evalCase.expected().labelNote()).isNotBlank();
        }
    }

    private static void writeProbe(Path dir, String acceptableType, String labelNote) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        root.put("id", "bad-case");
        root.put("description", "schema probe");
        root.put("log", sampleLog());
        var expected = root.putObject("expected");
        expected.put("errorType", "UNKNOWN");
        expected.put("severity", "HIGH");
        expected.putArray("acceptable").add(acceptableType);
        if (labelNote != null) {
            expected.put("labelNote", labelNote);
        }
        expected.putArray("rootCauseKeywords").add("alpha").add("beta");
        mapper.writeValue(dir.resolve("01-bad.json").toFile(), root);
    }

    private static String sampleLog() {
        StringBuilder builder = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            if (i > 1) {
                builder.append('\n');
            }
            builder.append("2026-10-09T00:00:").append(String.format("%02d", i)).append("Z INFO probe line ").append(i);
        }
        return builder.toString();
    }
}
