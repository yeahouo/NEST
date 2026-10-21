package com.umi.nest.dorm.eval;

import com.umi.nest.dorm.config.ConstraintConfig;
import com.umi.nest.dorm.data.GenParams;
import com.umi.nest.dorm.data.SyntheticDataGenerator;
import com.umi.nest.dorm.data.SyntheticDataset;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Solution;
import com.umi.nest.dorm.solver.NestSolver;
import com.umi.nest.dorm.solver.RoomScorer;
import com.umi.nest.dorm.solver.SolveResult;
import com.umi.nest.dorm.compat.RuleBasedCompatibility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 实验矩阵运行器：申请书 §10.4 承诺的那张表，这里把它跑出来。
 *
 * <p><b>实验设计的三条纪律</b>（缺一条结论就不成立）：
 * <ol>
 *   <li><b>同一个 (配置, 种子) 上跑完所有方法</b>。数据集只生成一次，四个方法吃同一批学生、
 *       同一批房间、同一批志愿。这样两个方法之间的差异只能来自算法本身，
 *       而不是"这一批学生恰好比较好带"。</li>
 *   <li><b>指标全部在隐真值 g 上算</b>（{@link Evaluator} 负责），算法看不到 g。</li>
 *   <li><b>配对检验，配对单位是 (配置, 种子)</b>——不是把 500 个 cell 当独立样本。</li>
 * </ol>
 *
 * <p>运行时间随 {@code students × 配置数 × 种子数 × 方法数} 增长，配置由调用方显式给出，
 * 默认只给一个小矩阵——大矩阵请走 CLI 并用后台任务。
 */
public final class ExperimentRunner {

    private ExperimentRunner() {
    }

    // ------------------------------------------------------------------ 配置

    /** 一组实验条件。前 4 个字段直接喂给 {@link GenParams}。 */
    public record Config(int students, double noise, double missingRate, double tension, int roomCapacity) {

        public Config(int students, double noise, double missingRate, double tension) {
            this(students, noise, missingRate, tension, 4);
        }

        public String label() {
            return String.format("n=%d 噪声%.0f%% 缺失%.0f%% 紧张%.1f",
                    students, noise * 100, missingRate * 100, tension);
        }

        public GenParams toGenParams(long seed) {
            // 参数顺序与 GenParams.defaults 一致：preferenceCount=3, specialNeedRate=0.03, gradeMix=true, buildingCount=3
            return new GenParams(students, roomCapacity, noise, missingRate, tension, 3, 0.03, seed, true, 3);
        }
    }

    /** 一个待测方法的种类。 */
    public enum Kind { B0, B1, B2, NEST, JUDGE }

    /**
     * 一个待测方法。
     *
     * @param tweak 在 {@link ConstraintConfig#defaults()} 之上做的改动，用来表达"消融"。
     *              必须是纯函数式的改动，不能依赖前一次调用留下的状态。
     */
    public record Method(String name, Kind kind, Consumer<ConstraintConfig> tweak) {

        public static Method b0() {
            return new Method(BaselineSolvers.B0, Kind.B0, c -> { });
        }

        public static Method b1() {
            return new Method(BaselineSolvers.B1, Kind.B1, c -> { });
        }

        public static Method b2() {
            return new Method(BaselineSolvers.B2, Kind.B2, c -> { });
        }

        public static Method nest() {
            return new Method(NestSolver.NAME, Kind.NEST, c -> { });
        }

        public static List<Method> baselines() {
            return List.of(b0(), b1(), b2());
        }

        /**
         * 消融组。每一组只砍掉一个东西，所以结论能一一对应到申请书里的那条设计决策。
         *
         * <p>注意 {@code NEST−木桶项}：去掉的是<b>求解时的木桶压力</b>，
         * 不是"评估时不报最差那对"——评估指标一个都没少。
         */
        public static List<Method> ablations() {
            return List.of(
                    new Method(NestSolver.NAME + " −木桶项", Kind.NEST, c -> c.getWeights().setBucket(0.0)),
                    new Method(NestSolver.NAME + " −阻塞对修复", Kind.NEST, c -> c.setEnableBlockingPairRepair(false)),
                    new Method(NestSolver.NAME + " −专业大类分块", Kind.NEST, c -> c.setBlockByMajorCluster(false)),
                    new Method(NestSolver.NAME + " −局部搜索", Kind.NEST, c -> c.setLocalSearchRounds(0)));
        }
    }

    /** 一轮实验的全部参数。 */
    public record Spec(List<Config> configs, List<Long> seeds, List<Method> methods,
                       boolean includeJudge, int judgeMaxStudents) {

        /** 冒烟用：几十秒能跑完，只为了验证链路通。 */
        public static Spec quick() {
            return new Spec(
                    List.of(new Config(200, 0.20, 0.0, 0.5), new Config(400, 0.20, 0.20, 0.8)),
                    List.of(1L, 2L, 3L),
                    Method.baselines(), false, 200);
        }

        /** 主实验：规模 × 噪声 × 缺失率 × 志愿紧张度，每个格子 10 个种子。 */
        public static Spec headline() {
            List<Config> configs = new ArrayList<>();
            for (int n : new int[]{1000, 5000}) {
                for (double noise : new double[]{0.10, 0.25, 0.45}) {
                    for (double missing : new double[]{0.0, 0.25, 0.50}) {
                        for (double tension : new double[]{0.2, 0.8}) {
                            configs.add(new Config(n, noise, missing, tension));
                        }
                    }
                }
            }
            List<Long> seeds = new ArrayList<>();
            for (long s = 1; s <= 10; s++) {
                seeds.add(s);
            }
            List<Method> methods = new ArrayList<>(Method.baselines());
            methods.add(Method.nest());
            return new Spec(configs, seeds, methods, false, 200);
        }

        public int totalRuns() {
            return configs.size() * seeds.size() * methods.size();
        }
    }

    // ------------------------------------------------------------------ 产出结构

    /** 一个 (方法, 配置, 种子) 的完整结果。 */
    public record Cell(String method, String configLabel, long seed, EvaluationMetrics metrics,
                       boolean feasible, double softObjective, String note) {
    }

    /** 一个方法在一组 cell 上的汇总。 */
    public record Aggregate(String method, String scope, int n,
                            Stats.Summary meanCompat, Stats.Summary worstCompat, Stats.Summary roomVariance,
                            Stats.Summary preference, Stats.Summary blockingPairs, Stats.Summary fairnessSpread,
                            Stats.Summary runtimeMs, double feasibleRate, int totalViolations, int totalUnassigned) {

        public String row() {
            return String.format("%-46s %-30s %4d %17s %17s %16s %15s %13s %15s %14s",
                    method, scope, n,
                    meanCompat.pm(), worstCompat.pm(), roomVariance.pm(), preference.pm(),
                    blockingPairs.pm(), fairnessSpread.pm(), runtimeMs.pm());
        }
    }

    /** 一条配对显著性结论。 */
    public record Comparison(String metric, String scope, String treatment, String control, int n,
                             double meanTreatment, double meanControl, double meanDiff, double relChangePct,
                             double pT, double pWilcoxon, double cohensD, double ciLow, double ciHigh,
                             boolean higherIsBetter, String verdict) {

        public boolean significant() {
            return Double.isFinite(pWilcoxon) && pWilcoxon < 0.05;
        }

        public boolean favorsTreatment() {
            return significant() && (higherIsBetter ? meanDiff > 0 : meanDiff < 0);
        }
    }

    /** 与最优解的间隙（只有 CP-SAT 裁判在场时才有）。 */
    public record Gap(String configLabel, long seed, int students, double judgeObjective, double nestObjective,
                      double nestGapPct, long judgeMs, long nestMs, boolean judgeFeasible, boolean judgeProvenOptimal) {

        /**
         * 这个间隙能不能读成"最优间隙"。
         *
         * <p>裁判如果只是给了一个<b>可行解</b>（时限内没证明最优），那么真实最优 ≥ 裁判目标值，
         * 于是报出的间隙是真实间隙的<b>下界</b>——"至少差这么多"，不是"最多差这么多"。
         * 两句话在结题报告里是完全不同的结论，所以这一位必须跟着间隙一起走，不能丢。
         */
        public String gapReading() {
            return judgeProvenOptimal ? "最优间隙（裁判已证明最优）" : "间隙下界（裁判未证明最优）";
        }
    }

    public record Report(List<Cell> cells, List<Aggregate> aggregates, List<Comparison> comparisons,
                         List<Gap> gaps, List<String> notes, long elapsedMs) {

        public String markdown() {
            return ExperimentRunner.markdown(this);
        }

        public Map<String, Object> toJson() {
            return ExperimentRunner.toJson(this);
        }
    }

    /** 进度回调，CLI 用来打印。"done/total" 是 cell 级进度。 */
    public interface Listener {
        void onProgress(int done, int total, String label);
    }

    // ------------------------------------------------------------------ 主流程

    public static Report run(Spec spec) {
        return run(spec, (d, t, l) -> { });
    }

    public static Report run(Spec spec, Listener listener) {
        long t0 = System.currentTimeMillis();
        List<String> notes = new ArrayList<>();
        List<Cell> cells = new ArrayList<>();
        List<Gap> gaps = new ArrayList<>();

        OptimalJudge judge = spec.includeJudge() ? OptimalJudge.load() : null;
        if (spec.includeJudge()) {
            notes.add(judge == null
                    ? "未提供最优解裁判（" + OptimalJudge.loadFailureReason() + "），报告中没有最优间隙一栏。"
                    : "最优解裁判：" + judge.name() + "，仅对学生数 ≤ " + spec.judgeMaxStudents() + " 的实例求解。");
        }

        int total = spec.configs().size() * spec.seeds().size();
        int done = 0;
        for (Config config : spec.configs()) {
            for (long seed : spec.seeds()) {
                // 数据集只生成一次，所有方法共用——这是配对的前提
                SyntheticDataset ds = SyntheticDataGenerator.generate(config.toGenParams(seed));
                ProblemInstance problem = ds.problem();

                for (Method method : spec.methods()) {
                    boolean isJudge = method.kind() == Kind.JUDGE;
                    if (isJudge && (judge == null || problem.studentCount() > spec.judgeMaxStudents())) {
                        continue;
                    }
                    ConstraintConfig cfg = ConstraintConfig.defaults();
                    cfg.setSeed(seed);
                    method.tweak().accept(cfg);

                    SolveResult r;
                    String note = "";
                    try {
                        r = switch (method.kind()) {
                            case B0 -> BaselineSolvers.ruleByStudentId(problem, cfg);
                            case B1 -> BaselineSolvers.randomFill(problem, cfg);
                            case B2 -> BaselineSolvers.greedyOnly(problem, cfg);
                            case NEST -> new NestSolver().solve(problem, cfg);
                            case JUDGE -> judge.solve(problem, cfg);
                        };
                    } catch (RuntimeException ex) {
                        // 一个方法在某一个 cell 上崩了，不该让整轮实验报销
                        note = "求解失败: " + ex.getClass().getSimpleName() + " " + ex.getMessage();
                        cells.add(new Cell(method.name(), config.label(), seed, null, false, Double.NaN, note));
                        continue;
                    }
                    if (r == null) {
                        String why = isJudge && judge != null ? judge.lastFailure() : null;
                        cells.add(new Cell(method.name(), config.label(), seed, null, false, Double.NaN,
                                "未给出结论：" + (why == null ? "原因未记录" : why)));
                        continue;
                    }

                    EvaluationMetrics m = Evaluator.evaluate(ds, r);
                    if (isJudge && !r.warnings().isEmpty()) {
                        // 裁判给了结论但自身有告警（如"未证明最优"）——必须原样转述，
                        // 否则读者会把"时限内找到的最好解"当成"已证明的最优"
                        note = String.join("；", r.warnings());
                    }
                    cells.add(new Cell(method.name(), config.label(), seed, m, r.feasible(),
                            r.softObjective(), note));

                    if (isJudge) {
                        double nestObj = objectiveOf(problem, cfg, cells, config, seed, spec.methods());
                        double gap = Double.isNaN(nestObj) ? Double.NaN
                                : (r.softObjective() - nestObj) / Math.max(1e-9, Math.abs(r.softObjective()));
                        gaps.add(new Gap(config.label(), seed, problem.studentCount(), r.softObjective(), nestObj,
                                gap, r.runtimeMs(), nestRuntimeMs(cells, config, seed), r.feasible(),
                                provenOptimal(r)));
                    }
                }

                done++;
                listener.onProgress(done, total, config.label() + " seed=" + seed);
            }
        }

        addDesignNotes(spec, cells, notes);
        addGapNotes(gaps, notes);
        List<Aggregate> aggregates = aggregate(cells, spec);
        List<Comparison> comparisons = compare(cells, spec);
        return new Report(cells, aggregates, comparisons, gaps, notes, System.currentTimeMillis() - t0);
    }

    // ------------------------------------------------------------------ 汇总

    private static List<Aggregate> aggregate(List<Cell> cells, Spec spec) {
        List<Aggregate> out = new ArrayList<>();
        List<String> scopes = new ArrayList<>();
        scopes.add("全部");
        for (Config c : spec.configs()) {
            scopes.add(c.label());
        }
        for (Method m : spec.methods()) {
            for (String scope : scopes) {
                List<Cell> sub = cells.stream()
                        .filter(c -> c.method().equals(m.name()) && c.metrics() != null)
                        .filter(c -> scope.equals("全部") || c.configLabel().equals(scope))
                        .toList();
                if (sub.isEmpty()) {
                    continue;
                }
                out.add(new Aggregate(m.name(), scope, sub.size(),
                        sum(sub, c -> c.metrics().meanCompat()),
                        sum(sub, c -> c.metrics().worstCompat()),
                        sum(sub, c -> c.metrics().meanRoomVariance()),
                        sum(sub, c -> c.metrics().preferenceSatisfaction()),
                        sum(sub, c -> c.metrics().blockingPairs()),
                        sum(sub, c -> c.metrics().fairnessSpreadMajor()),
                        sum(sub, c -> c.metrics().runtimeMs()),
                        sub.stream().filter(Cell::feasible).count() / (double) sub.size(),
                        sub.stream().mapToInt(c -> c.metrics().hardViolations()).sum(),
                        sub.stream().mapToInt(c -> c.metrics().unassigned()).sum()));
            }
        }
        return out;
    }

    private static Stats.Summary sum(List<Cell> cells, java.util.function.ToDoubleFunction<Cell> f) {
        double[] v = new double[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            v[i] = f.applyAsDouble(cells.get(i));
        }
        return Stats.summarize(v);
    }

    /** 待比较的指标：名字 → 取值函数 → 越大越好。 */
    private record MetricSpec(String name, java.util.function.ToDoubleFunction<Cell> f, boolean higherIsBetter) {
    }

    private static final List<MetricSpec> METRICS = List.of(
            new MetricSpec("均值相容度", c -> c.metrics().meanCompat(), true),
            new MetricSpec("最差那对相容度", c -> c.metrics().worstCompat(), true),
            new MetricSpec("房内方差", c -> c.metrics().meanRoomVariance(), false),
            new MetricSpec("志愿满足率", c -> c.metrics().preferenceSatisfaction(), true),
            new MetricSpec("阻塞对数", c -> (double) c.metrics().blockingPairs(), false),
            new MetricSpec("公平差距(专业大类)", c -> c.metrics().fairnessSpreadMajor(), false),
            new MetricSpec("耗时(ms)", c -> (double) c.metrics().runtimeMs(), false));

    private static List<Comparison> compare(List<Cell> cells, Spec spec) {
        List<Comparison> out = new ArrayList<>();
        // 配对键 = 配置标签 + 种子；key 的顺序一旦确定，所有方法的数组就按同一顺序取，配对才成立
        List<String> keys = new ArrayList<>();
        for (Config c : spec.configs()) {
            for (long s : spec.seeds()) {
                keys.add(c.label() + "#" + s);
            }
        }
        Map<String, Map<String, Cell>> byMethod = new LinkedHashMap<>();
        for (Cell c : cells) {
            byMethod.computeIfAbsent(c.method(), k -> new LinkedHashMap<>()).put(c.configLabel() + "#" + c.seed(), c);
        }

        Method treatment = spec.methods().stream().filter(m -> m.kind() == Kind.NEST).findFirst().orElse(null);
        if (treatment == null) {
            return out;
        }
        for (Method control : spec.methods()) {
            if (control.kind() == Kind.NEST || control.kind() == Kind.JUDGE) {
                continue;
            }
            for (MetricSpec ms : METRICS) {
                double[] a = series(byMethod.get(treatment.name()), keys, ms.f());
                double[] b = series(byMethod.get(control.name()), keys, ms.f());
                if (a.length == 0 || b.length == 0) {
                    continue;
                }
                Stats.PairedT t = Stats.pairedT(a, b);
                Stats.Wilcoxon w = Stats.wilcoxon(a, b);
                double mA = Stats.mean(a);
                double mB = Stats.mean(b);
                double diff = mA - mB;
                double rel = Math.abs(mB) > 1e-12 ? diff / Math.abs(mB) * 100 : Double.NaN;
                double[] ci = Stats.bootstrapCI(diffArray(a, b), 0.05, 20260927L, 4000);
                String verdict = verdictOf(t, w, ms.higherIsBetter(), diff);
                out.add(new Comparison(ms.name(), "全部", treatment.name(), control.name(), t.n(),
                        mA, mB, diff, rel, t.pTwoSided(), w.pTwoSided(), t.cohensD(), ci[0], ci[1],
                        ms.higherIsBetter(), verdict));
            }
        }
        return out;
    }

    private static double[] series(Map<String, Cell> map, List<String> keys,
                                   java.util.function.ToDoubleFunction<Cell> f) {
        if (map == null) {
            return new double[0];
        }
        List<Double> v = new ArrayList<>();
        for (String k : keys) {
            Cell c = map.get(k);
            if (c != null && c.metrics() != null) {
                v.add(f.applyAsDouble(c));
            }
        }
        double[] out = new double[v.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = v.get(i);
        }
        return out;
    }

    private static double[] diffArray(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            d[i] = a[i] - b[i];
        }
        return d;
    }

    // ------------------------------------------------------------------ 说明与渲染

    /** 把"这批数据能支持什么结论、不能支持什么"写进报告，省得后面有人过度解读。 */
    private static void addDesignNotes(Spec spec, List<Cell> cells, List<String> notes) {
        notes.add("指标全部在隐真值 g 上计算（basis=" + EvaluationMetrics.BASIS_TRUTH + "），算法看不到 g。");
        notes.add("配对检验的配对单位是 (配置, 种子)：每个格子里所有方法共用同一份数据。");
        long infeasible = cells.stream().filter(c -> !c.feasible()).count();
        notes.add(infeasible == 0
                ? "所有方法在所有格子上都产出了硬约束零违规、零未分配的可行解。"
                : "有 " + infeasible + " 个格子未产出可行解，相关均值统计已自动排除这些格子，"
                  + "报出的可行解率请与均值一起看。");
        if (spec.seeds().size() < 5) {
            notes.add("种子数仅 " + spec.seeds().size() + " 个，配对检验功效很低："
                    + "p>0.05 只说明这批样本没测出差异，不说明没有差异，请以自助置信区间为主。");
        }
        boolean hasAblation = spec.methods().stream().anyMatch(m -> m.name().contains("−"));
        if (hasAblation) {
            notes.add("消融组的\"−XX\"只改求解配置，评估指标一个都没减少。");
        }
        // 没结论的格子必须逐条说明原因。少了这段，读者看到"表 3 缺席"
        // 只会以为是没做，而看不到"超规模"（设计使然）、"原生库失败"（环境）、
        // "该块硬约束组合不可行"（真实发现）这三种截然不同的情况。
        cells.stream()
                .filter(c -> c.metrics() == null && c.note() != null && !c.note().isBlank())
                .map(Cell::note).distinct().limit(6)
                .forEach(notes::add);
        // 有结论但自身带告警的（如裁判未证明最优），也要转述
        cells.stream()
                .filter(c -> c.metrics() != null && c.note() != null && !c.note().isBlank())
                .map(Cell::note).distinct().limit(4)
                .forEach(notes::add);
    }

    /**
     * 间隙能不能读成"最优间隙"，取决于裁判有没有证明最优。
     * <p>不写这一段的后果很具体：读者会把一个"未证明最优的可行解"当成最优解，
     * 于是把"至少差 2.5%"读成"最多差 2.5%"，把算法宣传得比实际好。
     */
    private static void addGapNotes(List<Gap> gaps, List<String> notes) {
        if (gaps.isEmpty()) {
            return;
        }
        long unproven = gaps.stream().filter(g -> !g.judgeProvenOptimal()).count();
        if (unproven == 0) {
            notes.add("所有 " + gaps.size() + " 个格子的最优解都已被裁判证明最优，间隙是真实的最优间隙。");
        } else {
            notes.add(unproven + "/" + gaps.size() + " 个格子里裁判未在时限内证明最优，"
                    + "这些格子的真实最优目标值 ≥ 裁判目标值，因此报出的间隙是**下界**"
                    + "（真实间隙不小于它），表中\"裁判已证明最优\"列标为**否**。");
        }
        notes.add("裁判的目标函数与评估指标不是同一个函数：间隙衡量的是"
                + "\"在模型预测的相容度上离最优有多远\"，不是\"在隐真值 g 上离最优有多远\"。"
                + "两者可以背离，报告里要分开说。");
    }

    public static String markdown(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 表 1　各方法总体表现（全部格子汇总，均值±标准差）\n\n");
        sb.append("| 方法 | 范围 | 样本 | 均值相容度 | 最差那对 | 房内方差 | 志愿满足率 | 阻塞对数 | 公平差距 | 耗时ms | 可行率 |\n");
        sb.append("|---|---|---:|---|---|---|---|---|---|---|---:|\n");
        for (Aggregate a : r.aggregates()) {
            sb.append(String.format("| %s | %s | %d | %s | %s | %s | %s | %s | %s | %s | %.0f%% |%n",
                    a.method(), a.scope(), a.n(), a.meanCompat().pm(), a.worstCompat().pm(),
                    a.roomVariance().pm(), a.preference().pm(), a.blockingPairs().pm(),
                    a.fairnessSpread().pm(), a.runtimeMs().pm(), a.feasibleRate() * 100));
        }

        if (!r.comparisons().isEmpty()) {
            sb.append("\n## 表 2　配对显著性检验（处理组 vs 对照组，全部格子）\n\n");
            sb.append("| 指标 | 处理 | 对照 | 配对 n | 处理均值 | 对照均值 | 差值 | 相对变化 | Wilcoxon p | t p | Cohen's d | 差值 95%CI | 结论 |\n");
            sb.append("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---|\n");
            for (Comparison c : r.comparisons()) {
                sb.append(String.format(
                        "| %s | %s | %s | %d | %s | %s | %s | %s | %s | %s | %s | [%s, %s] | %s |%n",
                        c.metric(), c.treatment(), c.control(), c.n(),
                        Stats.fmt(c.meanTreatment(), 4), Stats.fmt(c.meanControl(), 4),
                        Stats.fmt(c.meanDiff(), 4),
                        Double.isFinite(c.relChangePct()) ? String.format("%+.1f%%", c.relChangePct()) : "—",
                        Stats.fmtP(c.pWilcoxon()), Stats.fmtP(c.pT()), Stats.fmt(c.cohensD(), 2),
                        Stats.fmt(c.ciLow(), 4), Stats.fmt(c.ciHigh(), 4), c.verdict()));
            }
        }

        if (!r.gaps().isEmpty()) {
            boolean allProven = r.gaps().stream().allMatch(Gap::judgeProvenOptimal);
            sb.append("\n## 表 3　与 CP-SAT 最优解的间隙（仅小规模实例）\n\n");
            sb.append("| 配置 | 种子 | 学生数 | 最优目标值 | NEST 目标值 | NEST 间隙 | 裁判已证明最优 | 裁判耗时ms | NEST耗时ms |\n");
            sb.append("|---|---:|---:|---:|---:|---:|:---:|---:|---:|\n");
            for (Gap g : r.gaps()) {
                sb.append(String.format("| %s | %d | %d | %s | %s | %s | %s | %d | %d |%n",
                        g.configLabel(), g.seed(), g.students(),
                        Stats.fmt(g.judgeObjective(), 3), Stats.fmt(g.nestObjective(), 3),
                        Double.isFinite(g.nestGapPct()) ? String.format("%.2f%%", g.nestGapPct() * 100) : "—",
                        g.judgeProvenOptimal() ? "是" : "**否**",
                        g.judgeMs(), g.nestMs()));
            }
            double avg = r.gaps().stream().mapToDouble(Gap::nestGapPct).filter(Double::isFinite).average().orElse(Double.NaN);
            String avgText = Double.isFinite(avg) ? String.format("%.2f%%", avg * 100) : "—";
            if (allProven) {
                sb.append(String.format("%n平均**最优间隙**：%s（裁判已证明最优，这是真实最优间隙）%n", avgText));
            } else {
                long unproven = r.gaps().stream().filter(g -> !g.judgeProvenOptimal()).count();
                sb.append(String.format(
                        "%n平均**间隙下界**：%s（%d/%d 个格子里裁判未在时限内证明最优，"
                                + "此时真实最优目标值 ≥ 裁判目标值，真实间隙**不小于**这个数——"
                                + "只能说\"至少差这么多\"，不能说\"最多差这么多\"）%n",
                        avgText, unproven, r.gaps().size()));
            }
        }

        sb.append("\n## 说明\n\n");
        for (String n : r.notes()) {
            sb.append("- ").append(n).append('\n');
        }
        sb.append(String.format("%n实验总耗时：%.1f 秒，cell 数：%d。%n", r.elapsedMs() / 1000.0, r.cells().size()));
        return sb.toString();
    }

    public static Map<String, Object> toJson(Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("elapsedMs", r.elapsedMs());
        m.put("notes", r.notes());
        m.put("markdown", markdown(r));

        List<Map<String, Object>> aggs = new ArrayList<>();
        for (Aggregate a : r.aggregates()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("method", a.method());
            o.put("scope", a.scope());
            o.put("n", a.n());
            o.put("meanCompat", a.meanCompat().mean());
            o.put("meanCompatStd", a.meanCompat().std());
            o.put("worstCompat", a.worstCompat().mean());
            o.put("roomVariance", a.roomVariance().mean());
            o.put("preference", a.preference().mean());
            o.put("blockingPairs", a.blockingPairs().mean());
            o.put("fairnessSpread", a.fairnessSpread().mean());
            o.put("runtimeMs", a.runtimeMs().mean());
            o.put("feasibleRate", a.feasibleRate());
            o.put("totalViolations", a.totalViolations());
            o.put("totalUnassigned", a.totalUnassigned());
            aggs.add(o);
        }
        m.put("aggregates", aggs);

        List<Map<String, Object>> cmps = new ArrayList<>();
        for (Comparison c : r.comparisons()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("metric", c.metric());
            o.put("treatment", c.treatment());
            o.put("control", c.control());
            o.put("n", c.n());
            o.put("meanTreatment", c.meanTreatment());
            o.put("meanControl", c.meanControl());
            o.put("meanDiff", c.meanDiff());
            o.put("relChangePct", c.relChangePct());
            o.put("pWilcoxon", c.pWilcoxon());
            o.put("pT", c.pT());
            o.put("cohensD", c.cohensD());
            o.put("ciLow", c.ciLow());
            o.put("ciHigh", c.ciHigh());
            o.put("higherIsBetter", c.higherIsBetter());
            o.put("verdict", c.verdict());
            o.put("favorsTreatment", c.favorsTreatment());
            cmps.add(o);
        }
        m.put("comparisons", cmps);

        List<Map<String, Object>> gs = new ArrayList<>();
        for (Gap g : r.gaps()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("configLabel", g.configLabel());
            o.put("seed", g.seed());
            o.put("students", g.students());
            o.put("judgeObjective", g.judgeObjective());
            o.put("nestObjective", g.nestObjective());
            o.put("nestGapPct", g.nestGapPct());
            o.put("judgeMs", g.judgeMs());
            o.put("nestMs", g.nestMs());
            o.put("judgeProvenOptimal", g.judgeProvenOptimal());
            o.put("gapReading", g.gapReading());
            gs.add(o);
        }
        m.put("gaps", gs);
        m.put("cellCount", r.cells().size());
        return m;
    }

    // ------------------------------------------------------------------ 内部小工具

    /**
     * 裁判是否<b>证明</b>了最优。
     *
     * <p>优先信任裁判自己写在 diagnostics 里的 {@code provenOptimal}；
     * 拿不到就退化为"告警列表里有'未证明最优'字样即视为未证明"——
     * 宁可把已证明的最优误标成未证明（结论更保守），也不能反过来。
     */
    private static boolean provenOptimal(SolveResult r) {
        Object v = r.diagnostics() == null ? null : r.diagnostics().get("provenOptimal");
        if (v instanceof Boolean b) {
            return b;
        }
        return r.warnings() == null || r.warnings().stream().noneMatch(x -> x.contains("未证明最优"));
    }

    /**
     * 由两个检验共同给出结论。
     *
     * <p>为什么不直接听 Wilcoxon：符号秩检验在 n 小时<b>物理上无法显著</b>——
     * 最小双侧 p = 2/2ⁿ，n=5 时是 0.0625，n=6 才降到 0.03125。
     * 只看它，就会把 n=5 的报告写成满屏"无显著差异"，
     * 而同一行的配对 t 检验明明写着 p&lt;0.001、Cohen's d=21。
     * 这是把"检验没有功效"误报成"没有差异"，是本项目最严重的一类报告错误。
     *
     * <p>所以规则是：Wilcoxon 够得着 0.05 就以它为准（更稳健），
     * 够不着就改用配对 t 检验，<b>并把"为什么换判据"写在结论里</b>。
     */
    private static String verdictOf(Stats.PairedT t, Stats.Wilcoxon w, boolean higherIsBetter, double meanDiff) {
        double minW = Stats.Wilcoxon.minAttainableP(w.n());
        boolean tSig = Double.isFinite(t.pTwoSided()) && t.pTwoSided() < 0.05;
        String direction = (meanDiff > 0) == higherIsBetter ? "更优" : "更差";

        if (minW > 0.05) {
            if (tSig) {
                return String.format("显著%s（配对 t 检验 p=%s；Wilcoxon 在 n=%d 时最小可能 p=%.4f，判不了显著，故以 t 检验为准）",
                        direction, Stats.fmtP(t.pTwoSided()), w.n(), minW);
            }
            return String.format("样本量不足，无法判定（n=%d：Wilcoxon 最小可能 p=%.4f，配对 t 检验 p=%s）",
                    w.n(), minW, Stats.fmtP(t.pTwoSided()));
        }

        // 注意：不能用 w.verdict(0.05)——Wilcoxon 只看差值的符号与秩，
        // 它不知道"这个指标越小越好"。用它会给"房内方差下降 36.8%"配上"显著更差"，
        // 给"耗时涨了 65 倍"配上"显著更优"，方向全部说反。
        // 结论里的方向一律由 higherIsBetter 与 meanDiff 决定（即上面的 direction）。
        if (!w.significant()) {
            return "无显著差异";
        }
        if (Double.isFinite(t.pTwoSided()) && t.pTwoSided() >= 0.05) {
            return String.format("显著%s（Wilcoxon p=%s；但配对 t 检验 p=%s 不显著，两法结论不一致，已如实保留）",
                    direction, Stats.fmtP(w.pTwoSided()), Stats.fmtP(t.pTwoSided()));
        }
        return String.format("显著%s（Wilcoxon p=%s；配对 t 检验 p=%s，两法一致）",
                direction, Stats.fmtP(w.pTwoSided()), Stats.fmtP(t.pTwoSided()));
    }

    /** 从已产出的 cell 里找出 NEST 在同一格子的软目标值（用于算间隙）。 */
    private static double objectiveOf(ProblemInstance problem, ConstraintConfig cfg, List<Cell> cells,
                                      Config config, long seed, List<Method> methods) {
        String nestName = methods.stream().filter(m -> m.kind() == Kind.NEST).findFirst()
                .map(Method::name).orElse(NestSolver.NAME);
        return cells.stream()
                .filter(c -> c.method().equals(nestName) && c.configLabel().equals(config.label()) && c.seed() == seed)
                .mapToDouble(Cell::softObjective)
                .findFirst().orElse(Double.NaN);
    }

    private static long nestRuntimeMs(List<Cell> cells, Config config, long seed) {
        // 必须精确匹配 NEST 的方法名：消融组的名字以 NEST 名开头（"…−木桶项"），
        // 用 startsWith 会挑到消融组，把消融的耗时当成主方法的耗时。
        return (long) cells.stream()
                .filter(c -> c.method().equals(NestSolver.NAME) && c.configLabel().equals(config.label())
                        && c.seed() == seed && c.metrics() != null)
                .mapToDouble(c -> c.metrics().runtimeMs())
                .findFirst().orElse(-1L);
    }

    /** 直接给定解，按 {@link RoomScorer} 的口径算软目标值（供裁判与自检共用）。 */
    public static double softObjective(ProblemInstance problem, ConstraintConfig cfg, Solution sol) {
        RoomScorer scorer = new RoomScorer(problem, cfg,
                new RuleBasedCompatibility(problem.observations(), cfg.getCompatWeights()));
        return scorer.softObjective(sol);
    }

    /** 供调用方构造"标准方法集"：基线 + NEST（+ 可选裁判 + 可选消融）。 */
    public static List<Method> standardMethods(boolean withAblations) {
        List<Method> ms = new ArrayList<>(Arrays.asList(Method.b0(), Method.b1(), Method.b2(), Method.nest()));
        if (withAblations) {
            ms.addAll(Method.ablations());
        }
        return ms;
    }
}
