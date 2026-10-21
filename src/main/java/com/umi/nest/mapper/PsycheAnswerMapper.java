package com.umi.nest.mapper;

import com.umi.nest.entity.PsycheAnswer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 学工 P14 心理健康 · 整卷作答数据访问（psyche_answer）。
 *
 * <p>uk_pa_task(task_id) 保证一份卷一条；insert 走 ON DUPLICATE KEY UPDATE 以容忍重放。</p>
 */
@Mapper
public interface PsycheAnswerMapper {

    int insert(PsycheAnswer answer);

    PsycheAnswer findByTaskId(@Param("taskId") Long taskId);
}
