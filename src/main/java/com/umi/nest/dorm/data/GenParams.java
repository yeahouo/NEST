package com.umi.nest.dorm.data;

/**
 * 合成数据生成参数。实验矩阵的四个维度（规模 / 噪声 / 缺失 / 志愿紧张度）都在这里。
 *
 * @param studentCount       学生总数
 * @param roomCapacity       标准房间容量（4 人间）
 * @param noiseLevel         问卷噪声强度：观测 = 真值 + N(0, noiseLevel * 尺度)
 * @param missingRate        每个特征分量被"漏填"的概率（0 = 无缺失）
 * @param preferenceTension  志愿紧张度 0..1：越高越容易出现"互相想住一起"的强簇，
 *                           也越容易产生阻塞对
 * @param preferenceCount    每人填写的希望同住人数
 * @param specialNeedRate    特殊需求学生比例
 * @param seed               随机种子（复现性）
 * @param gradeMix           是否允许多年级混合（false 时按年级分块，等价于"年级不混住"）
 * @param buildingCount      每种性别分配的楼栋数
 */
public record GenParams(
        int studentCount,
        int roomCapacity,
        double noiseLevel,
        double missingRate,
        double preferenceTension,
        int preferenceCount,
        double specialNeedRate,
        long seed,
        boolean gradeMix,
        int buildingCount
) {
    public static GenParams defaults(int studentCount, long seed) {
        return new GenParams(studentCount, 4, 0.20, 0.0, 0.5, 3, 0.03, seed, true, 3);
    }

    public GenParams withStudentCount(int n) {
        return new GenParams(n, roomCapacity, noiseLevel, missingRate, preferenceTension,
                preferenceCount, specialNeedRate, seed, gradeMix, buildingCount);
    }

    public GenParams withSeed(long s) {
        return new GenParams(studentCount, roomCapacity, noiseLevel, missingRate, preferenceTension,
                preferenceCount, specialNeedRate, s, gradeMix, buildingCount);
    }

    /** 实验用的可读标签，例如 {@code n10000_noise0.20_miss0.00_tension0.50_s1}。 */
    public String label() {
        return String.format("n%d_cap%d_noise%.2f_miss%.2f_tension%.2f_special%.2f_grademix%s_s%d",
                studentCount, roomCapacity, noiseLevel, missingRate, preferenceTension,
                specialNeedRate, gradeMix, seed);
    }
}
