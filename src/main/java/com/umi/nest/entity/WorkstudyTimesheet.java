package com.umi.nest.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 勤工助学工时记录（学工 P10）：workstudy_timesheet 表。
 *
 * <p>用工单位记录用工时间 → 学生可见 → 单位确认（{@code confirmed}）后方可进月度酬金发放表
 * （办法第二十四条）。超限（单月 &gt; 40 小时或单周 &gt; 8 小时）只置 {@code over_limit} 提示位，不硬拦。</p>
 */
@Data
public class WorkstudyTimesheet {

    private Long id;

    /** workstudy_application.id（岗位 + 学生 + 学期的一笔录用关系） */
    private Long applicationId;

    /** workstudy_post.id */
    private Long postId;

    /** users.id（学生） */
    private Long studentId;

    /** 用工月份，如 2026-10 */
    private String workMonth;

    private LocalDate workDate;

    /** 本次工时（小时），decimal(4,1) */
    private BigDecimal hours;

    /** 工作内容 */
    private String content;

    /** 是否超出办法第十六条上限（1 = 提示，不阻断发放） */
    private Integer overLimit;

    /** submitted 待确认 / confirmed 已确认 */
    private String status;

    /** 记录人 users.id（用工单位） */
    private Long recorderId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN 得来） ----
    private String postName;
    private String unit;
    private String studentName;
    private String studentNo;
    private String className;
}
