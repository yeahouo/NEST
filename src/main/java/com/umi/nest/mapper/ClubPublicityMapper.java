package com.umi.nest.mapper;

import com.umi.nest.entity.ClubPublicity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 社团公示（学工 P17，S25a / S25b）数据访问。
 *
 * <p>{@code publicity_type}：{@code club_review} / {@code club_rating}（§2）；
 * {@code status}：{@code open} 公示中 / {@code closed} 已结束（§3.1）。
 * {@code closePublicity} 是 CAS（§7-1）：只允许 {@code open → closed}，
 * **{@code closed} 后不得再关闭**（§7-2，重复关闭 400）。</p>
 *
 * <p>可见性（§6）：{@code open} 公示全站可见，故本表不按学生/辅导员做数据范围过滤；
 * 导出时由服务层剔除学号与联系方式。</p>
 */
@Mapper
public interface ClubPublicityMapper {

    int insert(ClubPublicity publicity);

    ClubPublicity selectById(@Param("id") Long id);

    /** 增量修改（动态 set）。CAS：{@code id + deleted=0 + status=fromStatus}。 */
    int update(@Param("p") ClubPublicity publicity, @Param("fromStatus") String fromStatus);

    int softDelete(@Param("id") Long id);

    List<ClubPublicity> selectList(@Param("scopeUserId") Long scopeUserId,
                                   @Param("scopeCounselorId") Long scopeCounselorId,
                                   @Param("publicityType") String publicityType,
                                   @Param("status") String status,
                                   @Param("keyword") String keyword,
                                   @Param("offset") int offset,
                                   @Param("size") int size);

    long selectCount(@Param("scopeUserId") Long scopeUserId,
                     @Param("scopeCounselorId") Long scopeCounselorId,
                     @Param("publicityType") String publicityType,
                     @Param("status") String status,
                     @Param("keyword") String keyword);

    /** 关闭公示 CAS：{@code open → closed}（§7-1、§7-2：重复关闭影响行数 0 → 400）。 */
    int closePublicity(@Param("id") Long id, @Param("fromStatus") String fromStatus);

    /** 前置校验用：按类型+状态计数（{@code publicityType}/{@code status} 均可空表示不限）。 */
    long countByTypeAndStatus(@Param("publicityType") String publicityType,
                             @Param("status") String status);

    /** 统计用：按类型分组计数（年审公示 / 星级公示）。 */
    List<Map<String, Object>> countGroupByType();
}
