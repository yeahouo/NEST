package com.umi.nest.dorm.eval;

/**
 * 一次实验的全部指标。
 *
 * <p>关键约定：<b>所有"相容度"类指标都在隐真值 g 上计算</b>，
 * 算法永远看不到 g。这正是本课题实验可信度的来源——
 * 否则就是"用一个自己定义的分数证明自己算得好"。
 *
 * <p>{@code basis} 字段把这件事写成数据而不是注释：跑合成数据时是
 * {@link #BASIS_TRUTH}，跑真实学生数据时<b>没有隐真值可对</b>，
 * 只能退化成 {@link #BASIS_MODEL}（算法自己预测的相容度）。
 * 后者本质是"自己给自己打分"，必须在任何报告里显式标注，
 * 不能和真值指标混在一张表里比。
 */
public record EvaluationMetrics(
        String solver,
        String basis,
        int students,
        int rooms,
        /** 全体室友对的平均真值相容度（越高越好）。 */
        double meanCompat,
        /** 最差那一对室友的真值相容度（木桶指标，越高越好）。 */
        double worstCompat,
        /** 各房间内部相容度方差的平均值（越低说明"每间房都还行"，避免好坏极端）。 */
        double meanRoomVariance,
        /** 志愿满足率（0..1）。 */
        double preferenceSatisfaction,
        /** 阻塞对数：互相点名却没住一起（越低越好，核心指标）。 */
        int blockingPairs,
        /** 硬约束违规数，必须为 0。 */
        int hardViolations,
        /** 未分配人数，必须为 0。 */
        int unassigned,
        /** 各专业大类"平均满意度"的最大差距（公平性，越低越好）。 */
        double fairnessSpreadMajor,
        /** 各年级平均满意度的最大差距。 */
        double fairnessSpreadGrade,
        /** 全体学生平均满意度（与该房间友的真值相容度均值）。 */
        double meanStudentSatisfaction,
        long runtimeMs) {

    /** 指标算在隐真值 g 上（合成数据专用，可信）。 */
    public static final String BASIS_TRUTH = "隐真值g";
    /** 指标算在算法自己的相容度模型上（真实数据只能如此，不可与外面对比）。 */
    public static final String BASIS_MODEL = "模型预测值";

    public boolean truthBased() {
        return BASIS_TRUTH.equals(basis);
    }

    public static String header() {
        return String.format("%-22s %-10s %8s %9s %9s %8s %8s %7s %9s %8s %8s %10s",
                "solver", "指标基准", "n", "均值相容", "最差相容", "房内方差", "志愿满足", "阻塞对", "违规", "未分配", "公平差距", "耗时ms");
    }

    public String row() {
        return String.format("%-22s %-10s %8d %9.4f %9.4f %8.4f %8.4f %7d %9d %8d %8.4f %10d",
                solver, basis, students, meanCompat, worstCompat, meanRoomVariance,
                preferenceSatisfaction, blockingPairs, hardViolations, unassigned,
                fairnessSpreadMajor, runtimeMs);
    }

    public String summary() {
        return String.format(
                "%s[%s]: 均值相容=%.4f 最差=%.4f 方差=%.4f 志愿=%.3f 阻塞对=%d 违规=%d 用时=%dms",
                solver, basis, meanCompat, worstCompat, meanRoomVariance, preferenceSatisfaction,
                blockingPairs, hardViolations, runtimeMs);
    }
}
