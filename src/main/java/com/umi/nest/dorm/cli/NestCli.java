package com.umi.nest.dorm.cli;

import com.umi.nest.dorm.config.ConstraintConfig;
import com.umi.nest.dorm.data.GenParams;
import com.umi.nest.dorm.data.SyntheticDataGenerator;
import com.umi.nest.dorm.data.SyntheticDataset;
import com.umi.nest.dorm.eval.BaselineSolvers;
import com.umi.nest.dorm.eval.EvaluationMetrics;
import com.umi.nest.dorm.eval.Evaluator;
import com.umi.nest.dorm.solver.NestSolver;
import com.umi.nest.dorm.solver.SolveResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 命令行实验入口——求解器与界面完全解耦的证据：不启动 Spring、不连数据库，
 * 纯 Java 就能跑完"造数据 → 求解 → 评估"的完整闭环。
 *
 * <pre>
 *   java -cp target/classes com.umi.nest.dorm.cli.NestCli --students 10000 --repeat 3
 * </pre>
 */
public final class NestCli {

    public static void main(String[] args) {
        Map<String, String> a = parse(args);

        int n = intOf(a, "students", 1000);
        long seed = longOf(a, "seed", 20260927L);
        int repeat = intOf(a, "repeat", 1);
        double noise = doubleOf(a, "noise", 0.20);
        double missing = doubleOf(a, "missing", 0.0);
        double tension = doubleOf(a, "tension", 0.5);
        long budgetMs = longOf(a, "timeBudget", 30_000L);
        boolean gradeMix = Boolean.parseBoolean(a.getOrDefault("gradeMix", "true"));
        boolean withBaselines = !"false".equals(a.getOrDefault("baselines", "true"));

        System.out.println("NEST 命令行实验 — 规模 " + n + "，重复 " + repeat + " 次（种子自增），"
                + "噪声 " + noise + "，缺失率 " + missing + "，志愿紧张度 " + tension
                + "，时间预算 " + budgetMs + "ms");
        System.out.println();

        List<EvaluationMetrics> nestRows = new ArrayList<>();
        boolean allFeasible = true;
        long maxRuntime = 0;

        System.out.println(EvaluationMetrics.header());
        for (int r = 0; r < repeat; r++) {
            long s = seed + r;
            GenParams gp = new GenParams(n, 4, noise, missing, tension, 3, 0.03, s, gradeMix, 3);
            SyntheticDataset ds = SyntheticDataGenerator.generate(gp);
            ConstraintConfig cfg = ConstraintConfig.defaults();
            cfg.setTimeBudgetMs(budgetMs);
            cfg.setSeed(s);

            if (r == 0) {
                System.out.println("# " + gp.label() + "  学生=" + ds.problem().studentCount()
                        + " 房间=" + ds.problem().rooms().size() + " 床位=" + ds.problem().totalCapacity());
            }

            if (withBaselines) {
                System.out.println(Evaluator.evaluate(ds, BaselineSolvers.ruleByStudentId(ds.problem(), cfg)).row());
                System.out.println(Evaluator.evaluate(ds, BaselineSolvers.randomFill(ds.problem(), cfg)).row());
                System.out.println(Evaluator.evaluate(ds, BaselineSolvers.greedyOnly(ds.problem(), cfg)).row());
            }

            SolveResult res = new NestSolver().solve(ds.problem(), cfg);
            EvaluationMetrics m = Evaluator.evaluate(ds, res);
            nestRows.add(m);
            System.out.println(m.row());
            maxRuntime = Math.max(maxRuntime, res.runtimeMs());
            if (!res.feasible()) {
                allFeasible = false;
                System.out.println("  !! 不可行：" + res.violations().stream().limit(5).toList());
            }
            if (r == 0) {
                System.out.println();
                System.out.println("NEST 诊断：");
                res.diagnostics().forEach((k, v) -> System.out.println("  " + k + " = " + v));
                if (!res.warnings().isEmpty()) {
                    System.out.println("  警告：" + res.warnings());
                }
                System.out.println();
                System.out.println(EvaluationMetrics.header());
            }
        }

        System.out.println();
        System.out.println("== 汇总（NEST，" + repeat + " 次）==");
        System.out.printf(Locale.ROOT, "均值相容=%.4f  最差相容=%.4f  房内方差=%.4f  志愿满足=%.3f%n",
                avg(nestRows.stream().mapToDouble(EvaluationMetrics::meanCompat).toArray()),
                avg(nestRows.stream().mapToDouble(EvaluationMetrics::worstCompat).toArray()),
                avg(nestRows.stream().mapToDouble(EvaluationMetrics::meanRoomVariance).toArray()),
                avg(nestRows.stream().mapToDouble(EvaluationMetrics::preferenceSatisfaction).toArray()));
        System.out.printf(Locale.ROOT, "阻塞对=%.1f  违规合计=%d  最大耗时=%dms%n",
                avg(nestRows.stream().mapToDouble(EvaluationMetrics::blockingPairs).toArray()),
                nestRows.stream().mapToInt(EvaluationMetrics::hardViolations).sum(), maxRuntime);

        System.out.println();
        System.out.println(allFeasible ? "[PASS] 所有重复实验硬约束违规为 0、无未分配学生"
                : "[FAIL] 存在硬约束违规或未分配学生");
        System.exit(allFeasible ? 0 : 1);
    }

    private static double avg(double[] xs) {
        if (xs.length == 0) {
            return Double.NaN;
        }
        double s = 0;
        for (double x : xs) {
            s += x;
        }
        return s / xs.length;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String k = args[i];
            if (!k.startsWith("--")) {
                continue;
            }
            String key = k.substring(2);
            String val = (i + 1 < args.length && !args[i + 1].startsWith("--")) ? args[++i] : "true";
            m.put(key, val);
        }
        return m;
    }

    private static int intOf(Map<String, String> a, String k, int d) {
        return a.containsKey(k) ? Integer.parseInt(a.get(k)) : d;
    }

    private static long longOf(Map<String, String> a, String k, long d) {
        return a.containsKey(k) ? Long.parseLong(a.get(k)) : d;
    }

    private static double doubleOf(Map<String, String> a, String k, double d) {
        return a.containsKey(k) ? Double.parseDouble(a.get(k)) : d;
    }

    private NestCli() {
    }
}
