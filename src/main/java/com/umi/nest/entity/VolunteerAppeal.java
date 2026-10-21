package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 志愿服务申诉（学工 P17，S21 第三十七条）：volunteer_appeal 表。
 *
 * <p>申诉窗口 = **5 个工作日**（§4），超窗返回 400。{@code withinWindow} 在提交时由服务层算好落库
 * （1 在窗口内 / 0 超窗）；{@code deadline} 是返回前现算的提示字段，不落库。</p>
 *
 * <p>状态机（§3.1）：{@code pending} 待受理 → {@code accepted} 已受理 → {@code closed} 已结案；
 * 任一在途态可 {@code rejected} 已驳回。判定是 CAS（§7-1）。</p>
 *
 * <p>本表属学生范围表：list/count 须支持按辅导员过滤（§6）。</p>
 */
@Data
public class VolunteerAppeal {

    private Long id;

    /** 申诉学生 users.id */
    private Long userId;

    /** 针对的时长记录 volunteer_record.id（可为空） */
    private Long recordId;

    /** 针对的黑名单条目 volunteer_blacklist.id（可为空） */
    private Long blacklistId;

    /** 申诉理由（必填） */
    private String reason;

    private LocalDateTime submitTime;

    /** 受理组织（默认「团委」） */
    private String acceptOrg;

    /** 是否在 5 个工作日窗口内（1 是 / 0 否；提交时服务层算好落库） */
    private Integer withinWindow;

    /** pending 待受理 / accepted 已受理 / rejected 已驳回 / closed 已结案（§3.1） */
    private String status;

    /** 裁定内容 */
    private String ruling;

    /** 裁定人 users.id */
    private Long rulerId;

    private LocalDateTime rulingTime;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN / 服务层带出） ----

    /** 学生姓名（users.real_name） */
    @JsonProperty("studentName")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String studentName;

    /** 学号（student_profile.student_no） */
    @JsonProperty("studentNo")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String studentNo;

    /** 申诉截止时间（服务层按 submitTime + 5 个工作日算，不落库） */
    @JsonProperty("deadline")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private LocalDateTime deadline;
}
