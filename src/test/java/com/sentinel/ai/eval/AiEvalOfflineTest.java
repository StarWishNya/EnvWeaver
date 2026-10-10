package com.sentinel.ai.eval;

import com.sentinel.service.DiagnosisService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
