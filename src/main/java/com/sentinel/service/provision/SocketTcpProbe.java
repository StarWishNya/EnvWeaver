package com.sentinel.service.provision;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 对配置主机上的映射端口做短超时 TCP 连接。
 */
@Slf4j
@Component
public class SocketTcpProbe implements TcpProbe {

    private static final int CONNECT_TIMEOUT_MILLIS = 300;

    @Override
    public boolean accepts(String host, int port) {
        String target = host == null || host.isBlank() ? "localhost" : host.trim();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target, port), CONNECT_TIMEOUT_MILLIS);
            return true;
        } catch (IOException ex) {
            log.debug("端口尚未接受连接: port={}", port);
            return false;
        }
    }
}
