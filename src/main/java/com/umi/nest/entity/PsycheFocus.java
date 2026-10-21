package com.umi.nest.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学工 P14 心理健康 · 关注台账（psyche_focus）。
 *
 * <p>level：general 一般关注 / key 重点关注 / risk 危机预警；
 * source：task 普查 / apply 学生求助 / counselor 辅导员发现 / appointment 咨询 / referral 转介 / other。</p>
 *
 * <p>保密（S16）：仅 admin 与该生辅导员可读写；level 不在学生端展示。</p>
 */
@Data
public class PsycheFocus {

    private Long id;

    private Long studentId;
    private Long batchId;

    /** general 一般关注 / key 重点关注 / risk 危机预警 */
    private String level;

    /** task / apply / counselor / appointment / referral / other */
    private String source;

    private Long sourceId;

    /** 关注缘由（不含临床诊断结论） */
    private String summary;

    /** 已采取措施 */
    private String measures;

    /** 跟进辅导员 users.id */
    private Long counselorId;

    /** open 待跟进 / following 跟进中 / closed 已关闭 */
    private String status;

    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;

    /** 列表展示用（JOIN users 带出，非表字段） */
    private String studentName;

    /** 列表展示用（JOIN users.student_id 带出，非表字段） */
    private String studentNo;

    /** 列表展示用（JOIN student_profile 带出，非表字段） */
    private String className;

    /** 列表展示用（JOIN student_profile 带出，非表字段） */
    private String major;

    /** 列表展示用（JOIN users 带出，非表字段） */
    private String counselorName;
}
