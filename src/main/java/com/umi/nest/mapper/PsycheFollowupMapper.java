package com.umi.nest.mapper;

import com.umi.nest.entity.PsycheFollowup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 学工 P14 心理健康 · 关注台账跟进流水数据访问（psyche_followup）。
 */
@Mapper
public interface PsycheFollowupMapper {

    int insert(PsycheFollowup followup);

    List<PsycheFollowup> listByFocus(@Param("focusId") Long focusId);
}
