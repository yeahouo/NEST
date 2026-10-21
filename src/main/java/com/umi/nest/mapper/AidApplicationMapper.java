package com.umi.nest.mapper;

import com.umi.nest.entity.AidApplication;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 资助申请（学工 P5）数据访问。
 */
@Mapper
public interface AidApplicationMapper {

    int insert(AidApplication record);

    AidApplication findById(@Param("id") Long id);

    List<AidApplication> listMine(@Param("studentId") Long studentId,
                                  @Param("status") String status,
                                  @Param("offset") int offset,
                                  @Param("limit") int limit);

    int countMine(@Param("studentId") Long studentId,
                  @Param("status") String status);

    /**
     * 审批收件箱：scopeCounselorId 非空时只看该辅导员名下学生（依 student_profile.counselor_id）。
     */
    List<AidApplication> listInbox(@Param("status") String status,
                                   @Param("aidType") String aidType,
                                   @Param("keyword") String keyword,
                                   @Param("scopeCounselorId") Long scopeCounselorId,
                                   @Param("offset") int offset,
                                   @Param("limit") int limit);

    int countInbox(@Param("status") String status,
                   @Param("aidType") String aidType,
                   @Param("keyword") String keyword,
                   @Param("scopeCounselorId") Long scopeCounselorId);

    /** 状态机：WHERE status = #{from} 保证并发下只有一个审批生效。amount 非空时同时写入（审批时可核定金额）。 */
    int updateStatus(@Param("id") Long id,
                     @Param("from") String from,
                     @Param("to") String to,
                     @Param("approverId") Long approverId,
                     @Param("approveNote") String approveNote,
                     @Param("amount") BigDecimal amount);

    int cancel(@Param("id") Long id, @Param("studentId") Long studentId);

    /**
     * 重复提交闸：同一学生 + 同一学年 + 同一项目是否已有待审/已批准的申请。
     * 在 SQL 里判定，不受历史申请条数（分页上限）影响。
     */
    int countOccupying(@Param("studentId") Long studentId,
                       @Param("aidType") String aidType,
                       @Param("academicYear") String academicYear);

    List<Map<String, Object>> countGroupByStatus(@Param("studentId") Long studentId,
                                                 @Param("scopeCounselorId") Long scopeCounselorId);

    // =========================================================================
    // P11 家庭经济困难学生认定（aid_type IN ('need','困难认定') 的行专用）
    // 说明：以上 P5 方法签名与语义一律未改，以下全部是新增方法。
    // 所有写方法都是 CAS：WHERE review_stage = #{fromStage}，返回 0 行即「非法流转」。
    // =========================================================================

    /** 认定申请落库（比 P5 的 insert 多 materials / family_note / cert_year / review_stage）。 */
    int insertReview(AidApplication record);

    /** 本人认定申请（全部学年，倒序）。 */
    List<AidApplication> listMineReview(@Param("studentId") Long studentId);

    /** 同一学年是否已提交过认定申请（重复提交闸，任何状态都算占用）。 */
    int countCertApplications(@Param("studentId") Long studentId,
                              @Param("certYear") String certYear);

    List<AidApplication> listReview(@Param("stage") String stage,
                                    @Param("certYear") String certYear,
                                    @Param("keyword") String keyword,
                                    @Param("scopeCounselorId") Long scopeCounselorId,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);

    int countReview(@Param("stage") String stage,
                    @Param("certYear") String certYear,
                    @Param("keyword") String keyword,
                    @Param("scopeCounselorId") Long scopeCounselorId);

    /**
     * 认定统计：一条 GROUP BY review_stage 查出全部阶段计数（供 /api/aid/review/stats 用，
     * 不要在 Service 里循环查 6 次）。返回行形如 {review_stage, cnt}。
     */
    List<Map<String, Object>> countByStage(@Param("stage") String stage,
                                           @Param("certYear") String certYear,
                                           @Param("keyword") String keyword,
                                           @Param("scopeCounselorId") Long scopeCounselorId);

    /** 公示名单：已发布公示且在办（publicity / objection 阶段）的认定申请。 */
    List<AidApplication> listPublicity(@Param("certYear") String certYear);

    /** 学期复查名单：本学年已批准的认定学生。 */
    List<AidApplication> listRecheck(@Param("certYear") String certYear,
                                     @Param("scopeCounselorId") Long scopeCounselorId);

    /** 民主评议通过：写 committee_note 并推进阶段。 */
    int committeePass(@Param("id") Long id,
                      @Param("fromStage") String fromStage,
                      @Param("toStage") String toStage,
                      @Param("note") String note);

    /** 院系工作组定级：写 poverty_level + 意见并推进阶段。 */
    int setLevel(@Param("id") Long id,
                 @Param("fromStage") String fromStage,
                 @Param("toStage") String toStage,
                 @Param("level") String level,
                 @Param("note") String note);

    /** 单独调整级别（复议采纳 / 复查调整），不动流程阶段。 */
    int updatePovertyLevel(@Param("id") Long id, @Param("level") String level);

    /** 发布公示：写 publicity_start / publicity_end / objection_deadline（同轮只发布一次）。 */
    int startPublicity(@Param("id") Long id,
                       @Param("fromStage") String fromStage,
                       @Param("start") java.time.LocalDateTime start,
                       @Param("end") java.time.LocalDateTime end);

    /** 只推阶段（publicity → objection / objection → publicity）。 */
    int advanceStage(@Param("id") Long id,
                     @Param("fromStage") String fromStage,
                     @Param("toStage") String toStage);

    /** 院系书面评审报告：写 report_note 并推进阶段。 */
    int toReport(@Param("id") Long id,
                 @Param("fromStage") String fromStage,
                 @Param("toStage") String toStage,
                 @Param("note") String note);

    /** 资助中心审核 / 领导小组审批（未定终态）：记审批人与意见并推进阶段。 */
    int approveStage(@Param("id") Long id,
                     @Param("fromStage") String fromStage,
                     @Param("toStage") String toStage,
                     @Param("approverId") Long approverId,
                     @Param("note") String note);

    /** 领导小组审批通过：定终态 approved + 最终级别。 */
    int approveFinal(@Param("id") Long id,
                     @Param("fromStage") String fromStage,
                     @Param("approverId") Long approverId,
                     @Param("level") String level,
                     @Param("note") String note);

    /** 十类情形 / 各环节驳回：终态 rejected + 原因。 */
    int rejectApplication(@Param("id") Long id,
                          @Param("fromStage") String fromStage,
                          @Param("note") String note);

    /** 学期复查：写结论 / 可选调级 / 可选意见（只在已批准的行上生效）。 */
    int updateRecheck(@Param("id") Long id,
                      @Param("certYear") String certYear,
                      @Param("result") String result,
                      @Param("newLevel") String newLevel,
                      @Param("note") String note);
}
