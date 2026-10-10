package com.sentinel.config;

import com.sentinel.service.provision.MiddlewareCatalog;
import com.sentinel.service.provision.TemplateCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 启动时加载运维目录。文件不合法时抛出 {@link com.sentinel.service.provision.CatalogLoadException}，应用不会起来。
 */
@Configuration
public class CatalogConfiguration {

    @Bean
    public MiddlewareCatalog middlewareCatalog() {
        return MiddlewareCatalog.load();
    }

    @Bean
    public TemplateCatalog templateCatalog(MiddlewareCatalog middlewareCatalog) {
        return TemplateCatalog.load(middlewareCatalog);
    }
}
