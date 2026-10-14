package com.sentinel.service.provision;

/**
 * 判断主机端口是否已经接受 TCP 连接。
 */
public interface TcpProbe {

    /**
     * @param host 连接主机，调用方传入配置的 sentinel.provisioning.host
     * @param port 主机端口
     * @return 连接成功返回 true
     */
    boolean accepts(String host, int port);
}
