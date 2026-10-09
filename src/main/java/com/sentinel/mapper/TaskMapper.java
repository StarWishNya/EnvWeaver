package com.sentinel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sentinel.domain.Task;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务 Mapper 接口
 * 
 * @author Sentinel Team
 */
@Mapper
public interface TaskMapper extends BaseMapper<Task> {

    /**
     * 根据状态查询任务列表
     * 
     * @param status 任务状态
     * @return 任务列表
     */
    @Select("SELECT * FROM t_task WHERE status = #{status} ORDER BY priority DESC, created_at ASC")
    List<Task> findByStatus(@Param("status") String status);

    /**
     * 分页查询任务列表。status、testType 为空时不过滤。
     *
     * @param page 分页对象（页码从 1 开始，与 MyBatis-Plus 一致）
     * @param status 任务状态（可选）
     * @param testType 测试类型（可选）
     * @return 分页结果
     */
    @Select("<script>" +
            "SELECT * FROM t_task " +
            "<where>" +
            "<if test='status != null and status != \"\"'>" +
            "AND status = #{status}" +
            "</if>" +
            "<if test='testType != null and testType != \"\"'>" +
            "AND test_type = #{testType}" +
            "</if>" +
            "</where>" +
            "ORDER BY created_at DESC" +
            "</script>")
    IPage<Task> selectTaskPage(Page<Task> page,
                               @Param("status") String status,
                               @Param("testType") String testType);

    /**
     * 比较并设置状态。只有当前状态等于 expected 时才更新，返回影响行数。
     * startedAt / completedAt 为 null 时保留原值。
     */
    @Update("UPDATE t_task SET status = #{next}, " +
            "started_at = IF(#{startedAt,jdbcType=TIMESTAMP} IS NULL, started_at, #{startedAt,jdbcType=TIMESTAMP}), " +
            "completed_at = IF(#{completedAt,jdbcType=TIMESTAMP} IS NULL, completed_at, #{completedAt,jdbcType=TIMESTAMP}) " +
            "WHERE id = #{taskId} AND status = #{expected}")
    int compareAndSetStatus(@Param("taskId") Long taskId,
                            @Param("expected") String expected,
                            @Param("next") String next,
                            @Param("startedAt") LocalDateTime startedAt,
                            @Param("completedAt") LocalDateTime completedAt);

    /**
     * 只更新环境 ID，避免把并发改过的状态写回去。
     */
    @Update("UPDATE t_task SET environment_id = #{environmentId,jdbcType=BIGINT} WHERE id = #{taskId}")
    int updateEnvironmentId(@Param("taskId") Long taskId,
                            @Param("environmentId") Long environmentId);
}
