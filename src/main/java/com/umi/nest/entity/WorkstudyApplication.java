package com.umi.nest.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 勤工助学申请（学工 P10）：workstudy_application 表。
 *
 * <p>状态机（唯一权威在 WorkstudyService）：
 * pending --面试通过--> interview_passed --录用--> hired
 *         ↘ 面试未通过 --> interview_failed          ↘ 未录用 --> rejected
 * hired --学生辞职--> resigning --单位确认--> resigned</p>
 *
 * <p>硬校验（办法第二十一条）：同一学生同一学期原则上不能同时在两个及以上固定岗位在岗。</p>
 */
@Data
public class WorkstudyApplication {

    private Long id;

    /** workstudy_post.id */
    private Long postId;

    /** users.id（学生） */
    private Long studentId;

    /** 学期快照，如 2025-2026-1（来自岗位，用于「同学期固定岗 ≤ 1」判定） */
    private String academicTerm;

    /** 申请理由 */
    private String reason;

    /** 证明材料路径/说明 */
    private String evidence;

    /** pending / interview_passed / interview_failed / hired / rejected / resigning / resigned */
    private String status;

    /** pass / fail（面试结论；未面试为 null） */
    private String interviewResult;

    /** 面试意见 */
    private String interviewNote;

    private LocalDateTime appliedAt;

    private LocalDateTime hiredAt;

    /** 辞职理由（学生填写，办法第三十一条：书面提前 7 个工作日） */
    private String resignReason;

    /** 用工单位/资助中心备注 */
    private String note;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN 得来） ----
    private String studentName;
    private String studentNo;
    private String className;
    /** 是否家庭经济困难学生（student_profile.family_income_level 非空） */
    private Integer poverty;
    private String postName;
    private String unit;
    private String postType;

    /** 岗位时薪（JOIN workstudy_post 得来，供「我的申请」展示计酬标准） */
    private java.math.BigDecimal hourlyWage;
}
