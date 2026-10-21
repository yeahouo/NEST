package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 党校培训班次（学工 P16）：party_training 表。
 *
 * <p>培训时长两条标准并存、平台**不替学校选**（§11.2-3）：
 * 国标 S24 第十七条「不少于三天（或不少于二十四个学时）」；
 * 校规 S23「五至七天（或不少于四十个学时）」。{@code days}/{@code hours} 原样登记，
 * 服务层只给出两条口径的提示文本，不校验学校选了哪条。</p>
 *
 * <p>§11.1-③：党校结业标准（及格线、考核权重）无任何来源 => 本实体/本表**不建分数与及格线字段**。</p>
 */
@Data
public class PartyTraining {

    private Long id;

    /** 班次名称，如「2026 年第一期入党积极分子培训班」 */
    private String trainingName;

    /** 期次文本，如 2026-1 */
    private String term;

    /** 开班日期 */
    private LocalDate startDate;

    /** 结业日期 */
    private LocalDate endDate;

    /** 培训天数（国标不少于 3 天 / 校规 5-7 天，两条来源并存，平台不校验选哪条） */
    private Integer days;

    /** 培训学时（国标不少于 24 学时 / 校规不少于 40 学时，两条并存，平台不替学校选） */
    private Integer hours;

    /** 培训地点 */
    private String location;

    /** 主办单位 */
    private String organizer;

    /** 名额上限：0 = 不限 */
    private Integer quota;

    /** draft 草稿 / signup 报名中 / ongoing 进行中 / finished 已结束 / cancelled 已取消 */
    private String status;

    /** 培训内容/课程安排说明 */
    private String content;

    private String note;

    /** 创建人 users.id */
    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN / 服务层计算得出；一律 @JsonProperty 防 Lombok 大小写坑） ----

    /** 状态中文文案（draft→草稿 / signup→报名中 / ongoing→进行中 / finished→已结束 / cancelled→已取消） */
    @JsonProperty("statusText")
    private String statusText;

    /** 已报名人数（party_training_member 计数，统计用） */
    @JsonProperty("memberCount")
    private Integer memberCount;

    /** 已结业人数（party_training_member.status = completed 计数，统计用） */
    @JsonProperty("completedCount")
    private Integer completedCount;

    /** 培训时长口径提示（国标 / 校规两条并列，带来源；平台不替学校选） */
    @JsonProperty("hoursTip")
    private String hoursTip;
}
