package com.umi.nest.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学工 P14 心理健康 · 关注台账跟进流水（psyche_followup）。
 *
 * <p>§9 的 7 张表之外的第 8 张：POST /focus/{id}/followups 需要留痕，见 sql/nest_psyche_work.sql 文件头。</p>
 */
@Data
public class PsycheFollowup {

    private Long id;

    private Long focusId;

    /** 跟进内容（谈话 / 联系中心 / 家长沟通等） */
    private String content;

    /** 本次措施 */
    private String measures;

    /** 下一步计划 */
    private String nextPlan;

    /** 记录人 users.id */
    private Long operatorId;

    private LocalDateTime happenedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;

    /** 列表展示用（JOIN users 带出，非表字段） */
    private String operatorName;
}
