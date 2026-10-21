package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 志愿服务黑名单（学工 P17，S21 第三十四/三十五/三十六条）：volunteer_blacklist 表。
 *
 * <p>{@code clause} 存条文款项码：{@code c34_1}…{@code c34_5}（第三十四条 (一)~(五)）、
 * {@code c35}（未按要求开展工作或受警告及以上处分）。</p>
 *
 * <p>期限口径（§4）：**6 个月**，{@code expireAt = listedAt + 6 个月}由服务层按日期推算
 * （**不做定时任务**）；{@code daysLeft} 是返回前现算的提示字段，不落库。
 * {@code status}：{@code active} 生效中 / {@code expired} 已到期 / {@code revoked} 已撤销（§3.1）。</p>
 *
 * <p>本表属学生范围表：list/count 须支持按辅导员过滤（§6）。</p>
 */
@Data
public class VolunteerBlacklist {

    private Long id;

    /** 被列入黑名单的学生 users.id */
    private Long userId;

    /** 条款码：c34_1…c34_5 / c35（§3.1） */
    private String clause;

    private String reason;

    /** 列入日期 */
    private LocalDate listedAt;

    /** 到期日期（= listedAt + 6 个月，服务层推算） */
    private LocalDate expireAt;

    /** 通知送达时间 */
    private LocalDateTime notifyTime;

    /** active 生效中 / expired 已到期 / revoked 已撤销（§3.1） */
    private String status;

    /** 操作人 users.id */
    private Long operatorId;

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

    /** 剩余天数（服务层按当日与 expireAt 比算，不落库） */
    @JsonProperty("daysLeft")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long daysLeft;
}
