package com.sentinel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sentinel.domain.DiagnosisCache;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 诊断缓存 Mapper 接口
 * 
 * @author Sentinel Team
 */
@Mapper
public interface DiagnosisCacheMapper extends BaseMapper<DiagnosisCache> {

    /**
     * 根据日志哈希查询诊断缓存
     * 
     * @param logHash 日志哈希值
     * @return 诊断缓存对象
     */
    @Select("SELECT * FROM t_diagnosis_cache WHERE log_hash = #{logHash}")
    DiagnosisCache findByLogHash(@Param("logHash") String logHash);
}
