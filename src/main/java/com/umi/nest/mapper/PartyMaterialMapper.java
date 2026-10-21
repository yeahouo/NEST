package com.umi.nest.mapper;

import com.umi.nest.entity.PartyMaterial;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 材料清单（学工 P16）数据访问。
 *
 * <p>口径（§11.2-8）：只登记「名称 + 应交阶段 + 已交与否 + 备注 + 附件文件名」，
 * 平台不生成官方表格 PDF。{@code required} 只是提示口径，不阻断阶段推进。</p>
 */
@Mapper
public interface PartyMaterialMapper {

    int insert(PartyMaterial material);

    PartyMaterial findById(@Param("id") Long id);

    List<PartyMaterial> list(@Param("userId") Long userId,
                             @Param("counselorId") Long counselorId,
                             @Param("stage") String stage,
                             @Param("submitted") Integer submitted,
                             @Param("offset") int offset,
                             @Param("limit") int limit);

    long count(@Param("userId") Long userId,
               @Param("counselorId") Long counselorId,
               @Param("stage") String stage,
               @Param("submitted") Integer submitted);

    /** 增量修改（不含 user_id / stage）。 */
    int update(@Param("material") PartyMaterial material);

    /** 交件/退件登记：写 submitted / submit_date / attachment_name / note。 */
    int submit(@Param("id") Long id,
               @Param("submitted") Integer submitted,
               @Param("submitDate") LocalDate submitDate,
               @Param("attachmentName") String attachmentName,
               @Param("note") String note);

    /** 按阶段统计应交/已交（统计导出用）。 */
    List<Map<String, Object>> countGroupByStage();

    /** 某学生某阶段已交材料数。 */
    long countSubmittedByUserAndStage(@Param("userId") Long userId, @Param("stage") String stage);
}
