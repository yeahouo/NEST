package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 入党申请登记（学工 P16）：party_application 表。
 *
 * <p>口径（对齐说明 §11.2-1）：平台**不做自动资格判定**。本表只做台账：登记递交入党申请书的
 * 日期与党支部派人谈话的日期/谈话人。谈话是否落在「一个月内」（国标第七条 / 校规）由
 * {@code PartyService} 按 TALK_WITHIN_DAYS 计算，只作为**带条款引用的提示**，不阻断任何推进。</p>
 *
 * <p>口径（§11.2-4）：{@code internalRemark} 是支部内部备注，学生端
 * （{@code /api/party/my/**}）由服务层抹掉后不下发，仅 staff 可见。</p>
 */
@Data
public class PartyApplication {

    private Long id;

    /** 申请学生 users.id */
    private Long userId;

    /** 申请时所在党支部（文本登记；支部主数据无来源，不建表） */
    private String branchName;

    /** 递交入党申请书日期 */
    private LocalDate applyDate;

    /** 党支部派人谈话日期（国标第七条 / 校规：一个月内） */
    private LocalDate talkDate;

    /** 谈话人（姓名或职务文本） */
    private String talkBy;

    /** 谈话情况摘要（可下发学生本人） */
    private String talkSummary;

    /**
     * 支部内部备注（学生端服务层抹掉，仅 staff 可见）。
     *
     * <p>序列化口径（§11.2-4 + P16 字段级扫描）：值为 null 时**整个键都不下发**
     * （{@code @JsonInclude(NON_NULL)}），学生端响应体不出现 {@code internalRemark} 键名；
     * staff 有内容时照常下发。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String internalRemark;

    /** active 有效 / withdrawn 撤回 */
    private String status;

    /** 登记人 users.id */
    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN / 服务层计算得出；一律 @JsonProperty 防 Lombok 大小写坑） ----

    /** 学生姓名（users.real_name） */
    @JsonProperty("userName")
    private String userName;

    /** 学号（users.student_id） */
    @JsonProperty("studentNo")
    private String studentNo;

    /** 班级（student_profile.class_name） */
    @JsonProperty("className")
    private String className;

    /** 申请→谈话时限提示（带 rule_ref 条款引用；只提示不阻断） */
    @JsonProperty("talkLimitTip")
    private String talkLimitTip;

    /** 谈话是否已超「一个月内」（1 超期 / 0 未超期或未谈话；只提示不阻断） */
    @JsonProperty("talkOverdue")
    private Integer talkOverdue;
}
