package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 社团公示（学工 P17，S25a / S25b）：club_publicity 表。
 *
 * <p>{@code publicityType}：{@code club_review} 年审结果公示 / {@code club_rating} 星级评定公示（§2）。
 * {@code status}：{@code open} 公示中 / {@code closed} 已结束（§3.1）；关闭是 CAS，
 * **{@code closed} 后不得再关闭**（§7-2，重复关闭 400）。</p>
 *
 * <p>公示期 5 天（§4），{@code contactEmail} 默认 {@code twh@xujc.com}（S25a / S25b 异议邮箱）。
 * {@code items} 是服务层装配的公示明细（社团名/结果或星级/单位/类别），不落库。</p>
 */
@Data
public class ClubPublicity {

    private Long id;

    /** club_review 年审公示 / club_rating 星级公示（§2） */
    private String publicityType;

    private String title;

    private String content;

    private LocalDate startDate;

    private LocalDate endDate;

    /** 异议联系邮箱，默认 twh@xujc.com */
    private String contactEmail;

    /** open 公示中 / closed 已结束（§3.1） */
    private String status;

    /** 发布人 users.id */
    private Long publishUserId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（服务层 / JOIN 带出） ----

    /** 发布人姓名（users.real_name） */
    @JsonProperty("publishUserName")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String publishUserName;
}
