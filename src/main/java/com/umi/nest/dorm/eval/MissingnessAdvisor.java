package com.umi.nest.dorm.eval;

import com.umi.nest.dorm.compat.CompatDimensions;
import com.umi.nest.dorm.compat.RuleBasedCompatibility;
import com.umi.nest.dorm.config.CompatWeights;
import com.umi.nest.dorm.model.CompatBreakdown;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.model.StudentFeature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 缺失感知决策支持（MAA，Missingness-Aware Assignment）的产品侧计算内核。
 *
 * <p>这一层只做数学，不碰数据库、不碰 HTTP，便于单测。三条口径来自
 * {@code docs/创新点-不确定感知的宿舍分配.md}：
 * <ol>
 *   <li><b>未知 = 中性</b>：相容度只累加「双方这一维都答过」的项，缺失维贡献 0（不补齐、
 *       也不惩罚）。这条不需要这里实现——它就是 {@link RuleBasedCompatibility} 的既有语义，
 *       本类只是<b>复用同一份实现</b>去估计补齐后的收益，避免出现第二套公式。</li>
 *   <li><b>可评估性</b>：给一份实际分配（谁和谁同房），算
 *       {@code Cov = 同房对上「双方都答过」的维度权重之和的均值}、
 *       {@code Eval = 至少有一维可比的同房对占比}。分数在 Cov 很低时不是结论，只是噪声。</li>
 *   <li><b>信息价值（VOI）</b>：给定「只补 k 个格子」的预算，估每个（学生 × 缺失项）
 *       补齐后的<b>期望相容度增益</b>，降序给出催填名单。口径是期望值，
 *       假设补齐后取值落在本校分布上（不是完美答案），因此这是「值不值得去问」而非「答了能涨多少」。</li>
 * </ol>
 *
 * <p><b>为什么增益用 {@code explain()} 而不是自己写一遍八维公式</b>：自己写就有第二套公式，
 * 一旦权重改了、维度改了，两处会悄悄漂移。{@code explain()} 返回的是已加权分维贡献
 * （{@code Map<维度名, w_d * 维度分>}），直接取目标维那一项就是 H3 里的 {@code w_d · c_d}。
 * 代价是每次评估多一次对象分配，所以入口都带评估次数上限（{@code maxEvaluations}），
 * 超出就把 {@code truncated} 标为 true，绝不让管理端页面卡死。
 *
 * <p>没有任何地方读隐真值：候选集、样本值都只来自 {@code observations}。
 */
public final class MissingnessAdvisor {

    /** 模型真正参与打分的 10 个格子（下标即 {@link StudentFeature#toArray()} 的下标）。 */
    public static final int[] CELLS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

    /** 与 {@link StudentFeature#toArray()} 下标对齐的题面短名（管理端表格直接显示）。 */
    public static final String[] CELL_LABEL = {
            "入睡时刻", "起床时刻", "吸烟倾向", "整洁要求", "外向程度",
            "在寝室学习", "噪声容忍", "社交活跃", "共享意愿", "打游戏"
    };

    /**
     * 八个相容维度各自依赖哪些格子。作息要两个格子都已知才算可比；
     * 学习噪声与游戏噪声都要用到噪声容忍（格子 6），所以格子 6 的缺失会同时影响两个维度。
     */
    public static final int[][] DIM_CELLS = {
            {0, 1}, {2}, {3}, {4}, {5, 6}, {9, 6}, {7}, {8}
    };

    public static final String[] DIM_NAME = {
            CompatDimensions.SLEEP,
            CompatDimensions.SMOKING,
            CompatDimensions.CLEANLINESS,
            CompatDimensions.EXTROVERSION,
            CompatDimensions.STUDY_NOISE,
            CompatDimensions.GAMING_NOISE,
            CompatDimensions.SOCIAL,
            CompatDimensions.SHARING
    };

    /** 可评估性门限：低于它就不应该把相容度分数当结论（见文档 §7.1）。 */
    public static final double COV_MIN = 0.15;
    public static final double EVAL_MIN = 0.50;

    private MissingnessAdvisor() {
    }

    // ---------------- 基础判定 ----------------

    /** 这个格子答过没有。 */
    public static boolean known(StudentFeature f, int cell) {
        return f != null && !Double.isNaN(f.toArray()[cell]);
    }

    /** 这一维在双方是否都可比（该维依赖的格子全部已知）。 */
    public static boolean dimKnown(StudentFeature a, StudentFeature b, int dim) {
        if (a == null || b == null) {
            return false;
        }
        for (int cell : DIM_CELLS[dim]) {
            if (!known(a, cell) || !known(b, cell)) {
                return false;
            }
        }
        return true;
    }

    /** 一名学生在模型维度上一共缺几个格子（ambition 不算，模型不用它）。 */
    public static int missingCellCount(StudentFeature f) {
        int n = 0;
        for (int cell : CELLS) {
            if (!known(f, cell)) {
                n++;
            }
        }
        return n;
    }

    /** 按维度权重取 8 维权重数组，顺序与 {@link #DIM_NAME} 一致。 */
    public static double[] dimWeights(CompatWeights weights) {
        CompatWeights w = (weights == null ? CompatWeights.defaults() : weights).normalized();
        return new double[]{
                w.sleep(), w.smoking(), w.cleanliness(), w.extroversion(),
                w.studyNoise(), w.gamingNoise(), w.social(), w.sharing()
        };
    }

    // ---------------- 可评估性 ----------------

    /**
     * 一份分配的可评估性。
     *
     * @param observations 学生 id → 观测特征（可含 NaN；没有特征行的学生视作全缺）
     * @param roomOf       学生 id → 房间 id（只统计这里出现的学生）
     * @param weights      维度权重
     */
    public static Coverage coverage(Map<Long, StudentFeature> observations,
                                    Map<Long, Long> roomOf,
                                    CompatWeights weights) {
        Map<Long, StudentFeature> obs = observations == null ? Map.of() : observations;
        double[] w = dimWeights(weights);
        Map<Long, List<Long>> byRoom = new LinkedHashMap<>();
        for (Map.Entry<Long, Long> e : roomOf.entrySet()) {
            if (e.getValue() != null) {
                byRoom.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
            }
        }
        int pairs = 0;
        int noEvidence = 0;
        double covSum = 0;
        for (List<Long> members : byRoom.values()) {
            members.sort(Comparator.naturalOrder());
            for (int i = 0; i < members.size(); i++) {
                for (int j = i + 1; j < members.size(); j++) {
                    StudentFeature a = obs.get(members.get(i));
                    StudentFeature b = obs.get(members.get(j));
                    pairs++;
                    double knownWeight = 0;
                    int knownDims = 0;
                    for (int d = 0; d < DIM_CELLS.length; d++) {
                        if (dimKnown(a, b, d)) {
                            knownWeight += w[d];
                            knownDims++;
                        }
                    }
                    if (knownDims == 0) {
                        noEvidence++;
                    }
                    covSum += knownWeight;
                }
            }
        }
        Map<Long, Integer> missingByStudent = new HashMap<>();
        int students = 0;
        int missingCells = 0;
        for (Long id : roomOf.keySet()) {
            StudentFeature f = obs.get(id);
            students++;
            int m = missingCellCount(f);
            missingByStudent.put(id, m);
            missingCells += m;
        }
        return new Coverage(students, missingCells, students * CELLS.length, pairs, noEvidence,
                pairs == 0 ? 0 : covSum / pairs,
                pairs == 0 ? 0 : (double) (pairs - noEvidence) / pairs,
                missingByStudent);
    }

    /** 缺格子数按题目汇总，管理端画「哪道题最缺」用。 */
    public static List<CellTally> cellTallies(Map<Long, StudentFeature> observations) {
        Map<Long, StudentFeature> obs = observations == null ? Map.of() : observations;
        List<CellTally> out = new ArrayList<>();
        for (int cell : CELLS) {
            int missing = 0;
            for (StudentFeature f : obs.values()) {
                if (!known(f, cell)) {
                    missing++;
                }
            }
            out.add(new CellTally(cell, CELL_LABEL[cell], missing, obs.size()));
        }
        return out;
    }

    // ---------------- 信息价值 ----------------

    /**
     * 催填优先级名单。
     *
     * <p>候选室友集取「同性同年级」（与分块的天然同桌范围一致）：跨年级、跨性别在硬约束里本来就
     * 不住一起，问出来的信息对这份分配没有价值。
     *
     * @param students       全部学生（用于取性别 / 年级 / 姓名）
     * @param studentNoOf    学生 id → 学号（可空，仅用于展示）
     * @param observations   学生 id → 观测特征
     * @param weights        维度权重
     * @param limit          返回前多少条
     * @param maxCandidates  每名学生最多看多少个候选室友（抽样，按 id 等距）
     * @param maxSamples     每个格子的分布样本数上限
     * @param maxEvaluations 总评估次数上限，超出即截断
     */
    public static Report rank(List<Student> students,
                              Map<Long, String> studentNoOf,
                              Map<Long, StudentFeature> observations,
                              CompatWeights weights,
                              int limit,
                              int maxCandidates,
                              int maxSamples,
                              long maxEvaluations) {
        List<Student> all = students == null ? List.of() : new ArrayList<>(students);
        Map<Long, StudentFeature> obs = observations == null ? Map.of() : observations;
        Map<Long, String> nos = studentNoOf == null ? Map.of() : studentNoOf;
        double[] w = dimWeights(weights);
        int capCand = Math.max(2, maxCandidates);
        int capSample = Math.max(1, maxSamples);
        long budget = Math.max(1L, maxEvaluations);

        // 候选池按（性别, 年级）分桶
        Map<String, List<Student>> pools = new LinkedHashMap<>();
        for (Student s : all) {
            pools.computeIfAbsent(poolKey(s), k -> new ArrayList<>()).add(s);
        }

        List<PriorityRow> rows = new ArrayList<>();
        long evals = 0;
        int missingTotal = 0;
        boolean truncated = false;
        for (Student s : all) {
            StudentFeature me = obs.get(s.id());
            int missing = missingCellCount(me);
            missingTotal += missing;
            if (missing == 0) {
                continue;
            }
            if (evals >= budget) {
                truncated = true;
                break;
            }
            List<Student> cands = sample(pools.getOrDefault(poolKey(s), List.of()), s.id(), capCand);
            for (int cell : CELLS) {
                if (known(me, cell)) {
                    continue;
                }
                // 这个格子能解锁哪些维度（这些维度除它之外的格子必须都已答过）
                List<Integer> unlock = new ArrayList<>();
                for (int d = 0; d < DIM_CELLS.length; d++) {
                    if (!usesCell(d, cell)) {
                        continue;
                    }
                    boolean othersKnown = true;
                    for (int c : DIM_CELLS[d]) {
                        if (c != cell && !known(me, c)) {
                            othersKnown = false;
                            break;
                        }
                    }
                    if (othersKnown) {
                        unlock.add(d);
                    }
                }
                if (unlock.isEmpty()) {
                    rows.add(new PriorityRow(0, s.id(), nos.get(s.id()), s.name(), cell, CELL_LABEL[cell],
                            0.0, 0));
                    continue;
                }
                List<Double> samples = sampleValues(obs, pools.getOrDefault(poolKey(s), List.of()),
                        s.id(), cell, capSample);
                double delta = 0;
                int knownCounterparts = 0;
                for (int d : unlock) {
                    double sum = 0;
                    int n = 0;
                    for (Student cand : cands) {
                        StudentFeature other = obs.get(cand.id());
                        if (!dimKnownAll(other, d)) {
                            continue;
                        }
                        knownCounterparts++;
                        for (double v : samples) {
                            if (evals >= budget) {
                                truncated = true;
                                break;
                            }
                            evals++;
                            sum += dimContribution(filled(me, cell, v), other, d);
                            n++;
                        }
                    }
                    if (n > 0) {
                        delta += sum / n;
                    }
                }
                rows.add(new PriorityRow(0, s.id(), nos.get(s.id()), s.name(), cell, CELL_LABEL[cell],
                        delta, knownCounterparts));
            }
        }

        rows.sort(Comparator.comparingDouble(PriorityRow::delta).reversed()
                .thenComparingLong(PriorityRow::studentId)
                .thenComparingInt(PriorityRow::cell));
        List<PriorityRow> top = new ArrayList<>();
        int rank = 0;
        for (PriorityRow r : rows) {
            if (top.size() >= Math.max(1, limit)) {
                break;
            }
            top.add(new PriorityRow(++rank, r.studentId(), r.studentNo(), r.name(),
                    r.cell(), r.cellLabel(), r.delta(), r.knownCounterparts()));
        }
        return new Report(all.size(), missingTotal, top, rows.size(), truncated, evals);
    }

    // ---------------- 内部工具 ----------------

    private static boolean usesCell(int dim, int cell) {
        for (int c : DIM_CELLS[dim]) {
            if (c == cell) {
                return true;
            }
        }
        return false;
    }

    /** 该学生对这一维是否「每个依赖格子都答过」。 */
    private static boolean dimKnownAll(StudentFeature f, int dim) {
        if (f == null) {
            return false;
        }
        for (int c : DIM_CELLS[dim]) {
            if (!known(f, c)) {
                return false;
            }
        }
        return true;
    }

    /** 把某个格子替换成给定值，返回新特征（其余格子保持原样，包括 NaN）。 */
    static StudentFeature filled(StudentFeature f, int cell, double v) {
        double[] arr = f.toArray();
        arr[cell] = v;
        return StudentFeature.of(arr);
    }

    /** 只取目标维的已加权贡献：{@code w_d · 维度分}。用 production 的 explain()，不另写公式。 */
    static double dimContribution(StudentFeature a, StudentFeature b, int dim) {
        Map<Long, StudentFeature> pair = new LinkedHashMap<>();
        pair.put(1L, a);
        pair.put(2L, b);
        CompatBreakdown bd = new RuleBasedCompatibility(pair).explain(1L, 2L);
        Double v = bd.dimensions().get(DIM_NAME[dim]);
        return v == null ? 0.0 : v;
    }

    /** 该格子的经验分布样本（只取候选池里答过这题的人，等距抽样）。 */
    private static List<Double> sampleValues(Map<Long, StudentFeature> obs, List<Student> pool,
                                             long selfId, int cell, int cap) {
        List<Double> vals = new ArrayList<>();
        for (Student s : pool) {
            if (s.id() == selfId) {
                continue;
            }
            StudentFeature f = obs.get(s.id());
            if (known(f, cell)) {
                vals.add(f.toArray()[cell]);
            }
        }
        if (vals.size() <= cap) {
            return vals;
        }
        List<Double> out = new ArrayList<>(cap);
        for (int i = 0; i < cap; i++) {
            out.add(vals.get((int) Math.round((double) i * (vals.size() - 1) / (cap - 1))));
        }
        return out;
    }

    /** 候选室友等距抽样，保证同一输入的结果稳定可复现（不用随机数）。 */
    private static List<Student> sample(List<Student> pool, long selfId, int cap) {
        List<Student> others = new ArrayList<>(pool.size());
        for (Student s : pool) {
            if (s.id() != selfId) {
                others.add(s);
            }
        }
        if (others.size() <= cap) {
            return others;
        }
        List<Student> out = new ArrayList<>(cap);
        for (int i = 0; i < cap; i++) {
            out.add(others.get((int) Math.round((double) i * (others.size() - 1) / (cap - 1))));
        }
        return out;
    }

    private static String poolKey(Student s) {
        String gender = s.gender() == null ? "" : s.gender().name();
        String grade = s.grade() == null ? "" : s.grade();
        return gender + "|" + grade;
    }

    // ---------------- 结果类型 ----------------

    /** 一份分配的可评估性指标。 */
    public record Coverage(int students,
                           int missingCells,
                           int totalCells,
                           int pairs,
                           int pairsWithoutEvidence,
                           double cov,
                           double evalRatio,
                           Map<Long, Integer> missingByStudent) {

        /** 已答格子占比。 */
        public double knownRate() {
            return totalCells == 0 ? 0 : (double) (totalCells - missingCells) / totalCells;
        }

        /** 是否够格把相容度分数当结论。 */
        public boolean evaluable() {
            return cov >= COV_MIN && evalRatio >= EVAL_MIN;
        }

        public List<String> reasons() {
            List<String> r = new ArrayList<>();
            if (pairs == 0) {
                r.add("还没有分配结果，无法评估可评估性");
                return r;
            }
            if (cov < COV_MIN) {
                r.add(String.format("证据覆盖率 %.3f 低于门限 %.2f：室友对大部分维度双方都没答过", cov, COV_MIN));
            }
            if (evalRatio < EVAL_MIN) {
                r.add(String.format("可评估对占比 %.1f%% 低于门限 %.0f%%：近半同房对一维都不可比",
                        evalRatio * 100, EVAL_MIN * 100));
            }
            if (r.isEmpty()) {
                r.add("可评估性达标：分数可以作为结论使用");
            }
            return r;
        }
    }

    /** 单题的缺失统计。 */
    public record CellTally(int cell, String label, int missing, int students) {
    }

    /** 一条催填建议。 */
    public record PriorityRow(int rank,
                              long studentId,
                              String studentNo,
                              String name,
                              int cell,
                              String cellLabel,
                              double delta,
                              int knownCounterparts) {
    }

    /** 汇总。 */
    public record Report(int students,
                         int missingCells,
                         List<PriorityRow> top,
                         int candidatesScored,
                         boolean truncated,
                         long evaluations) {
    }

    /** 便于测试与调用方直接拿维度名集合。 */
    public static Set<String> dimNames() {
        return new LinkedHashSet<>(List.of(DIM_NAME));
    }

    /** 便于测试：所有参与打分的格子。 */
    public static List<Integer> cells() {
        List<Integer> l = new ArrayList<>();
        for (int c : CELLS) {
            l.add(c);
        }
        return l;
    }
}
