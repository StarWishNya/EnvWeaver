package com.sentinel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sentinel.domain.SystemConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 系统配置 Mapper 接口
 * 
 * @author Sentinel Team
 */
@Mapper
public interface SystemConfigMapper extends BaseMapper<SystemConfig> {

    /**
     * 根据配置键查询配置
     * 
     * @param configKey 配置键
     * @return 系统配置对象
     */
    @Select("SELECT * FROM t_system_config WHERE config_key = #{configKey}")
    SystemConfig findByConfigKey(@Param("configKey") String configKey);
}
