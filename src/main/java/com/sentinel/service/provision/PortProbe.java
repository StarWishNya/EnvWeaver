package com.sentinel.service.provision;

/**
 * 判断主机端口当前能否被绑定。
 */
public interface PortProbe {

    /**
     * @param port 主机端口
     * @return 可以独占绑定时返回 true
     */
    boolean canBind(int port);
}
