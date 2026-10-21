package com.umi.nest.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 综测公示异议（学工 P13）：rank_objection 表。
 *
 * <p>办法第十四条：公示期内有异议向辅导员反馈并答复；同一批次同一学生只允许一条 pending。
 * 处理走 CAS（只处理 status='pending'），所以两个辅导员同时点不会互相覆盖答复。</p>
 */
@Data
public class RankObjection {

    private Long id;

    private Long batchId;

    /** users.id（提出异议的学生） */
    private Long studentId;

    /** 异议内容（去空白后 ≥4 字） */
    private String reason;

    /** pending / accepted / rejected */
    private String status;

    /** 辅导员答复（去空白后 ≥4 字） */
    private String answer;

    /** 处理人 users.id */
    private Long handledBy;

    private LocalDateTime handledAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // ---- 列表展示用的非表字段（JOIN 得来） ----

    private String studentName;

    private String studentNo;

    private String className;

    /** 批次名（rank_batch.batch_name） */
    private String batchName;

    /** 处理人姓名 */
    private String handledByName;
}
