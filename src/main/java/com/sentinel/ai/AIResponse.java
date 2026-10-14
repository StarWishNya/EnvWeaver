package com.sentinel.ai;

/**
 * 一次成功的补全结果。provider 是配置里的提供商名称，不是协议名。
 */
public record AIResponse(String provider, String model, String content) {
}
