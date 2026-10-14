package com.sentinel.controller;

import com.sentinel.dto.ApiResponse;
import com.sentinel.dto.MiddlewareCatalogResponse;
import com.sentinel.dto.TemplateCatalogResponse;
import com.sentinel.service.provision.MiddlewareCatalog;
import com.sentinel.service.provision.TemplateCatalog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 中间件目录与环境模板。任何已登录角色都可以读。
 */
@RestController
@RequestMapping("/api/catalog")
@Tag(name = "Catalog", description = "中间件目录与环境模板")
public class CatalogController {

    private final MiddlewareCatalog middlewareCatalog;
    private final TemplateCatalog templateCatalog;

    public CatalogController(MiddlewareCatalog middlewareCatalog, TemplateCatalog templateCatalog) {
        this.middlewareCatalog = middlewareCatalog;
        this.templateCatalog = templateCatalog;
    }

    @GetMapping("/middleware")
    @Operation(summary = "中间件目录", description = "返回种类、版本、别名、默认版本、镜像和容器端口。不含环境变量。")
    public ApiResponse<MiddlewareCatalogResponse> middleware() {
        return ApiResponse.success(MiddlewareCatalogResponse.from(middlewareCatalog));
    }

    @GetMapping("/templates")
    @Operation(summary = "环境模板", description = "返回命名模板及其依赖记号。")
    public ApiResponse<TemplateCatalogResponse> templates() {
        return ApiResponse.success(TemplateCatalogResponse.from(templateCatalog));
    }
}
