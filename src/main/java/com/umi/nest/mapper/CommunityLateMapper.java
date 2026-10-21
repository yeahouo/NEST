package com.umi.nest.mapper;

import com.umi.nest.entity.CommunityLate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 晚归 / 夜不归宿记录（学工 P15）数据访问。
 *
 * <p>状态列为 handling（pending/contacted/closed），CAS 写成
 * WHERE id=? AND deleted=0 AND handling=#{fromStatus}；确认（confirmed）单独一条 CAS。</p>
 *
 * <p>辅导员范围裁剪：counselorId 非空时只返回
 * EXISTS student_profile p WHERE p.counselor_id=? AND p.user_id=l.student_id 的记录。</p>
 */
@Mapper
public interface CommunityLateMapper {

    int insert(CommunityLate late);

    CommunityLate findById(@Param("id") Long id);

    CommunityLate findByStudentDate(@Param("studentId") Long studentId,
                                    @Param("occurDate") LocalDate occurDate,
                                    @Param("type") String type);

    List<CommunityLate> list(@Param("studentId") Long studentId,
                             @Param("building") String building,
                             @Param("type") String type,
                             @Param("handling") String handling,
                             @Param("confirmed") Integer confirmed,
                             @Param("keyword") String keyword,
                             @Param("counselorId") Long counselorId,
                             @Param("startDate") LocalDate startDate,
                             @Param("endDate") LocalDate endDate,
                             @Param("offset") int offset,
                             @Param("limit") int limit);

    /** 列表总数（过滤条件与 {@link #list} 一致）。 */
    int countList(@Param("studentId") Long studentId,
                  @Param("building") String building,
                  @Param("type") String type,
                  @Param("handling") String handling,
                  @Param("confirmed") Integer confirmed,
                  @Param("keyword") String keyword,
                  @Param("counselorId") Long counselorId,
                  @Param("startDate") LocalDate startDate,
                  @Param("endDate") LocalDate endDate);

    /** 增量修改（CAS 在 handling 上）。 */
    int update(@Param("late") CommunityLate late, @Param("fromStatus") String fromStatus);

    /** 学生本人据实确认：confirmed=1（CAS 在 confirmed=0 上）。 */
    int confirm(@Param("id") Long id, @Param("by") Long by, @Param("at") LocalDateTime at);

    /** 处理推进：handling → 新状态，同时写处理说明与关联违规。 */
    int handle(@Param("id") Long id,
               @Param("fromStatus") String fromStatus,
               @Param("handling") String handling,
               @Param("handlingNote") String handlingNote,
               @Param("violationId") Long violationId);

    /** 学生补充说明（本人记录，服务层先校验归属）。 */
    int submitNote(@Param("id") Long id, @Param("studentId") Long studentId, @Param("note") String note);

    /** 由违规台账回填关联的晚归记录（幂等：violation_id IS NULL 才写）。 */
    int linkViolation(@Param("id") Long id, @Param("violationId") Long violationId);

    List<Map<String, Object>> countGroupByType(@Param("counselorId") Long counselorId,
                                              @Param("startDate") LocalDate startDate,
                                              @Param("endDate") LocalDate endDate);

    List<Map<String, Object>> countGroupByHandling(@Param("counselorId") Long counselorId,
                                                   @Param("startDate") LocalDate startDate,
                                                   @Param("endDate") LocalDate endDate);

    Map<String, Object> summary(@Param("counselorId") Long counselorId,
                                @Param("startDate") LocalDate startDate,
                                @Param("endDate") LocalDate endDate);

    /** 该学生是否属于此辅导员名下（辅导员登记晚归/违规的权限边界）。 */
    int countStudentOfCounselor(@Param("counselorId") Long counselorId,
                                @Param("studentId") Long studentId);
}
