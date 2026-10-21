package com.umi.nest.mapper;

import com.umi.nest.entity.WorkstudyPost;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 勤工助学岗位 mapper（学工 P10）。动态 SQL 全在 WorkstudyPostMapper.xml。
 */
@Mapper
public interface WorkstudyPostMapper {

    int insert(WorkstudyPost record);

    /** 带用工单位负责人姓名的详情（不存在返回 null） */
    WorkstudyPost findById(@Param("id") Long id);

    List<WorkstudyPost> list(@Param("type") String type,
                             @Param("term") String term,
                             @Param("keyword") String keyword,
                             @Param("status") String status,
                             @Param("unitUserId") Long unitUserId,
                             @Param("limit") int limit,
                             @Param("offset") int offset);

    int count(@Param("type") String type,
              @Param("term") String term,
              @Param("keyword") String keyword,
              @Param("status") String status,
              @Param("unitUserId") Long unitUserId);

    /** 非空字段才更新；updated_at 一律 NOW() */
    int update(WorkstudyPost record);

    /**
     * 状态机迁移：{@code fromStatus} 非空时作为乐观条件（并发行数 0 → 服务层报冲突）。
     * reviewedAt 非空才写 reviewed_at。
     */
    int updateStatus(@Param("id") Long id,
                     @Param("fromStatus") String fromStatus,
                     @Param("toStatus") String toStatus,
                     @Param("reviewerId") Long reviewerId,
                     @Param("reviewNote") String reviewNote,
                     @Param("reviewedAt") LocalDateTime reviewedAt);

    /** 录用计数增减；delta=+1 时带 headcount 上限校验（行数 0 = 名额已满） */
    int updateHiredCount(@Param("id") Long id, @Param("delta") int delta);

    /** 按岗位状态聚合：每行 {status, cnt}，供概览统计 */
    List<Map<String, Object>> countGroupByStatus(@Param("academicTerm") String academicTerm,
                                                 @Param("unitUserId") Long unitUserId);
}
