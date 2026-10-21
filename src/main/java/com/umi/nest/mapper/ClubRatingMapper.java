package com.umi.nest.mapper;

import com.umi.nest.entity.ClubRating;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 社团星级登记（学工 P17，S25b）数据访问。
 *
 * <p>口径（§2）：{@code business_unit} 是**业务指导单位**，与 {@code club.attach_unit}
 * 「挂靠单位」**不得互换**。{@code star_level}：{@code star3}/{@code star4}/{@code star5}（§3.1）；
 * {@code basis_note}（评定依据说明）必填。一个社团一个学年只有一条有效星级。</p>
 *
 * <p>所有查询一律带 {@code deleted = 0}。</p>
 */
@Mapper
public interface ClubRatingMapper {

    int insert(ClubRating rating);

    ClubRating selectById(@Param("id") Long id);

    /** 增量修改（动态 set）。 */
    int update(@Param("r") ClubRating rating);

    int softDelete(@Param("id") Long id);

    List<ClubRating> selectList(@Param("scopeUserId") Long scopeUserId,
                                @Param("scopeCounselorId") Long scopeCounselorId,
                                @Param("clubId") Long clubId,
                                @Param("schoolYear") String schoolYear,
                                @Param("starLevel") String starLevel,
                                @Param("keyword") String keyword,
                                @Param("offset") int offset,
                                @Param("size") int size);

    long selectCount(@Param("scopeUserId") Long scopeUserId,
                     @Param("scopeCounselorId") Long scopeCounselorId,
                     @Param("clubId") Long clubId,
                     @Param("schoolYear") String schoolYear,
                     @Param("starLevel") String starLevel,
                     @Param("keyword") String keyword);

    /** 一个社团一个学年只有一条有效星级（前置校验用）。 */
    ClubRating selectByClubAndYear(@Param("clubId") Long clubId, @Param("schoolYear") String schoolYear);

    /** 前置校验用：某社团某学年的星级条数。 */
    long countByClubAndYear(@Param("clubId") Long clubId, @Param("schoolYear") String schoolYear);

    /** 前置校验用：按星级计数（{@code starLevel} 可为空表示全部）。 */
    long countByStarLevel(@Param("starLevel") String starLevel);

    /** 统计用：按星级分组计数（星级分布，§3.1 三值）。 */
    List<Map<String, Object>> countGroupByStarLevel(@Param("schoolYear") String schoolYear);
}
