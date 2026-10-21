package com.umi.nest.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 综测学期汇总：总分、班级排名、等级。
 *
 * <p>由 POST /api/eval/compute 重算（幂等 upsert），算法口径见 EvalService：
 * 维度得分 = Σ(指标得分 × 维度内权重)，总分 = Σ(维度得分 × 维度权重)；
 * 等级优秀 ≥90 / 良好 ≥80 / 合格 ≥60 / 不合格 &lt;60；rank_in_class 在同班级、同学期内按总分降序。</p>
 */
@Data
public class EvalSummary {

    private Long id;

    private Long studentId;

    private String academicTerm;

    private BigDecimal totalScore;

    private Integer rankInClass;

    /** 优秀 / 良好 / 合格 / 不合格 */
    private String gradeLevel;

    /** 各维度得分快照（JSON） */
    private String detailJson;

    private LocalDateTime computedAt;

    // ---- 非表字段（JOIN 出来的展示信息）----

    private String studentName;

    private String studentNo;

    private String className;

    private String major;

    private String grade;

    private String counselorName;
}
