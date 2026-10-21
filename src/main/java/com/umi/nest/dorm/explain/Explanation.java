package com.umi.nest.dorm.explain;

import java.util.List;

/**
 * 一个学生的四维可解释报告。这是给<b>学生本人</b>看的，不是给管理员看的报表——
 * 所以每一维都必须回答一个具体问题，而不是罗列数字。
 *
 * <ol>
 *   <li>{@code constraints} 约束层：我受哪些硬规则约束？哪条规则真正决定了我的去向？</li>
 *   <li>{@code dimensions} 相容度层：我和室友在作息/吸烟/卫生/性格上具体差多少，各自贡献了多少分？</li>
 *   <li>{@code preferences} 志愿层：我想同住的人满足了吗？没满足是"因为什么"？</li>
 *   <li>{@code comparatives} 对比层：换一间房会差多少？为什么不能那么分？</li>
 * </ol>
 *
 * <p>这些内容<b>求解时就批量算好存起来</b>，不在学生点开页面时实时重算——
 * 万级数据实时重算的解释接口是扛不住的，而且解释必须和那一版分配严格对应。
 */
public record Explanation(
        long studentId,
        String studentName,
        long roomId,
        String roomLabel,
        String buildingId,
        Double roomMeanCompat,
        Double roomWorstCompat,
        /** 本房间有几对室友是**双方都有特征数据**、因而真的算得出相容度的。 */
        int evaluatedPairs,
        /** 本房间室友对数（{@code C(人数,2)}）。{@code evaluatedPairs < totalPairs} 时，
         *  未评估的那些对在总分里按 0 计——所以光看 roomMeanCompat 会低估。 */
        int totalPairs,
        List<DimensionNote> dimensions,
        List<PreferenceNote> preferences,
        List<ConstraintNote> constraints,
        List<ComparativeNote> comparatives,
        String narrative) {

    /**
     * JSON 里没有 {@code NaN} 这个值。Jackson 默认会把它写成<b>字符串</b> {@code "NaN"}，
     * 于是「这个房间只有你一个人、算不出平均相容度」在客户端变成一个字符串，
     * 按数字解析就炸；更糟的是它看上去像个测量值。
     * 非有限值一律落成 {@code null}，客户端判空即可。
     */
    private static Double finite(Double v) {
        return (v == null || !Double.isFinite(v)) ? null : v;
    }

    public Explanation {
        roomMeanCompat = finite(roomMeanCompat);
        roomWorstCompat = finite(roomWorstCompat);
    }

    /** 相容度层的一条：某个维度上"我"和"室友"的差、权重、对总分的贡献。 */
    public record DimensionNote(
            String dimension,
            long otherId,
            String otherName,
            Double mine,
            Double theirs,
            Double gap,
            double contribution,
            String comment) {

        public DimensionNote {
            mine = finite(mine);
            theirs = finite(theirs);
            gap = finite(gap);
        }
    }

    /** 志愿层的一条：想同住 / 不想同住，满足与否，以及未满足的原因。 */
    public record PreferenceNote(
            long otherId,
            String otherName,
            String kind,
            boolean satisfied,
            String reason) {
    }

    /** 约束层的一条：规则名、是否对本人生效（binding）、说明。 */
    public record ConstraintNote(String rule, boolean binding, String detail) {
    }

    /** 对比层的一条：把本人换到某个选择上，全楼目标会变多少。 */
    public record ComparativeNote(String option, double delta, String comment) {
    }
}
