package com.umi.nest.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学工 P14 心理健康 · 转介登记与跟踪（psyche_referral）。
 *
 * <p>target：campus 校内中心 / hospital 医院 / family 家庭配合。公开出处没有校内危机干预与转介制度文本
 * ⇒ 只做「登记 + 跟踪留痕」，不写任何流程结论。</p>
 */
@Data
public class PsycheReferral {

    private Long id;

    private Long studentId;

    /** task 普查 / focus 关注台账 / appointment 咨询 / other */
    private String sourceType;

    private Long sourceId;

    /** campus 校内中心 / hospital 医院 / family 家庭配合 */
    private String target;

    /** 转介原因（不含临床诊断结论） */
    private String reason;

    /** 转介人 users.id */
    private Long referredBy;

    private LocalDateTime referredAt;

    /** open 待跟踪 / following 跟踪中 / closed 已结案 */
    private String status;

    /** 跟踪记录（最新一条摘要） */
    private String followNote;

    private LocalDateTime closedAt;
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
    private String referredByName;
}
