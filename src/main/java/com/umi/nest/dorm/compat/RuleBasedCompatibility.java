package com.umi.nest.dorm.compat;

import com.umi.nest.dorm.config.CompatWeights;
import com.umi.nest.dorm.model.CompatBreakdown;
import com.umi.nest.dorm.model.StudentFeature;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 默认相容度模型：八个维度的加权和。
 * <p>每个维度总分都落在 [-1, 1]：
 * <ul>
 *   <li>相似型（吸烟、卫生、外向、社交、共享）：{@code 1 - 2|Δ|}</li>
 *   <li>作息：{@code 1 - 2 * min(1, (|Δ入睡| + 0.5|Δ起床|) / 5)}</li>
 *   <li>噪声型（学习/游戏）：双向不对称冲突取和，{@code 1 - (c1 + c2)}，
 *       其中 {@code c = max(0, 声源强度 - 对方容忍度)}</li>
 * </ul>
 * <p><b>这个模型刻意做得比隐真值简单</b>——它只有主效应，没有交互项，
 * 也没有隐变量。这样评估阶段才能量出"算法只能优化一个代理指标"的真实代价。
 *
 * <p><b>缺失维度的语义是「未知 = 中性」</b>：某一位是 NaN（学生没答这一题）时，
 * 该维度对这组相容度的贡献是 {@code 0}，既不因为"不知道"加分、也不减分，
 * 分数只由双方都答过的维度决定。这样做的两个理由：
 * <ol>
 *   <li><b>不制造假证据</b>。早期用总体均值补齐缺失位，两个都没回答「吸烟」的学生
 *       会被算成差异 0.00、在解释里显示成「很合」——那是在断言一件谁都不知道的事。</li>
 *   <li><b>不给"少答题"留捷径</b>。均值补齐会让"答得越少越容易被算成合拍"。</li>
 * </ol>
 * 注意这条与"整行都没有特征行"的处理是同一个原则：那种学生不在这张表里，
 * {@link #score} 的 null 守卫直接返回 0——两种缺失在语义上终于一致了。
 */
public final class RuleBasedCompatibility implements CompatibilityFunction {

    private static final double SLEEP_NORM = 5.0;

    private final Map<Long, StudentFeature> features;
    private final CompatWeights w;

    public RuleBasedCompatibility(Map<Long, StudentFeature> rawFeatures, CompatWeights weights) {
        this.w = (weights == null ? CompatWeights.defaults() : weights).normalized();
        // 刻意不做均值补齐，原样保留 NaN（缺失的维度）。语义见类注释。
        // 这里删掉过一行 `StudentFeature.imputed(mean)`：它把每一位缺失都填成总体均值，
        // 于是所有没答同一道题的学生都变成"完全相同"，差异 0.00、满权重的"很合"。
        this.features = new LinkedHashMap<>(rawFeatures);
    }

    public RuleBasedCompatibility(Map<Long, StudentFeature> rawFeatures) {
        this(rawFeatures, CompatWeights.defaults());
    }

    public Set<Long> knownStudents() {
        return features.keySet();
    }

    public StudentFeature featureOf(long id) {
        return features.get(id);
    }

    public CompatWeights weights() {
        return w;
    }

    /** 收集一组学生，用于构造评估/解释所需的特征视图。 */
    public static Map<Long, StudentFeature> index(Collection<com.umi.nest.dorm.model.Student> students,
                                                  Map<Long, StudentFeature> obs) {
        Map<Long, StudentFeature> m = new LinkedHashMap<>(students.size() * 2);
        for (com.umi.nest.dorm.model.Student s : students) {
            StudentFeature f = obs.get(s.id());
            if (f != null) {
                m.put(s.id(), f);
            }
        }
        return m;
    }

    @Override
    public double score(long studentA, long studentB) {
        StudentFeature a = features.get(studentA);
        StudentFeature b = features.get(studentB);
        if (a == null || b == null) {
            return 0.0;
        }
        return w.sleep() * dimSleep(a, b)
                + w.smoking() * dimSimilar(a.smoking(), b.smoking())
                + w.cleanliness() * dimSimilar(a.cleanliness(), b.cleanliness())
                + w.extroversion() * dimSimilar(a.extroversion(), b.extroversion())
                + w.studyNoise() * dimQuiet(a.studyInRoom(), b.noiseTolerance(), b.studyInRoom(), a.noiseTolerance())
                + w.gamingNoise() * dimQuiet(a.gaming(), b.noiseTolerance(), b.gaming(), a.noiseTolerance())
                + w.social() * dimSimilar(a.socialActivity(), b.socialActivity())
                + w.sharing() * dimSimilar(a.shareItems(), b.shareItems());
    }

    @Override
    public CompatBreakdown explain(long studentA, long studentB) {
        StudentFeature a = features.get(studentA);
        StudentFeature b = features.get(studentB);
        if (a == null || b == null) {
            return new CompatBreakdown(0.0, Map.of());
        }
        Map<String, Double> dims = new LinkedHashMap<>();
        dims.put(CompatDimensions.SLEEP, w.sleep() * dimSleep(a, b));
        dims.put(CompatDimensions.SMOKING, w.smoking() * dimSimilar(a.smoking(), b.smoking()));
        dims.put(CompatDimensions.CLEANLINESS, w.cleanliness() * dimSimilar(a.cleanliness(), b.cleanliness()));
        dims.put(CompatDimensions.EXTROVERSION, w.extroversion() * dimSimilar(a.extroversion(), b.extroversion()));
        dims.put(CompatDimensions.STUDY_NOISE,
                w.studyNoise() * dimQuiet(a.studyInRoom(), b.noiseTolerance(), b.studyInRoom(), a.noiseTolerance()));
        dims.put(CompatDimensions.GAMING_NOISE,
                w.gamingNoise() * dimQuiet(a.gaming(), b.noiseTolerance(), b.gaming(), a.noiseTolerance()));
        dims.put(CompatDimensions.SOCIAL, w.social() * dimSimilar(a.socialActivity(), b.socialActivity()));
        dims.put(CompatDimensions.SHARING, w.sharing() * dimSimilar(a.shareItems(), b.shareItems()));
        double total = 0;
        for (double v : dims.values()) {
            total += v;
        }
        return new CompatBreakdown(total, dims);
    }

    @Override
    public String name() {
        return "rule-based-weighted-v1";
    }

    // ---------------- 维度函数 ----------------

    /** 这一维是否已知。解释层用它决定报"很合/一般"还是"未知"。 */
    public static boolean known(double v) {
        return !Double.isNaN(v);
    }

    /** 双方这一维都已知，才谈得上相容或冲突。 */
    public static boolean bothKnown(double x, double y) {
        return !Double.isNaN(x) && !Double.isNaN(y);
    }

    /** 任一侧未知 → 0.0（中性）。理由见类注释。 */
    static double dimSimilar(double x, double y) {
        if (!bothKnown(x, y)) {
            return 0.0;
        }
        return 1.0 - 2.0 * Math.abs(clip01(x) - clip01(y));
    }

    static double dimSleep(StudentFeature a, StudentFeature b) {
        if (!bothKnown(a.sleepStart(), b.sleepStart()) || !bothKnown(a.sleepEnd(), b.sleepEnd())) {
            return 0.0;
        }
        double d = Math.abs(a.sleepStart() - b.sleepStart()) + 0.5 * Math.abs(a.sleepEnd() - b.sleepEnd());
        return 1.0 - 2.0 * Math.min(1.0, d / SLEEP_NORM);
    }

    /** @param srcA A 的"声源"强度；@param tolB B 的容忍度；@param srcB B 的声源强度；@param tolA A 的容忍度 */
    static double dimQuiet(double srcA, double tolB, double srcB, double tolA) {
        if (!bothKnown(srcA, tolB) || !bothKnown(srcB, tolA)) {
            return 0.0;
        }
        double c1 = Math.max(0.0, clip01(srcA) - clip01(tolB));
        double c2 = Math.max(0.0, clip01(srcB) - clip01(tolA));
        return 1.0 - (c1 + c2);
    }

    private static double clip01(double v) {
        if (Double.isNaN(v)) {
            return 0.5;
        }
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
