package com.umi.nest.dorm.explain;

import java.util.List;

/**
 * 申诉试算结果。核心设计是"<b>把搜索范围摊开给你看</b>"：
 * 我们不能承诺"全局无解"，只能说"在我搜过的这个范围里没找到"。
 * 所以每一份结果都必须带上 {@code scope}（搜了多大范围、花了多久）
 * 和三级结论之一：
 *
 * <ul>
 *   <li>{@code SATISFIED} —— 找到了满足你诉求的改法（附具体动作和代价）；</li>
 *   <li>{@code UNSATISFIABLE} —— 在你的硬约束下<b>根本不可能</b>满足（例如你要同住的人异性/年级不同）；</li>
 *   <li>{@code NOT_FOUND_IN_SCOPE} —— 约束上没矛盾，但搜过的范围里没找到不超预算的改法。</li>
 * </ul>
 *
 * <p>{@code totalCostDelta} 是这次改动让目标函数变化多少（负数=变差）。
 * 申诉的代价预算是硬上限：超过预算宁可回"没找到"，也不偷偷牺牲别人。
 */
public record AppealResult(
        String status,
        String message,
        String scope,
        List<Swap> suggestions,
        double totalCostDelta,
        double costBudget,
        long elapsedMs) {

    public static final String SATISFIED = "SATISFIED";
    public static final String UNSATISFIABLE = "UNSATISFIABLE";
    public static final String NOT_FOUND_IN_SCOPE = "NOT_FOUND_IN_SCOPE";

    public boolean hasSuggestion() {
        return SATISFIED.equals(status) && suggestions != null && !suggestions.isEmpty();
    }

    /**
     * 一次具体的人员变动。<b>永远写成"两人互换"</b>（哪怕对面是空床位，也记对方为 -1），
     * 这样学生看到的是一句人话："你和张三对调"，而不是"系统重算了"。
     */
    public record Swap(
            long studentId,
            String studentName,
            long fromRoom,
            long toRoom,
            String fromLabel,
            String toLabel,
            long otherId,
            String otherName,
            double delta,
            String note) {
    }
}
