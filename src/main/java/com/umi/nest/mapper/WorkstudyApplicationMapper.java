package com.umi.nest.mapper;

import com.umi.nest.entity.WorkstudyApplication;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 勤工助学申请 mapper（学工 P10）。动态 SQL 全在 WorkstudyApplicationMapper.xml。
 */
@Mapper
public interface WorkstudyApplicationMapper {

    int insert(WorkstudyApplication record);

    /** 带岗位/学生/班级的详情（不存在返回 null） */
    WorkstudyApplication findById(@Param("id") Long id);

    /** 学生端「我的申请」 */
    List<WorkstudyApplication> listByStudent(@Param("studentId") Long studentId,
                                             @Param("limit") int limit,
                                             @Param("offset") int offset);

    int countByStudent(@Param("studentId") Long studentId);

    /** 某学生的在岗（hired）记录，用于「我的岗位」与「同学期固定岗 ≤ 1」 */
    List<WorkstudyApplication> listHiredByStudent(@Param("studentId") Long studentId,
                                                 @Param("postType") String postType,
                                                 @Param("academicTerm") String academicTerm);

    /** 用工单位/资助中心的申请列表（按岗位筛） */
    List<WorkstudyApplication> listByPost(@Param("postId") Long postId,
                                          @Param("status") String status,
                                          @Param("unitUserId") Long unitUserId,
                                          @Param("academicTerm") String academicTerm,
                                          @Param("limit") int limit,
                                          @Param("offset") int offset);

    int countByPost(@Param("postId") Long postId,
                    @Param("status") String status,
                    @Param("unitUserId") Long unitUserId,
                    @Param("academicTerm") String academicTerm);

    /** 重复申请判定（唯一键 uk_post_student(post_id,student_id,deleted) 兜底） */
    int countByPostStudent(@Param("postId") Long postId, @Param("studentId") Long studentId);

    int countByPostAndStatus(@Param("postId") Long postId, @Param("status") String status);

    /**
     * 状态机迁移（替换式更新，服务层只填本次要改的键）：
     * fromStatus 非空 → 乐观条件；interviewResult/interviewNote/note/resignReason 非空才写。
     */
    int updateStatus(Map<String, Object> params);

    /** 学生辞职：hired/resigning → resigned 之外，也用于回填辞职理由 */
    int updateResignReason(@Param("id") Long id, @Param("reason") String reason);

    /** 按申请状态聚合：每行 {status, cnt}，供概览统计 */
    List<Map<String, Object>> countGroupByStatus(@Param("unitUserId") Long unitUserId,
                                                 @Param("academicTerm") String academicTerm);
}
