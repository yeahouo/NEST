package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 第二课堂板块（学工 P18，S26）：second_class_category 表。
 *
 * <p><b>诚实边界</b>：S26（中青联发〔2018〕5 号，转载非一手）只规定「七个内容板块 + 可采学分式评价」，
 * <b>没有任何学时/学分数值</b>；S27 成长手册栏目全文无「第二课堂」字样，校内是否实施未能核实。
 * ⇒ 本表的 {@code requiredHours} / {@code yearlyCapHours} / {@code creditPerHour} <b>三列全部可 NULL</b>，
 * NULL 的含义是「学校还没定」，页面必须显示「未设置」，禁止用默认值冒充政策要求。</p>
 *
 * <p>{@code requiredHours} = 毕业/学年要求学时（学校自定）；{@code yearlyCapHours} = 单学年计入上限
 * （只做提示，不硬截断，见 {@code npm} / spec §4）；{@code creditPerHour} = 学时→学分换算系数。</p>
 *
 * <p>{@code enabled=0} 的板块不再接受新申报，历史记录仍保留（不得删除板块，防止台账断裂）。</p>
 */
@Data
public class SecondClassCategory {

    private Long id;

    /** 板块名称（学校自定；七板块名只作演示数据） */
    private String name;

    /** 板块编码（唯一，大写字母与下划线） */
    private String code;

    /** 排序号，小的在前 */
    private Integer sortNo;

    /** 毕业/学年要求学时；null = 学校未设置 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private BigDecimal requiredHours;

    /** 单学年计入上限学时；null = 学校未设置（只提示不截断） */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private BigDecimal yearlyCapHours;

    /** 学时→学分换算系数；null = 学校未设置 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private BigDecimal creditPerHour;

    /** 说明（学校填，可空） */
    private String note;

    /** 1 启用 / 0 停用 */
    private Integer enabled;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
