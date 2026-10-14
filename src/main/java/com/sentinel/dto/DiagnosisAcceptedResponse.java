package com.sentinel.dto;

import lombok.Data;

/**
 * 诊断受理结果。HTTP 202 的 data 只包含这两个字段。
 *
 * @author Sentinel Team
 */
@Data
public class DiagnosisAcceptedResponse {

    /**
     * 诊断 ID。命中 t_diagnosis_cache 时为该行主键，否则为预先生成的正整数。
     */
    private Long diagnosisId;

    /**
     * PENDING、PROCESSING 或 COMPLETED。
     */
    private String status;
}
