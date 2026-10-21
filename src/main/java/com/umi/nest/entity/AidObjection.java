package com.umi.nest.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 家庭经济困难学生认定 · 公示异议（复议）记录（P11）：aid_objection 表。
 *
 * <p>学生只能对**自己**的认定申请、且只在公示期内（objection_deadline 之前）提交；
 * 管理员答复后终态为 accepted / rejected（采纳则调级或取消资格）。</p>
 *
 * <p>状态机：pending --answer(verdict)--> accepted / rejected。
 * 其中 answered 保留在库表允许值内（DDL 注释列出），当前实现不写入该值：答复直接落结论，
 * 让「是否未答复」用一个字段就能判定。</p>
 */
@Data
public class AidObjection {

    private Long id;

    /** aid_application.id（必须是认定申请） */
    private Long applicationId;

    /** 认定申请所属学生（users.id），权限判定用它 */
    private Long studentId;

    /** 提交人（正常即 studentId 本人） */
    private Long objectorId;

    /** 提交人姓名快照 */
    private String objectorName;

    /** 复议内容 */
    private String content;

    /** 佐证材料说明 */
    private String evidence;

    /** pending / accepted / rejected */
    private String status;

    /** 答复内容 */
    private String answer;

    /** 答复人（users.id） */
    private Long answeredBy;

    /** 答复截止时间（提交时间 + 5 个工作日） */
    private LocalDateTime deadline;

    /** 答复时间 */
    private LocalDateTime answeredAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN aid_application 得来） ----

    /** 认定学年（来自 aid_application.cert_year） */
    private String certYear;

    /** 认定级别（来自 aid_application.poverty_level） */
    private String povertyLevel;
}
