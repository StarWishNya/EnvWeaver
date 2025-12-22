package com.sentinel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sentinel.domain.Task;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
     * 分页查询任务列表
     * 
     * @param page 分页对象
     * @param status 任务状态（可选）
     * @return 分页结果
     */
    @Select("<script>" +
            "SELECT * FROM t_task " +
            "<where>" +
            "<if test='status != null and status != \"\"'>" +
            "AND status = #{status}" +
            "</if>" +
            "</where>" +
            "ORDER BY created_at DESC" +
            "</script>")
    IPage<Task> selectTaskPage(Page<Task> page, @Param("status") String status);
}
