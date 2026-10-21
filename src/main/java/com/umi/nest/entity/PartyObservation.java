package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 考察与思想汇报台账（学工 P16）：party_observation 表。
 *
 * <p>{@code type = thought_report} 思想汇报（学生自报）/
 * {@code type = observation} 考察记录（支部登记）。</p>
 *
 * <p>时限口径（§11.2-3）：校规「至少每季度……书面思想汇报」「超过三个月不交思想汇报可能
 * 取消积极分子资格」，考察「国标每半年一次（第十一条）/ 校规每季度考察」两条来源并存。
 * 服务层只按 THOUGHT_REPORT_WARN_DAYS 计算是否超期并给出**带条款引用的提示**，
 * 不做自动资格判定、不阻断推进（§11.2-1）。</p>
 *
 * <p>口径（§11.2-4）：{@code internalNote} 学生端由服务层抹掉，仅 staff 可见。</p>
 */
@Data
public class PartyObservation {

    private Long id;

    /** 学生 users.id */
    private Long userId;

    /** thought_report 思想汇报 / observation 考察记录 */
    private String type;

    /** 提交时间（思想汇报 = 学生提交；考察 = 考察组织时间） */
    private LocalDateTime submittedAt;

    /** 所属期次文本，如 2026-1（第一季度） */
    private String periodLabel;

    /** 思想汇报内容 / 考察情况记录 */
    private String content;

    /** pending 待审核 / reviewed 已审核 / returned 退回 */
    private String reviewStatus;

    /** 审核人 users.id */
    private Long reviewBy;

    /** 审核时间 */
    private LocalDateTime reviewAt;

    /** 审核意见（可下发学生本人） */
    private String reviewOpinion;

    /**
     * 支部内部考察意见（学生端服务层抹掉，仅 staff 可见）。
     *
     * <p>序列化口径（§11.2-4 + P16 字段级扫描）：值为 null 时**整个键都不下发**
     * （{@code @JsonInclude(NON_NULL)}），学生端响应体不出现 {@code internalNote} 键名。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String internalNote;

    /** 时限/依据条款引用文本（如「S23：至少每季度书面思想汇报」） */
    private String ruleRef;

    /** 登记人 users.id（思想汇报为学生本人） */
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

    /** 类型中文文案（thought_report→思想汇报 / observation→考察记录） */
    @JsonProperty("typeText")
    private String typeText;

    /** 审核状态中文文案（pending→待审核 / reviewed→已审核 / returned→已退回） */
    @JsonProperty("reviewStatusText")
    private String reviewStatusText;

    /** 是否已超期未交（1 超期 / 0 正常；只提示不阻断，见 §11.2-1） */
    @JsonProperty("overdue")
    private Integer overdue;

    /** 时限提示文案（带条款引用） */
    @JsonProperty("limitTip")
    private String limitTip;
}
