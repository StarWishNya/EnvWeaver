package com.sentinel.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 诊断缓存实体类
 * 
 * @author Sentinel Team
 */
@Data
@TableName("t_diagnosis_cache")
public class DiagnosisCache {

    /**
     * 缓存ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 日志内容的 MD5 哈希
     */
    private String logHash;

    /**
     * 日志样本(前 500 字符)
     */
    private String logSample;

    /**
     * 根本原因(AI 分析结果)
     */
    private String rootCause;

    /**
     * 可能原因列表 JSON
     */
    private String possibleReasons;

    /**
     * 解决方案列表 JSON
     */
    private String solutions;

    /**
     * 命中次数
     */
    private Integer hitCount;

    /**
     * 最后命中时间
     */
    private LocalDateTime lastHitAt;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
