package com.umi.nest.mapper;

import com.umi.nest.entity.RankBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 综测排名批次（学工 P13）数据访问。
 *
 * <p>所有状态流转都是 CAS（WHERE id=? AND deleted=0 AND status=fromStatus），
 * 影响行数 ≠ 1 即「批次状态已变化，请刷新后重试」，由服务层统一转 400。</p>
 */
@Mapper
public interface RankBatchMapper {

    int insert(RankBatch batch);

    RankBatch findById(@Param("id") Long id);

    /** 批次列表：hideDraft=true 时隐藏草稿（非 admin 看不到尚未发布的批次）。 */
    List<RankBatch> list(@Param("hideDraft") boolean hideDraft,
                         @Param("status") String status,
                         @Param("keyword") String keyword);

    /** 增量修改：只覆盖服务层已合并好的字段（CAS，status 由服务层保证没被改过）。 */
    int update(@Param("batch") RankBatch batch, @Param("fromStatus") String fromStatus);

    int updateStatus(@Param("id") Long id,
                     @Param("fromStatus") String fromStatus,
                     @Param("toStatus") String toStatus);

    /** draft/computed → publicity：同时写公示起止时间与天数。 */
    int startPublicity(@Param("id") Long id,
                       @Param("fromStatus") String fromStatus,
                       @Param("publicityDays") Integer publicityDays,
                       @Param("publicityStart") LocalDateTime publicityStart,
                       @Param("publicityEnd") LocalDateTime publicityEnd);

    /** draft/computed → computed：写 computed_at（重算也走这条）。 */
    int markComputed(@Param("id") Long id,
                     @Param("fromStatus") String fromStatus,
                     @Param("computedAt") LocalDateTime computedAt);

    /** 该学期最新的批次（学生端 /api/rank/my 不传学期时用来定位当前批次）。 */
    RankBatch findLatestByTerm(@Param("academicTerm") String academicTerm);
}
