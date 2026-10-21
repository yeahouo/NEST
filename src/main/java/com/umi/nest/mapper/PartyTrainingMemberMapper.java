package com.umi.nest.mapper;

import com.umi.nest.entity.PartyTrainingMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 党校学员报名与结业（学工 P16）数据访问。
 *
 * <p>状态机（全部 CAS on status）：
 * {@code signed -> approved -> completed}；{@code approved -> rejected}。
 * UNIQUE(training_id, user_id, deleted) 保证一人一班一条报名。</p>
 */
@Mapper
public interface PartyTrainingMemberMapper {

    int insert(PartyTrainingMember member);

    PartyTrainingMember findById(@Param("id") Long id);

    PartyTrainingMember findByTrainingAndUser(@Param("trainingId") Long trainingId,
                                              @Param("userId") Long userId);

    /** 辅导员范围：counselorId 非空时只返回该辅导员名下学生。 */
    List<PartyTrainingMember> list(@Param("trainingId") Long trainingId,
                                   @Param("userId") Long userId,
                                   @Param("counselorId") Long counselorId,
                                   @Param("status") String status,
                                   @Param("offset") int offset,
                                   @Param("limit") int limit);

    long count(@Param("trainingId") Long trainingId,
               @Param("userId") Long userId,
               @Param("counselorId") Long counselorId,
               @Param("status") String status);

    /** 报名人数统计（含各状态）。 */
    List<Map<String, Object>> countGroupByStatus(@Param("trainingId") Long trainingId);

    /** 审核：signed → approved/rejected，写 review_by / review_at / reject_reason（CAS on status）。 */
    int review(@Param("id") Long id,
               @Param("fromStatus") String fromStatus,
               @Param("toStatus") String toStatus,
               @Param("reviewBy") Long reviewBy,
               @Param("reviewAt") LocalDateTime reviewAt,
               @Param("rejectReason") String rejectReason);

    /** 结业登记：approved → completed，写结业结论/日期/证书（CAS on status）。 */
    int complete(@Param("id") Long id,
                 @Param("fromStatus") String fromStatus,
                 @Param("completeResult") String completeResult,
                 @Param("completeDate") LocalDate completeDate,
                 @Param("certNo") String certNo,
                 @Param("certIssueDate") LocalDate certIssueDate,
                 @Param("certValidUntil") LocalDate certValidUntil,
                 @Param("note") String note);

    /** 结业证书有效期覆盖（仅 completed）。 */
    int updateCert(@Param("id") Long id,
                   @Param("certNo") String certNo,
                   @Param("certIssueDate") LocalDate certIssueDate,
                   @Param("certValidUntil") LocalDate certValidUntil);

    /** 学员状态分布（统计用）。 */
    List<Map<String, Object>> countGroupByStatusAll();
}
