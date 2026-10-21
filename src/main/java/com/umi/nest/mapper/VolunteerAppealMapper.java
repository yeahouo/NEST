package com.umi.nest.mapper;

import com.umi.nest.entity.VolunteerAppeal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 志愿服务申诉（学工 P17，S21 第三十七条）数据访问。
 *
 * <p>申诉窗口 = **5 个工作日**（§4），超窗返回 400；{@code within_window} 在提交时由服务层算好落库。
 * 裁定是 CAS（{@code WHERE id=? AND deleted=0 AND status=fromStatus}，§7-1），
 * {@code rejected}/{@code closed} 为终态（§7-2）。</p>
 *
 * <p>本表属学生范围表（§6）：{@code scopeCounselorId} 非空时
 * {@code JOIN student_profile sp ON sp.user_id = t.user_id AND sp.counselor_id = #{scopeCounselorId}}；
 * {@code scopeUserId} 用 {@code t.user_id = #{scopeUserId}}。所有查询一律带 {@code deleted = 0}。</p>
 */
@Mapper
public interface VolunteerAppealMapper {

    int insert(VolunteerAppeal appeal);

    VolunteerAppeal selectById(@Param("id") Long id);

    /** 增量修改（动态 set）。CAS：{@code id + deleted=0 + status=fromStatus}。 */
    int update(@Param("a") VolunteerAppeal appeal, @Param("fromStatus") String fromStatus);

    int softDelete(@Param("id") Long id);

    List<VolunteerAppeal> selectList(@Param("scopeUserId") Long scopeUserId,
                                     @Param("scopeCounselorId") Long scopeCounselorId,
                                     @Param("recordId") Long recordId,
                                     @Param("blacklistId") Long blacklistId,
                                     @Param("status") String status,
                                     @Param("keyword") String keyword,
                                     @Param("offset") int offset,
                                     @Param("size") int size);

    long selectCount(@Param("scopeUserId") Long scopeUserId,
                     @Param("scopeCounselorId") Long scopeCounselorId,
                     @Param("recordId") Long recordId,
                     @Param("blacklistId") Long blacklistId,
                     @Param("status") String status,
                     @Param("keyword") String keyword);

    /** 判定 CAS：{@code pending/accepted → accepted/rejected/closed}（§7-1）。 */
    int ruleAppeal(@Param("id") Long id,
                   @Param("fromStatus") String fromStatus,
                   @Param("toStatus") String toStatus,
                   @Param("ruling") String ruling,
                   @Param("rulerId") Long rulerId);

    /** 前置校验用：按状态计数。 */
    long countByStatus(@Param("status") String status);

    /** 前置校验用：同一学生对同一记录的待受理申诉数（防重复提交）。 */
    long countPendingByUserAndTarget(@Param("userId") Long userId,
                                     @Param("recordId") Long recordId,
                                     @Param("blacklistId") Long blacklistId);

    /** 统计用：按状态分组计数。 */
    List<Map<String, Object>> countGroupByStatus();
}
