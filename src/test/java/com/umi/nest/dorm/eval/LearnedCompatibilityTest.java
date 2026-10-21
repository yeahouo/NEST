package com.umi.nest.dorm.eval;

import com.umi.nest.dorm.compat.LearnedCompatibility;
import com.umi.nest.dorm.compat.RuleBasedCompatibility;
import com.umi.nest.dorm.data.GenParams;
import com.umi.nest.dorm.data.SyntheticDataGenerator;
import com.umi.nest.dorm.data.SyntheticDataset;
import com.umi.nest.dorm.model.CompatBreakdown;
import com.umi.nest.dorm.model.StudentFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 学习型相容度基线的护栏测试。
 *
 * <p>最要紧的三条：① 学习型在隐真值上确实<b>优于</b>规则型（否则「学习增强」无从谈起）；
 * ② 它学到了规则模型缺失的三个项（学业拼搏、作息交互、卫生非线性）；③ 前 8 个特征
 * 与规则模型逐项一致（一旦有人改了规则维度函数而忘了同步学习型，这里会红）。
 */
class LearnedCompatibilityTest {

    private static final double EPS = 1e-9;

    // ------------------------------------------------------------------ 质量

    @Test
    @DisplayName("学习型在隐真值上 MAE 更低、相关更高（训练/测试不同种子）")
    void learnedBeatsRuleOnTruth() {
        LearnedCompatExperiment.RunResult r = LearnedCompatExperiment.run(240, 240, 20261021L,
                LearnedCompatibility.DEFAULT_LAMBDA);
        // 打印真实读数，便于回归时核对（不参与断言）
        System.out.println("[learned-compat] " + r.rule().summary());
        System.out.println("[learned-compat] " + r.learned().summary());

        assertTrue(r.maeReduction() > 0.03,
                "学习型 MAE 应至少比规则型低 3%，实际下降 " + String.format("%.1f%%", r.maeReduction() * 100));
        assertTrue(r.corrGain() > 0.03,
                "学习型相关应至少比规则型高 0.03，实际提升 " + String.format("%.4f", r.corrGain()));
    }

    @Test
    @DisplayName("学到了规则模型缺失的三个项：学业拼搏、作息交互、卫生非线性")
    void learnsRuleMissingDimensions() {
        SyntheticDataset ds = SyntheticDataGenerator.generate(GenParams.defaults(400, 20261021L));
        double[] beta = LearnedCompatibility.fit(ds, LearnedCompatibility.DEFAULT_LAMBDA);

        // 学业拼搏（规则模型完全没有这一维，隐真值权重 0.12）
        assertTrue(beta[8] > 0.04,
                "应学到正的学业拼搏权重，实际 β[8]=" + String.format("%.4f", beta[8]));
        // 卫生非线性（隐真值用 |Δ|^1.6，规则模型用线性）
        assertTrue(beta[10] > 0.04,
                "应学到正的卫生非线性权重，实际 β[10]=" + String.format("%.4f", beta[10]));
        // 作息交互（隐真值里早起×晚睡额外 -0.55，特征是 0/1 触发旗标 → 权重应为负）
        assertTrue(beta[9] < -0.02,
                "应学到负的作息交互权重，实际 β[9]=" + String.format("%.4f", beta[9]));
    }

    // ------------------------------------------------------------------ 接口契约

    @Test
    @DisplayName("分数对称、落在 [-1,1]、空表与全缺失归 0")
    void symmetricBoundedNullSafe() {
        double[] beta = new double[LearnedCompatibility.FEATURE_DIM];
        for (int i = 0; i < beta.length; i++) {
            beta[i] = 0.1;
        }
        Map<Long, StudentFeature> obs = new LinkedHashMap<>();
        obs.put(1L, full(0.2));
        obs.put(2L, full(0.8));
        LearnedCompatibility model = new LearnedCompatibility(obs, beta);

        double ab = model.score(1L, 2L);
        double ba = model.score(2L, 1L);
        assertEquals(ab, ba, EPS, "相容度应对称");
        assertTrue(ab >= -1.0 && ab <= 1.0, "分数应落在 [-1,1]，实际 " + ab);
        assertEquals(0.0, model.score(1L, 999L), EPS, "对方无观测应返回 0");
        assertEquals(0.0, new LearnedCompatibility(Map.of(), beta).score(1L, 2L), EPS, "空表应返回 0");

        Map<Long, StudentFeature> allMissing = new LinkedHashMap<>();
        allMissing.put(1L, allMissing());
        allMissing.put(2L, allMissing());
        assertEquals(0.0, new LearnedCompatibility(allMissing, beta).score(1L, 2L), EPS,
                "双方全缺失应返回 0（未知=中性）");
    }

    @Test
    @DisplayName("explain 的维度贡献之和等于总分")
    void explainConsistentWithScore() {
        double[] beta = new double[LearnedCompatibility.FEATURE_DIM];
        Random rnd = new Random(7L);
        for (int i = 0; i < beta.length; i++) {
            beta[i] = rnd.nextDouble() * 0.3 - 0.15;
        }
        Map<Long, StudentFeature> obs = new LinkedHashMap<>();
        obs.put(1L, full(0.3));
        obs.put(2L, full(0.7));
        LearnedCompatibility model = new LearnedCompatibility(obs, beta);

        CompatBreakdown bd = model.explain(1L, 2L);
        double sum = 0.0;
        for (double v : bd.dimensions().values()) {
            sum += v;
        }
        assertEquals(bd.total(), sum, 1e-9, "维度贡献之和应等于总分");
        assertEquals(model.score(1L, 2L), bd.total(), 1e-9, "未越界时 explain 总分应等于 score");
    }

    // ------------------------------------------------------------------ 特征一致性

    @Test
    @DisplayName("前 8 个特征与规则模型维度逐项一致（含缺失）")
    void phiFirstEightMatchRuleDims() {
        Random rnd = new Random(20261021L);
        for (int t = 0; t < 200; t++) {
            double miss = switch (t % 4) {
                case 0 -> 0.0;
                case 1 -> 0.30;
                case 2 -> 0.60;
                default -> 0.90;
            };
            StudentFeature a = randomFeature(rnd, miss);
            StudentFeature b = randomFeature(rnd, miss);
            double[] phi = LearnedCompatibility.phi(a, b);
            for (int d = 0; d < 8; d++) {
                assertEquals(InformationValueExperiment.dimScore(d, a, b), phi[d], EPS,
                        "第 " + t + " 对特征第 " + d + " 维与规则模型不一致");
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    private static StudentFeature randomFeature(Random rnd, double missing) {
        double[] v = new double[StudentFeature.DIM];
        for (int i = 0; i < v.length; i++) {
            v[i] = rnd.nextDouble();
        }
        for (int cell : InformationValueExperiment.MODEL_CELLS) {
            if (rnd.nextDouble() < missing) {
                v[cell] = Double.NaN;
            }
        }
        return StudentFeature.of(v);
    }

    private static StudentFeature full(double val) {
        double[] v = new double[StudentFeature.DIM];
        for (int i = 0; i < v.length; i++) {
            v[i] = val;
        }
        return StudentFeature.of(v);
    }

    private static StudentFeature allMissing() {
        double[] v = new double[StudentFeature.DIM];
        for (int i = 0; i < v.length; i++) {
            v[i] = Double.NaN;
        }
        return StudentFeature.of(v);
    }
}
