package com.umi.nest.dorm.eval;

import com.umi.nest.dorm.compat.CompatibilityFunction;
import com.umi.nest.dorm.compat.LearnedCompatibility;
import com.umi.nest.dorm.compat.RuleBasedCompatibility;
import com.umi.nest.dorm.data.GenParams;
import com.umi.nest.dorm.data.GroundTruth;
import com.umi.nest.dorm.data.SyntheticDataGenerator;
import com.umi.nest.dorm.data.SyntheticDataset;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.model.StudentFeature;

import java.util.List;
import java.util.Map;

/**
 * 学习型相容度基线实验：训练 {@link LearnedCompatibility}，与手写规则模型
 * {@link RuleBasedCompatibility} 在<b>隐真值</b>上比 MAE 与皮尔逊相关。
 *
 * <p>训练集与测试集用<b>不同种子</b>生成，模型质量只在测试集上量，避免「背答案」。
 * 度量口径与 {@link Evaluator} 一致：质量指标只在隐真值 {@code g} 上算，不拿模型自评充数。
 */
public final class LearnedCompatExperiment {

    public static final String VERSION = "learned-compat-v1";

    /** 单模型在隐真值上的预测质量。 */
    public record ModelQuality(String model, double mae, double correlation, int pairs) {
        public String summary() {
            return String.format("%-26s MAE=%.4f  corr=%.4f  pairs=%d", model, mae, correlation, pairs);
        }
    }

    /** 一组（训练、测试种子）的完整结果。 */
    public record RunResult(int trainStudents, int testStudents, long seed, double lambda,
                            ModelQuality rule, ModelQuality learned) {
        /** MAE 相对下降（0.25 = 降 25%）。 */
        public double maeReduction() {
            return 1.0 - learned.mae() / rule.mae();
        }

        public double corrGain() {
            return learned.correlation() - rule.correlation();
        }
    }

    private LearnedCompatExperiment() {
    }

    public static SyntheticDataset dataset(int n, long seed) {
        return SyntheticDataGenerator.generate(GenParams.defaults(n, seed));
    }

    /** 跑一组实验：训练 → 绑定测试集 → 两模型在测试集上量质量。 */
    public static RunResult run(int trainN, int testN, long seed, double lambda) {
        SyntheticDataset train = dataset(trainN, seed);
        SyntheticDataset test = dataset(testN, seed + 1);

        double[] beta = LearnedCompatibility.fit(train, lambda);
        LearnedCompatibility learned = new LearnedCompatibility(test.problem().observations(), beta);
        RuleBasedCompatibility rule = new RuleBasedCompatibility(test.problem().observations());

        return new RunResult(trainN, testN, seed, lambda,
                measure(rule, test), measure(learned, test));
    }

    /** 在数据集全部学生对上，量模型分数与隐真值的 MAE 与皮尔逊相关。 */
    public static ModelQuality measure(CompatibilityFunction model, SyntheticDataset ds) {
        GroundTruth truth = ds.truth();
        List<Student> students = ds.problem().students();
        Map<Long, StudentFeature> obs = ds.problem().observations();

        double sumAbs = 0.0, sumY = 0.0, sumP = 0.0, sumYY = 0.0, sumPP = 0.0, sumYP = 0.0;
        long n = 0;
        for (int i = 0; i < students.size(); i++) {
            for (int j = i + 1; j < students.size(); j++) {
                long a = students.get(i).id();
                long b = students.get(j).id();
                if (!obs.containsKey(a) || !obs.containsKey(b)) {
                    continue;
                }
                double p = model.score(a, b);
                double y = truth.trueCompat(a, b);
                sumAbs += Math.abs(p - y);
                sumY += y;
                sumP += p;
                sumYY += y * y;
                sumPP += p * p;
                sumYP += y * p;
                n++;
            }
        }
        double mae = n == 0 ? Double.NaN : sumAbs / n;
        double corr = correlation(sumY, sumP, sumYY, sumPP, sumYP, n);
        return new ModelQuality(model.name(), mae, corr, (int) n);
    }

    private static double correlation(double sumY, double sumP, double sumYY, double sumPP,
                                      double sumYP, long n) {
        if (n < 2) {
            return Double.NaN;
        }
        double cov = sumYP - sumY * sumP / n;
        double varY = sumYY - sumY * sumY / n;
        double varP = sumPP - sumP * sumP / n;
        if (varY <= 0 || varP <= 0) {
            return Double.NaN;
        }
        return cov / Math.sqrt(varY * varP);
    }

    /** 命令行入口：打印一组对照（复现用）。 */
    public static void main(String[] args) {
        RunResult r = run(600, 600, 20261021L, LearnedCompatibility.DEFAULT_LAMBDA);
        System.out.println("== 学习型相容度基线 ==");
        System.out.println("train=" + r.trainStudents() + " test=" + r.testStudents()
                + " seed=" + r.seed() + " lambda=" + r.lambda());
        System.out.println(r.rule().summary());
        System.out.println(r.learned().summary());
        System.out.printf("MAE 下降 %.1f%%  相关提升 %+.4f%n", r.maeReduction() * 100, r.corrGain());
    }
}
