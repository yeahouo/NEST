package com.umi.nest.mapper;

import com.umi.nest.entity.AwardPunishment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 奖励与处分（学工 P5）数据访问。
 */
@Mapper
public interface AwardPunishmentMapper {

    int insert(AwardPunishment record);

    AwardPunishment findById(@Param("id") Long id);

    /**
     * 列表：studentId 非空看某个学生；scopeCounselorId 非空看该辅导员名下学生；两者都空看全校。
     */
    List<AwardPunishment> list(@Param("studentId") Long studentId,
                               @Param("kind") String kind,
                               @Param("level") String level,
                               @Param("keyword") String keyword,
                               @Param("scopeCounselorId") Long scopeCounselorId,
                               @Param("offset") int offset,
                               @Param("limit") int limit);

    int count(@Param("studentId") Long studentId,
              @Param("kind") String kind,
              @Param("level") String level,
              @Param("keyword") String keyword,
              @Param("scopeCounselorId") Long scopeCounselorId);

    int update(AwardPunishment record);

    int softDelete(@Param("id") Long id);

    List<Map<String, Object>> countGroupByKind(@Param("studentId") Long studentId,
                                               @Param("scopeCounselorId") Long scopeCounselorId);
}
