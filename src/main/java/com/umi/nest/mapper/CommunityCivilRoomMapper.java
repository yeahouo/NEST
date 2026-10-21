package com.umi.nest.mapper;

import com.umi.nest.entity.CommunityCivilRoom;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 文明宿舍候选与结果（学工 P15）数据访问。
 *
 * <p>本表没有 status 列：候选由 compute 生成，公示与否看 published_at 是否为 null，
 * 因此公示的 CAS 谓词是 WHERE id=? AND deleted=0 AND published_at IS NULL。</p>
 */
@Mapper
public interface CommunityCivilRoomMapper {

    int insert(CommunityCivilRoom room);

    /** 已公示结果的分数刷新：只更新汇总字段，不动 awarded / award_level / published_at。 */
    int refresh(CommunityCivilRoom room);

    CommunityCivilRoom findById(@Param("id") Long id);

    CommunityCivilRoom findByTermRoom(@Param("term") String term,
                                      @Param("building") String building,
                                      @Param("roomNo") String roomNo);

    List<CommunityCivilRoom> list(@Param("term") String term,
                                  @Param("building") String building,
                                  @Param("awarded") Integer awarded,
                                  @Param("publishedOnly") boolean publishedOnly);

    /** 清掉该学期尚未公示的候选（已公示的结果保留）。 */
    int deleteDraftByTerm(@Param("term") String term);

    /** 公示 / 人工确认获奖：published_at IS NULL 才写（CAS）。 */
    int publish(@Param("id") Long id,
                @Param("awarded") Integer awarded,
                @Param("awardLevel") String awardLevel,
                @Param("publishedAt") LocalDateTime publishedAt);

    /** 按学期汇总已发布批次的检查均分（楼栋 + 房号维度），名次在服务层按楼栋内排序算。 */
    List<Map<String, Object>> aggregate(@Param("term") String term, @Param("statuses") List<String> statuses);

    Map<String, Object> summary(@Param("term") String term);
}
