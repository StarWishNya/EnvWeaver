package com.sentinel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sentinel.domain.Environment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    /**
     * 非空闲环境已占用的端口映射 JSON。
     */
    @Select("SELECT port_mappings FROM t_environment WHERE status <> 'IDLE' AND port_mappings IS NOT NULL")
    List<String> selectActivePortMappings();

    /**
     * 供给失败：标记 ERROR，并清空端口映射，避免失败环境占住主机端口。
     */
    @Update("UPDATE t_environment SET status = 'ERROR', port_mappings = NULL WHERE id = #{id}")
    int markProvisionFailed(@Param("id") Long id);

    /**
     * 按状态查询环境，按 id 升序，最多 limit 条。
     * 采集器用来扫描 READY、BUSY、RETAINED。
     */
    @Select("<script>" +
            "SELECT * FROM t_environment WHERE status IN " +
            "<foreach collection='statuses' item='status' open='(' separator=',' close=')'>" +
            "#{status}" +
            "</foreach> " +
            "ORDER BY id ASC LIMIT #{limit}" +
            "</script>")
    List<Environment> selectByStatuses(@Param("statuses") List<String> statuses, @Param("limit") int limit);

    /**
     * 仍写着资源使用的空闲环境，释放后用来清掉过期的 CPU / 内存。
     */
    @Select("SELECT * FROM t_environment WHERE status = 'IDLE' AND resource_usage IS NOT NULL " +
            "ORDER BY id ASC LIMIT #{limit}")
    List<Environment> selectIdleWithResourceUsage(@Param("limit") int limit);

    /**
     * 只更新 resource_usage，不改 status。
     * resourceUsage 为 null 时写成 SQL NULL。updated_at 可以随行更新而变化。
     */
    @Update("UPDATE t_environment SET resource_usage = #{resourceUsage, jdbcType=VARCHAR} WHERE id = #{id}")
    int updateResourceUsage(@Param("id") Long id, @Param("resourceUsage") String resourceUsage);
}
