package com.umi.nest.mapper;

import com.umi.nest.entity.CommunityActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 社区活动（学工 P15）数据访问。
 *
 * <p>状态列为 status（draft/signup/ongoing/finished/cancelled），CAS 写成
 * WHERE id=? AND deleted=0 AND status=#{fromStatus}。</p>
 */
@Mapper
public interface CommunityActivityMapper {

    int insert(CommunityActivity activity);

    CommunityActivity findById(@Param("id") Long id);

    List<CommunityActivity> list(@Param("term") String term,
                                 @Param("activityType") String activityType,
                                 @Param("level") String level,
                                 @Param("status") String status,
                                 @Param("keyword") String keyword,
                                 @Param("hideDraft") boolean hideDraft);

    /** 增量修改（CAS 在 status 上）。 */
    int update(@Param("activity") CommunityActivity activity, @Param("fromStatus") String fromStatus);

    /** 状态推进（报名 → 进行 → 结束 / 取消）。 */
    int updateStatus(@Param("id") Long id,
                     @Param("fromStatus") String fromStatus,
                     @Param("toStatus") String toStatus);

    List<Map<String, Object>> countGroupByStatus(@Param("term") String term);

    List<Map<String, Object>> countGroupByType(@Param("term") String term);

    Map<String, Object> summary(@Param("term") String term);
}
