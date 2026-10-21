package com.umi.nest.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 奖励与处分（学工 P5）：award_punishment 表。
 *
 * <p>一条记录只表示「一名学生的一件奖惩事实」，由辅导员/管理员录入，学生端只读。</p>
 */
@Data
public class AwardPunishment {

    private Long id;

    /** users.id（学生） */
    private Long studentId;

    /** award 奖励 / punishment 处分 */
    private String kind;

    /** national 国家级 / provincial 省级 / school 校级 / college 院级 */
    private String level;

    /** 名称，如「国家励志奖学金」「校级三好学生」 */
    private String title;

    /** 事由/说明 */
    private String reason;

    /** 金额（奖励可有，处分一般为空） */
    private BigDecimal amount;

    /** 文号 */
    private String docNo;

    /** 授予/处分日期 */
    private LocalDate grantedOn;

    /** 处分解除日期（奖励为空） */
    private LocalDate revokedOn;

    /** 录入人 users.id */
    private Long operatorId;

    private LocalDateTime createdAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN 得来） ----
    private String studentName;
    private String studentNo;
    private String className;
    private String operatorName;
}
