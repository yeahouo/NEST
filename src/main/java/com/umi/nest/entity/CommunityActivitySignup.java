package com.umi.nest.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 社区活动报名（学工 P15）：community_activity_signup 表。
 *
 * <p>同活动同学生只允许一条报名（DB 唯一键 uk_cas_activity_student）。
 * status：signed 已报名 → approved 通过 / rejected 驳回；attended 已参加 / absent 未参加
 * 由签到置位；award 只由管理员/辅导员登记（对齐说明 §10.2-8）。</p>
 */
@Data
public class CommunityActivitySignup {

    private Long id;

    private Long activityId;

    private Long studentId;

    private String building;

    private String roomNo;

    private LocalDateTime signupAt;

    /** signed / approved / rejected / attended / absent */
    private String status;

    private String rejectReason;

    /** none / first / second / third / excellent */
    private String award;

    private BigDecimal score;

    private String note;

    private Long reviewedBy;

    private LocalDateTime reviewedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN 得来） ----

    private String studentName;

    private String studentNo;

    private String className;

    private String activityTitle;

    private String activityStatus;

    private LocalDateTime signupDeadline;

    private String reviewedByName;
}
