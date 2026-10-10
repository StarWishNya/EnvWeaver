package com.sentinel.service.provision;

import com.sentinel.config.BusinessException;
import com.sentinel.enums.ErrorCode;
import lombok.Getter;

/**
 * 环境行已经写入之后供给失败。
 * 编排器用 environmentId 把这个 ERROR 环境挂到任务上，而不是当成“还没创建”。
 */
@Getter
public class ProvisioningFailedException extends BusinessException {

    private final Long environmentId;

    public ProvisioningFailedException(ErrorCode errorCode, String message, Long environmentId) {
        super(errorCode, message, null);
        this.environmentId = environmentId;
    }

    public ProvisioningFailedException(Integer code, String message, Long environmentId) {
        super(code, message, null);
        this.environmentId = environmentId;
    }
}
