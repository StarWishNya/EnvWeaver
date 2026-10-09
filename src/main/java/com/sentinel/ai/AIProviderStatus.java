package com.sentinel.ai;

/**
 * 提供商的廉价状态快照。available 不代表做过网络探测。
 */
public record AIProviderStatus(
        String name,
        AIProtocol protocol,
        String model,
        boolean configured,
        boolean available,
        String detail) {
}
