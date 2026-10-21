package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 志愿服务项目立项（学工 P17，S21）：volunteer_project 表。
 *
 * <p>状态机（§3.1）：{@code draft} 草稿 → {@code pending} 待审批 → {@code approved} 已批准 /
 * {@code rejected} 已驳回。学生只能在 {@code approved} 项目上报名（§7-4）。
 * 审核是 CAS（{@code WHERE id=? AND deleted=0 AND status=fromStatus}），终态不可重复审核（§7-2）。</p>
 *
 * <p>{@code category} 存 §3.3 的 15 类中文类别值；{@code organizerType} 存 §3.1 英文码
 * （center/department/club/studentActivity），展示文案由服务层按枚举生成，不落库。</p>
 */
@Data
public class VolunteerProject {

    private Long id;

    /** 项目名称 */
    private String projectName;

    /** 志愿类别（§3.3 十五类，中文值直接存库） */
    private String category;

    /** 发起组织 */
    private String organizer;

    /** 发起组织类型：center 指导中心 / department 院系组织 / club 公益类社团 / studentActivity 学生活动组织单位 */
    private String organizerType;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private String location;

    /** 时长说明 */
    private String durationNote;

    /** 招募人数上限 */
    private Integer recruitLimit;

    /** 提交立项申请的学生/教师 users.id */
    private Long submitUserId;

    /** 审核人 users.id */
    private Long reviewUserId;

    private LocalDateTime reviewTime;

    /** 驳回原因 */
    private String rejectReason;

    /** draft / pending / approved / rejected（§3.1） */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN 带出；一律 @JsonProperty 防 Lombok 大小写坑） ----

    /** 提交人姓名（users.real_name） */
    @JsonProperty("submitUserName")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String submitUserName;

    /** 审核人姓名（users.real_name） */
    @JsonProperty("reviewUserName")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String reviewUserName;

    /** 已确认报名人数（volunteer_enrollment 统计，不落库） */
    @JsonProperty("enrolledCount")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer enrolledCount;
}
