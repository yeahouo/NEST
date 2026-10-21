package com.umi.nest.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 综合素质测评指标（德智体美劳五维）。
 *
 * <p>口径：每维内 weight 合计 1.0000；max_score 是该指标满分（默认 100）。
 * 维度权重在 EvalService 里（可按配置覆盖），不在本表。</p>
 */
@Data
public class EvalIndicator {

    private Long id;

    /** moral 德 / intellectual 智 / physical 体 / aesthetic 美 / labor 劳 */
    private String dimension;

    /** 稳定编码，前端按它取指标（如 MORAL_BASIC） */
    private String code;

    private String name;

    private String description;

    /** 该项满分 */
    private BigDecimal maxScore;

    /** 维度内权重，合计 1.0000 */
    private BigDecimal weight;

    private Integer sortOrder;

    private Integer active;

    private LocalDateTime createdAt;
}
