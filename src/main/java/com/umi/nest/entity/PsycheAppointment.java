package com.umi.nest.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学工 P14 心理健康 · 预约咨询（psyche_appointment）。
 *
 * <p>S16：时间限定每次 50 分钟、时段周一至周五 8:00-17:00、须提前至少一个工作日；
 * S17：线上咨询提前半天确认，续约可直接向咨询师申请（renewedFrom 留痕）。</p>
 *
 * <p>note 是中心内部备注，**学生端不下发**；mainIssue 全文只在学生本人与详情里出现。</p>
 */
@Data
public class PsycheAppointment {

    private Long id;

    private Long studentId;

    /** 咨询师/辅导员 users.id（续约时沿用上次） */
    private Long counselorId;

    /** office 面询（主5#118）/ online 线上（腾讯会议） */
    private String mode;

    /** 主要咨询问题（S16：预约时需提供；列表与导出不含全文） */
    private String mainIssue;

    private String contactPhone;
    private LocalDateTime expectStart;
    private LocalDateTime expectEnd;

    /** 单次时长（分钟，默认 50） */
    private Integer durationMinutes;

    /** requested 已申请 / confirmed 已确认 / done 已完成 / cancelled 已取消 / no_show 未到 */
    private String status;

    private LocalDateTime confirmedAt;
    private Long confirmedBy;
    private LocalDateTime finishedAt;
    private String cancelReason;

    /** 续约来源 psyche_appointment.id */
    private Long renewedFrom;

    /** 中心内部备注（学生端不可见） */
    private String note;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;

    /** 列表展示用（JOIN users 带出，非表字段） */
    private String studentName;

    /** 列表展示用（JOIN users.student_id 带出，非表字段） */
    private String studentNo;

    /** 列表展示用（JOIN student_profile 带出，非表字段） */
    private String className;

    /** 列表展示用（JOIN users 带出，非表字段） */
    private String counselorName;
}
