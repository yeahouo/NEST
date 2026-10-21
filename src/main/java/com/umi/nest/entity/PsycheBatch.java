package com.umi.nest.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学工 P14 心理健康 · 普查批次（psyche_batch）。
 *
 * <p>status：draft 草稿 → open 进行中（已发布）→ closed 已关闭。一个批次带一个量表。</p>
 */
@Data
public class PsycheBatch {

    private Long id;

    private String batchName;

    /** 普查对象年级（S8：按年级组织） */
    private String grade;

    private String academicYear;

    private Long instrumentId;

    /** 开放时间 */
    private LocalDateTime openAt;

    /** 关闭时间 */
    private LocalDateTime closeAt;

    /** 是否需要抽查面对面访谈：0/1 */
    private Integer interviewRequired;

    private String interviewNote;
    private String noticeTitle;
    private String noticeUrl;

    /** draft 草稿 / open 进行中 / closed 已关闭 */
    private String status;

    private LocalDateTime publishedAt;
    private LocalDateTime closedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;

    /** 列表展示用（JOIN psyche_instrument 带出，非表字段） */
    private String instrumentName;

    /** 列表展示用（JOIN users 带出，非表字段） */
    private String createdByName;
}
