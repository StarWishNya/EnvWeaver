package com.sentinel.ai.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

/**
 * 真实模型评估。默认 surefire 排除 ai-eval。运行：mvn -B -o -Pai-eval test
 */
@Tag("ai-eval")
class AiDiagnosisEvalTest {

    @Test
    @Timeout(value = 45, unit = TimeUnit.MINUTES)
    void evaluateDiagnosisCases() throws Exception {
        String label = System.getProperty("ai.eval.label", "tuned");
        int runs = Integer.parseInt(System.getProperty("ai.eval.runs", "2"));
        AiDiagnosisEvalRunner.run(label, runs);
    }
}
