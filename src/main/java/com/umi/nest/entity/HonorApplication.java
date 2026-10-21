package com.umi.nest.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 评优申报单（学工 P12）：honor_application 表。
 *
 * <p>一名学生在一个批次里只有一张（唯一键 uk_ha_batch_student）。</p>
 *
 * <p>状态机（全部 CAS，见 HonorService）：
 * 提交即 submitted，辅导员通过 → counselor_approved，学院通过 → college_approved，
 * 学工部复核通过 → dept_approved，批次进入公示 → publicized，批次结束 → awarded；
 * 任一节点 reject ⇒ rejected（写 reject_reason，学生端可见并可在申报期内改后重提）。</p>
 *
 * <p>表里没有 draft 行：撤回（withdraw）把 submitted 退回 draft，
 * 但「提交」这个动作本身就是落 submitted —— 草稿只存在于 UI，见 docs\学工平台功能对齐说明.md §7.2。</p>
 */
@Data
public class HonorApplication {

    private Long id;

    /** honor_batch.id */
    private Long batchId;

    /** users.id（学生） */
    private Long studentId;

    /** 冗余批次类型，便于统计与导出（提交时从批次固化） */
    private String honorType;

    /** 冗余学年，便于统计与导出（提交时从批次固化） */
    private String academicYear;

    /** 个人自述（主要表现，服务端要求去空白后 ≥30 字） */
    private String selfStatement;

    /** 荣誉称号清单（自报，人工核验用；校内奖项写说明即可，无需附证明） */
    private String honorsText;

    /** 奖学金清单（自报） */
    private String scholarshipsText;

    /** 体测成绩（自报，人工核验 —— 库里没有《国家学生体质健康标准》测试分表） */
    private BigDecimal peScore;

    /** 自报无不及格科目（教学成绩数据已随 P8 下线，只能自报 + 人工核验） */
    private Boolean noFailDeclared;

    /** 自报无违法违纪 */
    private Boolean noViolationDeclared;

    /** 材料说明/清单（校外奖项证明等，线下交件，不假装上传） */
    private String materialNote;

    private String contactPhone;

    /** draft / submitted / counselor_approved / college_approved / dept_approved / publicized / awarded / rejected */
    private String status;

    /** 驳回原因（= 驳回节点的审核意见，学生端可见） */
    private String rejectReason;

    /** 提交时固化该生辅导员 users.id（审核范围判定用；本人在系统里调岗也不影响历史工单） */
    private Long counselorId;

    private LocalDateTime submittedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN 得来） ----

    private String studentName;

    /** 学号（student_profile.student_no，回退 users.student_id） */
    private String studentNo;

    private String className;

    private String major;

    private String grade;

    /** 该生辅导员姓名（student_profile.counselor_id → users） */
    private String counselorName;

    /** 批次名（honor_batch.batch_name） */
    private String batchName;

    /** 批次状态（honor_batch.status）——学生端据此判断还能不能改，不用再发一次请求 */
    private String batchStatus;
}
