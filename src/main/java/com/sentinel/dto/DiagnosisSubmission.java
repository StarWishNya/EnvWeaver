package com.sentinel.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 服务层受理结果。fromCache 只写到 {@link ApiResponse#fromCache}，不放进 202 的 data。
 *
 * @author Sentinel Team
 */
@Getter
@AllArgsConstructor
public class DiagnosisSubmission {

    private final DiagnosisAcceptedResponse response;

    private final boolean fromCache;
}
