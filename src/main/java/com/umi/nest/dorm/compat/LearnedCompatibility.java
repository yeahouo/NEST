package com.umi.nest.dorm.compat;

import com.umi.nest.dorm.data.GroundTruth;
import com.umi.nest.dorm.data.SyntheticDataset;
import com.umi.nest.dorm.model.CompatBreakdown;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.model.StudentFeature;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习型相容度基线——方案 3（双塔 / GNN 学习增强）的最简落地。
 *
 * <p>这不是双塔也不是 GNN，而是<b>线性岭回归</b>：把一对学生编码成 11 维 pairwise 特征
 * {@code φ(a,b)}，从合成数据学出权重 {@code β}，使 {@code β·φ} 尽量逼近隐真值
 * {@link GroundTruth#trueCompat}。它是「学习型相容度」插拔点的第一个非规则实现，
 * 用来回答两个问题：
 * <ol>
 *   <li>把「手写权重 + 手写维度函数」换成「从数据学权重」，在隐真值上到底能好多少；</li>
 *   <li>{@link CompatibilityFunction} 接口是否真的能无痛换上一个非规则实现（不改求解器、不改评估器）。</li>
 * </ol>
 *
 * <p><b>特征设计</b>：前 8 个与 {@link RuleBasedCompatibility} 的维度逐一对齐（同一套
 * 维度函数，见 {@link #phi}），后 3 个是规则模型<b>刻意没有</b>、但隐真值里真实存在的项：
 * <ul>
 *   <li>学业拼搏差异（隐真值有 0.12 权重，规则模型完全忽略）；</li>
 *   <li>作息交互项（早起者 × 晚睡者，隐真值里额外 -0.55，规则模型没有）；</li>
 *   <li>卫生超线性（隐真值用 |Δ|^1.6，规则模型用线性 |Δ|）。</li>
 * </ul>
 * 于是「学习型比规则型好多少」恰好量出了规则模型放弃这些项的真实代价。
 *
 * <p><b>训练</b>：在合成数据上把所有同性别池里的学生两两配对，目标是
 * {@link GroundTruth#trueCompat}，闭式解 {@code β = (XᵀX + λI)⁻¹Xᵀy}，纯 Java、
 * 无任何外部依赖（不引矩阵库）。11×11 的线性方程组用列主元高斯消元解。
 *
 * <p><b>缺失语义与规则模型一致</b>：任一侧某维是 NaN，该维贡献 0（未知 = 中性），
 * 见 {@link #phi} 复用 {@link RuleBasedCompatibility} 的维度函数（它们内部都判 NaN）。
 *
 * <p><b>诚实边界</b>：隐真值里还有两个<b>未观测隐变量</b>（人际磁场、真实吸烟容忍度），
 * 观测特征里根本没有它们，因此学习型模型无论怎么学都有天花板——这正是它和双塔 / GNN
 * 之间的差距所在，也是「学习增强」下一步要逼近的地方。
 */
public final class LearnedCompatibility implements CompatibilityFunction {

    public static final String NAME = "learned-linear-ridge-v1";
    public static final double DEFAULT_LAMBDA = 1e-3;

    /** 11 个 pairwise 特征：前 8 个对齐规则模型，后 3 个是学习型额外项。 */
    public static final int FEATURE_DIM = 11;

    /** 特征名（报告 / 解释用），下标与 {@link #phi} 返回数组一一对应。 */
    public static final String[] FEATURE_LABELS = {
            "作息", "吸烟", "卫生(线性)", "外向", "学习噪声", "游戏噪声",
            "社交", "共享", "学业拼搏", "作息交互", "卫生(非线性)"
    };

    private final Map<Long, StudentFeature> features;
    private final double[] beta;

    /**
     * @param observations 打分时读的特征表（通常是某个数据集 / 问题实例的观测）
     * @param beta         学习到的 11 维权向量（{@link #fit} 的输出）
     */
    public LearnedCompatibility(Map<Long, StudentFeature> observations, double[] beta) {
        if (beta.length != FEATURE_DIM) {
            throw new IllegalArgumentException("β 维度应为 " + FEATURE_DIM + "，实际 " + beta.length);
        }
        this.features = new LinkedHashMap<>(observations);
        this.beta = beta.clone();
    }

    /** 学习到的权重（副本），供报告 / 解释 / 消融使用。 */
    public double[] weights() {
        return beta.clone();
    }

    // ------------------------------------------------------------------ 接口

    @Override
    public double score(long studentA, long studentB) {
        StudentFeature x = features.get(studentA);
        StudentFeature y = features.get(studentB);
        if (x == null || y == null) {
            return 0.0;
        }
        return clamp(rawScore(x, y), -1.0, 1.0);
    }

    @Override
    public CompatBreakdown explain(long studentA, long studentB) {
        StudentFeature x = features.get(studentA);
        StudentFeature y = features.get(studentB);
        if (x == null || y == null) {
            return new CompatBreakdown(0.0, Map.of());
        }
        double[] phi = phi(x, y);
        Map<String, Double> dims = new LinkedHashMap<>();
        double total = 0.0;
        for (int i = 0; i < FEATURE_DIM; i++) {
            double contribution = beta[i] * phi[i];
            dims.put(FEATURE_LABELS[i], contribution);
            total += contribution;
        }
        // 与 GroundTruth 同口径：score 夹到 [-1,1]，解释层返回未夹的 Σdims（Σdims == total 恒成立）。
        return new CompatBreakdown(total, dims);
    }

    @Override
    public String name() {
        return NAME;
    }

    // ------------------------------------------------------------------ 训练

    /** 从一组合成数据学出 11 维权向量。 */
    public static double[] fit(SyntheticDataset ds, double lambda) {
        return fit(ds.problem().students(), ds.problem().observations(), ds.truth(), lambda);
    }

    /**
     * 岭回归闭式解。
     *
     * @param students 学生列表（训练样本的「人口」）
     * @param obs      观测特征表（算法实际看得到的东西）
     * @param truth    隐真值（训练目标）
     * @param lambda   L2 正则强度
     */
    public static double[] fit(List<Student> students, Map<Long, StudentFeature> obs,
                               GroundTruth truth, double lambda) {
        double[][] xtx = new double[FEATURE_DIM][FEATURE_DIM];
        double[] xty = new double[FEATURE_DIM];
        int n = students.size();
        for (int i = 0; i < n; i++) {
            StudentFeature fa = obs.get(students.get(i).id());
            if (fa == null) {
                continue;
            }
            for (int j = i + 1; j < n; j++) {
                StudentFeature fb = obs.get(students.get(j).id());
                if (fb == null) {
                    continue;
                }
                double[] phi = phi(fa, fb);
                double y = truth.trueCompat(students.get(i).id(), students.get(j).id());
                for (int r = 0; r < FEATURE_DIM; r++) {
                    for (int c = 0; c < FEATURE_DIM; c++) {
                        xtx[r][c] += phi[r] * phi[c];
                    }
                    xty[r] += phi[r] * y;
                }
            }
        }
        return ridgeSolve(xtx, xty, lambda);
    }

    /** 训练并绑定到该数据集的观测，得到一个可直接打分的模型。 */
    public static LearnedCompatibility fitAndBind(SyntheticDataset ds, double lambda) {
        return new LearnedCompatibility(ds.problem().observations(), fit(ds, lambda));
    }

    // ------------------------------------------------------------------ 特征

    /** 一对学生的 11 维特征向量。任一侧缺失的维度贡献 0（复用规则模型的 NaN 语义）。 */
    public static double[] phi(StudentFeature a, StudentFeature b) {
        return new double[]{
                RuleBasedCompatibility.dimSleep(a, b),
                RuleBasedCompatibility.dimSimilar(a.smoking(), b.smoking()),
                RuleBasedCompatibility.dimSimilar(a.cleanliness(), b.cleanliness()),
                RuleBasedCompatibility.dimSimilar(a.extroversion(), b.extroversion()),
                RuleBasedCompatibility.dimQuiet(a.studyInRoom(), b.noiseTolerance(), b.studyInRoom(), a.noiseTolerance()),
                RuleBasedCompatibility.dimQuiet(a.gaming(), b.noiseTolerance(), b.gaming(), a.noiseTolerance()),
                RuleBasedCompatibility.dimSimilar(a.socialActivity(), b.socialActivity()),
                RuleBasedCompatibility.dimSimilar(a.shareItems(), b.shareItems()),
                RuleBasedCompatibility.dimSimilar(a.ambition(), b.ambition()),
                sleepInteraction(a, b),
                cleanPower(a, b)
        };
    }

    /** 作息交互：早起者 × 晚睡者且作息明显不同 → 1（隐真值里的 -0.55 项，规则模型没有）。 */
    static double sleepInteraction(StudentFeature a, StudentFeature b) {
        if (!RuleBasedCompatibility.bothKnown(a.sleepStart(), b.sleepStart())
                || !RuleBasedCompatibility.bothKnown(a.sleepEnd(), b.sleepEnd())) {
            return 0.0;
        }
        double ds = Math.abs(a.sleepStart() - b.sleepStart()) + 0.5 * Math.abs(a.sleepEnd() - b.sleepEnd());
        boolean earlyLate = (a.sleepEnd() < 7.5 && b.sleepStart() > 24.5)
                || (b.sleepEnd() < 7.5 && a.sleepStart() > 24.5);
        return (earlyLate && ds > 1.5) ? 1.0 : 0.0;
    }

    /** 卫生超线性：1 - 2|Δ|^1.6（隐真值用 ^1.6，规则模型用线性 ^1）。 */
    static double cleanPower(StudentFeature a, StudentFeature b) {
        if (!RuleBasedCompatibility.bothKnown(a.cleanliness(), b.cleanliness())) {
            return 0.0;
        }
        double d = Math.abs(clip01(a.cleanliness()) - clip01(b.cleanliness()));
        return 1.0 - 2.0 * Math.pow(d, 1.6);
    }

    // ------------------------------------------------------------------ 内部

    private double rawScore(StudentFeature a, StudentFeature b) {
        double[] phi = phi(a, b);
        double s = 0.0;
        for (int i = 0; i < FEATURE_DIM; i++) {
            s += beta[i] * phi[i];
        }
        return s;
    }

    /** 解 (XᵀX + λI)β = Xᵀy，列主元高斯消元。 */
    static double[] ridgeSolve(double[][] xtx, double[] xty, double lambda) {
        int n = FEATURE_DIM;
        double[][] a = new double[n][n + 1];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                a[r][c] = xtx[r][c] + (r == c ? lambda : 0.0);
            }
            a[r][n] = xty[r];
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) {
                    piv = r;
                }
            }
            if (piv != col) {
                double[] tmp = a[col];
                a[col] = a[piv];
                a[piv] = tmp;
            }
            double pv = a[col][col];
            if (Math.abs(pv) < 1e-12) {
                continue;
            }
            for (int r = 0; r < n; r++) {
                if (r == col) {
                    continue;
                }
                double factor = a[r][col] / pv;
                for (int c = col; c <= n; c++) {
                    a[r][c] -= factor * a[col][c];
                }
            }
        }
        double[] beta = new double[n];
        for (int r = 0; r < n; r++) {
            beta[r] = Math.abs(a[r][r]) < 1e-12 ? 0.0 : a[r][n] / a[r][r];
        }
        return beta;
    }

    private static double clip01(double v) {
        if (Double.isNaN(v)) {
            return 0.5;
        }
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
