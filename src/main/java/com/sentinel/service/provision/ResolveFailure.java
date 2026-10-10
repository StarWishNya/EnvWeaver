package com.sentinel.service.provision;

import lombok.Getter;

import java.util.List;

/**
 * 解析失败。code 固定为 VALIDATION_ERROR 对应的业务码，candidates 是可选版本或种类。
 */
@Getter
public class ResolveFailure {

    public static final String UNKNOWN_VERSION = "UNKNOWN_VERSION";
    public static final String UNKNOWN_KIND = "UNKNOWN_KIND";

    private final String code;
    private final String token;
    private final String message;
    private final List<String> candidates;

    public ResolveFailure(String code, String token, String message, List<String> candidates) {
        this.code = code;
        this.token = token;
        this.message = message;
        this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
