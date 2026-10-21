package com.umi.nest.mapper;

import com.umi.nest.entity.VolunteerEnrollment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 志愿服务报名（学工 P17，S21）数据访问。
 *
 * <p>状态机（§3.1）：{@code pending} → {@code confirmed} → {@code attended} / {@code absent}；
 * {@code pending}/{@code confirmed} 可 {@code cancelled}。取消与登记出勤都是 CAS
 * （{@code WHERE id=? AND deleted=0 AND status=fromStatus}，§7-1）。</p>
 *
 * <p>本表属学生范围表（§6）：{@code scopeCounselorId} 非空时
 * {@code JOIN student_profile sp ON sp.user_id = t.user_id AND sp.counselor_id = #{scopeCounselorId}}；
 * {@code scopeUserId} 用 {@code t.user_id = #{scopeUserId}}。所有查询一律带 {@code deleted = 0}。</p>
 */
@Mapper
public interface VolunteerEnrollmentMapper {

    int insert(VolunteerEnrollment enrollment);

    VolunteerEnrollment selectById(@Param("id") Long id);

    /** 增量修改（动态 set）。CAS：{@code id + deleted=0 + status=fromStatus}。 */
    int update(@Param("e") VolunteerEnrollment enrollment, @Param("fromStatus") String fromStatus);

    int softDelete(@Param("id") Long id);

    List<VolunteerEnrollment> selectList(@Param("scopeUserId") Long scopeUserId,
                                         @Param("scopeCounselorId") Long scopeCounselorId,
                                         @Param("projectId") Long projectId,
                                         @Param("status") String status,
                                         @Param("keyword") String keyword,
                                         @Param("offset") int offset,
                                         @Param("size") int size);

    long selectCount(@Param("scopeUserId") Long scopeUserId,
                     @Param("scopeCounselorId") Long scopeCounselorId,
                     @Param("projectId") Long projectId,
                     @Param("status") String status,
                     @Param("keyword") String keyword);

    /** 报名资格前置校验：同一学生对同一项目的有效报名（UNIQUE uk_project_user）。 */
    VolunteerEnrollment selectByProjectAndUser(@Param("projectId") Long projectId,
                                               @Param("userId") Long userId);

    /** 取消报名 CAS：{@code pending / confirmed → cancelled}（§7-1）。 */
    int cancelEnrollment(@Param("id") Long id,
                         @Param("fromStatus") String fromStatus,
                         @Param("remark") String remark);

    /** 出勤登记 CAS：{@code confirmed → attended / absent}；{@code cancelled} 不得登记（§7-4）。 */
    int markAttendance(@Param("id") Long id,
                       @Param("fromStatus") String fromStatus,
                       @Param("toStatus") String toStatus);

    /** 前置校验用：某项目某状态的报名数（{@code status} 可为空表示全部）。 */
    long countByProjectAndStatus(@Param("projectId") Long projectId, @Param("status") String status);

    /** 统计用：按状态分组计数。 */
    List<Map<String, Object>> countGroupByStatus(@Param("projectId") Long projectId);
}
