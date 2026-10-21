package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 阶段台账（学工 P16）：party_stage 表。
 *
 * <p>阶段枚举固定五档且**只能向前**：
 * {@code applicant -> activist -> candidate -> probationary -> full}。</p>
 *
 * <p>口径（§11.2-9）：推进 = 服务层显式前置校验 + CAS
 * {@code WHERE id=#{id} AND deleted=0 AND stage=#{fromStage}}，
 * fromStage 取**显式枚举前置态**（不是刚读到的值，否则 WHERE 恒真）。
 * 回退需 admin + 原因 + 审计。</p>
 *
 * <p>口径（§11.2-4）：{@code internalNote}（支部内部考察意见）与
 * {@code internalDiscussion}（推优讨论内容）学生端由服务层抹掉，仅 staff 可见。</p>
 */
@Data
public class PartyStage {

    private Long id;

    /** 学生 users.id */
    private Long userId;

    /** 所在党支部（文本；支部主数据无来源，不建表） */
    private String branchName;

    /** applicant / activist / candidate / probationary / full（只向前） */
    private String stage;

    /** 第一步·确定为入党申请人日期 */
    private LocalDate applicantDate;

    /** 第一步依据说明 */
    private String applicantRef;

    /** 第二步·确定为入党积极分子日期 */
    private LocalDate activistDate;

    /** 第二步依据说明（校规：党支部两个月以上培养教育） */
    private String activistRef;

    /** 第三步·列为发展对象日期 */
    private LocalDate candidateDate;

    /** 第三步依据说明（国标第十三条：一年以上） */
    private String candidateRef;

    /** 第四步·接收为预备党员日期（支部大会通过之日，国标第三十二条） */
    private LocalDate probationaryDate;

    /** 第四步依据说明 */
    private String probationaryRef;

    /** 第五步·转为正式党员日期（党龄自此起算，国标第三十四条） */
    private LocalDate fullDate;

    /** 第五步依据说明 */
    private String fullRef;

    /**
     * 支部内部考察意见（学生端服务层抹掉，仅 staff 可见）。
     *
     * <p>序列化口径（§11.2-4 + P16 字段级扫描）：值为 null 时**整个键都不下发**
     * （{@code @JsonInclude(NON_NULL)}），学生端响应体不出现 {@code internalNote} 键名。</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String internalNote;

    /** 推优讨论内容（学生端服务层抹掉，仅 staff 可见；null 时不下发键名，同 internalNote） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String internalDiscussion;

    /** 当前阶段的公示 party_publicity.id（S22 第 11/14/25 步） */
    private Long stagePublicityId;

    /** 最近一次阶段回退原因（回退需 admin + 原因 + 审计） */
    private String rollbackReason;

    /** 最近一次阶段回退时间 */
    private LocalDateTime rollbackAt;

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

    /** 阶段中文文案（applicant→申请人 / activist→入党积极分子 / …；服务层计算） */
    @JsonProperty("stageText")
    private String stageText;

    /** 当前阶段的下一步待办与时限倒计时（带条款引用；只提示不阻断） */
    @JsonProperty("nextTip")
    private String nextTip;
}
