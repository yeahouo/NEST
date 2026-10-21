package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 志愿服务时长记录（学工 P17，S21）：volunteer_record 表。
 *
 * <p>口径：{@code hourType} 存 {@code credit} 信用时数 / {@code honor} 荣誉时数（§3.1）；
 * {@code source} 存 {@code platform} 平台打卡 / {@code makeup} 补录；校外自行参加不得录入
 * {@code source=platform}（S21 第二十五条，§4）。</p>
 *
 * <p>统计口径（§6、§10）：<b>只有 {@code status='approved'} 且未删除</b>的记录才计入累计时长与星级推算
 * （S21 第三十条 50/100/150/300/500）。统计 SQL 见 {@code VolunteerRecordMapper} 的
 * {@code sumHoursByUser} / {@code sumHoursByUserAndDate} / {@code countByUserAndDate}。</p>
 *
 * <p>{@code hours} 用 {@link BigDecimal}（DECIMAL(6,2)），禁止用 double 累计，避免浮点误差。</p>
 */
@Data
public class VolunteerRecord {

    private Long id;

    /** 志愿者 users.id */
    private Long userId;

    /** 关联项目 volunteer_project.id（补录可空） */
    private Long projectId;

    private LocalDate serviceDate;

    private LocalDateTime signIn;

    private LocalDateTime signOut;

    /** 时长（小时，DECIMAL(6,2)） */
    private BigDecimal hours;

    /** credit 信用时数 / honor 荣誉时数（§3.1） */
    private String hourType;

    /** platform 平台打卡 / makeup 补录（§3.1） */
    private String source;

    /** 录入人 users.id */
    private Long recorderId;

    /** 录入主体：center 指导中心 / department 院系组织 / club 公益类社团 / studentActivity 学生活动组织单位 */
    private String recorderSubject;

    /** pending 待审核 / approved 已通过 / rejected 已驳回（§3.1） */
    private String status;

    private Long reviewUserId;

    private LocalDateTime reviewTime;

    private String rejectReason;

    /** 补录说明（S21 第二十六条，6 个月窗口由服务层判定） */
    private String makeupNote;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN 带出） ----

    /** 学生姓名（users.real_name） */
    @JsonProperty("studentName")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String studentName;

    /** 学号（student_profile.student_no） */
    @JsonProperty("studentNo")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String studentNo;

    /** 班级（student_profile.class_name） */
    @JsonProperty("className")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String className;

    /** 项目名称（volunteer_project.project_name） */
    @JsonProperty("projectName")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String projectName;
}
