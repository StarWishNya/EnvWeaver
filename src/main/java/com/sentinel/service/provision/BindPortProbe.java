package com.sentinel.service.provision;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * 用一次本地 bind 判断端口是否空闲。绑定成功后立即关闭，供 Docker 重新占用。
 */
@Component
public class BindPortProbe implements PortProbe {

    @Override
    public boolean canBind(int port) {
        if (port < 1 || port > 65535) {
            return false;
        }
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("0.0.0.0", port));
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}
