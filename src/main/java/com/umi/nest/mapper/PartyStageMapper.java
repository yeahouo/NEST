package com.umi.nest.mapper;

import com.umi.nest.entity.PartyStage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 阶段台账（学工 P16）数据访问。
 *
 * <p>阶段推进 CAS：{@code WHERE id=#{id} AND deleted=0 AND stage=#{fromStage}}，
 * fromStage 取**显式枚举前置态**（P16 硬口径 §11.2-9）：服务层不能把刚读到的 stage 当参数，
 * 否则 WHERE 恒真、CAS 退化。阶段只向前，回退走 {@link #rollback}（需 admin + 原因 + 审计）。</p>
 */
@Mapper
public interface PartyStageMapper {

    int insert(PartyStage stage);

    PartyStage findById(@Param("id") Long id);

    /** 一个学生只允许一条有效台账（UNIQUE user_id + deleted）。 */
    PartyStage findByUserId(@Param("userId") Long userId);

    /** 辅导员范围：counselorId 非空时只返回该辅导员名下学生。 */
    List<PartyStage> list(@Param("userId") Long userId,
                          @Param("counselorId") Long counselorId,
                          @Param("stage") String stage,
                          @Param("branchName") String branchName,
                          @Param("keyword") String keyword,
                          @Param("offset") int offset,
                          @Param("limit") int limit);

    long count(@Param("userId") Long userId,
               @Param("counselorId") Long counselorId,
               @Param("stage") String stage,
               @Param("branchName") String branchName,
               @Param("keyword") String keyword);

    /** 一步向前推进：CAS on stage，同时写该步日期与依据。 */
    int advance(@Param("id") Long id,
                @Param("fromStage") String fromStage,
                @Param("toStage") String toStage,
                @Param("stepDate") LocalDate stepDate,
                @Param("stepRef") String stepRef,
                @Param("branchName") String branchName);

    /** 绑定当前阶段的公示（stage_publicity_id；CAS on stage）。 */
    int bindPublicity(@Param("id") Long id,
                      @Param("stage") String stage,
                      @Param("publicityId") Long publicityId);

    /** 支部信息/内部考察意见维护（CAS on stage；不推进阶段）。 */
    int updateInternal(@Param("id") Long id,
                       @Param("stage") String stage,
                       @Param("branchName") String branchName,
                       @Param("internalNote") String internalNote,
                       @Param("internalDiscussion") String internalDiscussion);

    /** 阶段回退（仅 admin）：CAS on stage，同时写 rollback_reason / rollback_at。 */
    int rollback(@Param("id") Long id,
                 @Param("fromStage") String fromStage,
                 @Param("toStage") String toStage,
                 @Param("reason") String reason,
                 @Param("at") LocalDateTime at);

    /** 阶段分布（统计用）。 */
    List<Map<String, Object>> countGroupByStage();

    /** 支部分布（统计用）。 */
    List<Map<String, Object>> countGroupByBranch();
}
