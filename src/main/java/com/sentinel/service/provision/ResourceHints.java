package com.sentinel.service.provision;

import lombok.Getter;

/**
 * 目录里的资源提示。供给器仍使用全局容器限额，这里只随目录暴露。
 */
@Getter
public class ResourceHints {

    private final Integer memoryMb;
    private final Double cpu;

    public ResourceHints(Integer memoryMb, Double cpu) {
        this.memoryMb = memoryMb;
        this.cpu = cpu;
    }
}
