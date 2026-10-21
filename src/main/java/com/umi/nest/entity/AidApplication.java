package com.umi.nest.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资助申请（学工 P5）：aid_application 表。
 *
 * <p>学生发起（困难认定 / 助学金 / 临时困难补助 / 勤工助学），辅导员或管理员审批；
 * 审批状态机：pending --approve--> approved / pending --reject--> rejected / pending --cancel--> cancelled。</p>
 */
@Data
public class AidApplication {

    private Long id;

    /** users.id（学生） */
    private Long studentId;

    /** need 困难认定 / grant 助学金 / temporary 临时困难补助 / workstudy 勤工助学 */
    private String aidType;

    /** general 一般困难 / difficult 困难 / special 特别困难 */
    private String level;

    /** 申请或批准的金额 */
    private BigDecimal amount;

    /** 申请理由 */
    private String reason;

    /** 证明材料路径/说明 */
    private String evidence;

    /** pending / approved / rejected / cancelled */
    private String status;

    private Long approverId;

    private String approveNote;

    /** 学年，如 2026-2027 */
    private String academicYear;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ================= P11 家庭经济困难学生认定（aid_type = need / 困难认定 的行才用） =================
    // 说明：以下字段全部是 aid_application 的**新增列**（见 sql\nest_aid_review.sql），
    // 既有 (P5) 字段名与类型一律未动，P5 的助学金/临时补助/勤工助学流程读不到这些值（为 NULL）。

    /** 认定级别：只有两档 —— 特困 / 贫困（P5 的 level 列是 general/difficult/special，两者并存互不覆盖） */
    private String povertyLevel;

    /** 认定流程阶段：apply / committee / workgroup / publicity / objection / report / center / leader / approved / rejected */
    private String reviewStage;

    /** 认定学年，形如 2025-2026 */
    private String certYear;

    /** 公示开始时间 */
    private LocalDateTime publicityStart;

    /** 公示结束时间（= 开始 + 5 个工作日） */
    private LocalDateTime publicityEnd;

    /** 复议截止时间（= 公示结束时间） */
    private LocalDateTime objectionDeadline;

    /** 民主评议意见 */
    private String committeeNote;

    /** 院系书面评审报告 */
    private String reportNote;

    /** 复查结论 */
    private String recheckResult;

    /** 最近一次复查时间 */
    private LocalDateTime recheckedAt;

    /** 4 项证明材料（JSON 数组文本，申请时提交；evidence 列只有 varchar(255)，放不下） */
    private String materials;

    /** 家庭情况说明（与 reason 语义不同，单独存，避免污染 P5 收件箱摘要） */
    private String familyNote;

    // ---- 列表展示用的非表字段（JOIN 得来） ----
    private String studentName;
    private String studentNo;
    private String className;
    private String approverName;

    /** 性别（非表字段，来自 student_profile.gender；仅供公示接口脱敏展示） */
    private String gender;
}
