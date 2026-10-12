package com.sentinel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志裁剪和宽松 JSON 解析。不访问网络。
 */
class DiagnosisPromptTuningTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("未超长的日志保持原样，包括 CRLF")
    void shortLogStaysIntact() {
        assertThat(DiagnosisService.trimLogForPrompt("a\r\nb", 10)).isEqualTo("a\r\nb");
        assertThat(DiagnosisService.trimLogForPrompt(null, 10)).isEmpty();
        assertThat(DiagnosisService.diagnosisUserPrompt("hello-log")).contains("hello-log").contains("JSON");
    }

    @Test
    @DisplayName("超长日志保留第一条异常、Caused by 和尾部")
    void keepsFirstExceptionCausedByAndTail() {
        StringBuilder log = new StringBuilder("PREAMBLE\n");
        for (int i = 0; i < 40; i++) {
            log.append("noise-").append(i).append('\n');
        }
        log.append("java.lang.IllegalStateException: boom\n");
        for (int i = 0; i < 30; i++) {
            log.append("    at com.example.Top.frame").append(i).append("(Top.java:").append(i).append(")\n");
        }
        log.append("Caused by: java.net.ConnectException: Connection refused\n");
        for (int i = 0; i < 30; i++) {
            log.append("    at com.example.Cause.frame").append(i).append("(Cause.java:").append(i).append(")\n");
        }
        log.append("MIDDLE_TOKEN should drop\n");
        for (int i = 0; i < 40; i++) {
            log.append("filler-").append(i).append('\n');
        }
        log.append("TAIL_TOKEN");

        String trimmed = DiagnosisService.trimLogForPrompt(log.toString(), 1200);

        assertThat(trimmed).contains("noise-39");
        assertThat(trimmed).contains("java.lang.IllegalStateException: boom");
        assertThat(trimmed).contains("Caused by: java.net.ConnectException: Connection refused");
        assertThat(trimmed).contains("TAIL_TOKEN");
        assertThat(trimmed).contains("...[中间日志已省略]");
        assertThat(trimmed).doesNotContain("MIDDLE_TOKEN");
        assertThat(trimmed).doesNotContain("noise-0\n");
        assertThat(trimmed.length()).isLessThanOrEqualTo(1200);
        int frames = 0;
        for (String line : trimmed.split("\n")) {
            if (line.trim().startsWith("at ")) {
                frames++;
            }
        }
        assertThat(frames).isLessThanOrEqualTo(12);
    }

    @Test
    @DisplayName("没有异常时保留头部和尾部")
    void keepsHeadAndTailWhenNoException() {
        StringBuilder log = new StringBuilder("HEAD_TOKEN\n");
        for (int i = 0; i < 80; i++) {
            log.append("middle-line-").append(i).append("-xxxxxxxx\n");
        }
        log.append("TAIL_TOKEN");

        String trimmed = DiagnosisService.trimLogForPrompt(log.toString(), 180);

        assertThat(trimmed).contains("HEAD_TOKEN");
        assertThat(trimmed).contains("TAIL_TOKEN");
        assertThat(trimmed).contains("...[中间日志已省略]");
        assertThat(trimmed).doesNotContain("middle-line-40");
        assertThat(trimmed.length()).isLessThanOrEqualTo(180);
    }

    @Test
    @DisplayName("线上提示词对超过 8000 字的日志仍保留后部的 Caused by")
    void userPromptKeepsCauseBeyondHeadCut() {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            log.append("padding-line-").append(i).append('\n');
        }
        log.append("java.lang.IllegalStateException: late\n");
        log.append("Caused by: java.io.IOException: UNIQUE_CAUSE\n");
        for (int i = 0; i < 200; i++) {
            log.append("after-").append(i).append('\n');
        }
        log.append("TAIL_TOKEN");

        assertThat(log.length()).isGreaterThan(8000);
        String prompt = DiagnosisService.diagnosisUserPrompt(log.toString());

        assertThat(prompt).contains("JSON");
        assertThat(prompt).contains("Caused by: java.io.IOException: UNIQUE_CAUSE");
        assertThat(prompt).contains("TAIL_TOKEN");
        assertThat(prompt).contains("...[中间日志已省略]");
        assertThat(prompt).doesNotContain("padding-line-0\n");
    }

    @Test
    @DisplayName("系统提示列出全部分类和 JSON，不写具体样本")
    void systemPromptDefinesTaxonomyWithoutCases() {
        String prompt = DiagnosisService.diagnosisSystemPrompt();

        assertThat(prompt).contains("只输出一个 JSON 对象");
        assertThat(prompt).contains(
                "RESOURCE_EXHAUSTED", "NETWORK_ERROR", "DEPENDENCY_FAILURE",
                "TIMEOUT", "CONFIG_ERROR", "UNKNOWN",
                "LOW", "MEDIUM", "HIGH", "CRITICAL");
        assertThat(prompt).contains("Address already in use", "AssertionFailedError", "No space left on device");
        assertThat(prompt).contains(
                "PKIX path building failed", "certificate expired", "hostname mismatch",
                "java.nio.file.AccessDeniedException", "UnrecognizedPropertyException",
                "MismatchedInputException", "JsonParseException", "Too many open files",
                "RejectedExecutionException");
        assertThat(prompt).contains("即使同一段日志还有 HTTP 503");
        assertThat(prompt).contains("只有内存和磁盘是 CRITICAL");
        assertThat(prompt).doesNotContain("InvoiceCalculator", "sentinel.jwt", "oom-heap", "HikariPool", "KafkaProducer");
    }

    @Test
    @DisplayName("尾逗号、字符串里的逗号、后面的合法对象、思考块和 BOM 都能解析")
    void parsesRepairedAndLaterJson() {
        DiagnosisService.ModelDiagnosis commas = DiagnosisService.parseModelContent(
                "{\"errorType\":\"TIMEOUT\",\"severity\":\"MEDIUM\",\"summary\":\"超时\",\"rootCause\":\"等待超过时限\","
                        + "\"suggestions\":[\"加超时\",],\"confidence\":0.5}",
                objectMapper);
        assertThat(commas.fallback()).isFalse();
        assertThat(commas.errorType()).isEqualTo("TIMEOUT");
        assertThat(commas.suggestions()).containsExactly("加超时");
        assertThat(commas.confidence()).isEqualTo(0.5d);

        DiagnosisService.ModelDiagnosis inside = DiagnosisService.parseModelContent(
                "{\"summary\":\"a,}\",\"errorType\":\"NETWORK_ERROR\",\"severity\":\"LOW\",\"rootCause\":\"r\",\"confidence\":0.2}",
                objectMapper);
        assertThat(inside.summary()).isEqualTo("a,}");
        assertThat(inside.errorType()).isEqualTo("NETWORK_ERROR");

        DiagnosisService.ModelDiagnosis later = DiagnosisService.parseModelContent(
                "前言 {not json} {\"errorType\":\"DEPENDENCY_FAILURE\",\"severity\":\"HIGH\","
                        + "\"summary\":\"缺类\",\"rootCause\":\"classpath 没有类\",\"suggestions\":[],\"confidence\":0.6}",
                objectMapper);
        assertThat(later.fallback()).isFalse();
        assertThat(later.errorType()).isEqualTo("DEPENDENCY_FAILURE");
        assertThat(later.summary()).isEqualTo("缺类");

        DiagnosisService.ModelDiagnosis thinking = DiagnosisService.parseModelContent(
                "<think>{\"errorType\":\"CONFIG_ERROR\",\"severity\":\"LOW\",\"summary\":\"忽略\",\"rootCause\":\"忽略\"}</think>\n"
                        + "{\"errorType\":\"TIMEOUT\",\"severity\":\"MEDIUM\",\"summary\":\"测试超时\",\"rootCause\":\"超过时限\",\"confidence\":0.7}",
                objectMapper);
        assertThat(thinking.errorType()).isEqualTo("TIMEOUT");
        assertThat(thinking.summary()).isEqualTo("测试超时");
        assertThat(thinking.confidence()).isEqualTo(0.7d);

        DiagnosisService.ModelDiagnosis bom = DiagnosisService.parseModelContent(
                "\uFEFF{\"errorType\":\"CONFIG_ERROR\",\"severity\":\"HIGH\",\"summary\":\"缺配置\",\"rootCause\":\"占位符\"}",
                objectMapper);
        assertThat(bom.fallback()).isFalse();
        assertThat(bom.errorType()).isEqualTo("CONFIG_ERROR");
        assertThat(bom.rootCause()).isEqualTo("占位符");
    }

    @Test
    @DisplayName("字符串中的尾逗号形状不会被删掉")
    void repairKeepsCommaInsideString() {
        String raw = "{\"summary\":\"a,}\",\"errorType\":\"TIMEOUT\"}";
        assertThat(DiagnosisService.repairTrailingCommas(raw)).isEqualTo(raw);
        assertThat(DiagnosisService.repairTrailingCommas("{\"a\":1,}")).isEqualTo("{\"a\":1}");
        assertThat(DiagnosisService.repairTrailingCommas("[\"x\",]")).isEqualTo("[\"x\"]");
    }
}
