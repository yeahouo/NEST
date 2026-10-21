package com.umi.nest.mapper;

import com.umi.nest.entity.Club;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 学生社团台账（学工 P17，S25a）数据访问。
 *
 * <p>口径（§2）：{@code attach_unit} 是**挂靠单位**，与 {@code club_rating.business_unit}
 * 「业务指导单位」**不得互换**。所有查询一律带 {@code deleted = 0}。</p>
 *
 * <p>scope（§6）：社团按 {@code club_member.user_id} 关联学生。{@code scopeUserId} 非空时
 * 用 {@code EXISTS} 子查询限定「该学生是成员」；{@code scopeCounselorId} 非空时限定
 * 「该辅导员名下有学生是本社团成员」。</p>
 */
@Mapper
public interface ClubMapper {

    int insert(Club club);

    Club selectById(@Param("id") Long id);

    /** 增量修改（动态 set）。CAS：{@code id + deleted=0 + status=fromStatus}。 */
    int update(@Param("c") Club club, @Param("fromStatus") String fromStatus);

    int softDelete(@Param("id") Long id);

    List<Club> selectList(@Param("scopeUserId") Long scopeUserId,
                          @Param("scopeCounselorId") Long scopeCounselorId,
                          @Param("category") String category,
                          @Param("attachUnit") String attachUnit,
                          @Param("status") String status,
                          @Param("keyword") String keyword,
                          @Param("offset") int offset,
                          @Param("size") int size);

    long selectCount(@Param("scopeUserId") Long scopeUserId,
                     @Param("scopeCounselorId") Long scopeCounselorId,
                     @Param("category") String category,
                     @Param("attachUnit") String attachUnit,
                     @Param("status") String status,
                     @Param("keyword") String keyword);

    /** 注销 CAS：{@code active → deregistered}。 */
    int deregister(@Param("id") Long id, @Param("fromStatus") String fromStatus);

    /** 前置校验用：按状态计数。 */
    long countByStatus(@Param("status") String status);

    /** 统计用：按类别分组计数（§3.4 六类）。 */
    List<Map<String, Object>> countGroupByCategory();

    /** 统计用：按状态分组计数。 */
    List<Map<String, Object>> countGroupByStatus();
}
