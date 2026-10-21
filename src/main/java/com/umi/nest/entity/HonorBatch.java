package com.umi.nest.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 评奖评优批次（学工 P12）：honor_batch 表。
 *
 * <p>一次评选 = 一个批次。批次承载「通知里那两次时限」（申报截止、学院审核截止）与公示窗口，
 * 所以学生能不能提交不是看它自己的状态，而是看<b>批次</b>状态：批次不在 recruiting 时，
 * 所有学生侧写操作一律 400（sql\nest_honor.sql 的列注释与 docs 的功能对齐说明 §7.2 同口径）。</p>
 *
 * <p>状态机（admin 显式推进，全部 CAS）：
 * draft --publish--> recruiting --publish 截止/超期--> reviewing --publicize--> publicity --finish--> finished。</p>
 */
@Data
public class HonorBatch {

    private Long id;

    /** 批次名，如「2026届优秀毕业生评选」 */
    private String batchName;

    /** 荣誉类型：graduate_outstanding / merit_outstanding / merit_student / cadres_outstanding */
    private String honorType;

    /** 学年，如 2025-2026 */
    private String academicYear;

    /**
     * 自动核验综测排名时用的学期，如 2025-2026-1。
     * <p>为空则「综测排名前 15%」这一项无法自动核验（只能人工），此时不假装通过也不假装不通过，
     * 一律返回 passed=null + auto=false。</p>
     */
    private String evalTerm;

    /** 名额（空 = 按当年文件比例，不作断言） */
    private Integer quota;

    /** 名额说明（比例口径等） */
    private String quotaNote;

    /** 参评条件摘要（通知原文要点，管理员录入） */
    private String criteria;

    /** 需交材料说明（线下交件，平台不假装上传） */
    private String materials;

    /** 申报开始日（为空则该端不校验） */
    private LocalDate applyStart;

    /** 申报截止日（含当天；通知写「5/13 24:00」时记 5/14） */
    private LocalDate applyEnd;

    /** 学院审核截止日（含当天，为空则不校验） */
    private LocalDate reviewEnd;

    /** 公示天数（工作日口径由 service 折算；空则用配置 nest.honor.publicity-days） */
    private Integer publicityDays;

    private LocalDateTime publicityStart;

    private LocalDateTime publicityEnd;

    /** draft / recruiting / reviewing / publicity / finished */
    private String status;

    private String noticeTitle;

    private String noticeUrl;

    /** 创建人 users.id */
    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN users 得来） ----

    /** 创建人姓名（非表字段，来自 users.real_name/nickname/username） */
    private String createdByName;
}
