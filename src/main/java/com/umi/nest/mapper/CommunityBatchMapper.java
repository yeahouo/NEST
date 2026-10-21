package com.umi.nest.mapper;

import com.umi.nest.entity.CommunityBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 社区检查批次（学工 P15）数据访问。
 *
 * <p>所有状态流转都是 CAS（WHERE id=? AND deleted=0 AND status=fromStatus），影响行数 ≠ 1
 * 即「状态已变化，请刷新后重试」，由服务层统一转 400。服务层必须先做前置校验，
 * 不能把「刚读到的状态」当成放行理由（否则 WHERE 恒真）。</p>
 */
@Mapper
public interface CommunityBatchMapper {

    int insert(CommunityBatch batch);

    CommunityBatch findById(@Param("id") Long id);

    List<CommunityBatch> list(@Param("term") String term,
                              @Param("checkType") String checkType,
                              @Param("status") String status,
                              @Param("keyword") String keyword,
                              @Param("hideDraft") boolean hideDraft);

    /** 某学期指定状态的批次（文明宿舍汇总用：published / finished）。 */
    List<CommunityBatch> listByTermAndStatuses(@Param("term") String term,
                                               @Param("statuses") List<String> statuses);

    /** 增量修改：只覆盖服务层已合并好的字段（CAS，status 必须还是 fromStatus）。 */
    int update(@Param("batch") CommunityBatch batch, @Param("fromStatus") String fromStatus);

    /** draft/checking/scored → published：同时写 publish_date。 */
    int publish(@Param("id") Long id,
                @Param("fromStatus") String fromStatus,
                @Param("publishDate") LocalDateTime publishDate);

    /** published → finished（移入历史）。 */
    int finish(@Param("id") Long id, @Param("fromStatus") String fromStatus);

    /** 批次状态分布（统计用）。 */
    List<Map<String, Object>> countGroupByStatus(@Param("term") String term);

    /** 批次总数 / 已公布数（统计用）。 */
    Map<String, Object> summary(@Param("term") String term);
}
