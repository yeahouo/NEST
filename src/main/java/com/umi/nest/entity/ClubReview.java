package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 社团年审（学工 P17，S25a）：club_review 表。
 *
 * <p>四项聚焦材料 + 挂靠单位意见：{@code architectureMaterial}（组织架构）、
 * {@code backboneMaterial}（骨干队伍）、{@code activityMaterial}（活动开展）、
 * {@code fundMaterial}（经费使用）、{@code attachOpinion}（挂靠单位意见，§2）。</p>
 *
 * <p>结果三值（§3.1）：{@code qualified} 合格 / {@code unqualified} 不合格 / {@code deregistered} 注销。
 * 登记结果是 CAS（§7-1），**结果登记后不得重设**（§7-2）。{@code schoolYear} 形如 {@code 2025-2026}。</p>
 */
@Data
public class ClubReview {

    private Long id;

    /** 社团 club.id */
    private Long clubId;

    /** 学年，形如 2025-2026 */
    private String schoolYear;

    /** 组织架构材料 */
    private String architectureMaterial;

    /** 骨干队伍材料 */
    private String backboneMaterial;

    /** 活动开展材料 */
    private String activityMaterial;

    /** 经费使用材料 */
    private String fundMaterial;

    /** 挂靠单位意见 */
    private String attachOpinion;

    /** qualified 合格 / unqualified 不合格 / deregistered 注销（§3.1；NULL 表示未登记） */
    private String result;

    private LocalDateTime submitTime;

    /** 审核人 users.id */
    private Long reviewUserId;

    private LocalDateTime reviewTime;

    /** 关联公示 club_publicity.id */
    private Long publicityId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN 带出） ----

    /** 社团名称（club.club_name） */
    @JsonProperty("clubName")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String clubName;

    /** 审核人姓名（users.real_name） */
    @JsonProperty("reviewUserName")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String reviewUserName;
}
