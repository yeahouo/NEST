package com.umi.nest.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 学工 P14 心理健康 · 整卷作答留痕（psyche_answer，一份卷一条，答案在 answerJson 里）。
 */
@Data
public class PsycheAnswer {

    private Long id;

    private Long taskId;
    private Long studentId;

    /** 作答 JSON：[{"no":1,"value":2}, …] */
    private String answerJson;

    private Integer answerCount;
    private Integer totalItems;
    private BigDecimal scoreTotal;
    private String scoreBand;
    private Integer durationSeconds;
    private LocalDateTime submittedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
}
