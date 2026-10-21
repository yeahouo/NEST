package com.umi.nest.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学工 P14 心理健康 · 量表/问卷定义（psyche_instrument）。
 *
 * <p>题本（itemJson）与分档阈值（bandJson）都由学校导入，平台只按 bandJson 折算 score_band，
 * 不做任何临床解释；SCL-90 / SDS 只留记录位（itemJson 为 NULL）。</p>
 */
@Data
public class PsycheInstrument {

    private Long id;

    /** 量表编码，如 demo-self-rating / scl90 / sds（唯一） */
    private String code;

    private String name;

    /** 出处说明（是否有公开题本、是否非临床量表） */
    private String sourceNote;

    /** 题本 JSON：[{"no":1,"text":"…","options":[{"value":0,"label":"没有"},…]}, …] */
    private String itemJson;

    /** 分档阈值 JSON：[{"band":"normal","max":10},…] */
    private String bandJson;

    /** 题目数（由 itemJson 汇总，供「必须答完」校验） */
    private Integer itemCount;

    /** 作答提示，如「按最近 2 周左右的实际状况填写」 */
    private String guideText;

    /** 1 启用 / 0 停用 */
    private Integer active;

    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
}
