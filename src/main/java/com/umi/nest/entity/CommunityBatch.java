package com.umi.nest.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 社区检查批次（学工 P15）：community_batch 表。
 *
 * <p>状态机（全部 CAS，见 CommunityService）：
 * draft 草稿 → checking 检查中 → scored 已评分 → published 已公布 → finished 已结束。</p>
 *
 * <p>口径（对齐说明 §10.2-1 / §10.2-2）：评分项 criteriaJson 由学校录入，本平台不建评分表；
 * 演示评分项（用电安全/消防通道/内务卫生/财物与家具/文明行为，各 20 分）必须标注
 * 「演示评分项，非学校发布口径」。</p>
 */
@Data
public class CommunityBatch {

    private Long id;

    private String batchName;

    /** 学期，如 2025-2026-1 */
    private String term;

    /** hygiene 卫生检查 / safety 安全检查 / joint 联合检查 */
    private String checkType;

    /** 适用范围楼栋（student_profile.dorm_building 口径）；空 = 全部楼栋 */
    private String scopeBuilding;

    private LocalDate startDate;

    private LocalDate endDate;

    /** 批次发布时间（draft 之后首次进入检查中时写入） */
    private LocalDateTime publishDate;

    /** 评分项 JSON：[{"key":"power","name":"用电安全","max":20,"ruleRef":"园区管理规定第十条"}] */
    private String criteriaJson;

    /** draft / checking / scored / published / finished */
    private String status;

    private String note;

    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN 得来） ----

    private String createdByName;

    /** 批次内检查记录条数（列表/统计用） */
    private Integer inspectionCount;

    /** 批次内检查平均分（统计用） */
    private BigDecimal avgScore;
}
