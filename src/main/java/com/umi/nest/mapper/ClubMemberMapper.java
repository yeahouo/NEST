package com.umi.nest.mapper;

import com.umi.nest.entity.ClubMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 社团成员（学工 P17，S25a）数据访问。
 *
 * <p>{@code role}：{@code leader} 负责人 / {@code member} 成员（§3.1）。退社是 CAS：
 * {@code quitMember} 只把 {@code quit_date} 由 NULL 置为指定日期（§7-1），
 * 影响行数 0 → 400「该成员已退社或状态已变化」。</p>
 *
 * <p>本表属学生范围表（§6）：{@code scopeCounselorId} 非空时
 * {@code JOIN student_profile sp ON sp.user_id = t.user_id AND sp.counselor_id = #{scopeCounselorId}}；
 * {@code scopeUserId} 用 {@code t.user_id = #{scopeUserId}}。所有查询一律带 {@code deleted = 0}。</p>
 */
@Mapper
public interface ClubMemberMapper {

    int insert(ClubMember member);

    ClubMember selectById(@Param("id") Long id);

    /** 增量修改（动态 set）。 */
    int update(@Param("m") ClubMember member);

    int softDelete(@Param("id") Long id);

    List<ClubMember> selectList(@Param("scopeUserId") Long scopeUserId,
                                @Param("scopeCounselorId") Long scopeCounselorId,
                                @Param("clubId") Long clubId,
                                @Param("role") String role,
                                @Param("keyword") String keyword,
                                @Param("offset") int offset,
                                @Param("size") int size);

    long selectCount(@Param("scopeUserId") Long scopeUserId,
                     @Param("scopeCounselorId") Long scopeCounselorId,
                     @Param("clubId") Long clubId,
                     @Param("role") String role,
                     @Param("keyword") String keyword);

    /** 前置校验用：某社团在册（{@code quit_date IS NULL}）成员数，也用于 club.memberCount 展示。 */
    long countByClub(@Param("clubId") Long clubId);

    /** 前置校验用：负责人人数（一个社团在册负责人数量，用于「已有负责人」拦截）。 */
    long countLeaderByClub(@Param("clubId") Long clubId);

    /** 前置校验用：某学生是否已在册于该社团（重复入社拦截）。 */
    ClubMember selectActiveByClubAndUser(@Param("clubId") Long clubId, @Param("userId") Long userId);

    /** 某学生当前在册的社团（`GET /list/mine` 用）。 */
    List<ClubMember> selectActiveByUser(@Param("userId") Long userId);

    /** 退社 CAS：只允许在册（{@code quit_date IS NULL}）→ 写 {@code quit_date}。 */
    int quitMember(@Param("id") Long id, @Param("quitDate") LocalDate quitDate);

    /** 统计用：按角色分组计数（{@code clubId} 可为空）。 */
    List<Map<String, Object>> countGroupByRole(@Param("clubId") Long clubId);
}
