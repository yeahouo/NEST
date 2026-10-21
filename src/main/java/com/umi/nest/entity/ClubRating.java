package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 社团星级登记（学工 P17，S25b）：club_rating 表。
 *
 * <p>字段名刻意区分：{@code businessUnit} 是**业务指导单位**（S25b 口径），与
 * {@link Club#getAttachUnit()} 的**挂靠单位**（S25a 口径）**不得互换**（§2）。</p>
 *
 * <p>{@code starLevel}：{@code star3} 三星级 / {@code star4} 四星级 / {@code star5} 五星级（§3.1）；
 * {@code basisNote}（评定依据说明）**必填**（§2）。一个社团一个学年只有一条有效星级。</p>
 */
@Data
public class ClubRating {

    private Long id;

    /** 社团 club.id */
    private Long clubId;

    /** 学年，形如 2025-2026 */
    private String schoolYear;

    /** star3 三星级 / star4 四星级 / star5 五星级（§3.1） */
    private String starLevel;

    /** 业务指导单位（S25b 字段名；勿与 club.attach_unit「挂靠单位」互换） */
    private String businessUnit;

    /** 评定依据说明（必填） */
    private String basisNote;

    /** 关联公示 club_publicity.id */
    private Long publicityId;

    /** 登记人 users.id */
    private Long registerUserId;

    private LocalDateTime registerTime;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN 带出） ----

    /** 社团名称（club.club_name） */
    @JsonProperty("clubName")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String clubName;
}
