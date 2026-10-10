package com.sentinel.service.provision;

/**
 * 中间件目录或环境模板在启动时无法加载。消息不含凭据。
 */
public class CatalogLoadException extends IllegalStateException {

    public CatalogLoadException(String message) {
        super(message);
    }

    public CatalogLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
