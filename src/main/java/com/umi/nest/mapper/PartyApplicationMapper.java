package com.umi.nest.mapper;

import com.umi.nest.entity.PartyApplication;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 入党申请登记（学工 P16）数据访问。
 *
 * <p>状态流转是 CAS（WHERE id=? AND deleted=0 AND status=fromStatus），影响行数 ≠ 1 即
 * 「状态已变化，请刷新后重试」，由服务层统一转 400。服务层必须先做前置校验，
 * 不能把「刚读到的状态」当成放行理由（否则 WHERE 恒真）。</p>
 */
@Mapper
public interface PartyApplicationMapper {

    int insert(PartyApplication application);

    PartyApplication findById(@Param("id") Long id);

    /** 一个学生只允许一条有效申请（UNIQUE user_id + deleted）。 */
    PartyApplication findByUserId(@Param("userId") Long userId);

    /** 辅导员范围：counselorId 非空时只返回该辅导员名下学生（users.role='student'）。 */
    List<PartyApplication> list(@Param("userId") Long userId,
                                @Param("counselorId") Long counselorId,
                                @Param("status") String status,
                                @Param("keyword") String keyword,
                                @Param("offset") int offset,
                                @Param("limit") int limit);

    long count(@Param("userId") Long userId,
               @Param("counselorId") Long counselorId,
               @Param("status") String status,
               @Param("keyword") String keyword);

    /** 增量修改（CAS）。user_id 不允许改。 */
    int update(@Param("application") PartyApplication application, @Param("fromStatus") String fromStatus);

    /** active → withdrawn（撤回申请）。 */
    int withdraw(@Param("id") Long id, @Param("fromStatus") String fromStatus);

    /** 谈话登记：只允许 active 且尚未谈话（talk_date IS NULL）→ 写 talk_date/talk_by/talk_summary。 */
    int recordTalk(@Param("id") Long id,
                   @Param("talkDate") java.time.LocalDate talkDate,
                   @Param("talkBy") String talkBy,
                   @Param("talkSummary") String talkSummary);

    /** 申请状态分布（统计用）。 */
    List<Map<String, Object>> countGroupByStatus();
}
