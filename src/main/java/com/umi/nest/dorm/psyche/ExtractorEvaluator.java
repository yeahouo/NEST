package com.umi.nest.dorm.psyche;

import com.umi.nest.dorm.model.StudentFeature;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 给特征提取器打分：拿它算出来的 11 维和"标准答案"逐维比。
 *
 * <p>这个类回答的是结题答辩上绕不过去的一个问题：
 * <b>你怎么知道大模型提取出来的性格是准的？</b>
 * 光展示"抽了一个学生，结果看着挺合理"不算回答——那是轶事，不是证据。
 *
 * <p>指标的选择不是随手挑的，每一个都对应一种具体的失败方式：
 * <ul>
 *   <li><b>MAE</b>（平均绝对误差）——最直观的"差多少"。
 *       但单独看它会骗人：一个把所有人都打成 0.5 的提取器，
 *       在均值附近的 MAE 反而可能很小。所以必须有下面两个。</li>
 *   <li><b>Pearson / Spearman 相关系数</b>——排序对不对。
 *       分配算法真正依赖的是"谁比谁更像"，而不是绝对刻度。
 *       相关系数高但 MAE 中等，远好过 MAE 小但相关系数接近 0。</li>
 *   <li><b>收敛比</b>（估计标准差 / 真值标准差）——<b>集中趋势的直接度量</b>。
 *       提示词里那段"不要都打在 0.4~0.6"就是为了拉高这个数。
 *       比值接近 1 说明模型敢把人和人区分开；接近 0 说明所有人的画像糊成一团，
 *       分配算法等于在瞎猜。这个指标是我们加了评分锚点前后最该看的东西。</li>
 *   <li><b>覆盖率</b>——该说"不知道"的时候有没有说不知道。
 *       素材里本来就故意漏掉了一些维度（见 {@link SelfReportGenerator}），
 *       硬凑出值的提取器会在这里被抓住。</li>
 * </ul>
 */
public final class ExtractorEvaluator {

    private ExtractorEvaluator() {
    }

    /** 一个被测样本：输入 + 标准答案。 */
    public record Case(long studentId, ExtractionInput input, StudentFeature truth) {
    }

    /**
     * 单维统计。
     *
     * @param n         参与统计的样本数（估计值与真值都存在的那些）
     * @param mae       平均绝对误差
     * @param bias      平均有符号误差（正 = 系统性高估）
     * @param pearson   皮尔逊相关
     * @param spearman  斯皮尔曼秩相关
     * @param truthStd  真值的标准差
     * @param estStd    估计值的标准差
     * @param coverage  该维给出值的比例（n / 总样本数）
     */
    public record DimStat(String dimension, boolean hourScale, int n, double mae, double bias,
                          double pearson, double spearman, double truthStd, double estStd,
                          double coverage) {

        /** 收敛比 = 估计标准差 / 真值标准差。1 最好，接近 0 表示退化成"给所有人打中间分"。 */
        public double collapse() {
            return truthStd > 1e-9 ? estStd / truthStd : Double.NaN;
        }
    }

    /** 一次评测的完整结果。 */
    public record Report(String extractor, String variant, int cases, int failed, long elapsedMs,
                         List<DimStat> dims, double maeHour, double mae01, double pearsonMean,
                         double spearmanMean, double collapse01, double coverage) {
    }

    // ==================================================================
    // 评测入口
    // ==================================================================

    /**
     * @param selfOnly true = 先剔除他人与助手的发言（生产口径）；
     *                 false = 照原样喂进去（消融，用来看"只分析本人发言"这条规则值多少）
     */
    public static Report evaluate(String extractorName, FeatureExtractor extractor, List<Case> cases,
                                  boolean selfOnly) {
        long t0 = System.nanoTime();
        int dim = StudentFeature.DIM;
        List<double[]> est = new ArrayList<>();
        List<double[]> truth = new ArrayList<>();
        int failed = 0;

        for (Case c : cases) {
            // 生产口径 = 剔除他人与助手的发言 + 出站脱敏；
            // 消融口径 = 原样喂进去，用来测出这两步到底值多少误差。
            ExtractionInput in = selfOnly
                    ? c.input().sanitized(List.of(c.input().name()))
                    : c.input();
            ExtractionResult r = null;
            try {
                r = extractor.extract(in);
            } catch (Exception e) {
                // 评测工具不该因为被测对象抛异常就整个崩掉
                failed++;
            }
            double[] e = new double[dim];
            Arrays.fill(e, Double.NaN);
            if (r != null) {
                for (PsycheDimension d : PsycheDimension.values()) {
                    e[d.index()] = r.valueOf(d);
                }
            } else {
                failed++;
            }
            est.add(e);
            truth.add(c.truth().toArray());
        }

        List<DimStat> stats = new ArrayList<>();
        for (PsycheDimension d : PsycheDimension.values()) {
            stats.add(stat(d.label(), d.hourScale(), d.index(), est, truth));
        }

        double maeHour = meanOf(stats, true, DimStat::mae);
        double mae01 = meanOf(stats, false, DimStat::mae);
        double pearsonMean = meanAbsCorr(stats, DimStat::pearson);
        double spearmanMean = meanAbsCorr(stats, DimStat::spearman);
        double collapse = pooledCollapse(est, truth);
        double coverage = meanOf(stats, null, DimStat::coverage);

        long elapsed = (System.nanoTime() - t0) / 1_000_000L;
        return new Report(extractorName, selfOnly ? "清洗后" : "原样",
                cases.size(), failed, elapsed, stats, maeHour, mae01, pearsonMean, spearmanMean,
                collapse, coverage);
    }

    /**
     * 收敛比：把 0-1 维的估计值与真值<b>汇总起来</b>各求标准差，再比。
     *
     * <p>不逐维求比值再平均，是因为逐维比值会被"真值本身几乎没变化"的维度炸掉：
     * 某一维在 6 个学生上的真值标准差只有 0.003，估计值哪怕只差 0.19，比值就是 58。
     * 一个这样的维度就能把整体均值推到 8.6，看的人以为提取器彻底失控，
     * 其实只是小样本下分母趋近于零。汇总到方差层面再比，分母是几百个样本，
     * 这个数才能反映"整体上敢不敢把人区分开"。
     */
    private static double pooledCollapse(List<double[]> est, List<double[]> truth) {
        double se = 0;
        double st = 0;
        int ne = 0;
        int nt = 0;
        for (int i = 0; i < est.size(); i++) {
            for (PsycheDimension d : PsycheDimension.values()) {
                if (d.hourScale()) {
                    continue;
                }
                double e = est.get(i)[d.index()];
                double t = truth.get(i)[d.index()];
                if (!Double.isNaN(e)) {
                    se += e;
                    ne++;
                }
                if (!Double.isNaN(t)) {
                    st += t;
                    nt++;
                }
            }
        }
        if (ne < 3 || nt < 3) {
            return Double.NaN;
        }
        double me = se / ne;
        double mt = st / nt;
        double ve = 0;
        double vt = 0;
        for (int i = 0; i < est.size(); i++) {
            for (PsycheDimension d : PsycheDimension.values()) {
                if (d.hourScale()) {
                    continue;
                }
                double e = est.get(i)[d.index()];
                double t = truth.get(i)[d.index()];
                if (!Double.isNaN(e)) {
                    ve += (e - me) * (e - me);
                }
                if (!Double.isNaN(t)) {
                    vt += (t - mt) * (t - mt);
                }
            }
        }
        ve /= ne;
        vt /= nt;
        return vt > 1e-12 ? Math.sqrt(ve / vt) : Double.NaN;
    }

    private static DimStat stat(String label, boolean hourScale, int index,
                                List<double[]> est, List<double[]> truth) {
        List<Double> ea = new ArrayList<>();
        List<Double> ta = new ArrayList<>();
        int produced = 0;
        for (int i = 0; i < est.size(); i++) {
            double e = est.get(i)[index];
            double t = truth.get(i)[index];
            if (!Double.isNaN(e)) {
                produced++;
            }
            if (!Double.isNaN(e) && !Double.isNaN(t)) {
                ea.add(e);
                ta.add(t);
            }
        }
        int n = ea.size();
        if (n == 0) {
            return new DimStat(label, hourScale, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    std(toArray(ta)), Double.NaN, est.isEmpty() ? Double.NaN : 0.0);
        }
        double[] a = toArray(ea);
        double[] b = toArray(ta);
        double mae = 0;
        double bias = 0;
        for (int i = 0; i < n; i++) {
            mae += Math.abs(a[i] - b[i]);
            bias += a[i] - b[i];
        }
        mae /= n;
        bias /= n;
        double cov = est.isEmpty() ? Double.NaN : (double) produced / est.size();
        return new DimStat(label, hourScale, n, mae, bias, pearson(a, b), spearman(a, b),
                std(b), std(a), cov);
    }

    // ==================================================================
    // 统计工具
    // ==================================================================

    /** 皮尔逊相关。样本少于 3 或任一方无方差时返回 NaN。 */
    public static double pearson(double[] a, double[] b) {
        int n = a.length;
        if (n < 3 || b.length != n) {
            return Double.NaN;
        }
        double ma = mean(a);
        double mb = mean(b);
        double num = 0;
        double da = 0;
        double db = 0;
        for (int i = 0; i < n; i++) {
            double x = a[i] - ma;
            double y = b[i] - mb;
            num += x * y;
            da += x * x;
            db += y * y;
        }
        if (da < 1e-12 || db < 1e-12) {
            return Double.NaN;
        }
        return num / Math.sqrt(da * db);
    }

    /**
     * 斯皮尔曼秩相关。
     * <p>比皮尔逊更适合这个场景：提取器若把刻度整体压缩（比如真值 0.2~0.9 被压成 0.4~0.6），
     * 皮尔逊会被惩罚，但只要顺序还对，分配算法拿到的距离关系就是对的。
     */
    public static double spearman(double[] a, double[] b) {
        if (a.length < 3 || a.length != b.length) {
            return Double.NaN;
        }
        return pearson(rank(a), rank(b));
    }

    /** 平均秩（并列取平均），返回 double[]。 */
    static double[] rank(double[] v) {
        int n = v.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (x, y) -> Double.compare(v[x], v[y]));
        double[] r = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && Math.abs(v[idx[j + 1]] - v[idx[i]]) < 1e-12) {
                j++;
            }
            double avg = (i + j) / 2.0 + 1.0;
            for (int k = i; k <= j; k++) {
                r[idx[k]] = avg;
            }
            i = j + 1;
        }
        return r;
    }

    static double mean(double[] v) {
        if (v.length == 0) {
            return Double.NaN;
        }
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s / v.length;
    }

    /** 总体标准差（除以 n）。 */
    static double std(double[] v) {
        if (v.length < 2) {
            return Double.NaN;
        }
        double m = mean(v);
        double s = 0;
        for (double x : v) {
            s += (x - m) * (x - m);
        }
        return Math.sqrt(s / v.length);
    }

    private static double[] toArray(List<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = l.get(i);
        }
        return a;
    }

    private static double meanOf(List<DimStat> stats, Boolean hourScale,
                                 java.util.function.ToDoubleFunction<DimStat> f) {
        double s = 0;
        int n = 0;
        for (DimStat d : stats) {
            if (hourScale != null && d.hourScale() != hourScale) {
                continue;
            }
            double v = f.applyAsDouble(d);
            if (!Double.isNaN(v)) {
                s += v;
                n++;
            }
        }
        return n == 0 ? Double.NaN : s / n;
    }

    /** 相关系数取绝对值后再平均：正相关和负相关都说明"提取器抓到了信号"，负相关的方向问题另行看待。 */
    private static double meanAbsCorr(List<DimStat> stats,
                                      java.util.function.ToDoubleFunction<DimStat> f) {
        double s = 0;
        int n = 0;
        for (DimStat d : stats) {
            double v = f.applyAsDouble(d);
            if (!Double.isNaN(v)) {
                s += Math.abs(v);
                n++;
            }
        }
        return n == 0 ? Double.NaN : s / n;
    }

    // ==================================================================
    // 输出
    // ==================================================================

    /** 多份报告并排对比——这是要贴进结题报告的那张表。 */
    public static String compareTable(List<Report> reports) {
        List<String> names = new ArrayList<>();
        for (Report r : reports) {
            names.add(r.extractor() + "·" + r.variant());
        }
        List<String> labels = new ArrayList<>(Arrays.asList(
                "样本数", "提取失败数", "耗时(ms)", "MAE 作息(小时)", "MAE 其余(0-1)",
                "|Pearson| 均值", "|Spearman| 均值", "收敛比(0-1 维)", "覆盖率"));
        List<java.util.function.Function<Report, String>> fns = new ArrayList<>(Arrays.asList(
                r -> String.valueOf(r.cases()),
                r -> String.valueOf(r.failed()),
                r -> String.valueOf(r.elapsedMs()),
                r -> f3(r.maeHour()),
                r -> f3(r.mae01()),
                r -> f3(r.pearsonMean()),
                r -> f3(r.spearmanMean()),
                r -> f3(r.collapse01()),
                r -> pct(r.coverage())));

        StringBuilder sb = new StringBuilder();
        sb.append("| 指标 |");
        for (String n : names) {
            sb.append(' ').append(n).append(" |");
        }
        sb.append('\n').append("|---|");
        for (int i = 0; i < names.size(); i++) {
            sb.append("---|");
        }
        sb.append('\n');
        for (int i = 0; i < labels.size(); i++) {
            sb.append("| ").append(labels.get(i)).append(" |");
            for (Report r : reports) {
                sb.append(' ').append(fns.get(i).apply(r)).append(" |");
            }
            sb.append('\n');
        }
        sb.append("\n> MAE 越小越好；相关系数与收敛比越大越好。收敛比接近 1 表示提取器敢把人和人区分开，")
                .append("接近 0 表示它给所有人都打了中间分——那样分配算法在软特征上等于瞎猜。\n");
        return sb.toString();
    }

    /** 单份报告的逐维明细表。 */
    public static String markdown(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(r.extractor()).append("（").append(r.variant()).append("）\n\n");
        sb.append("| 维度 | n | MAE | 偏差 | Pearson | Spearman | 真值σ | 估计σ | 收敛比 | 覆盖 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (DimStat d : r.dims()) {
            sb.append("| ").append(d.dimension())
                    .append(" | ").append(d.n())
                    .append(" | ").append(f3(d.mae()))
                    .append(" | ").append(f3(d.bias()))
                    .append(" | ").append(f3(d.pearson()))
                    .append(" | ").append(f3(d.spearman()))
                    .append(" | ").append(f3(d.truthStd()))
                    .append(" | ").append(f3(d.estStd()))
                    .append(" | ").append(f3(d.collapse()))
                    .append(" | ").append(pct(d.coverage()))
                    .append(" |\n");
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String f3(double v) {
        if (Double.isNaN(v)) {
            return "—";
        }
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static String pct(double v) {
        if (Double.isNaN(v)) {
            return "—";
        }
        return String.format(Locale.ROOT, "%.1f%%", v * 100);
    }
}
