package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 第二课堂申报记录（学工 P18，S26）：second_class_record 表。
 *
 * <p><b>流程如实为两级，不假装四级</b>：S26 原文的四级是「学生申报 → 团支部/院系审查 → 主办方审核 → 校团委认定」，
 * 本系统把「团支部/院系审查」合成为<b>辅导员初审</b>（counselor_approved），把「主办方审核 + 校团委认定」合成为
 * <b>管理员认定</b>（confirmed）。文档与页面均按两级描述，不声明实现了四级。</p>
 *
 * <p>状态机（全部 CAS，行数 0 → 400「状态已变化，请刷新后重试」）：
 * {@code submitted} → {@code counselor_approved}（辅导员通过）/ {@code returned}（退回，必填 return_reason）/
 * {@code withdrawn}（学生撤回）；{@code returned} → {@code submitted}（学生改后重交）；
 * {@code counselor_approved} → {@code confirmed}（管理员认定）/ {@code rejected}（管理员否定，必填 return_reason）。
 * 终态：{@code confirmed}、{@code rejected}、{@code withdrawn}。</p>
 *
 * <p>{@code titleSnapshot} 是提交时的标题快照：活动改名/删除后台账仍可读（与 P17 的评审表同思路）。</p>
 */
@Data
public class SecondClassRecord {

    private Long id;

    /** 关联活动；NULL = 学生自报（不挂活动） */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Long activityId;

    /** 申报板块 second_class_category.id */
    private Long categoryId;

    /** 申报学生 users.id */
    private Long userId;

    /** 提交时的标题快照（活动名或自报标题） */
    private String titleSnapshot;

    /** 申报学时 */
    private BigDecimal hours;

    /** 申报学分；null = 不计学分 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private BigDecimal credit;

    /** 证明材料链接（可空；本系统不收附件二进制，只存链接/说明） */
    private String evidenceUrl;

    private String note;

    /** submitted / counselor_approved / confirmed / returned / rejected / withdrawn */
    private String status;

    /** 退回/否定原因（returned、rejected 必填） */
    private String returnReason;

    private LocalDateTime submittedAt;

    /** 初审辅导员 users.id（走 P13 scopeCounselor 口径） */
    private Long counselorId;

    private LocalDateTime counselorAt;

    /** 认定管理员 users.id */
    private Long reviewerId;

    private LocalDateTime reviewedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // ── 展示用（非表字段） ──

    private String studentName;
    private String studentNo;
    private String className;
    private String categoryName;
    private String counselorName;
    private String reviewerName;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private Integer deleted;
}
