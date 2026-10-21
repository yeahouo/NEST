package com.umi.nest.dto;

import lombok.Data;

import java.util.List;

/**
 * AI 预批改的请求体。两种用法：
 * <ul>
 *   <li>{@code action = "suggest"}（默认）：只让模型给出建议分与理由，不写人工分；</li>
 *   <li>{@code action = "apply"}：把上一次 suggest 存在库里的建议采纳为人工分与评语。</li>
 * </ul>
 * 拆成两步是有意的：AI 给的分必须经过一次「人手点一下」才会变成学生看到的成绩。
 * 一次调用既批又定分，老师就没有机会拦住明显离谱的结果。
 */
@Data
public class AiGradeDTO {

    private Long submissionId;

    /** suggest（默认）或 apply。 */
    private String action;

    /** 只处理这几条答案；留空表示这次作答里全部主观题。 */
    private List<Long> answerIds;

    /** suggest 时是否重算已有建议（默认跳过已批过的，省钱）。 */
    private Boolean force;

    /** 给模型的额外评卷要求，例如「证明过程比结果更重要」。 */
    private String rubric;
}
