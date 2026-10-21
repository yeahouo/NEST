package com.umi.nest.dorm.psyche;

/**
 * 证据：一个特征值凭什么这么定。
 *
 * <p>这是整个心理模块里最重要的一个类型。没有它，系统就是一个
 * "输入一段话、输出十一个数字"的黑箱——学生问"你凭什么觉得我外向"，
 * 只能回答"模型说的"。有了它，可以回答"因为你在自我介绍里写了
 * 『我比较喜欢一个人待着』，所以外向度判了 0.3，置信度 0.72"。
 *
 * <p>因此有一条硬规定：<b>没有证据就不给值</b>。提取器宁可返回
 * {@code NaN} 让下游走均值插补，也不允许在没有原文支撑的情况下
 * 猜一个数出来——猜出来的数字会被求解器当事实用，而它其实不是。
 *
 * @param dimension 目标维度（{@link PsycheDimension#label()}）
 * @param quote     原文片段；量表作答时是选项文字
 * @param reasoning 为什么这段原文能说明这一维
 * @param weight    该条证据的分量 0..1，多个证据加权合成最终值
 * @param source    证据来源：RULE / LLM / QUESTIONNAIRE
 */
public record Evidence(String dimension, String quote, String reasoning, double weight, String source) {

    public static final String SOURCE_RULE = "RULE";
    public static final String SOURCE_LLM = "LLM";
    public static final String SOURCE_QUESTIONNAIRE = "QUESTIONNAIRE";

    public Evidence {
        dimension = dimension == null ? "" : dimension;
        quote = quote == null ? "" : quote;
        reasoning = reasoning == null ? "" : reasoning;
        source = source == null ? SOURCE_RULE : source;
        weight = Math.max(0.0, Math.min(1.0, weight));
    }

    public static Evidence rule(String dimension, String quote, String reasoning, double weight) {
        return new Evidence(dimension, quote, reasoning, weight, SOURCE_RULE);
    }

    public static Evidence llm(String dimension, String quote, String reasoning, double weight) {
        return new Evidence(dimension, quote, reasoning, weight, SOURCE_LLM);
    }

    public static Evidence questionnaire(String dimension, String quote, String reasoning) {
        return new Evidence(dimension, quote, reasoning, 1.0, SOURCE_QUESTIONNAIRE);
    }

    /** 同维度多题取均值时，单题的分量低于 1.0。 */
    public static Evidence questionnaire(String dimension, String quote, String reasoning, double weight) {
        return new Evidence(dimension, quote, reasoning, weight, SOURCE_QUESTIONNAIRE);
    }
}
