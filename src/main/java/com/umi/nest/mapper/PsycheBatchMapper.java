package com.umi.nest.mapper;

import com.umi.nest.entity.PsycheBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 学工 P14 心理健康 · 普查批次数据访问（psyche_batch）。
 *
 * <p>状态流转一律 CAS：WHERE id=? AND deleted=0 AND status=fromStatus，影响行数 ≠ 1 由服务层转
 * 400「状态已变化，请刷新后重试」。</p>
 */
@Mapper
public interface PsycheBatchMapper {

    int insert(PsycheBatch batch);

    PsycheBatch findById(@Param("id") Long id);

    /** 批次列表：hideDraft=true 时隐藏草稿（非 admin 看不到尚未发布的批次）。 */
    List<PsycheBatch> list(@Param("hideDraft") boolean hideDraft,
                           @Param("status") String status,
                           @Param("keyword") String keyword);

    /** 增量修改：服务层已把没传的字段填成现值，这里整行覆盖 + CAS（status 必须还是 fromStatus）。 */
    int update(@Param("batch") PsycheBatch batch, @Param("fromStatus") String fromStatus);

    /** draft → open：同时写 published_at。 */
    int publish(@Param("id") Long id,
                @Param("fromStatus") String fromStatus,
                @Param("publishedAt") LocalDateTime publishedAt);

    /** open → closed：同时写 closed_at（未作答名单由服务层置 absent）。 */
    int close(@Param("id") Long id,
              @Param("fromStatus") String fromStatus,
              @Param("closedAt") LocalDateTime closedAt);
}
