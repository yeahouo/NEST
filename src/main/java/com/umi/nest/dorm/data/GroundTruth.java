package com.umi.nest.dorm.data;

import com.umi.nest.dorm.model.CompatBreakdown;
import com.umi.nest.dorm.model.StudentFeature;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 隐真值（ground truth）——<b>算法永远看不到的东西</b>。
 * <p>这是整个评估体系的基石：合成数据时先造出"真实特征 z"和"真实相容度 g(z_i,z_j)"，
 * 再把带噪声、带缺失的问卷观测 o 交给算法。求解器优化的是 o 上的代理指标，
 * 而所有实验指标都在 g 上计算，于是"代理指标优化得好看 ≠ 真实体验变好"这件事
 * 才会被如实暴露出来。
 * <p>真值 g 刻意比 {@code RuleBasedCompatibility} 更复杂：
 * <ol>
 *   <li>非线性：卫生冲突在真值里是超线性的（Δ^1.6）</li>
 *   <li>交互项：早睡者 × 晚睡者额外扣分（开灯/敲键盘这类真实痛点）</li>
 *   <li>未观测隐变量：吸烟容忍度、两人之间的"磁场"</li>
 *   <li>额外维度：学业拼搏程度差异</li>
 * </ol>
 */
public final class GroundTruth {

    private final Map<Long, StudentFeature> trueFeatures;
    /** 未观测隐变量：人际磁场，0..1，与任何问卷分量都不相关。 */
    private final Map<Long, Double> latentAffinity;
    /** 未观测隐变量：真实吸烟容忍度，0..1。 */
    private final Map<Long, Double> smokeTolerance;

    public GroundTruth(Map<Long, StudentFeature> trueFeatures,
                       Map<Long, Double> latentAffinity,
                       Map<Long, Double> smokeTolerance) {
        this.trueFeatures = Map.copyOf(trueFeatures);
        this.latentAffinity = Map.copyOf(latentAffinity);
        this.smokeTolerance = Map.copyOf(smokeTolerance);
    }

    public Set<Long> students() {
        return trueFeatures.keySet();
    }

    public StudentFeature trueFeatureOf(long id) {
        return trueFeatures.get(id);
    }

    public double latentOf(long id) {
        return latentAffinity.getOrDefault(id, 0.5);
    }

    public double smokeToleranceOf(long id) {
        return smokeTolerance.getOrDefault(id, 0.5);
    }

    // ---------------- 真值相容度 ----------------

    public double trueCompat(long a, long b) {
        StudentFeature x = trueFeatures.get(a);
        StudentFeature y = trueFeatures.get(b);
        if (x == null || y == null) {
            return 0.0;
        }
        double la = latentOf(a);
        double lb = latentOf(b);
        double ta = smokeToleranceOf(a);
        double tb = smokeToleranceOf(b);

        // 1) 作息：主效应
        double ds = Math.abs(x.sleepStart() - y.sleepStart()) + 0.5 * Math.abs(x.sleepEnd() - y.sleepEnd());
        double sSleep = 1.0 - 2.0 * Math.min(1.0, ds / 5.0);
        // 1b) 作息交互项：早起者 × 晚睡者 且作息明显不同 → 真值里额外重扣
        boolean earlyLate = (x.sleepEnd() < 7.5 && y.sleepStart() > 24.5) || (y.sleepEnd() < 7.5 && x.sleepStart() > 24.5);
        if (earlyLate && ds > 1.5) {
            sSleep -= 0.55;
        }

        // 2) 吸烟：用未观测的真实容忍度
        double c1 = Math.max(0.0, x.smoking() - tb);
        double c2 = Math.max(0.0, y.smoking() - ta);
        double sSmoke = 1.0 - 2.0 * (c1 + c2);

        // 3) 卫生：超线性
        double sClean = 1.0 - 2.0 * Math.pow(Math.abs(x.cleanliness() - y.cleanliness()), 1.6);

        // 4) 外向
        double sExt = 1.0 - 2.0 * Math.abs(x.extroversion() - y.extroversion());

        // 5) 安静（学习 / 游戏）
        double sStudy = 1.0 - (Math.max(0.0, x.studyInRoom() - y.noiseTolerance())
                + Math.max(0.0, y.studyInRoom() - x.noiseTolerance()));
        double sGame = 1.0 - (Math.max(0.0, x.gaming() - y.noiseTolerance())
                + Math.max(0.0, y.gaming() - x.noiseTolerance()));

        // 6) 社交 / 共享
        double sSocial = 1.0 - 2.0 * Math.abs(x.socialActivity() - y.socialActivity());
        double sShare = 1.0 - 2.0 * Math.abs(x.shareItems() - y.shareItems());

        // 7) 学业拼搏差异（算法模型里完全没有这一维）
        double sAmbition = 1.0 - 2.0 * Math.abs(x.ambition() - y.ambition());

        double s = 0.17 * sSleep
                + 0.19 * sSmoke
                + 0.13 * sClean
                + 0.08 * sExt
                + 0.12 * sStudy
                + 0.09 * sGame
                + 0.05 * sSocial
                + 0.05 * sShare
                + 0.12 * sAmbition;

        // 8) 隐变量交互：未观测的"磁场"——算法无论怎么学都学不到
        s += 0.16 * (2 * la - 1) * (2 * lb - 1);

        return clamp(s, -1.0, 1.0);
    }

    /** 真值版本的分解，用于回答"这对室友为什么真实体验差"。 */
    public CompatBreakdown explainTrue(long a, long b) {
        StudentFeature x = trueFeatures.get(a);
        StudentFeature y = trueFeatures.get(b);
        if (x == null || y == null) {
            return new CompatBreakdown(0.0, Map.of());
        }
        Map<String, Double> d = new LinkedHashMap<>();
        double ds = Math.abs(x.sleepStart() - y.sleepStart()) + 0.5 * Math.abs(x.sleepEnd() - y.sleepEnd());
        double sSleep = 1.0 - 2.0 * Math.min(1.0, ds / 5.0);
        boolean earlyLate = (x.sleepEnd() < 7.5 && y.sleepStart() > 24.5) || (y.sleepEnd() < 7.5 && x.sleepStart() > 24.5);
        if (earlyLate && ds > 1.5) {
            sSleep -= 0.55;
        }
        d.put("作息", 0.17 * sSleep);
        d.put("吸烟", 0.19 * (1.0 - 2.0 * (Math.max(0.0, x.smoking() - smokeToleranceOf(b))
                + Math.max(0.0, y.smoking() - smokeToleranceOf(a)))));
        d.put("卫生", 0.13 * (1.0 - 2.0 * Math.pow(Math.abs(x.cleanliness() - y.cleanliness()), 1.6)));
        d.put("性格外向度", 0.08 * (1.0 - 2.0 * Math.abs(x.extroversion() - y.extroversion())));
        d.put("安静/学习", 0.12 * (1.0 - (Math.max(0.0, x.studyInRoom() - y.noiseTolerance())
                + Math.max(0.0, y.studyInRoom() - x.noiseTolerance()))));
        d.put("游戏噪声", 0.09 * (1.0 - (Math.max(0.0, x.gaming() - y.noiseTolerance())
                + Math.max(0.0, y.gaming() - x.noiseTolerance()))));
        d.put("社交活跃度", 0.05 * (1.0 - 2.0 * Math.abs(x.socialActivity() - y.socialActivity())));
        d.put("物品共享", 0.05 * (1.0 - 2.0 * Math.abs(x.shareItems() - y.shareItems())));
        d.put("学业拼搏", 0.12 * (1.0 - 2.0 * Math.abs(x.ambition() - y.ambition())));
        d.put("未观测磁场", 0.16 * (2 * latentOf(a) - 1) * (2 * latentOf(b) - 1));
        double total = 0;
        for (double v : d.values()) {
            total += v;
        }
        return new CompatBreakdown(total, d);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
