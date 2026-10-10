package com.sentinel.service.provision;

import lombok.Getter;

/**
 * 容器就绪探测。当前只支持对容器端口做 TCP 探测。
 */
@Getter
public class ReadinessSpec {

    private final String type;
    private final int port;
    private final int startupGraceSeconds;

    public ReadinessSpec(String type, int port, int startupGraceSeconds) {
        this.type = type;
        this.port = port;
        this.startupGraceSeconds = startupGraceSeconds;
    }
}
