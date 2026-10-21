package com.umi.nest.mapper;

import com.umi.nest.entity.CommunityActivitySignup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 社区活动报名（学工 P15）数据访问。
 *
 * <p>同活动同学生唯一（uk_cas_activity_student），服务层先查重再插入，DB 唯一键兜底并发。
 * 状态列 status（signed/approved/rejected/attended/absent），CAS 写成
 * WHERE id=? AND deleted=0 AND status=#{fromStatus}。</p>
 */
@Mapper
public interface CommunityActivitySignupMapper {

    int insert(CommunityActivitySignup signup);

    CommunityActivitySignup findById(@Param("id") Long id);

    /** 查重：同活动同学生是否已有报名（deleted=0）。 */
    CommunityActivitySignup findByActivityStudent(@Param("activityId") Long activityId,
                                                  @Param("studentId") Long studentId);

    List<CommunityActivitySignup> listByActivity(@Param("activityId") Long activityId,
                                                 @Param("status") String status,
                                                 @Param("keyword") String keyword);

    List<CommunityActivitySignup> listMine(@Param("studentId") Long studentId,
                                           @Param("activityId") Long activityId);

    /** 报名审核：signed → approved / rejected。 */
    int review(@Param("id") Long id,
               @Param("fromStatus") String fromStatus,
               @Param("toStatus") String toStatus,
               @Param("rejectReason") String rejectReason,
               @Param("reviewedBy") Long reviewedBy,
               @Param("reviewedAt") LocalDateTime reviewedAt);

    /** 签到置位 attended / absent，可同时登记 award 与 score。 */
    int markAttendance(@Param("id") Long id,
                       @Param("fromStatus") String fromStatus,
                       @Param("toStatus") String toStatus,
                       @Param("award") String award,
                       @Param("score") BigDecimal score,
                       @Param("note") String note,
                       @Param("reviewedBy") Long reviewedBy,
                       @Param("reviewedAt") LocalDateTime reviewedAt);

    /** 已占用名额（不含已驳回）。 */
    Integer occupancy(@Param("activityId") Long activityId);

    List<Map<String, Object>> countGroupByStatus(@Param("activityId") Long activityId);

    /** 某学生是否在某活动有已通过/已参加的报名（文明/违规联动用）。 */
    Integer countActive(@Param("activityId") Long activityId, @Param("studentId") Long studentId);
}
