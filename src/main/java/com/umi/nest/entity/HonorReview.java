package com.umi.nest.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评优审核流水（学工 P12）：honor_review 表。
 *
 * <p>每一个审核节点落一行，不覆盖、不修改 —— 学生要知道「学院到底怎么说的」，
 * 出现争议时也要能回看每一步是谁在什么时候写的意见，所以这张表只追加。</p>
 */
@Data
public class HonorReview {

    private Long id;

    /** honor_application.id */
    private Long applicationId;

    /** 审核节点：counselor 辅导员初审 / college 学院审核 / dept 学工部复核 */
    private String node;

    /** pass 通过 / reject 驳回 */
    private String verdict;

    /** 审核意见（通过也要求写实：≥8 字且不能是「同意推荐」这类空话） */
    private String comment;

    /** 审核人 users.id */
    private Long reviewerId;

    /** 审核时的角色快照（teacher / admin），避免日后调岗导致历史失真 */
    private String reviewerRole;

    private LocalDateTime reviewedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN users 得来） ----

    /** 审核人姓名（非表字段，来自 users.real_name/nickname/username） */
    private String reviewerName;
}
