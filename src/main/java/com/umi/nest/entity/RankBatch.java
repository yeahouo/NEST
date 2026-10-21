package com.umi.nest.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 综测排名批次（学工 P13）：rank_batch 表。
 *
 * <p>状态机（全部 CAS，见 RankService）：draft → computed → publicity → finished。
 * 比例（qualify_pct / tier*_pct）与 μ 边界（mu_min / mu_max）按批次存，
 * 这样历史批次能复现当时的算法口径。</p>
 */
@Data
public class RankBatch {

    private Long id;

    private String batchName;

    /** 综测学期，如 2025-2026-1 */
    private String academicTerm;

    /** 是否毕业学年第二学期：1 = 只按往年学期 A 排名，C 不计（B=0、M=A） */
    private Integer graduationLast;

    /** μ 下界，默认 0.5 */
    private BigDecimal muMin;

    /** μ 上界，默认 0.7 */
    private BigDecimal muMax;

    /** 具备获奖资格的专业排名百分比，默认 30 */
    private Integer qualifyPct;

    /** 一等奖名额占年级专业人数比例（%），默认 5 */
    private Integer tier1Pct;

    /** 二等奖名额比例（%），默认 10 */
    private Integer tier2Pct;

    /** 三等奖名额比例（%），默认 15 */
    private Integer tier3Pct;

    /** 公示天数（工作日），默认 5 */
    private Integer publicityDays;

    /** draft / computed / publicity / finished */
    private String status;

    private String noticeTitle;

    private String noticeUrl;

    private LocalDateTime publicityStart;

    private LocalDateTime publicityEnd;

    private LocalDateTime computedAt;

    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN 得来） ----

    private String createdByName;
}
