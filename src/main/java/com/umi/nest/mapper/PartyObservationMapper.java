package com.umi.nest.mapper;

import com.umi.nest.entity.PartyObservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 考察与思想汇报台账（学工 P16）数据访问。
 *
 * <p>审核流转 CAS：{@code WHERE id=#{id} AND deleted=0 AND review_status=#{fromStatus}}，
 * 服务层先显式前置校验（不接受 pending 之外的状态）。</p>
 */
@Mapper
public interface PartyObservationMapper {

    int insert(PartyObservation observation);

    PartyObservation findById(@Param("id") Long id);

    List<PartyObservation> list(@Param("userId") Long userId,
                                @Param("counselorId") Long counselorId,
                                @Param("type") String type,
                                @Param("reviewStatus") String reviewStatus,
                                @Param("offset") int offset,
                                @Param("limit") int limit);

    long count(@Param("userId") Long userId,
               @Param("counselorId") Long counselorId,
               @Param("type") String type,
               @Param("reviewStatus") String reviewStatus);

    /** 某学生最近一条思想汇报（时限提示用）。 */
    PartyObservation findLatest(@Param("userId") Long userId, @Param("type") String type);

    /** 审核流转（CAS on review_status）；删除/撤回也走这里（仅 pending）。 */
    int update(@Param("observation") PartyObservation observation, @Param("fromStatus") String fromStatus);

    /** 审核：pending → reviewed/returned，写 review_by / review_at / review_opinion。 */
    int review(@Param("id") Long id,
               @Param("fromStatus") String fromStatus,
               @Param("toStatus") String toStatus,
               @Param("reviewBy") Long reviewBy,
               @Param("reviewAt") LocalDateTime reviewAt,
               @Param("reviewOpinion") String reviewOpinion);

    /** 审核状态分布（统计用）。 */
    List<Map<String, Object>> countGroupByReviewStatus();

    /** 某学生思想汇报条数（统计用）。 */
    long countByUserAndType(@Param("userId") Long userId, @Param("type") String type);
}