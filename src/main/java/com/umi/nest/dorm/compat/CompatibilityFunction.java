package com.umi.nest.dorm.compat;

import com.umi.nest.dorm.model.CompatBreakdown;

/**
 * 相容度函数：给一对学生打分。
 * <p>做成接口是为了留出"学习型相容度"的插拔点（设计文档 MoSCoW 的 Could 项）：
 * 只要实现这个接口，求解器与评估器都不需要改动。
 * <p>约定：
 * <ul>
 *   <li>对称：{@code score(a,b) == score(b,a)}</li>
 *   <li>值域 [-1, 1]，-1 = 最差（天天吵架），1 = 最理想</li>
 *   <li>特征缺失时由实现自行补齐，绝不返回 NaN</li>
 * </ul>
 */
public interface CompatibilityFunction {

    double score(long studentA, long studentB);

    CompatBreakdown explain(long studentA, long studentB);

    /** 求解器/报告里显示的相容度模型名。 */
    String name();

    /** 一对学生若从未被观测，返回默认值 0（中性）。 */
    default double scoreOrNeutral(long a, long b) {
        double s = score(a, b);
        return Double.isNaN(s) ? 0.0 : s;
    }
}
