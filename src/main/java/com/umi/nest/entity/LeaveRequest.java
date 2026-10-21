package com.umi.nest.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 学工平台·请假 / 离校 / 外出申请。
 *
 * <p>状态机（service 层强制）：pending 待审 → approved 批准 → closed 已销假；
 * pending → rejected 驳回；pending → cancelled 学生撤回。其余流转一律拒绝。</p>
 *
 * <p>studentName / studentNo / className / approverName 不是表字段，
 * 由列表查询 JOIN users / student_profile 带出，仅用于列表展示。</p>
 */
@Data
public class LeaveRequest {

    private Long id;
    private Long studentId;
    /** personal 事假 / sick 病假 / leave_school 离校 / outing 外出 / internship 实习 */
    private String type;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private BigDecimal days;
    private String reason;
    private String place;
    private String contact;
    private String attachment;
    /** pending / approved / rejected / closed / cancelled */
    private String status;
    private Long approverId;
    private String approveNote;
    private LocalDateTime approvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;

    /** 列表展示用（JOIN 带出，非表字段） */
    private String studentName;
    private String studentNo;
    private String className;
    private String approverName;
}
