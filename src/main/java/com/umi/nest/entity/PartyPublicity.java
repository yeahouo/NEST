package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 公示留痕（学工 P16）：party_publicity 表。
 *
 * <p>node_type 只登记**有来源的三类节点**（S22 第 11/14/25 步，§11.1）：
 * {@code training_student} 确定党校学员公示（第 11 步）/
 * {@code develop_party} 发展党员公示（第 14 步）/
 * {@code probation_convert} 拟转正对象公示（第 25 步）。</p>
 *
 * <p>状态机（全部 CAS，见 PartyService）：
 * {@code draft -> publicizing -> closed}。
 * <b>publish 只接受 {draft}；close 只接受 {publicizing}；已结束再 close 必须 400
 * （不得做幂等 200 —— P15 E2E 缺陷 D2）。</b></p>
 *
 * <p>异议入口（§11.2-6）只写**校级**受理渠道，**不写支部个人联系方式**。</p>
 */
@Data
public class PartyPublicity {

    private Long id;

    /** 被公示学生 users.id（名单公示；空 = 不针对个人的事项公示） */
    private Long userId;

    /** training_student / develop_party / probation_convert */
    private String nodeType;

    /** 公示标题 */
    private String title;

    /** 对应阶段：applicant/activist/candidate/probationary/full */
    private String stage;

    /** 公示起始日期 */
    private LocalDate startDate;

    /** 公示结束日期（默认按 5 个工作日推算） */
    private LocalDate endDate;

    /** 公示工作日数（S22 各公示节点均为 5 个工作日；校规另有 5-7 天表述） */
    private Integer workdays;

    /** 公示正文/依据文本（含条款引用） */
    private String noticeText;

    /** 异议入口（只写校级受理渠道，不写支部个人联系方式） */
    private String objectionChannel;

    /** draft 草稿 / publicizing 公示中 / closed 已结束 */
    private String status;

    /** 公示结论/处理结果 */
    private String result;

    /** 发布（进入公示中）时间 */
    private LocalDateTime publishedAt;

    /** 结束时间 */
    private LocalDateTime closedAt;

    /** 创建人 users.id */
    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN / 服务层计算得出；一律 @JsonProperty 防 Lombok 大小写坑） ----

    /** 被公示学生姓名（users.real_name） */
    @JsonProperty("userName")
    private String userName;

    /** 学号（users.student_id） */
    @JsonProperty("studentNo")
    private String studentNo;

    /** 节点类型中文文案（training_student→确定党校学员公示 / develop_party→发展党员公示 / probation_convert→拟转正对象公示） */
    @JsonProperty("nodeTypeText")
    private String nodeTypeText;

    /** 状态中文文案（draft→草稿 / publicizing→公示中 / closed→已结束） */
    @JsonProperty("statusText")
    private String statusText;

    /** 距公示结束还剩的自然日天数（服务层计算，可空） */
    @JsonProperty("remainDays")
    private Integer remainDays;
}
