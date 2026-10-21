package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 综测排名快照行（学工 P13）：rank_row 表。
 *
 * <p>为什么是快照：公示期内页面展示的必须是「当时算出来的那一版」，期间任何 A 补录或加分审核
 * 都不该悄悄改变已公示的名次；有异议走异议流程，处理完再重算并重新公示。</p>
 *
 * <p>award_tier 存英文码 t1/t2/t3（列宽 VARCHAR(4) 放不下 full word），中文文案由服务端出。</p>
 */
@Data
public class RankRow {

    private Long id;

    private Long batchId;

    /** users.id（学生） */
    private Long studentId;

    /** 专业（测评单位） */
    private String major;

    private String className;

    private String grade;

    /** 学习成绩 A */
    private BigDecimal scoreA;

    /**
     * 综合素质表现得分 C（分类封顶 + 总分封顶 8 分之后）。
     *
     * <p>为什么显式写 {@code @JsonProperty("cScore")}：Lombok 生成的 getter 是 {@code getCScore()}，
     * Jackson 的旧式命名推导会把开头连续大写压成小写 ⇒ 默认序列化成 {@code "cscore"}，
     * 而前端页面与 E2E 都按 {@code cScore} 取值（A/C/μ/B/M 五项同列展示）。显式命名把这个坑钉死。</p>
     */
    @JsonProperty("cScore")
    private BigDecimal cScore;

    /** 本专业年级的 μ（已钳制到 [mu_min, mu_max]） */
    private BigDecimal mu;

    /** 综合素质 B = C × μ */
    private BigDecimal scoreB;

    /** 综合测评 M = A + B */
    private BigDecimal scoreM;

    /** 专业内排名（M 降序，同分并列 1,2,2,4） */
    private Integer rankInMajor;

    /** 同专业同年级排名 */
    private Integer rankInGradeMajor;

    /** 本专业已排名人数 */
    private Integer totalInMajor;

    /** t1 / t2 / t3 / null（无名额） */
    private String awardTier;

    /** 是否具备获奖学金资格 */
    private Integer qualified;

    private LocalDateTime createdAt;

    // ---- 展示用的非表字段（JOIN 或服务端推导） ----

    private String studentName;

    private String studentNo;

    /** 学号后四位（公示脱敏口径） */
    private String studentNoTail;

    /** 奖学金等级中文：一等奖学金 / 二等奖学金 / 三等奖学金，无名额为 null */
    private String awardTierText;
}
