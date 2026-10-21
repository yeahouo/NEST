package com.umi.nest.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 综合素质表现得分 C 的加分/扣分申报（学工 P13）：eval_bonus 表。
 *
 * <p>状态机（全部 CAS，见 EvalBonusService）：draft → submitted → counselor_approved / rejected；
 * 学生撤回 submitted → draft；被驳回可在申报期改后重提（回到 submitted）。</p>
 *
 * <p><b>只有 counselor_approved 计入 C</b>；每个大类累计封顶（见 EvalBonusService 的 @Value 配置），
 * 分类封顶求和后再按办法第十二条把 C 总封顶到 8 分。</p>
 */
@Data
public class EvalBonus {

    private Long id;

    /** users.id（学生） */
    private Long studentId;

    /** 学期，如 2025-2026-1 */
    private String academicTerm;

    /** 加分大类：academic_competition / nonacademic_competition / research / quality / media / service / penalty */
    private String category;

    /** 项目名称 */
    private String itemName;

    /** 申报分值，可负（惩处扣分 penalty） */
    private BigDecimal points;

    /** 佐证材料说明 */
    private String evidence;

    /** draft / submitted / counselor_approved / rejected */
    private String status;

    /** 驳回原因（辅导员审核意见） */
    private String rejectReason;

    /** 审核人 users.id */
    private Long reviewerId;

    private LocalDateTime reviewedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN 得来） ----

    private String studentName;

    private String studentNo;

    private String className;

    private String major;

    private String grade;

    /** 审核人姓名 */
    private String reviewerName;

    /** 非表字段：大类中文名（服务端补，页面直接渲染） */
    private String categoryText;

    /** 非表字段：状态中文名（服务端补，页面直接渲染） */
    private String statusText;
}
