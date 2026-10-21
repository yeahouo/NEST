package com.umi.nest.dorm.model;

import java.util.Collection;

/**
 * 学生软特征向量——也就是"问卷调查观测到的"那一组数值。
 * <p>所有分量都已归一化到 [0,1]，唯一例外是 sleepStart / sleepEnd（小时制）。
 * <p>缺失值统一用 {@link Double#NaN} 表示，语义是「未知 = 中性」：
 * {@link com.umi.nest.dorm.compat.RuleBasedCompatibility} 对任一侧缺失的维度贡献 0 分，
 * 既不因为「不知道」加分、也不减分，<b>刻意不做均值补齐</b>——把没答过的题填成总体均值，
 * 等于断言一件谁都不知道的事（两个都没回答「吸烟」的人会被算成差异 0.00、显示「很合」）。
 * <p>{@link #meanOf} 与 {@link #imputed} 只保留给「确实需要一个不含 NaN 的稠密向量」
 * 的老路径（如个别基线/诊断），<b>不再参与主求解流程</b>。
 *
 * @param sleepStart     入睡时刻，20.0 ~ 26.0（26 表示次日 2:00）
 * @param sleepEnd       起床时刻，5.0 ~ 13.0
 * @param smoking        吸烟倾向 0..1
 * @param cleanliness    整洁要求（洁癖程度）0..1
 * @param extroversion   外向程度 0..1
 * @param studyInRoom    在寝室内学习/需要安静的强度 0..1
 * @param noiseTolerance 对噪声的容忍度 0..1
 * @param socialActivity 社交活跃度（带朋友回寝、串门）0..1
 * @param shareItems     愿意共享物品/空间的程度 0..1
 * @param gaming         打游戏（尤其开麦）频率 0..1
 * @param ambition       学业拼搏程度（考研/保研）0..1
 */
public record StudentFeature(
        double sleepStart,
        double sleepEnd,
        double smoking,
        double cleanliness,
        double extroversion,
        double studyInRoom,
        double noiseTolerance,
        double socialActivity,
        double shareItems,
        double gaming,
        double ambition
) {
    /** 特征维度数，用于均值向量数组长度。 */
    public static final int DIM = 11;

    /**
     * 作息两维的兜底默认值，取真实分布中心。
     * <p>单独提成常量是因为它不只用在均值插补里：规则提取器在只知道
     * "他熬夜"却不知道具体钟点时，也要以这个基线为锚做小幅偏移。
     * 两处硬编码同一个数，改了一处忘了另一处就会出现两套基线。
     */
    public static final double SLEEP_START_DEFAULT = 23.5;
    public static final double SLEEP_END_DEFAULT = 8.0;

    public double[] toArray() {
        return new double[]{sleepStart, sleepEnd, smoking, cleanliness, extroversion,
                studyInRoom, noiseTolerance, socialActivity, shareItems, gaming, ambition};
    }

    public static StudentFeature of(double[] v) {
        if (v.length != DIM) {
            throw new IllegalArgumentException("特征维度应为 " + DIM + "，实际 " + v.length);
        }
        return new StudentFeature(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10]);
    }

    /** 统计一组特征的逐维均值；NaN 分量跳过。 */
    public static double[] meanOf(Collection<StudentFeature> list) {
        double[] sum = new double[DIM];
        int[] cnt = new int[DIM];
        for (StudentFeature f : list) {
            double[] v = f.toArray();
            for (int i = 0; i < DIM; i++) {
                if (!Double.isNaN(v[i])) {
                    sum[i] += v[i];
                    cnt[i]++;
                }
            }
        }
        double[] mean = new double[DIM];
        for (int i = 0; i < DIM; i++) {
            mean[i] = cnt[i] == 0 ? 0.5 : sum[i] / cnt[i];
        }
        // 作息两维的兜底默认值取真实分布中心
        if (cnt[0] == 0) {
            mean[0] = SLEEP_START_DEFAULT;
        }
        if (cnt[1] == 0) {
            mean[1] = SLEEP_END_DEFAULT;
        }
        return mean;
    }

    /** 用总体均值补齐缺失分量，返回一个不含 NaN 的特征。 */
    public StudentFeature imputed(double[] mean) {
        double[] v = toArray();
        for (int i = 0; i < DIM; i++) {
            if (Double.isNaN(v[i])) {
                v[i] = mean[i];
            }
        }
        return of(v);
    }

    /** 是否为"无观测"（全部缺失）。 */
    public boolean isAllMissing() {
        for (double v : toArray()) {
            if (!Double.isNaN(v)) {
                return false;
            }
        }
        return true;
    }
}
