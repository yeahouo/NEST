package com.umi.nest.mapper;

import com.umi.nest.entity.LeaveRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 学工平台·请假 / 离校 Mapper。
 *
 * <p>所有查询都带 deleted = 0；列表按 created_at DESC。</p>
 */
@Mapper
public interface LeaveRequestMapper {

    int insert(LeaveRequest leave);

    LeaveRequest findById(@Param("id") Long id);

    /** 学生自己的假条（可按状态过滤） */
    List<LeaveRequest> listByStudent(@Param("studentId") Long studentId,
                                     @Param("status") String status,
                                     @Param("offset") int offset,
                                     @Param("limit") int limit);

    int countByStudent(@Param("studentId") Long studentId,
                       @Param("status") String status);

    /** 辅导员 / 管理端收件箱：scopeCounselorId 为 null 表示不限（admin） */
    List<LeaveRequest> listInbox(@Param("scopeCounselorId") Long scopeCounselorId,
                                 @Param("status") String status,
                                 @Param("keyword") String keyword,
                                 @Param("offset") int offset,
                                 @Param("limit") int limit);

    int countInbox(@Param("scopeCounselorId") Long scopeCounselorId,
                   @Param("status") String status,
                   @Param("keyword") String keyword);

    /** 状态流转：只在 from 状态匹配时才改（乐观状态机，防并发重复审批） */
    int updateStatus(@Param("id") Long id,
                     @Param("from") String from,
                     @Param("to") String to,
                     @Param("approverId") Long approverId,
                     @Param("approveNote") String approveNote);

    /** 学生撤回：只有本人的 pending 才能撤 */
    int cancel(@Param("id") Long id, @Param("studentId") Long studentId);

    int softDelete(@Param("id") Long id, @Param("studentId") Long studentId);

    /** 按状态计数（收件箱角标） */
    List<Map<String, Object>> countGroupByStatus(@Param("scopeCounselorId") Long scopeCounselorId);

    /** 辅导员工作台·逾期预警：已批准但结束时间已过（未销假）的假条，按结束时间正序 */
    List<LeaveRequest> listOverdue(@Param("scopeCounselorId") Long scopeCounselorId,
                                   @Param("before") java.time.LocalDateTime before,
                                   @Param("offset") int offset,
                                   @Param("limit") int limit);

    int countOverdue(@Param("scopeCounselorId") Long scopeCounselorId,
                     @Param("before") java.time.LocalDateTime before);

    /** 辅导员工作台·请假频繁预警：since 之后提交条数 >= minCount 的学生（Map 的键是列原名） */
    List<Map<String, Object>> listFrequentStudents(@Param("scopeCounselorId") Long scopeCounselorId,
                                                   @Param("since") java.time.LocalDateTime since,
                                                   @Param("minCount") int minCount);
}
