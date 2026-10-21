package com.umi.nest.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 学工 P14 心理健康 · 批次名单与作答进度（psyche_task，一人一批次一条）。
 *
 * <p>status：pending 未开始 / in_progress 作答中 / completed 已完成 / absent 未参加。
 * resultLevel 是**中心/辅导员回填**字段，平台不自动判定学生心理状况。</p>
 */
@Data
public class PsycheTask {

    private Long id;

    private Long batchId;
    private Long studentId;

    /** pending / in_progress / completed / absent */
    private String status;

    private Integer answerCount;
    private Integer totalItems;

    /** 卷面总分（按 bandJson 折算用，不是诊断结论） */
    private BigDecimal scoreTotal;

    /** 按 bandJson 折算出的档位名 */
    private String scoreBand;

    /** 回填结果等级：normal 正常 / attention 关注 / warning 预警 */
    private String resultLevel;

    private String resultNote;
    private Long resultBy;

    /** 是否已抽查访谈：0/1 */
    private Integer interviewed;

    private String interviewNote;
    private Long interviewedBy;
    private LocalDateTime interviewedAt;
    private LocalDateTime startedAt;
    private LocalDateTime submittedAt;
    private Integer durationSeconds;
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

    /** 列表展示用（JOIN student_profile 带出，非表字段） */
    private String grade;

    /** 列表展示用（JOIN psyche_batch 带出，非表字段） */
    private String batchName;
}
