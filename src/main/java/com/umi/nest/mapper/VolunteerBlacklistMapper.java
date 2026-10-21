package com.umi.nest.mapper;

import com.umi.nest.entity.VolunteerBlacklist;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 志愿服务黑名单（学工 P17，S21 第三十四/三十五/三十六条）数据访问。
 *
 * <p>期限口径（§4）：6 个月，{@code expire_at = listed_at + 6 个月}由**服务层**按日期推算，
 * 不做定时任务。撤销是 CAS（{@code active → revoked}，§7-1）。</p>
 *
 * <p>本表属学生范围表（§6）：{@code scopeCounselorId} 非空时
 * {@code JOIN student_profile sp ON sp.user_id = t.user_id AND sp.counselor_id = #{scopeCounselorId}}；
 * {@code scopeUserId} 用 {@code t.user_id = #{scopeUserId}}。所有查询一律带 {@code deleted = 0}。</p>
 */
@Mapper
public interface VolunteerBlacklistMapper {

    int insert(VolunteerBlacklist blacklist);

    VolunteerBlacklist selectById(@Param("id") Long id);

    /** 增量修改（动态 set）。CAS：{@code id + deleted=0 + status=fromStatus}。 */
    int update(@Param("b") VolunteerBlacklist blacklist, @Param("fromStatus") String fromStatus);

    int softDelete(@Param("id") Long id);

    List<VolunteerBlacklist> selectList(@Param("scopeUserId") Long scopeUserId,
                                        @Param("scopeCounselorId") Long scopeCounselorId,
                                        @Param("clause") String clause,
                                        @Param("status") String status,
                                        @Param("keyword") String keyword,
                                        @Param("offset") int offset,
                                        @Param("size") int size);

    long selectCount(@Param("scopeUserId") Long scopeUserId,
                     @Param("scopeCounselorId") Long scopeCounselorId,
                     @Param("clause") String clause,
                     @Param("status") String status,
                     @Param("keyword") String keyword);

    /** 前置校验用：某学生当前生效中的黑名单（用于 `POST /blacklist` 重复列入拦截）。 */
    VolunteerBlacklist selectActiveByUser(@Param("userId") Long userId);

    /** 前置校验用：按状态计数（引用 {@code expire_at}，便于服务层把到期项标为 {@code expired}）。 */
    long countByStatus(@Param("status") String status);

    /** 前置校验用：{@code expire_at} 已过但仍是 {@code active} 的条数（服务层惰性清理依据）。 */
    long countExpiredActive(@Param("today") LocalDate today);

    /** 统计用：按状态分组计数。 */
    List<Map<String, Object>> countGroupByStatus();
}
