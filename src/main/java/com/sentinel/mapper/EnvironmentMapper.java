package com.sentinel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sentinel.domain.Environment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 环境 Mapper 接口
 * 
 * @author Sentinel Team
 */
@Mapper
public interface EnvironmentMapper extends BaseMapper<Environment> {

    /**
     * 根据状态查询环境列表
     * 
     * @param status 环境状态
     * @return 环境列表
     */
    @Select("SELECT * FROM t_environment WHERE status = #{status}")
    List<Environment> findByStatus(@Param("status") String status);

    /**
     * 查询空闲环境
     * 
     * @return 空闲环境列表
     */
    @Select("SELECT * FROM t_environment WHERE status IN ('IDLE', 'READY') AND task_id IS NULL")
    List<Environment> findIdleEnvironments();

    /**
     * 按 id 倒序查询环境，最多返回 maxRows 条。
     * status 为空时不过滤状态。
     *
     * @param status 环境状态，可空
     * @param maxRows 最大条数
     * @return 环境列表
     */
    @Select("<script>" +
            "SELECT * FROM t_environment " +
            "<where>" +
            "<if test='status != null and status != \"\"'>" +
            "AND status = #{status}" +
            "</if>" +
            "</where>" +
            "ORDER BY id DESC " +
            "LIMIT #{maxRows}" +
            "</script>")
    List<Environment> selectRecent(@Param("status") String status, @Param("maxRows") int maxRows);
}
