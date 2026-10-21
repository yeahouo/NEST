package com.umi.nest.dorm.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 创新点实验命令行：E-A（可评估性）与 E-B（信息价值）。
 *
 * <pre>
 *   # E-B：四臂对比（不补 / 随机补 10% / VOI 补 10% / 按 VOI 挑格但填均值）
 *   java -cp target/classes com.umi.nest.dorm.eval.InformationValueCli \
 *        --students 200 --missing 0.50 --seeds 5 --budget 0.10 --out target/info-value.md
 *
 *   # E-A：不同缺失率下的证据覆盖率
 *   java -cp target/classes com.umi.nest.dorm.eval.InformationValueCli --evaluability \
 *        --students 200 --missingList 0,0.25,0.50 --seeds 5 --out target/evaluability.md
 * </pre>
 *
 * <p>报告写成文件而不是只打屏：中文控制台重定向极易乱码（本项目踩过，
 * 见 {@code docs/学工平台改造路线图.md} 的排错记录）。
 */
public final class InformationValueCli {

    private static final String USAGE = """
            用法：
              java -cp target/classes com.umi.nest.dorm.eval.InformationValueCli [选项]

              --students N          学生数（默认 200）
              --missing P           缺失率 0..1（E-B 用，默认 0.50）
              --missingList a,b,c   E-A 的缺失率列表（配 --evaluability）
              --seeds K             种子个数，从 --seedStart 起连续取（默认 5）
              --seedStart S         第一个种子（默认 1）
              --budget P            补齐预算占缺失格比例（默认 0.10）
              --budgetList a,b,c    扫多个预算（E-B 扫描模式，每个预算跑全部种子）
              --evaluability        跑 E-A 而不是 E-B
              --out PATH            报告路径（默认 target/info-value.md）
            """;

    private InformationValueCli() {
    }

    public static void main(String[] args) {
        Map<String, String> a = parse(args);
        if (a.containsKey("help")) {
            System.out.println(USAGE);
            return;
        }
        boolean evalMode = a.containsKey("evaluability");
        int n = intOf(a, "students", 200);
        int seeds = intOf(a, "seeds", 5);
        long seedStart = intOf(a, "seedStart", 1);
        String out = a.getOrDefault("out", evalMode ? "target/evaluability.md" : "target/info-value.md");

        System.out.println("信息价值实验 " + InformationValueExperiment.VERSION
                + "｜n=" + n + "｜seeds=" + seeds + "｜" + (evalMode ? "E-A 可评估性" : "E-B 四臂对比"));
        String md;
        if (evalMode) {
            md = runEvaluability(a, n, seeds, seedStart);
        } else if (a.containsKey("budgetList")) {
            md = runSweep(a, n, seeds, seedStart);
        } else {
            md = runInfoValue(a, n, seeds, seedStart);
        }
        write(out, md);
        System.out.println("报告已写入：" + Path.of(out).toAbsolutePath());
    }

    // ------------------------------------------------------------------ E-B

    private static String runInfoValue(Map<String, String> a, int n, int seeds, long seedStart) {
        double missing = doubleOf(a, "missing", 0.50);
        double budget = doubleOf(a, "budget", 0.10);
        List<InformationValueExperiment.RunResult> runs = new ArrayList<>();
        for (int i = 0; i < seeds; i++) {
            long seed = seedStart + i;
            InformationValueExperiment.RunResult r =
                    InformationValueExperiment.runOne(n, missing, seed, budget);
            runs.add(r);
            System.out.printf("  [%d/%d] n=%d miss=%.2f seed=%d 缺失格=%d 预算=%d ｜ none=%.4f random=%.4f voi=%.4f mean=%.4f%n",
                    i + 1, seeds, n, missing, seed, r.missingCells(), r.budget(),
                    r.arm("none").meanCompat(), r.arm("random").meanCompat(),
                    r.arm("voi").meanCompat(), r.arm("mean").meanCompat());
        }
        return reportInfoValue(n, missing, budget, runs);
    }

    // ------------------------------------------------------------------ E-B 预算扫描

    /**
     * 预算扫描：固定缺失率，扫多个补齐预算，看「补多少才开始改变分配」以及 VOI 相对随机的差距。
     * 动机：10% 预算在 n=60 上只改了几格，分配完全没动，真值指标自然打平——
     * 必须用扫描说明「不是 VOI 无效，而是这个预算下决策对该信息不敏感」。
     */
    private static String runSweep(Map<String, String> a, int n, int seeds, long seedStart) {
        double missing = doubleOf(a, "missing", 0.50);
        double[] budgets = doublesOf(a.get("budgetList"));
        StringBuilder sb = new StringBuilder();
        sb.append("# 信息价值实验（E-B 预算扫描）\n\n");
        sb.append("- 实验版本：`").append(InformationValueExperiment.VERSION).append("`\n");
        sb.append("- 配置：学生 ").append(n).append(" 人，4 人间，问卷噪声 0.20，缺失率 ")
                .append(String.format(Locale.ROOT, "%.2f", missing))
                .append("，种子 ").append(seedStart).append("..").append(seedStart + seeds - 1)
                .append("（共 ").append(seeds).append(" 个）\n");
        sb.append("- 每个预算都跑全部四臂；表内是跨种子均值。`换室友人数` = 室友集合与 `none` 臂不同的学生数。\n\n");
        sb.append("| 预算 | 缺失格 | 补齐格 | 臂 | 均值相容 | 最差那对 | 房内方差 | 阻塞对 | cov | 可评估率 | MAE | 换室友人数 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");

        for (double budget : budgets) {
            List<InformationValueExperiment.RunResult> runs = new ArrayList<>();
            for (int i = 0; i < seeds; i++) {
                runs.add(InformationValueExperiment.runOne(n, missing, seedStart + i, budget));
            }
            int missingCells = runs.get(0).missingCells();
            int revealed = runs.get(0).budget();
            for (String arm : new String[]{"none", "random", "voi", "mean"}) {
                double[] v = new double[8];
                for (var r : runs) {
                    var m = r.arm(arm);
                    v[0] += m.meanCompat();
                    v[1] += m.worstCompat();
                    v[2] += m.meanRoomVariance();
                    v[3] += m.blockingPairs();
                    v[4] += m.cov();
                    v[5] += m.evalRatio();
                    v[6] += m.modelTruthMae();
                    v[7] += m.changedStudents();
                }
                for (int i = 0; i < v.length; i++) {
                    v[i] /= runs.size();
                }
                sb.append(String.format(Locale.ROOT,
                        "| %.0f%% | %d | %d | %s | %.4f | %.4f | %.4f | %.1f | %.3f | %.3f | %.4f | %.1f |%n",
                        budget * 100, missingCells, revealed, arm, v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]));
            }
            System.out.printf("  budget=%.2f 补齐=%d/%d 均值相容 none=%.4f random=%.4f voi=%.4f 换室友 voi=%d%n",
                    budget, revealed, missingCells,
                    runs.get(0).arm("none").meanCompat(), runs.get(0).arm("random").meanCompat(),
                    runs.get(0).arm("voi").meanCompat(), runs.get(0).arm("voi").changedStudents());
        }
        return sb.toString();
    }

    private static String reportInfoValue(int n, double missing, double budget,
                                          List<InformationValueExperiment.RunResult> runs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 信息价值实验（E-B）\n\n");
        sb.append("- 实验版本：`").append(InformationValueExperiment.VERSION).append("`\n");
        sb.append("- 配置：学生 ").append(n).append(" 人，4 人间，问卷噪声 0.20，缺失率 ")
                .append(String.format(Locale.ROOT, "%.2f", missing))
                .append("，志愿紧张度 0.50，补齐预算 = 缺失格的 ")
                .append(String.format(Locale.ROOT, "%.0f%%", budget * 100)).append("\n");
        sb.append("- 四臂：`none` 不补 / `random` 随机挑格填真值 / `voi` 按期望增益挑格填真值 / ")
                .append("`mean` 按 VOI 挑同一批格但填总体均值\n");
        sb.append("- **口径**：所有质量指标在隐真值 g 上计算；补齐值取 g（等价于学生如实作答、不含观测噪声），")
                .append("因此这是**收益上界**，不是期望收益。`cov` = 同房对「双方都已知的维度权重」均值，")
                .append("`可评估率` = 至少一维可比的同房对比例，`MAE` = 模型分数与真值分数的平均绝对误差。\n\n");

        sb.append("## 逐种子明细\n\n");
        sb.append("| 种子 | 缺失格 | 预算 | 臂 | 均值相容 | 最差那对 | 房内方差 | 志愿满足 | 阻塞对 | cov | 可评估率 | MAE | 换室友人数 | 耗时ms |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (var r : runs) {
            for (var m : r.arms()) {
                sb.append(String.format(Locale.ROOT,
                        "| %d | %d | %d | %s | %.4f | %.4f | %.4f | %.3f | %d | %.3f | %.3f | %.4f | %d | %d |%n",
                        r.seed(), r.missingCells(), r.budget(), m.arm(), m.meanCompat(), m.worstCompat(),
                        m.meanRoomVariance(), m.preferenceSatisfaction(), m.blockingPairs(),
                        m.cov(), m.evalRatio(), m.modelTruthMae(), m.changedStudents(), m.runtimeMs()));
            }
        }

        sb.append("\n## 跨种子均值与配对差\n\n");
        sb.append("| 臂 | 均值相容 | 最差那对 | 房内方差 | 志愿满足 | 阻塞对 | cov | 可评估率 | MAE | 换室友人数 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        String[] arms = {"none", "random", "voi", "mean"};
        Map<String, double[]> avg = new LinkedHashMap<>();
        for (String arm : arms) {
            double[] v = new double[9];
            for (var r : runs) {
                var m = r.arm(arm);
                v[0] += m.meanCompat();
                v[1] += m.worstCompat();
                v[2] += m.meanRoomVariance();
                v[3] += m.preferenceSatisfaction();
                v[4] += m.blockingPairs();
                v[5] += m.cov();
                v[6] += m.evalRatio();
                v[7] += m.modelTruthMae();
                v[8] += m.changedStudents();
            }
            for (int i = 0; i < v.length; i++) {
                v[i] /= runs.size();
            }
            avg.put(arm, v);
            sb.append(String.format(Locale.ROOT,
                    "| %s | %.4f | %.4f | %.4f | %.3f | %.1f | %.3f | %.3f | %.4f | %.1f |%n",
                    arm, v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8]));
        }

        sb.append("\n### 配对差（同种子逐配对比，均值 ± 标准差）\n\n");
        sb.append("| 对比 | Δ均值相容 | Δ最差那对 | Δ阻塞对 | 均值相容提升的种子数 |\n");
        sb.append("|---|---|---|---|---|\n");
        pairDiff(sb, runs, "voi", "none");
        pairDiff(sb, runs, "random", "none");
        pairDiff(sb, runs, "voi", "random");
        pairDiff(sb, runs, "mean", "none");

        double dVoiNone = meanDelta(runs, "voi", "none", 0);
        double dRandomNone = meanDelta(runs, "random", "none", 0);
        double dVoiRandom = meanDelta(runs, "voi", "random", 0);
        double dMeanNone = meanDelta(runs, "mean", "none", 0);
        int winsVoi = countWins(runs, "voi", "none", 0);
        sb.append("\n## 预登记判据的结果\n\n");
        sb.append("- **P1**（VOI > 不补 且 VOI > 随机）：Δ(VOI−none) = ")
                .append(String.format(Locale.ROOT, "%+.4f", dVoiNone))
                .append("，Δ(VOI−random) = ").append(String.format(Locale.ROOT, "%+.4f", dVoiRandom))
                .append("，VOI 胜出种子数 ").append(winsVoi).append("/").append(runs.size()).append(" ⇒ ")
                .append(dVoiNone > 0 && dVoiRandom > 0 ? "**成立**" : "**不成立**").append("\n");
        sb.append("- **P2**（均值瞎补 vs 不补）：Δ(mean−none) = ")
                .append(String.format(Locale.ROOT, "%+.4f", dMeanNone)).append(" ⇒ ")
                .append(dMeanNone >= 0 ? "假证据无害（但不该给它记功）" : "**插补会主动损害分配质量**").append("\n");
        sb.append("- 参考：Δ(random−none) = ").append(String.format(Locale.ROOT, "%+.4f", dRandomNone))
                .append("（随机补若也有正收益，说明「多知道一点就有用」，P1 比的是「同样预算下选格策略」）\n");
        return sb.toString();
    }

    private static void pairDiff(StringBuilder sb, List<InformationValueExperiment.RunResult> runs,
                                 String x, String y) {
        int k = runs.size();
        double[] dMean = new double[k];
        double[] dWorst = new double[k];
        double[] dBlock = new double[k];
        int wins = 0;
        for (int i = 0; i < k; i++) {
            var a = runs.get(i).arm(x);
            var b = runs.get(i).arm(y);
            dMean[i] = a.meanCompat() - b.meanCompat();
            dWorst[i] = a.worstCompat() - b.worstCompat();
            dBlock[i] = a.blockingPairs() - b.blockingPairs();
            if (dMean[i] > 0) {
                wins++;
            }
        }
        sb.append(String.format(Locale.ROOT, "| %s − %s | %+.4f ± %.4f | %+.4f ± %.4f | %+.1f ± %.1f | %d/%d |%n",
                x, y, mean(dMean), sd(dMean), mean(dWorst), sd(dWorst), mean(dBlock), sd(dBlock), wins, k));
    }

    private static double meanDelta(List<InformationValueExperiment.RunResult> runs, String x, String y, int idx) {
        double[] d = new double[runs.size()];
        for (int i = 0; i < d.length; i++) {
            d[i] = metric(runs.get(i).arm(x), idx) - metric(runs.get(i).arm(y), idx);
        }
        return mean(d);
    }

    private static int countWins(List<InformationValueExperiment.RunResult> runs, String x, String y, int idx) {
        int w = 0;
        for (var r : runs) {
            if (metric(r.arm(x), idx) > metric(r.arm(y), idx)) {
                w++;
            }
        }
        return w;
    }

    private static double metric(InformationValueExperiment.ArmMetrics m, int idx) {
        return switch (idx) {
            case 0 -> m.meanCompat();
            case 1 -> m.worstCompat();
            case 2 -> m.blockingPairs();
            default -> m.meanRoomVariance();
        };
    }

    // ------------------------------------------------------------------ E-A

    private static String runEvaluability(Map<String, String> a, int n, int seeds, long seedStart) {
        String list = a.getOrDefault("missingList", "0,0.25,0.50");
        List<Double> rates = new ArrayList<>();
        for (String s : list.split(",")) {
            rates.add(Double.parseDouble(s.trim()));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# 可评估性实验（E-A）\n\n");
        sb.append("- 实验版本：`").append(InformationValueExperiment.VERSION).append("`\n");
        sb.append("- 配置：学生 ").append(n).append(" 人，4 人间，问卷噪声 0.20，缺失率 ∈ ").append(rates)
                .append("，种子 ").append(seeds).append(" 个（起 ").append(seedStart).append("）\n");
        sb.append("- `cov` = 同房对「双方都已知的维度权重」均值（0..1）；`可评估率` = 至少一维可比的同房对比例；")
                .append("`零证据对` = 一维都不可比的同房对数量；`MAE` = 模型分数与隐真值分数的平均绝对误差。\n\n");
        sb.append("| 缺失率 | 种子 | 同房对 | cov | 可评估率 | 零证据对 | MAE |\n|---|---|---|---|---|---|---|\n");

        Map<Double, double[]> avg = new LinkedHashMap<>();
        for (double rate : rates) {
            for (int i = 0; i < seeds; i++) {
                long seed = seedStart + i;
                var row = InformationValueExperiment.evaluability(n, rate, seed);
                System.out.printf("  miss=%.2f seed=%d cov=%.3f eval=%.3f 零证据对=%d MAE=%.4f%n",
                        rate, seed, row.cov(), row.evalRatio(), row.pairsWithoutEvidence(), row.modelTruthMae());
                sb.append(String.format(Locale.ROOT, "| %.2f | %d | %d | %.3f | %.3f | %d | %.4f |%n",
                        rate, seed, row.pairs(), row.cov(), row.evalRatio(),
                        row.pairsWithoutEvidence(), row.modelTruthMae()));
                double[] v = avg.computeIfAbsent(rate, k -> new double[3]);
                v[0] += row.cov();
                v[1] += row.evalRatio();
                v[2] += row.modelTruthMae();
            }
        }
        sb.append("\n## 按缺失率汇总（跨种子均值）\n\n");
        sb.append("| 缺失率 | cov | 可评估率 | MAE |\n|---|---|---|---|\n");
        for (var e : avg.entrySet()) {
            double[] v = e.getValue();
            sb.append(String.format(Locale.ROOT, "| %.2f | %.3f | %.3f | %.4f |%n",
                    e.getKey(), v[0] / seeds, v[1] / seeds, v[2] / seeds));
        }
        sb.append("\n## 预登记判据 P3\n\n");
        List<Double> keys = new ArrayList<>(avg.keySet());
        boolean monotone = true;
        for (int i = 1; i < keys.size(); i++) {
            double prev = avg.get(keys.get(i - 1))[2] / seeds;
            double cur = avg.get(keys.get(i))[2] / seeds;
            if (cur < prev) {
                monotone = false;
            }
        }
        sb.append("- **P3**（cov 越低 / 缺失越多，MAE 越大）：缺失率 ")
                .append(keys).append(" 上 MAE 单调不降 ⇒ ").append(monotone ? "**成立**" : "**不成立**")
                .append("\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------ 小工具

    private static double mean(double[] v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return v.length == 0 ? Double.NaN : s / v.length;
    }

    private static double sd(double[] v) {
        if (v.length < 2) {
            return Double.NaN;
        }
        double m = mean(v);
        double s = 0;
        for (double x : v) {
            s += (x - m) * (x - m);
        }
        return Math.sqrt(s / (v.length - 1));
    }

    private static void write(String path, String text) {
        try {
            Path p = Path.of(path);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("写报告失败：" + e.getMessage());
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                continue;
            }
            String key = a.substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                m.put(key, args[++i]);
            } else {
                m.put(key, "true");
            }
        }
        return m;
    }

    private static int intOf(Map<String, String> a, String key, int def) {
        try {
            return a.containsKey(key) ? Integer.parseInt(a.get(key).trim()) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double doubleOf(Map<String, String> a, String key, double def) {
        try {
            return a.containsKey(key) ? Double.parseDouble(a.get(key).trim()) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double[] doublesOf(String csv) {
        if (csv == null || csv.isBlank()) {
            return new double[]{0.10, 0.30, 0.60};
        }
        String[] parts = csv.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                out[i] = 0.10;
            }
        }
        return out;
    }
}
