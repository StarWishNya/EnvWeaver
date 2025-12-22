package com.sentinel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sentinel.domain.Container;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 容器 Mapper 接口
 * 
 * @author Sentinel Team
 */
@Mapper
public interface ContainerMapper extends BaseMapper<Container> {

    /**
     * 根据环境ID查询容器列表
     * 
     * @param environmentId 环境ID
     * @return 容器列表
     */
    @Select("SELECT * FROM t_container WHERE environment_id = #{environmentId}")
    List<Container> findByEnvironmentId(@Param("environmentId") Long environmentId);
}
