package com.umi.nest.mapper;

import com.umi.nest.entity.PartyTraining;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 党校培训班次（学工 P16）数据访问。
 *
 * <p>班次状态流转 CAS：{@code WHERE id=#{id} AND deleted=0 AND status=#{fromStatus}}。</p>
 */
@Mapper
public interface PartyTrainingMapper {

    int insert(PartyTraining training);

    PartyTraining findById(@Param("id") Long id);

    /** 学生端只看 signup / ongoing / finished（hideDraft=true 时排除 draft 与 cancelled）。 */
    List<PartyTraining> list(@Param("status") String status,
                             @Param("term") String term,
                             @Param("keyword") String keyword,
                             @Param("hideDraft") boolean hideDraft,
                             @Param("offset") int offset,
                             @Param("limit") int limit);

    long count(@Param("status") String status,
               @Param("term") String term,
               @Param("keyword") String keyword,
               @Param("hideDraft") boolean hideDraft);

    /** 增量修改（CAS on status）。 */
    int update(@Param("training") PartyTraining training, @Param("fromStatus") String fromStatus);

    /** 状态流转（CAS on status）。 */
    int changeStatus(@Param("id") Long id,
                     @Param("fromStatus") String fromStatus,
                     @Param("toStatus") String toStatus);

    /** 名额占用数（报名中/通过/已结业的学员数，用于名额上限校验提示）。 */
    long countMembers(@Param("trainingId") Long trainingId);

    /** 已结业学员数（completed；班次列表/详情的 completedCount 非表字段）。 */
    long countCompleted(@Param("trainingId") Long trainingId);

    /** 班次状态分布（统计用）。 */
    List<Map<String, Object>> countGroupByStatus();
}
