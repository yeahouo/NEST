package com.umi.nest.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 勤工助学岗位（学工 P10）：workstudy_post 表。
 *
 * <p>状态机（唯一权威在 WorkstudyService）：
 * draft --submit--> pending --approve--> recruiting --close--> closed
 *                            ↘ reject --> rejected</p>
 *
 * <p>工时上限（办法第十六条）：每周 ≤ 8 小时、每月 ≤ 40 小时。时薪下限：临时岗 ≥ 15 元/小时
 * （办法第二十三条）。超限只在服务层给 warnings，不阻断保存 —— 寒暑假可延长。</p>
 */
@Data
public class WorkstudyPost {

    private Long id;

    /** fixed 固定岗（持续用工 ≥ 1 学期）/ temporary 临时岗（一次性或几次完成） */
    private String postType;

    /** 用工单位名称（如 图书馆 / 教务处） */
    private String unit;

    /** 用工单位负责人 users.id（=teacher），备案与权限归属用它 */
    private Long unitUserId;

    private String name;

    /** 岗位工作内容 */
    private String content;

    /** 技能要求 */
    private String skill;

    /** 招聘人数 */
    private Integer headcount;

    /** 已录用人数 */
    private Integer hiredCount;

    private BigDecimal hourlyWage;

    /** 每周工时上限（办法：≤ 8） */
    private BigDecimal hoursPerWeek;

    /** 每月工时上限（办法：≤ 40） */
    private BigDecimal hoursPerMonth;

    /** 学期，如 2025-2026-1 */
    private String academicTerm;

    private LocalDateTime applyDeadline;

    private LocalDateTime interviewAt;

    /** draft / pending / recruiting / closed / rejected */
    private String status;

    /** 审批意见 / 终止情形说明 */
    private String reviewNote;

    /** 审批人 users.id（资助中心 = admin） */
    private Long reviewerId;

    private LocalDateTime reviewedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN / Service 装配） ----

    /** 用工单位负责人姓名（JOIN users） */
    private String unitUserName;

    /** 已录用条数（审批/概览口径，来自 workstudy_application） */
    private Integer hiredCountActual;
}
