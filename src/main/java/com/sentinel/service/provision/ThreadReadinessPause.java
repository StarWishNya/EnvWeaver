package com.sentinel.service.provision;

import com.sentinel.config.BusinessException;
import com.sentinel.enums.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 使用 Thread.sleep 的就绪等待。
 */
@Component
public class ThreadReadinessPause implements ReadinessPause {

    @Override
    public void pause(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.ENVIRONMENT_CREATE_FAILED, "等待依赖就绪被中断");
        }
    }
}
