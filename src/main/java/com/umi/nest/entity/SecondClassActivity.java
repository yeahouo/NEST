package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 第二课堂活动（学工 P18，S26）：second_class_activity 表。
 *
 * <p>活动是「学校/学院/班级主办、学生参加后统一认定」的载体；学生也可以不挂活动自报（record.activity_id 为 NULL）。
 * 只有 {@code status='published'} 的活动可以被申报，{@code closed} 后不再接受新申报（已申报的继续走审核）。</p>
 *
 * <p>{@code level} 取 {@code school|college|class|other}——这是<b>系统内的组织层级</b>，不是 S26 的板块名，也不是评级。</p>
 *
 * <p>{@code credit} 允许 NULL：学校可能只记学时不给学分。{@code hours} 必须 > 0（活动本身的工作量）。</p>
 */
@Data
public class SecondClassActivity {

    private Long id;

    /** 所属板块 second_class_category.id */
    private Long categoryId;

    private String title;

    /** 主办方（自由填写：校团委 / 学院团委 / 某班级 …） */
    private String organizer;

    /** 组织层级：school 校级 / college 院级 / class 班级 / other 其他 */
    private String level;

    private LocalDate startDate;

    private LocalDate endDate;

    /** 活动可计学时 */
    private BigDecimal hours;

    /** 可计学分；null = 不计学分 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private BigDecimal credit;

    private String location;

    private String description;

    /** draft 草稿 / published 已发布 / closed 已结束 */
    private String status;

    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private Integer deleted;

    // ── 展示用（非表字段） ──

    /** 板块名称（列表联表带出） */
    private String categoryName;

    /** 已认定/待审定申报数（列表联表带出，供管理端判断能否关闭活动） */
    private Integer recordCount;
}
