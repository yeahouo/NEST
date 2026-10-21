package com.umi.nest.dorm.eval;

import com.umi.nest.dorm.compat.RuleBasedCompatibility;
import com.umi.nest.dorm.config.CompatWeights;
import com.umi.nest.dorm.config.ConstraintConfig;
import com.umi.nest.dorm.data.GenParams;
import com.umi.nest.dorm.data.GroundTruth;
import com.umi.nest.dorm.data.SyntheticDataGenerator;
import com.umi.nest.dorm.data.SyntheticDataset;
import com.umi.nest.dorm.model.Gender;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Solution;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.model.StudentFeature;
import com.umi.nest.dorm.solver.NestSolver;
import com.umi.nest.dorm.solver.SolveResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 创新点实验：<b>不确定感知的宿舍分配（MAA）</b> 的两条命题。
 *
 * <p>背景与命题定义见 {@code docs/创新点-不确定感知的宿舍分配.md}：
 * <ul>
 *   <li><b>E-A 可评估性</b>：一次分配里「有证据支撑的权重比例」{@code cov}、
 *       「至少一维可比的同房对比例」{@code evalRatio}，以及模型分数与隐真值分数的平均绝对误差。
 *       预期：缺失率越高，cov 越低、MAE 越大。</li>
 *   <li><b>E-B 信息价值</b>：在「只能补 10% 缺失格」的预算下，按期望增益挑选格子（VOI）
 *       是否比随机挑、比不补更好；另设「按 VOI 挑格但填总体均值」一臂，检验
 *       <b>插补是否本身有害</b>（H1 的反面证据）。</li>
 * </ul>
 *
 * <p>纪律（与 {@link Evaluator} / {@link ExperimentRunner} 一致）：
 * <ul>
 *   <li>所有质量指标都在<b>隐真值 g</b> 上计算（{@code BASIS_TRUTH}）；算法只看得到问卷观测。</li>
 *   <li>「补齐」一臂的取值用隐真值 {@code g}（等价于学生如实作答、且不含观测噪声），
 *       因此结果是<b>收益上界</b>；补齐值带噪声时收益只会更小。报告里必须写明这一条。</li>
 *   <li>VOI 只用数据里<b>已观测</b>的值估计经验分布，不引入外部先验。</li>
 * </ul>
 *
 * <p>VOI 用一次性（batch）选择，不做序列贝叶斯更新：一次催填的现实约束就是「一轮、批量」，
 * 序列版本会高估收益（后面补的格子信息已包含了前面补的）。
 */
public final class InformationValueExperiment {

    public static final String VERSION = "info-value-v1";

    /** 参与打分的观测格（{@link StudentFeature} 的下标）。ambition(10) 不参与打分，故不在此列。 */
    public static final int[] MODEL_CELLS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

    /** 观测量名（报告用）。 */
    public static final String[] CELL_LABEL = {
            "入睡时刻", "起床时刻", "吸烟", "整洁", "外向", "室内自习", "噪声容忍", "社交", "共享物品", "打游戏"
    };

    /**
     * 每个打分维度在<b>双方</b>身上分别需要哪些观测格。
     * <p>下标即维度序号：0 作息 / 1 吸烟 / 2 卫生 / 3 外向 / 4 学习噪声 / 5 游戏噪声 / 6 社交 / 7 共享。
     * <p>作息要四个格子（两人各两格）全已知才算可比；两种噪声维度是「声源 × 容忍度」交叉，
     * 因此学习噪声需要两人都已知 studyInRoom 与 noiseTolerance。
     */
    private static final int[][] DIM_NEED_CELLS = {
            {0, 1}, {2}, {3}, {4}, {5, 6}, {9, 6}, {7}, {8}
    };

    private static final String[] DIM_LABEL = {
            "作息", "吸烟", "卫生", "外向", "学习噪声", "游戏噪声", "社交", "共享"
    };

    /** 默认权重（与 {@code CompatWeights.defaults()} 归一化后一致）。 */
    private static final double[] DIM_W = weights();

    private static final ConstraintConfig CFG = ConstraintConfig.defaults();

    private InformationValueExperiment() {
    }

    // ------------------------------------------------------------------ 数据结构

    /** 一个缺失的观测格：某学生的第 {@code cell} 个软特征。 */
    public record Cell(long studentId, int cell) {
        public String label() {
            return studentId + ":" + CELL_LABEL[cell];
        }
    }

    /** 某一「补齐策略」臂的结果。 */
    public record ArmMetrics(
            String arm,
            int revealed,
            double meanCompat,
            double worstCompat,
            double meanRoomVariance,
            double preferenceSatisfaction,
            int blockingPairs,
            double cov,
            double evalRatio,
            double modelTruthMae,
            int changedStudents,
            long runtimeMs
    ) {
    }

    /** 一组配置（一个种子）的完整结果。 */
    public record RunResult(
            String label,
            int students,
            double missingRate,
            long seed,
            int missingCells,
            int budget,
            List<ArmMetrics> arms
    ) {
        public ArmMetrics arm(String name) {
            return arms.stream().filter(a -> a.arm().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("没有这一臂：" + name));
        }
    }

    /** E-A 一行的结果。 */
    public record EvaluabilityRow(
            int students,
            double missingRate,
            long seed,
            double cov,
            double evalRatio,
            int pairs,
            int pairsWithoutEvidence,
            double modelTruthMae
    ) {
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 跑一组配置的全部四臂。
     *
     * @param n              学生数
     * @param missingRate    每个观测格被抹掉的概率
     * @param seed           随机种子（数据与「随机挑格」都用它，保证同配置可复现）
     * @param budgetFraction 补齐预算占缺失格的比例（0.10 = 只补 10%）
     */
    public static RunResult runOne(int n, double missingRate, long seed, double budgetFraction) {
        SyntheticDataset ds = generate(n, missingRate, seed);
        ProblemInstance base = ds.problem();
        GroundTruth truth = ds.truth();
        Map<Long, StudentFeature> obs = ds.problem().observations();
        List<Cell> missing = missingCells(obs);
        int budget = Math.max(1, (int) Math.round(missing.size() * budgetFraction));

        Map<String, ArmMetrics> arms = new LinkedHashMap<>();
        BASELINE_SOLUTIONS.clear();
        ArmMetrics none = solveAndMeasure("none", 0, base, obs, truth, null);
        arms.put("none", none);

        Random rnd = new Random(seed * 1_000_003L + 17);
        List<Cell> randomPick = new ArrayList<>(missing);
        java.util.Collections.shuffle(randomPick, rnd);
        randomPick = new ArrayList<>(randomPick.subList(0, Math.min(budget, randomPick.size())));
        arms.put("random", solveAndMeasure("random", randomPick.size(),
                withCompletion(ds, randomPick, true, null), null, truth, none));

        List<Cell> voiPick = voiRanking(base, missing, budget);
        arms.put("voi", solveAndMeasure("voi", voiPick.size(),
                withCompletion(ds, voiPick, true, null), null, truth, none));
        arms.put("mean", solveAndMeasure("mean", voiPick.size(),
                withCompletion(ds, voiPick, false, populationMeans(obs)), null, truth, none));

        return new RunResult("n" + n + "_miss" + String.format("%.2f", missingRate) + "_s" + seed,
                n, missingRate, seed, missing.size(), budget, List.copyOf(arms.values()));
    }

    /** E-A：只求解一次、只测可评估性。 */
    public static EvaluabilityRow evaluability(int n, double missingRate, long seed) {
        SyntheticDataset ds = generate(n, missingRate, seed);
        Map<Long, StudentFeature> obs = ds.problem().observations();
        SolveResult r = new NestSolver().solve(ds.problem(), CFG);
        double[] cov = covAndEval(obs, r.solution());
        double mae = modelTruthMae(obs, r.solution(), ds.truth());
        int pairs = pairCount(r.solution());
        return new EvaluabilityRow(n, missingRate, seed, cov[0], cov[1], pairs,
                (int) Math.round((1.0 - cov[1]) * pairs), mae);
    }

    /** 生成器统一参数：4 人间、允许混合年级、3 栋楼、每人 3 个志愿、特殊需求 3%。 */
    public static SyntheticDataset generate(int n, double missingRate, long seed) {
        GenParams gp = new GenParams(n, 4, 0.20, missingRate, 0.5, 3, 0.03, seed, true, 3);
        return SyntheticDataGenerator.generate(gp);
    }

    // ------------------------------------------------------------------ 求解与度量

    private static ArmMetrics solveAndMeasure(String arm, int revealed, ProblemInstance problem,
                                              Map<Long, StudentFeature> objectiveObs, GroundTruth truth,
                                              ArmMetrics baseline) {
        long t0 = System.currentTimeMillis();
        SolveResult r = new NestSolver().solve(problem, CFG);
        long ms = Math.max(1, System.currentTimeMillis() - t0);
        Map<Long, StudentFeature> obs = objectiveObs != null ? objectiveObs : problem.observations();
        EvaluationMetrics m = Evaluator.evaluate(truth, problem, r.solution(), arm, ms);
        double[] cov = covAndEval(obs, r.solution());
        double mae = modelTruthMae(obs, r.solution(), truth);
        int changed = baseline == null ? 0 : changedStudents(problem, r.solution(), baseline);
        ArmMetrics out = new ArmMetrics(arm, revealed, m.meanCompat(), m.worstCompat(),
                m.meanRoomVariance(), m.preferenceSatisfaction(), m.blockingPairs(),
                cov[0], cov[1], mae, changed, ms);
        if (baseline == null) {
            // 本臂是基线：把解登记进旁路，后续臂据此统计「室友是否变化」
            BASELINE_SOLUTIONS.put(out, r.solution());
        }
        return out;
    }

    /**
     * 与基线臂相比，室友集合发生变化的<b>学生人数</b>（房间号变但室友不变不算变化——
     * 对我们真正有意义的是「和谁住」）。
     */
    private static int changedStudents(ProblemInstance p, Solution sol, ArmMetrics baseline) {
        Solution baseSol = BASELINE_SOLUTIONS.get(baseline);
        if (baseSol == null) {
            return -1;
        }
        int changed = 0;
        for (Student s : p.students()) {
            if (!sameRoommates(baseSol, sol, s.id())) {
                changed++;
            }
        }
        return changed;
    }

    private static boolean sameRoommates(Solution a, Solution b, long sid) {
        long ra = a.roomOf(sid);
        long rb = b.roomOf(sid);
        if (ra == -1 || rb == -1) {
            return ra == rb;
        }
        return new java.util.HashSet<>(a.occupants(ra)).equals(new java.util.HashSet<>(b.occupants(rb)));
    }

    /** 基线解的旁路（{@link ArmMetrics} 是结果记录，不适合塞解对象）。 */
    private static final Map<ArmMetrics, Solution> BASELINE_SOLUTIONS =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    /** 缺失格清单（按学生 id、再按格序号稳定排序，保证结果可复现）。 */
    public static List<Cell> missingCells(Map<Long, StudentFeature> obs) {
        List<Cell> out = new ArrayList<>();
        for (var e : obs.entrySet()) {
            double[] v = e.getValue().toArray();
            for (int c : MODEL_CELLS) {
                if (Double.isNaN(v[c])) {
                    out.add(new Cell(e.getKey(), c));
                }
            }
        }
        out.sort(Comparator.comparingLong(Cell::studentId).thenComparingInt(Cell::cell));
        return out;
    }

    /** 用观测数据（或隐真值 / 总体均值）补齐指定格子，返回新的观测表。 */
    private static ProblemInstance withCompletion(SyntheticDataset ds, List<Cell> cells, boolean useTruth,
                                                  double[] means) {
        Map<Long, StudentFeature> obs = new LinkedHashMap<>(ds.problem().observations());
        GroundTruth truth = ds.truth();
        for (Cell c : cells) {
            StudentFeature f = obs.get(c.studentId());
            if (f == null) {
                continue;
            }
            double v;
            if (useTruth) {
                v = truth.trueFeatureOf(c.studentId()).toArray()[c.cell()];
            } else {
                v = means == null || Double.isNaN(means[c.cell()]) ? 0.5 : means[c.cell()];
            }
            obs.put(c.studentId(), withCell(f, c.cell(), v));
        }
        return new ProblemInstance(ds.problem().students(), ds.problem().rooms(), obs, ds.problem().preferences());
    }

    /** 每格的群体均值（只用已观测值；全缺则 NaN）。 */
    public static double[] populationMeans(Map<Long, StudentFeature> obs) {
        double[] sum = new double[StudentFeature.DIM];
        int[] cnt = new int[StudentFeature.DIM];
        for (StudentFeature f : obs.values()) {
            double[] v = f.toArray();
            for (int i = 0; i < StudentFeature.DIM; i++) {
                if (!Double.isNaN(v[i])) {
                    sum[i] += v[i];
                    cnt[i]++;
                }
            }
        }
        double[] mean = new double[StudentFeature.DIM];
        for (int i = 0; i < StudentFeature.DIM; i++) {
            mean[i] = cnt[i] == 0 ? Double.NaN : sum[i] / cnt[i];
        }
        return mean;
    }

    /** 从群体已观测值里抽样本格的候选值（用于期望增益估计），最多 {@code cap} 个。 */
    private static double[] sampleValues(Map<Long, StudentFeature> obs, int cell, int cap, Random rnd) {
        List<Double> all = new ArrayList<>();
        for (StudentFeature f : obs.values()) {
            double v = f.toArray()[cell];
            if (!Double.isNaN(v)) {
                all.add(v);
            }
        }
        if (all.isEmpty()) {
            return new double[]{0.5};
        }
        if (all.size() <= cap) {
            double[] out = new double[all.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = all.get(i);
            }
            return out;
        }
        double[] out = new double[cap];
        for (int i = 0; i < cap; i++) {
            out[i] = all.get(rnd.nextInt(all.size()));
        }
        return out;
    }

    /**
     * 按信息价值排序缺失格，返回前 {@code budget} 个。
     *
     * <p>单格期望增益（一次性、批量）：
     * <pre>
     *   Δ(i,d) = mean over 同性别候选室友 j { mean over 该格候选值 v { w_dim · c_dim(i[cell=v], j) } }
     * </pre>
     * 只统计用到这一格的维度；「对方那格的维度需求也未知」时该维天然是 0（不另外特判），
     * 因为补一个人的题对「两个人都没答」的问题没有帮助。
     */
    public static List<Cell> voiRanking(ProblemInstance problem, List<Cell> missing, int budget) {
        Map<Long, StudentFeature> obs = problem.observations();
        Map<Gender, List<Student>> pools = new HashMap<>();
        for (Student s : problem.students()) {
            pools.computeIfAbsent(s.gender(), k -> new ArrayList<>()).add(s);
        }
        Random rnd = new Random(20261016L);
        Map<Integer, double[]> samples = new HashMap<>();
        for (int c : MODEL_CELLS) {
            samples.put(c, sampleValues(obs, c, 12, rnd));
        }

        List<Map.Entry<Cell, Double>> scored = new ArrayList<>(missing.size());
        for (Cell cell : missing) {
            StudentFeature fi = obs.get(cell.studentId());
            if (fi == null) {
                continue;
            }
            List<Student> pool = pools.getOrDefault(genderOf(problem, cell.studentId()), List.of());
            int others = Math.max(1, pool.size() - 1);
            double[] vals = samples.get(cell.cell());
            double total = 0;
            for (Student j : pool) {
                if (j.id() == cell.studentId()) {
                    continue;
                }
                StudentFeature fj = obs.get(j.id());
                if (fj == null) {
                    continue;
                }
                for (double v : vals) {
                    StudentFeature fv = withCell(fi, cell.cell(), v);
                    for (int dim = 0; dim < DIM_W.length; dim++) {
                        if (!usesCell(dim, cell.cell())) {
                            continue;
                        }
                        total += DIM_W[dim] * dimScore(dim, fv, fj);
                    }
                }
            }
            double voi = total / (others * (double) vals.length);
            scored.add(Map.entry(cell, voi));
        }
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<Cell> out = new ArrayList<>(budget);
        for (int i = 0; i < Math.min(budget, scored.size()); i++) {
            out.add(scored.get(i).getKey());
        }
        return out;
    }

    private static Gender genderOf(ProblemInstance p, long id) {
        for (Student s : p.students()) {
            if (s.id() == id) {
                return s.gender();
            }
        }
        return Gender.MALE;
    }

    /**
     * 证据覆盖率与可评估率。
     *
     * @return {@code [cov, evalRatio]}：cov = 同房对的「双方都已知的维度权重」均值；
     *         evalRatio = 至少有一维可比的同房对比例
     */
    public static double[] covAndEval(Map<Long, StudentFeature> obs, Solution sol) {
        double covSum = 0;
        long pairs = 0;
        long evaluable = 0;
        for (var e : sol.byRoom().entrySet()) {
            List<Long> members = e.getValue();
            for (int i = 0; i < members.size(); i++) {
                for (int j = i + 1; j < members.size(); j++) {
                    StudentFeature a = obs.get(members.get(i));
                    StudentFeature b = obs.get(members.get(j));
                    if (a == null || b == null) {
                        pairs++;
                        continue;
                    }
                    double w = knownWeight(a, b);
                    covSum += w;
                    pairs++;
                    if (w > 0) {
                        evaluable++;
                    }
                }
            }
        }
        return new double[]{
                pairs == 0 ? Double.NaN : covSum / pairs,
                pairs == 0 ? Double.NaN : (double) evaluable / pairs
        };
    }

    /** 模型侧分数与隐真值分数的平均绝对误差（逐同房对）。 */
    public static double modelTruthMae(Map<Long, StudentFeature> obs, Solution sol, GroundTruth truth) {
        RuleBasedCompatibility compat = new RuleBasedCompatibility(obs);
        double sum = 0;
        long n = 0;
        for (var e : sol.byRoom().entrySet()) {
            List<Long> m = e.getValue();
            for (int i = 0; i < m.size(); i++) {
                for (int j = i + 1; j < m.size(); j++) {
                    sum += Math.abs(compat.score(m.get(i), m.get(j)) - truth.trueCompat(m.get(i), m.get(j)));
                    n++;
                }
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    // ------------------------------------------------------------------ 维度工具

    /** 这一对室友「双方都已知」的维度权重之和（0..1）。 */
    public static double knownWeight(StudentFeature a, StudentFeature b) {
        double[] va = a.toArray();
        double[] vb = b.toArray();
        double w = 0;
        for (int dim = 0; dim < DIM_W.length; dim++) {
            boolean ok = true;
            for (int cell : DIM_NEED_CELLS[dim]) {
                if (Double.isNaN(va[cell]) || Double.isNaN(vb[cell])) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                w += DIM_W[dim];
            }
        }
        return w;
    }

    /** 维度 {@code dim} 是否用到观测格 {@code cell}。 */
    static boolean usesCell(int dim, int cell) {
        for (int c : DIM_NEED_CELLS[dim]) {
            if (c == cell) {
                return true;
            }
        }
        return false;
    }

    /** 某格涉及哪些维度。 */
    static List<Integer> dimsOfCell(int cell) {
        List<Integer> out = new ArrayList<>();
        for (int dim = 0; dim < DIM_NEED_CELLS.length; dim++) {
            if (usesCell(dim, cell)) {
                out.add(dim);
            }
        }
        return out;
    }

    public static String dimLabel(int dim) {
        return DIM_LABEL[dim];
    }

    public static double dimWeight(int dim) {
        return DIM_W[dim];
    }

    /** 用给定值替换某一格后的特征副本。 */
    public static StudentFeature withCell(StudentFeature f, int cell, double value) {
        double[] v = f.toArray();
        v[cell] = value;
        return StudentFeature.of(v);
    }

    public static double cellValue(StudentFeature f, int cell) {
        return f.toArray()[cell];
    }

    /**
     * 单维原始得分（不含权重），公式与 {@link RuleBasedCompatibility} 逐项一致：
     * 那边是包私有工具方法，跨包不可复用；一致性由
     * {@code InformationValueExperimentTest#dimScoreMatchesProductionModel} 钉住。
     */
    public static double dimScore(int dim, StudentFeature a, StudentFeature b) {
        return switch (dim) {
            case 0 -> dimSleep(a, b);
            case 1 -> similar(a.smoking(), b.smoking());
            case 2 -> similar(a.cleanliness(), b.cleanliness());
            case 3 -> similar(a.extroversion(), b.extroversion());
            case 4 -> quiet(a.studyInRoom(), b.noiseTolerance(), b.studyInRoom(), a.noiseTolerance());
            case 5 -> quiet(a.gaming(), b.noiseTolerance(), b.gaming(), a.noiseTolerance());
            case 6 -> similar(a.socialActivity(), b.socialActivity());
            case 7 -> similar(a.shareItems(), b.shareItems());
            default -> 0.0;
        };
    }

    private static double similar(double x, double y) {
        if (Double.isNaN(x) || Double.isNaN(y)) {
            return 0.0;
        }
        return 1.0 - 2.0 * Math.abs(clip01(x) - clip01(y));
    }

    private static double dimSleep(StudentFeature a, StudentFeature b) {
        if (Double.isNaN(a.sleepStart()) || Double.isNaN(a.sleepEnd())
                || Double.isNaN(b.sleepStart()) || Double.isNaN(b.sleepEnd())) {
            return 0.0;
        }
        double d = Math.abs(a.sleepStart() - b.sleepStart()) + 0.5 * Math.abs(a.sleepEnd() - b.sleepEnd());
        return 1.0 - 2.0 * Math.min(1.0, d / 5.0);
    }

    private static double quiet(double srcA, double tolB, double srcB, double tolA) {
        if (Double.isNaN(srcA) || Double.isNaN(tolB) || Double.isNaN(srcB) || Double.isNaN(tolA)) {
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

    private static double[] weights() {
        CompatWeights w = CompatWeights.defaults().normalized();
        return new double[]{w.sleep(), w.smoking(), w.cleanliness(), w.extroversion(),
                w.studyNoise(), w.gamingNoise(), w.social(), w.sharing()};
    }

    private static int pairCount(Solution sol) {
        int n = 0;
        for (List<Long> m : sol.byRoom().values()) {
            n += m.size() * (m.size() - 1) / 2;
        }
        return n;
    }
}
