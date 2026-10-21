package com.umi.nest.mapper;

import com.umi.nest.entity.ClubReview;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 社团年审（学工 P17，S25a）数据访问。
 *
 * <p>结果三值（§3.1）：{@code qualified} 合格 / {@code unqualified} 不合格 / {@code deregistered} 注销。
 * {@code recordResult} 是 CAS（§7-1），**结果登记后不得重设**（§7-2）——
 * 前置态取 {@code result IS NULL}，由 XML 里显式写成 {@code result IS NULL} 条件保证（不是刚读到的值）。
 * 一个社团一个学年只有一条年审。</p>
 *
 * <p>所有查询一律带 {@code deleted = 0}。</p>
 */
@Mapper
public interface ClubReviewMapper {

    int insert(ClubReview review);

    ClubReview selectById(@Param("id") Long id);

    /** 增量修改（动态 set）。 */
    int update(@Param("r") ClubReview review);

    int softDelete(@Param("id") Long id);

    List<ClubReview> selectList(@Param("scopeUserId") Long scopeUserId,
                                @Param("scopeCounselorId") Long scopeCounselorId,
                                @Param("clubId") Long clubId,
                                @Param("schoolYear") String schoolYear,
                                @Param("result") String result,
                                @Param("keyword") String keyword,
                                @Param("offset") int offset,
                                @Param("size") int size);

    long selectCount(@Param("scopeUserId") Long scopeUserId,
                     @Param("scopeCounselorId") Long scopeCounselorId,
                     @Param("clubId") Long clubId,
                     @Param("schoolYear") String schoolYear,
                     @Param("result") String result,
                     @Param("keyword") String keyword);

    /** 一个社团一个学年只有一条年审（前置校验用）。 */
    ClubReview selectByClubAndYear(@Param("clubId") Long clubId, @Param("schoolYear") String schoolYear);

    /** 提交 CAS：{@code result IS NULL}（未登记结果）→ 写 {@code submit_time}。 */
    int submitReview(@Param("id") Long id, @Param("fromResult") String fromResult);

    /** 挂靠单位意见写入（CAS：{@code result IS NULL}，结果已登记后不可再改意见）。 */
    int writeOpinion(@Param("id") Long id, @Param("attachOpinion") String attachOpinion);

    /**
     * 结果登记 CAS（§7-1、§7-2）：只允许**尚未登记结果**（{@code result IS NULL}）→ 写结果与审核人。
     * 终态不可重设；重复登记影响行数 0 → 400。
     */
    int recordResult(@Param("id") Long id,
                     @Param("toResult") String toResult,
                     @Param("reviewUserId") Long reviewUserId);

    /** 关联公示（CAS：{@code result IS NULL} 才允许挂公示，结果已公示不得重挂）。 */
    int linkPublicity(@Param("id") Long id, @Param("publicityId") Long publicityId);

    /** 前置校验用：某社团某学年的年审条数。 */
    long countByClubAndYear(@Param("clubId") Long clubId, @Param("schoolYear") String schoolYear);

    /** 前置校验用：按结果计数（{@code result} 可为空表示全部；NULL 结果不计入非空查询）。 */
    long countByResult(@Param("result") String result);

    /** 统计用：按结果分组计数（年审结果分布，含未登记）。 */
    List<Map<String, Object>> countGroupByResult(@Param("schoolYear") String schoolYear);
}
