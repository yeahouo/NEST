package com.umi.nest.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 综测逐项得分。
 *
 * <p>唯一键 (student_id, indicator_id, academic_term, source)：同一学生、同一指标、同一学期、
 * 同一评分来源只有一条，重复提交走 upsert。</p>
 *
 * <p>source：self 自评 / class 班评 / counselor 辅导员 / system 系统。
 * 汇总时同一指标按 counselor &gt; class &gt; self 取优先级最高的一条。</p>
 */
@Data
public class EvalScore {

    private Long id;

    private Long studentId;

    private Long indicatorId;

    /** 学期，如 2026-2027-1 */
    private String academicTerm;

    private BigDecimal score;

    private String source;

    private String evidence;

    private Long scorerId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // ---- 非表字段（JOIN 出来的展示信息）----

    private String studentName;

    private String studentNo;

    private String className;

    private String indicatorCode;

    private String indicatorName;

    private String dimension;

    private BigDecimal maxScore;

    private BigDecimal weight;

    private String scorerName;
}
