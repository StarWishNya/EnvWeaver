package com.sentinel.dto;

import lombok.Data;

import java.util.List;

/**
 * 分页数据。字段与 API 规范里 PageResponse.data 一致。
 *
 * @author Sentinel Team
 */
@Data
public class PageResponse<T> {

    private List<T> content;

    /**
     * 从 0 开始的页码
     */
    private int page;

    private int size;

    private long totalElements;

    private long totalPages;
}
