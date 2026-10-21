package com.umi.nest.dorm.config;

/**
 * 八个软特征的权重（≡ 各维在相容度里的重要性）。
 * <p>设计上是"可配置"的核心：换一所学校只需换这份权重与下面的硬约束开关，
 * 不需要改任何求解器代码。
 * <p>默认权重的依据见设计文档 §6：作息与吸烟是宿舍矛盾的两大主因，权重最高；
 * 卫生次之；性格/社交/共享属于长期相处的影响因素。
 */
public record CompatWeights(
        double sleep,
        double smoking,
        double cleanliness,
        double extroversion,
        double studyNoise,
        double gamingNoise,
        double social,
        double sharing
) {
    public static CompatWeights defaults() {
        return new CompatWeights(0.20, 0.20, 0.14, 0.10, 0.14, 0.10, 0.06, 0.06);
    }

    public double sum() {
        return sleep + smoking + cleanliness + extroversion + studyNoise + gamingNoise + social + sharing;
    }

    /** 归一化，保证权重的合法性（避免调用方填了不求和为 1 的数）。 */
    public CompatWeights normalized() {
        double s = sum();
        if (s <= 0) {
            return defaults();
        }
        return new CompatWeights(sleep / s, smoking / s, cleanliness / s, extroversion / s,
                studyNoise / s, gamingNoise / s, social / s, sharing / s);
    }
}
