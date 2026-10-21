package com.umi.nest.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 文明宿舍候选与结果（学工 P15）：community_civil_room 表。
 *
 * <p>口径（对齐说明 §10.2-6）：按学期汇总**已发布批次**（published/finished）的检查均分，
 * 按**楼栋内**排名生成候选；awarded 由管理员人工确认，名额不自动截断；
 * published_at 非空才对外公示，公示只露楼栋/房号/均分/等级。</p>
 */
@Data
public class CommunityCivilRoom {

    private Long id;

    private String term;

    /** 参与汇总的批次 id（JSON 数组，形如 [1,2]） */
    private String batchIds;

    private String building;

    private String roomNo;

    /** 参与汇总的检查记录条数 */
    private Integer memberCount;

    private BigDecimal avgScore;

    private BigDecimal minScore;

    /** 楼栋内名次（均分降序，同分并列） */
    private Integer rankInBuilding;

    /** 是否评为文明宿舍：0 候选 / 1 已评（管理员人工确认） */
    private Integer awarded;

    /** school 校级 / college 院级 */
    private String awardLevel;

    /** 公示发布时间；null = 未公示 */
    private LocalDateTime publishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;
}
