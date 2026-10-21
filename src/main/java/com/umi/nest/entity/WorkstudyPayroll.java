package com.umi.nest.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 勤工助学月酬金发放表（学工 P10）：workstudy_payroll 表。
 *
 * <p>由资助中心（admin）按月汇总「已确认」工时生成（办法第二十四条）：{@code amount = total_hours * hourly_wage}。
 * 幂等键 {@code (student_id, post_id, work_month)}：重跑只更新工时与金额，不产生重复行。</p>
 */
@Data
public class WorkstudyPayroll {

    private Long id;

    private Long studentId;

    private Long postId;

    /** workstudy_application.id */
    private Long applicationId;

    /** 发放月份，如 2026-10 */
    private String workMonth;

    /** 该学生该岗位该月已确认工时合计 */
    private BigDecimal totalHours;

    /** 时薪快照（生成时从岗位取，之后改岗位不影响已发月份） */
    private BigDecimal hourlyWage;

    /** total_hours * hourly_wage */
    private BigDecimal amount;

    /** 工作考核结果（《学生勤工助学工作考核表》摘要） */
    private String assessment;

    /** pending 待发放 / paid 已发放 */
    private String status;

    /** 发放人 users.id（资助中心） */
    private Long payerId;

    private LocalDateTime paidAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN 得来） ----
    private String studentName;
    private String studentNo;
    private String className;
    private String postName;
    private String unit;
}
