package com.sentinel.service.provision;

import java.time.Duration;

/**
 * 就绪轮询之间的等待，便于测试替换。
 */
public interface ReadinessPause {

    /**
     * 暂停当前线程。被中断时恢复中断标记并抛出业务异常。
     */
    void pause(Duration duration);
}
