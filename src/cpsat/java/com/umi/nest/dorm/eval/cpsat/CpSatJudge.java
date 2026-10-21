package com.umi.nest.dorm.eval.cpsat;

import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.LinearArgument;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import com.umi.nest.dorm.compat.RuleBasedCompatibility;
import com.umi.nest.dorm.config.ConstraintConfig;
import com.umi.nest.dorm.eval.OptimalJudge;
import com.umi.nest.dorm.model.Preference;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Room;
import com.umi.nest.dorm.model.Solution;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.solver.Block;
import com.umi.nest.dorm.solver.BlockDecomposer;
import com.umi.nest.dorm.solver.RoomScorer;
import com.umi.nest.dorm.solver.SolveResult;
import com.umi.nest.dorm.solver.SolutionValidator;
import com.umi.nest.dorm.solver.StabilityAudit;
import com.umi.nest.dorm.solver.Violation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B3：用 CP-SAT 求<b>真正的最优解</b>，作为启发式算法的裁判。
 *
 * <h2>为什么这个模型是精确的，而不是"另一种启发式"</h2>
 * 关键在于 {@link RoomScorer} 的目标函数可以完全展开成"同房对"的加和：
 * <pre>
 *   qualityTerm   = wSum·Σ_对 c(a,b) − wBucket·Σ_对 max(0, θ−c)²      —— 只依赖"谁和谁同房"
 *   preferenceTerm= wPref·(Σ_对 志愿加权 + Σ_(人,房) 楼栋志愿命中)      —— 前项只依赖同房对
 *   avoidTerm     = wAvoid·Σ_对 踩雷数                                —— 只依赖同房对
 * </pre>
 * 木桶项是<b>亏空平方的和</b>（不是 min），所以它和线性项一样可加——
 * 这一点让整个目标函数可以在 CP-SAT 里用「同房对」布尔变量精确线性化，不需要任何近似。
 * 如果木桶项真的写成 {@code min(两两相容度)}，那就不能这样建模，最优间隙也就无从谈起。
 *
 * <h2>为什么按块独立求解仍然是全局最优</h2>
 * 分块是按硬约束（性别 × 专业大类 × 年级 × 特殊需求）切开的，块与块之间
 * 既没有共享房间、也没有任何跨块约束。目标函数又是逐房间求和——
 * 于是 {@code max(Σ块) = Σ max(块)}，逐块求最优之和就是全局最优。
 * 这不是近似，是把一个不可解的大问题拆成若干个可解的小问题。
 *
 * <h2>诚实的边界</h2>
 * 超过 {@link #maxStudents()} 或单个块的建模变量数超过 {@code VARIABLE_BUDGET} 时不求解、
 * 返回 {@code null}，<b>而不是返回一个次优解冒充最优</b>。
 * 报告里"没有最优间隙一栏"与"间隙是 0%"是两件完全不同的事。
 */
public final class CpSatJudge implements OptimalJudge {

    public static final String NAME = "B3-CP-SAT最优(裁判)";

    /**
     * 目标系数的定点放大倍数。
     * <p>CP-SAT 的目标函数系数是整数，而相容度是 [-1,1] 的小数。
     * 放大 10⁶ 后单个系数的取整误差 ≤5e-7，相对间隙（通常在 0.1%~5%）小四个数量级，
     * 不影响任何结论；换来的是不必引入额外的浮点中间变量。
     */
    private static final long SCALE = 1_000_000L;

    /**
     * 单个块的布尔变量预算，超了就整体拒绝，而不是给个次优解冒充最优。
     *
     * <p>这个数字是实测出来的，不是拍的：3480 变量的块（29 人 / 8 间房）跑 120 秒仍只给出
     * 可行解、证不出最优；20034 变量的块（53 人 / 14 间房）跑 180 秒连可行解都没找到
     * （求解器状态 UNKNOWN）。既然过万变量就明显无望，与其把整段预算烧在一个注定失败的
     * 模型上、最后报一个含糊的 UNKNOWN，不如快速拒绝并明说"规模超出裁判能力"。
     */
    private static final long VARIABLE_BUDGET = 15_000L;

    private static boolean loaded = false;
    private static String loadError = null;

    /** 只转储第一个失败块的模型，避免几十个块把日志淹没。 */
    private static boolean dumpedModel = false;

    private final int maxStudents;
    private final double timeLimitSeconds;

    /**
     * 上一次 {@link #solve} 为什么没给出结论。
     * <p>记下来是因为：调用方只看到 {@code null}，而"学生数超上限"（设计使然）、
     * "原生库没加载起来"（环境故障）、"这个硬约束组合本身不可行"（真正的发现）
     * 是三种必须被区分对待的情况。混成一句"裁判不可用"等于没报告。
     */
    private String lastFailure = null;
    private String lastWarnings = null;

    @Override
    public String lastFailure() {
        return lastFailure;
    }

    /** 求解器给出的告警（如"未证明最优"），供报告原样转述。 */
    public String lastWarnings() {
        return lastWarnings;
    }

    public CpSatJudge() {
        // 用系统属性而不是新构造参数：加载路径是反射调无参构造（见 OptimalJudge.load），
        // 多一个参数就要在反射里跟着改。下限 200 人 / 60 秒是实测够用的取值。
        this(Integer.getInteger("nest.cpsat.maxStudents", 200),
                parseDouble(System.getProperty("nest.cpsat.seconds"), 60.0));
    }

    private static double parseDouble(String s, double fallback) {
        try {
            return s == null || s.isBlank() ? fallback : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public CpSatJudge(int maxStudents, double timeLimitSeconds) {
        this.maxStudents = maxStudents;
        this.timeLimitSeconds = timeLimitSeconds;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int maxStudents() {
        return maxStudents;
    }

    @Override
    public SolveResult solve(ProblemInstance problem, ConstraintConfig cfg) {
        lastFailure = null;
        lastWarnings = null;
        if (problem.studentCount() > maxStudents) {
            lastFailure = "学生数 " + problem.studentCount() + " 超过裁判上限 " + maxStudents + "（设计使然，非故障）";
            return null;
        }
        try {
            if (!loaded) {
                Loader.loadNativeLibraries();
                loaded = true;
            }
        } catch (Throwable t) {
            loadError = t.getClass().getSimpleName() + ": " + t.getMessage();
            lastFailure = "OR-Tools 原生库加载失败：" + loadError;
            return null;
        }

        cfg.validate();
        long t0 = System.currentTimeMillis();
        RoomScorer scorer = new RoomScorer(problem, cfg,
                new RuleBasedCompatibility(problem.observations(), cfg.getCompatWeights()));

        List<Block> blocks = BlockDecomposer.decompose(problem, cfg).blocks();
        if (blocks.isEmpty()) {
            lastFailure = "分块结果为空，无法建模（实例可能没有可用房间）";
            return null;
        }
        // 每块时限：**不按块数平均分摊**。
        // 实测踩到的坑：n=200 时 26 个块，30 秒按块数摊成每块 2 秒。可绝大多数块只有几个人、
        // 毫秒就解完，真正吃时间的只有个别大块——平均分摊等于"把预算均给不需要的人、
        // 让需要的人饿着"，结果是每个块都没证明最优，间隙只能退化成下界。
        // 正确做法：从总预算里扣掉已用时间，每块最多用 min(剩余预算, 单块上限)。
        //
        // 并且**按学生数降序求解**：预算有限的本质是"先到先得"，那就让最可能吃时间的块先到。
        // 实测 n=200 时只有 16 人那个块（544 变量）证明不了最优，其余 25 个块全是毫秒级；
        // 让它排最后等于把它饿死，排最前才有机会在预算内收口。
        List<Block> ordered = new ArrayList<>(blocks);
        ordered.sort((a, b) -> Integer.compare(b.studentCount(), a.studentCount()));
        long deadline = System.currentTimeMillis() + (long) (timeLimitSeconds * 1000);
        double perBlockCap = timeLimitSeconds;

        Solution merged = Solution.empty();
        List<String> warnings = new ArrayList<>();
        // 逐块留档：报告里"未证明最优"如果只有一句话，读者无法判断是"整体太大"还是
        // "某一个块特别难"——这两者的处置方式完全不同（前者只能换裁判，后者调预算就够）。
        List<Map<String, Object>> blockStats = new ArrayList<>();
        boolean allOptimal = true;
        int maxBlockSize = 0;

        for (Block block : ordered) {
            maxBlockSize = Math.max(maxBlockSize, block.studentCount());
            BlockPlan plan;
            try {
                plan = new BlockPlan(block, scorer, cfg);
            } catch (RuntimeException ex) {
                // 建模本身抛异常 = 代码缺陷，但裁判不能因此把整轮实验打断；如实记下原因
                lastFailure = "块 " + block.key() + " 建模失败：" + ex.getClass().getSimpleName()
                        + " " + ex.getMessage();
                return null;
            }
            if (plan.variables() > VARIABLE_BUDGET) {
                lastFailure = "块 " + block.key() + " 需要 " + plan.variables()
                        + " 个布尔变量，超过预算 " + VARIABLE_BUDGET + "，已整体放弃求解最优解";
                warnings.add(lastFailure);
                return null;
            }
            ModelOutcome out;
            long blockStart = System.currentTimeMillis();
            try {
                double remaining = (deadline - System.currentTimeMillis()) / 1000.0;
                out = plan.solve(Math.max(1.0, Math.min(remaining, perBlockCap)));
            } catch (RuntimeException ex) {
                lastFailure = "块 " + block.key() + " 求解异常：" + ex.getClass().getSimpleName()
                        + " " + ex.getMessage();
                return null;
            }
            if (out == null || out.solution() == null) {
                int cap = block.rooms().stream().mapToInt(Room::capacity).sum();
                long noRoom = plan.studentsWithNoAllowedRoom();
                String status = out == null ? "UNKNOWN" : out.status();
                // 三种"没解出来"是完全不同的事，必须分开说，否则会被当成同一个结论：
                //   UNKNOWN   = 时间/规模问题，不是可行性结论
                //   INFEASIBLE= 要么真不可行，要么建模过约束（漏了一条本应放宽的约束）
                //   有学生无合规房间 = 确定不可行，且原因明确
                String reading;
                if (noRoom > 0) {
                    reading = "有学生在这个块里没有任何一间合规的房间，硬约束组合确定不可行";
                } else if ("UNKNOWN".equals(status)) {
                    reading = "求解器在时限内既没找到可行解、也没证明不可行——这是"
                            + "「模型太大 / 线性松弛太弱」的规模问题，**不是可行性结论**，"
                            + "不要写成「该块无解」";
                } else {
                    reading = "求解器判定 INFEASIBLE；在排除了「学生没有合规房间」之后，"
                            + "这通常意味着建模过约束（漏了某条本应放宽的约束），"
                            + "而不是真实不可行——下结论前先手工构造一组合规分配验证";
                }
                lastFailure = "块 " + block.key() + " 未求出解：求解器状态 " + status
                        + "；学生 " + block.studentCount()
                        + " 人 / 房间 " + block.rooms().size() + " 间 / 总容量 " + cap
                        + " / 无任何合规房间的学生 " + noRoom + " 人"
                        + " / 建模变量 " + plan.variables() + " 个"
                        + "；" + reading;
                warnings.add(lastFailure);
                return null;
            }
            Map<String, Object> bs = new LinkedHashMap<>();
            bs.put("key", block.key());
            bs.put("students", block.studentCount());
            bs.put("rooms", block.rooms().size());
            bs.put("variables", plan.variables());
            bs.put("status", out.optimal() ? "OPTIMAL" : "FEASIBLE");
            bs.put("ms", System.currentTimeMillis() - blockStart);
            blockStats.add(bs);

            if (!out.optimal()) {
                allOptimal = false;
            }
            for (Map.Entry<Long, List<Long>> e : out.solution().byRoom().entrySet()) {
                for (Long sid : e.getValue()) {
                    merged.assign(sid, e.getKey());
                }
            }
        }

        long ms = System.currentTimeMillis() - t0;
        List<Violation> violations = SolutionValidator.validate(problem, cfg, merged);
        int unassigned = (int) problem.students().stream().filter(s -> !merged.isAssigned(s.id())).count();
        double softObjective = scorer.softObjective(merged);

        if (!allOptimal) {
            // 措辞必须报出**实际生效**的预算口径（总预算 + 单块上限），而不是笼统的"时限 N 秒"：
            // 报告里写"时限 30 秒"而实际每块只有 2 秒，属于误导。
            // 还要点名是哪些块没证明最优——"整体太大"和"某个块特别难"的处置方式完全不同。
            String unproven = blockStats.stream()
                    .filter(b -> "FEASIBLE".equals(b.get("status")))
                    .map(b -> b.get("key") + "(" + b.get("students") + "人/" + b.get("variables")
                            + "变量/" + b.get("ms") + "ms)")
                    .collect(java.util.stream.Collectors.joining("，"));
            warnings.add(String.format(
                    "有块未在时限内证明最优（总预算 %.1f 秒，单块上限 %.1f 秒，共 %d 个块）：%s；"
                            + "目标值是可行解值而非已证明最优，因此报出的间隙是真实最优间隙的下界",
                    timeLimitSeconds, perBlockCap, blocks.size(), unproven));
        }
        lastWarnings = warnings.isEmpty() ? null : String.join("；", warnings);

        Map<String, Object> diag = new LinkedHashMap<>();
        diag.put("judge", NAME);
        diag.put("provenOptimal", allOptimal);
        diag.put("objective", softObjective);
        diag.put("blocks", blocks.size());
        diag.put("timeBudgetSeconds", timeLimitSeconds);
        diag.put("perBlockCapSeconds", perBlockCap);
        diag.put("blockStats", blockStats);
        if (loadError != null) {
            diag.put("loadError", loadError);
        }

        return new SolveResult(NAME, merged, ms, violations.size(), unassigned, softObjective,
                StabilityAudit.blockingPairCount(problem, merged), blocks.size(), maxBlockSize,
                warnings, violations, diag);
    }

    // ================================================================== 单块模型

    /** 一个块的 CP-SAT 建模与求解。 */
    private static final class BlockPlan {

        private final Block block;
        private final RoomScorer scorer;
        private final ConstraintConfig cfg;
        private final CpModel model = new CpModel();

        /** studentId → roomId → 是否入住。 */
        private final Map<Long, Map<Long, BoolVar>> x = new LinkedHashMap<>();
        private final List<LinearArgument> allVars = new ArrayList<>();

        /**
         * 块的房间总容量是否恰好等于学生数（一个床位都不剩）。
         *
         * <p>这个布尔值换来两条被蕴含但极强的新约束，是让"证明最优"从不可能变成可能的关键：
         * 容量总计等于人数 ⇒ 每间房恰好住满（≤ 变 =），且房内同房对个数恒为 C(容量,2)。
         * 两者都只是把原模型隐含的事实显式写出来，不改变可行域，但把 AND 线性化那套
         * 弱松弛的传播能力拉起来一大截——原先 16 人 / 4 间房的小块跑 150 秒都证不出最优。
         */
        private final boolean roomsExactlyFull;

        BlockPlan(Block block, RoomScorer scorer, ConstraintConfig cfg) {
            this.block = block;
            this.scorer = scorer;
            this.cfg = cfg;
            this.roomsExactlyFull = block.capacity() == block.studentCount();
            build();
        }

        long variables() {
            return allVars.size();
        }

        /**
         * 有多少个学生在这块里<b>一间合规的房都进不去</b>。
         * <p>这是判定"建模过约束"还是"真实不可行"的第一把尺子：
         * 若这个数 > 0，说明是我的变量裁剪（{@code hardOk}）把某个学生彻底封死了；
         * 若 = 0 而模型仍无解，问题就出在容量或年级同质约束上。
         */
        long studentsWithNoAllowedRoom() {
            return x.values().stream().filter(m -> m.isEmpty()).count();
        }

        private void build() {
            buildAssignmentVars();
            buildCapacity();
            buildGradeHomogeneity();
            buildObjective();
        }

        /** 建立 x[学生][房间]，并限制每人恰好住一间；不满足硬约束的组合直接不建变量（等于禁止）。 */
        private void buildAssignmentVars() {
            for (Student s : block.students()) {
                Map<Long, BoolVar> perRoom = new LinkedHashMap<>();
                List<LinearArgument> choices = new ArrayList<>();
                for (Room r : block.rooms()) {
                    if (!scorer.hardOk(r, List.of(s.id()))) {
                        continue; // 性别/特殊需求/房间年级限制不符 —— 变量根本不建
                    }
                    BoolVar v = model.newBoolVar("x_" + s.id() + "_" + r.id());
                    perRoom.put(r.id(), v);
                    choices.add(v);
                    allVars.add(v);
                }
                x.put(s.id(), perRoom);
                if (choices.isEmpty()) {
                    // 这个学生没有一间合规的房，模型必然无解；交给求解器报 INFEASIBLE
                    model.addEquality(LinearExpr.constant(0), 1);
                } else {
                    model.addEquality(LinearExpr.sum(choices.toArray(new LinearArgument[0])), 1);
                }
            }
        }

        private void buildCapacity() {
            if (!cfg.isEnforceCapacity()) {
                return;
            }
            for (Room r : block.rooms()) {
                List<LinearArgument> inRoom = new ArrayList<>();
                for (Student s : block.students()) {
                    BoolVar v = x.get(s.id()).get(r.id());
                    if (v != null) {
                        inRoom.add(v);
                    }
                }
                if (!inRoom.isEmpty()) {
                    if (roomsExactlyFull) {
                        // 容量总计 == 人数 ⇒ 每间房必然住满。把 ≤ 收紧成 = 是被蕴含的，
                        // 但对求解器来说这是天差地别的信息量。
                        model.addEquality(LinearExpr.sum(inRoom.toArray(new LinearArgument[0])), r.capacity());
                    } else {
                        model.addLessOrEqual(LinearExpr.sum(inRoom.toArray(new LinearArgument[0])), r.capacity());
                    }
                }
            }
        }

        /**
         * 年级不混住。
         * <p>分块本身通常已把年级切开，但求解器不能"因为通常成立"就不加约束——
         * 一旦有人关掉分块或改动配置，缺了这条约束的最优解就是不可行的，
         * 而裁判给出不可行解比不给裁判更糟。特殊需求房按语义豁免。
         */
        private void buildGradeHomogeneity() {
            if (!cfg.isEnforceGradeHomogeneous()) {
                return;
            }
            for (Room r : block.rooms()) {
                if (r.forSpecialNeeds()) {
                    continue;
                }
                Set<String> grades = new LinkedHashSet<>();
                for (Student s : block.students()) {
                    if (x.get(s.id()).containsKey(r.id())) {
                        grades.add(s.grade());
                    }
                }
                if (grades.size() <= 1) {
                    continue;
                }
                List<LinearArgument> indicators = new ArrayList<>();
                for (String g : grades) {
                    BoolVar used = model.newBoolVar("g_" + r.id() + "_" + g);
                    allVars.add(used);
                    List<LinearArgument> ofGrade = new ArrayList<>();
                    for (Student s : block.students()) {
                        if (!g.equals(s.grade())) {
                            continue;
                        }
                        BoolVar v = x.get(s.id()).get(r.id());
                        if (v != null) {
                            ofGrade.add(v);
                        }
                    }
                    if (ofGrade.isEmpty()) {
                        model.addEquality(used, 0);
                    } else {
                        // 有该年级的人入住 ⇒ used=1
                        model.addLessOrEqual(LinearExpr.sum(ofGrade.toArray(new LinearArgument[0])),
                                LinearExpr.term(used, r.capacity()));
                    }
                    indicators.add(used);
                }
                model.addLessOrEqual(LinearExpr.sum(indicators.toArray(new LinearArgument[0])), 1);
            }
        }

        private void buildObjective() {
            LinearExprBuilder obj = LinearExpr.newBuilder();
            ConstraintConfig.SolveWeights w = cfg.getWeights();
            List<Student> students = block.students();
            // 逐房间收集同房对变量，供下面的 C(容量,2) 等式使用
            Map<Long, List<LinearArgument>> zByRoom = new LinkedHashMap<>();

            // 同房对：z[a][b][r] = 1 ⇔ a 和 b 都住 r
            for (int i = 0; i < students.size(); i++) {
                for (int j = i + 1; j < students.size(); j++) {
                    Student a = students.get(i);
                    Student b = students.get(j);
                    long coef = pairCoefficient(a, b, w);
                    Map<Long, BoolVar> ra = x.get(a.id());
                    Map<Long, BoolVar> rb = x.get(b.id());
                    for (Room r : block.rooms()) {
                        BoolVar va = ra.get(r.id());
                        BoolVar vb = rb.get(r.id());
                        if (va == null || vb == null) {
                            continue; // 有一方进不了这间房，同房对不可能成立
                        }
                        BoolVar z = model.newBoolVar("z_" + a.id() + "_" + b.id() + "_" + r.id());
                        allVars.add(z);
                        zByRoom.computeIfAbsent(r.id(), k -> new ArrayList<>()).add(z);
                        // z = va ∧ vb 的三条线性化约束。
                        // 参数顺序踩过坑：addGreaterOrEqual(a, b) 的语义是 a ≥ b。
                        // 写成 addGreaterOrEqual(va+vb−1, z) 得到的是 z ≤ va+vb−1，
                        // 配上 z ≥ 0 就变成"每一对都必须同房"——12 人每房 4 人时直接 INFEASIBLE，
                        // 而 proto 里读到的 x+x−z ≥ 1 代数上与 va+vb−1 ≥ z 是同一个式子，
                        // 所以对着 proto 做模式匹配根本发现不了，是靠最小复现二分才定位到的。
                        model.addLessOrEqual(z, va);
                        model.addLessOrEqual(z, vb);
                        model.addGreaterOrEqual(z, LinearExpr.newBuilder().add(va).add(vb).add(-1).build());
                        if (coef != 0) {
                            obj.addTerm(z, coef);
                        }
                    }
                }
            }

            // 楼栋志愿：只依赖 (人, 房间)
            if (w.getPreference() != 0) {
                for (Student s : students) {
                    Preference pref = scorer.preferenceOf(s.id());
                    if (pref.preferredBuildings().isEmpty()) {
                        continue;
                    }
                    for (Room r : block.rooms()) {
                        BoolVar v = x.get(s.id()).get(r.id());
                        if (v == null || !pref.preferredBuildings().contains(r.buildingId())) {
                            continue;
                        }
                        long coef = Math.round(w.getPreference() * 0.5 * SCALE);
                        if (coef != 0) {
                            obj.addTerm(v, coef);
                        }
                    }
                }
            }
            // 房间恰好住满时，房内"同房对"的个数恒为 C(容量,2)。
            // 这是被蕴含的（不改变可行域）但极强的约束：z 的 AND 线性化单看每一对都很松，
            // 把一屋子里的所有对"捆"到同一个定值上，求解器的传播能力会强一个量级。
            // 实测动机：块 F|N|经管类|2025（16 人 / 4 间房 / 544 变量）不给这条时跑 150 秒
            // 都证不出最优；这是把它从"证不出来"变成"证得出来"的关键。
            if (roomsExactlyFull) {
                for (Room r : block.rooms()) {
                    List<LinearArgument> zs = zByRoom.get(r.id());
                    if (zs == null || zs.isEmpty()) {
                        continue;
                    }
                    long pairsNeeded = (long) r.capacity() * (r.capacity() - 1) / 2;
                    model.addEquality(LinearExpr.sum(zs.toArray(new LinearArgument[0])), pairsNeeded);
                }
            }
            model.maximize(obj.build());
        }

        /**
         * 同房对 (a,b) 在同一个房间时贡献的系数，按 {@link RoomScorer} 的口径逐项复现。
         * <p>这里刻意不复用 {@code RoomScorer} 的私有实现，而是按公开语义重算——
         * 若两边口径漂移，{@code ExperimentRunner} 会用 {@code RoomScorer.softObjective}
         * 复核最优解的实际目标值，对不上就会露出来。
         */
        private long pairCoefficient(Student a, Student b, ConstraintConfig.SolveWeights w) {
            double c = scorer.compat().score(a.id(), b.id());
            double v = w.getSum() * c;
            double gap = w.getBucketThreshold() - c;
            if (gap > 0) {
                v -= w.getBucket() * gap * gap;
            }
            // 志愿：只统计 a 点名 b 的有序对（与 RoomScorer 一致：a<b 且 a 点名 b）
            if (a.id() < b.id()) {
                Preference pa = scorer.preferenceOf(a.id());
                if (pa.preferredRoommates().contains(b.id())) {
                    boolean both = scorer.preferenceOf(b.id()).preferredRoommates().contains(a.id());
                    v += w.getPreference() * (both ? 1.5 : 1.0);
                }
            }
            // 避让：RoomScorer 对每个成员各查一次，互相避让会计两次
            double avoid = 0;
            if (scorer.preferenceOf(a.id()).avoidRoommates().contains(b.id())) {
                avoid += 1;
            }
            if (scorer.preferenceOf(b.id()).avoidRoommates().contains(a.id())) {
                avoid += 1;
            }
            v -= w.getAvoid() * avoid;
            return Math.round(v * SCALE);
        }

        /** 定位无解成因用：把每间房的容量与可用学生数、每人的可用房间数打出来。 */
        String allowedSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("块内学生 ").append(block.studentCount())
              .append(" 人，房间 ").append(block.rooms().size()).append(" 间\n");
            for (Room r : block.rooms()) {
                int allowed = 0;
                for (Student s : block.students()) {
                    if (x.get(s.id()).containsKey(r.id())) {
                        allowed++;
                    }
                }
                sb.append("  房 ").append(r.id())
                  .append(" 容量 ").append(r.capacity())
                  .append(" 可用学生 ").append(allowed)
                  .append(" 性别 ").append(r.gender())
                  .append(" 楼栋 ").append(r.buildingId())
                  .append(" 特殊房 ").append(r.forSpecialNeeds())
                  .append(" 年级限制 ").append(r.gradeRestriction())
                  .append('\n');
            }
            int minAllowed = Integer.MAX_VALUE;
            int maxAllowed = 0;
            for (Student s : block.students()) {
                int c = x.get(s.id()).size();
                minAllowed = Math.min(minAllowed, c);
                maxAllowed = Math.max(maxAllowed, c);
            }
            sb.append("  每人可用房间数：最少 ").append(minAllowed)
              .append("，最多 ").append(maxAllowed).append('\n');
            return sb.toString();
        }

        ModelOutcome solve(double seconds) {
            CpSolver solver = new CpSolver();
            solver.getParameters().setMaxTimeInSeconds(seconds);
            solver.getParameters().setNumSearchWorkers(8);
            solver.getParameters().setRandomSeed((int) cfg.getSeed());
            // 打开求解器日志看它到底在抱怨什么（-Dnest.cpsat.verbose=true）；
            // 默认关，否则 30 个种子的实验日志会被淹没。
            solver.getParameters().setLogSearchProgress(Boolean.getBoolean("nest.cpsat.verbose"));
            CpSolverStatus status = solver.solve(model);
            if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
                // 把求解器的原话带回去：INFEASIBLE（约束互斥）与 MODEL_INVALID（我把模型建错了）
                // 是两种完全不同的故障，混成一句"无解"会把建模 bug 伪装成一条"研究发现"。
                if (Boolean.getBoolean("nest.cpsat.verbose") && !dumpedModel) {
                    dumpedModel = true;
                    System.err.println("=== 块 " + block.key() + " 判定 " + status + "，模型如下 ===");
                    System.err.println(allowedSummary());
                    // CpModel 没有覆写 toString（只打出对象地址），必须走 model() 拿 protobuf；
                    // validate() 会直接指出模型本身哪里非法（如引用了不存在的变量）。
                    System.err.println("validate: " + model.validate());
                    System.err.println(model.model());
                }
                return new ModelOutcome(null, false, Double.NaN, status.name());
            }
            Solution sol = Solution.empty();
            for (Student s : block.students()) {
                for (Map.Entry<Long, BoolVar> e : x.get(s.id()).entrySet()) {
                    if (Boolean.TRUE.equals(solver.booleanValue(e.getValue()))) {
                        sol.assign(s.id(), e.getKey());
                        break;
                    }
                }
            }
            double objective = solver.objectiveValue() / (double) SCALE;
            return new ModelOutcome(sol, status == CpSolverStatus.OPTIMAL, objective, status.name());
        }
    }

    private record ModelOutcome(Solution solution, boolean optimal, double objective, String status) {
    }
}
