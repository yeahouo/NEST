package com.umi.nest.dorm.eval;

import com.umi.nest.dorm.compat.CompatibilityFunction;
import com.umi.nest.dorm.config.ConstraintConfig;
import com.umi.nest.dorm.data.GroundTruth;
import com.umi.nest.dorm.data.SyntheticDataset;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Solution;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.solver.SolveResult;
import com.umi.nest.dorm.solver.SolutionValidator;
import com.umi.nest.dorm.solver.StabilityAudit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 评估器：把"分配方案"翻译成"研究结论能用的数字"。
 *
 * <p>三件必须做对的事：
 * <ol>
 *   <li><b>只在隐真值 g 上算相容度</b>。算法用的 RuleBasedCompatibility 有噪声、
 *       只有主效应没有交互项，用它的分数评估等于自己给自己打分。</li>
 *   <li><b>木桶指标必须单列</b>。均值高但有一间房是"地狱房"，在真实宿舍里就是投诉源头，
 *       所以 min(最差那一对) 与房内方差必须和均值一起报。</li>
 *   <li><b>公平性分组报</b>。同一个算法可能让某专业大类系统性吃亏，
 *       这正是可解释模块要能回答"为什么我们专业总是被塞进最差的房间"。</li>
 * </ol>
 *
 * <p>真实学生数据没有隐真值，只能退化为模型自评——这条退化路径是
 * {@link #evaluateModelSide}，产出的指标 basis 标为 {@link EvaluationMetrics#BASIS_MODEL}，
 * <b>不允许和真值指标放进同一张对比表</b>。
 */
public final class Evaluator {

    /** 一对室友的相容度打分函数（真值或模型，由调用方决定）。 */
    private interface PairScore {
        double score(long a, long b);
    }

    private Evaluator() {
    }

    // ---------------------------------------------------------------- 真值侧

    public static EvaluationMetrics evaluate(SyntheticDataset ds, SolveResult result) {
        return evaluate(ds, result.solution(), result.solver(), result.runtimeMs());
    }

    public static EvaluationMetrics evaluate(SyntheticDataset ds, Solution sol, String solver, long runtimeMs) {
        return evaluate(ds.truth(), ds.problem(), sol, solver, runtimeMs);
    }

    /**
     * 真值直给版本：服务端从 {@code dorm_ground_truth} 读回 {@link GroundTruth} 后走这条，
     * 不需要伪造一个 {@link SyntheticDataset}（那会强迫调用方编造一套 GenParams）。
     */
    public static EvaluationMetrics evaluate(GroundTruth truth, ProblemInstance problem, Solution sol,
                                             String solver, long runtimeMs) {
        return core(problem, sol, solver, runtimeMs, truth::trueCompat, EvaluationMetrics.BASIS_TRUTH);
    }

    // ---------------------------------------------------------------- 模型侧

    /**
     * 真实数据路径：没有隐真值，所有相容度指标都是<b>算法自己的预测</b>。
     * 只可用于"方案内部一致性自检"和可视化，<b>不能</b>用来宣称"我们的相容度提升了 X%"。
     */
    public static EvaluationMetrics evaluateModelSide(ProblemInstance problem, SolveResult result,
                                                      CompatibilityFunction compat) {
        return evaluateModelSide(problem, result.solution(), compat, result.solver(), result.runtimeMs());
    }

    public static EvaluationMetrics evaluateModelSide(ProblemInstance problem, Solution sol,
                                                      CompatibilityFunction compat, String solver, long runtimeMs) {
        return core(problem, sol, solver, runtimeMs, compat::score, EvaluationMetrics.BASIS_MODEL);
    }

    // ---------------------------------------------------------------- 公共内核

    private static EvaluationMetrics core(ProblemInstance problem, Solution sol, String solver, long runtimeMs,
                                          PairScore pairScore, String basis) {
        ConstraintConfig cfg = ConstraintConfig.defaults();

        double pairSum = 0;
        long pairCount = 0;
        double worst = Double.POSITIVE_INFINITY;
        double varianceSum = 0;
        int roomWithPairs = 0;

        for (var e : sol.byRoom().entrySet()) {
            List<Long> members = e.getValue();
            int m = members.size();
            if (m < 2) {
                continue;
            }
            double roomSum = 0;
            int cnt = 0;
            List<Double> vals = new ArrayList<>(m * (m - 1) / 2);
            for (int i = 0; i < m; i++) {
                for (int j = i + 1; j < m; j++) {
                    double c = pairScore.score(members.get(i), members.get(j));
                    vals.add(c);
                    roomSum += c;
                    cnt++;
                    worst = Math.min(worst, c);
                }
            }
            pairSum += roomSum;
            pairCount += cnt;
            double mean = roomSum / cnt;
            double var = 0;
            for (double v : vals) {
                var += (v - mean) * (v - mean);
            }
            varianceSum += var / cnt;
            roomWithPairs++;
        }

        // 每个学生的"满意度" = 与该房间友的相容度均值
        Map<Long, Double> studentSat = new HashMap<>();
        for (var st : problem.students()) {
            long room = sol.roomOf(st.id());
            if (room == -1) {
                continue;
            }
            List<Long> mates = sol.occupants(room);
            if (mates.size() < 2) {
                continue;
            }
            double s = 0;
            int c = 0;
            for (Long other : mates) {
                if (other != st.id()) {
                    s += pairScore.score(st.id(), other);
                    c++;
                }
            }
            studentSat.put(st.id(), s / c);
        }

        double meanSat = studentSat.values().stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        double spreadMajor = spread(problem.students(), studentSat, Student::majorCluster);
        double spreadGrade = spread(problem.students(), studentSat, Student::grade);

        return new EvaluationMetrics(
                solver,
                basis,
                problem.studentCount(),
                problem.rooms().size(),
                pairCount == 0 ? Double.NaN : pairSum / pairCount,
                pairCount == 0 ? Double.NaN : worst,
                roomWithPairs == 0 ? Double.NaN : varianceSum / roomWithPairs,
                StabilityAudit.preferenceSatisfactionRate(problem, sol),
                StabilityAudit.blockingPairCount(problem, sol),
                SolutionValidator.count(problem, cfg, sol),
                problem.studentCount() - sol.assignedCount(),
                spreadMajor,
                spreadGrade,
                meanSat,
                runtimeMs);
    }

    /** 分组平均满意度的极差（只统计样本数 ≥5 的组，避免小样本噪声）。 */
    private static double spread(List<Student> students, Map<Long, Double> sat,
                                 Function<Student, String> key) {
        Map<String, double[]> agg = new HashMap<>();
        for (Student s : students) {
            Double v = sat.get(s.id());
            if (v == null) {
                continue;
            }
            double[] a = agg.computeIfAbsent(key.apply(s), k -> new double[2]);
            a[0] += v;
            a[1] += 1;
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double[] a : agg.values()) {
            if (a[1] < 5) {
                continue;
            }
            double m = a[0] / a[1];
            min = Math.min(min, m);
            max = Math.max(max, m);
        }
        return (min == Double.POSITIVE_INFINITY) ? Double.NaN : max - min;
    }

    /** 按分组给出"组名 → [组人数, 平均满意度]"，供公平性审计落库与前端展示。 */
    public static Map<String, double[]> satisfactionByGroup(ProblemInstance problem, Solution sol,
                                                            CompatibilityFunction compat,
                                                            Function<Student, String> key) {
        Map<Long, Double> sat = new HashMap<>();
        for (Student st : problem.students()) {
            long room = sol.roomOf(st.id());
            if (room == -1) {
                continue;
            }
            List<Long> mates = sol.occupants(room);
            if (mates.size() < 2) {
                continue;
            }
            double s = 0;
            int c = 0;
            for (Long other : mates) {
                if (other != st.id()) {
                    s += compat.score(st.id(), other);
                    c++;
                }
            }
            sat.put(st.id(), s / c);
        }
        Map<String, double[]> agg = new HashMap<>();
        for (Student s : problem.students()) {
            Double v = sat.get(s.id());
            if (v == null) {
                continue;
            }
            double[] a = agg.computeIfAbsent(key.apply(s), k -> new double[2]);
            a[0] += 1;
            a[1] += v;
        }
        Map<String, double[]> out = new HashMap<>();
        for (var e : agg.entrySet()) {
            out.put(e.getKey(), new double[]{e.getValue()[0], e.getValue()[1] / e.getValue()[0]});
        }
        return out;
    }

    /** 房间级明细（房号 → 平均/最差相容度、人数），给热力图和房间详情页用。 */
    public static List<Map<String, Object>> roomDetails(ProblemInstance problem, Solution sol,
                                                        CompatibilityFunction compat) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : sol.byRoom().entrySet()) {
            var room = problem.roomById().get(e.getKey());
            if (room == null) {
                continue;
            }
            List<Long> members = e.getValue();
            double sum = 0;
            double worst = Double.POSITIVE_INFINITY;
            int cnt = 0;
            for (int i = 0; i < members.size(); i++) {
                for (int j = i + 1; j < members.size(); j++) {
                    double c = compat.score(members.get(i), members.get(j));
                    sum += c;
                    worst = Math.min(worst, c);
                    cnt++;
                }
            }
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("roomId", room.id());
            m.put("roomLabel", room.label());
            m.put("buildingId", room.buildingId());
            m.put("gender", room.gender().code());
            m.put("capacity", room.capacity());
            m.put("occupants", members.size());
            m.put("freeBeds", room.capacity() - members.size());
            m.put("meanCompat", cnt == 0 ? null : sum / cnt);
            m.put("worstCompat", cnt == 0 ? null : worst);
            m.put("memberIds", members);
            out.add(m);
        }
        out.sort(java.util.Comparator.comparing(a -> String.valueOf(a.get("roomLabel"))));
        return out;
    }
}
