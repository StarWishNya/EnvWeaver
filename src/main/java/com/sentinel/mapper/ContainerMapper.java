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

    /**
     * 根据 Docker 容器 ID 查询容器记录
     * 
     * @param containerId Docker 容器 ID
     * @return 容器记录，不存在时返回 null
     */
    @Select("SELECT * FROM t_container WHERE container_id = #{containerId} LIMIT 1")
    Container findByContainerId(@Param("containerId") String containerId);

    /**
     * 某个环境里仍在运行的容器。采集资源时只统计这些容器。
     */
    @Select("SELECT * FROM t_container WHERE environment_id = #{environmentId} AND status = 'RUNNING'")
    List<Container> findRunningByEnvironmentId(@Param("environmentId") Long environmentId);
}
