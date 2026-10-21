package com.umi.nest.dorm.solver;

import com.umi.nest.dorm.compat.CompatibilityFunction;
import com.umi.nest.dorm.compat.RuleBasedCompatibility;
import com.umi.nest.dorm.config.ConstraintConfig;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Room;
import com.umi.nest.dorm.model.Solution;
import com.umi.nest.dorm.model.Student;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;

/**
 * NEST 主求解器：分块分解 → 块内贪心构造 → 局部搜索 → 阻塞对修复 → 独立校验。
 *
 * <pre>
 *   ProblemInstance ──► BlockDecomposer ──► [Block × k]  （并行）
 *                                            │
 *                           GreedyConstructor ──► LocalSearch ──► BlockingPairRepair
 *                                            │
 *                                        合并 ──► 兜底补齐 ──► SolutionValidator
 * </pre>
 *
 * <p>几个刻意的设计点：
 * <ul>
 *   <li><b>贪心阶段不受时间预算约束</b>——先拿到一个完整可行解永远是第一优先；
 *       预算只用来限制后面两个"提质"阶段。</li>
 *   <li><b>合并后还要跑一次兜底补齐</b>：块分解在容量不足时会退化，
 *       这里再做一次全局 first-fit，确保未分配人数归零。</li>
 *   <li><b>校验用独立的 SolutionValidator</b>，不用求解器自己的判断。</li>
 *   <li>并行只发生在块之间（块之间无耦合），不引入任何锁。</li>
 * </ul>
 */
public final class NestSolver {

    public static final String NAME = "nest-block-greedy-ls-repair-v1";

    /** 单块求解的内部产出。 */
    private record BlockOutcome(Block block, Solution solution, long greedyMs, long lsMs, long repairMs,
                                LocalSearchOptimizer.Stats ls, BlockingPairRepair.Stats repair, int violations) {
    }

    public SolveResult solve(ProblemInstance problem, ConstraintConfig cfg) {
        long t0 = System.currentTimeMillis();
        cfg.validate();
        CompatibilityFunction compat = new RuleBasedCompatibility(problem.observations(), cfg.getCompatWeights());
        RoomScorer scorer = new RoomScorer(problem, cfg, compat);

        BlockDecomposer.Result decomposition = BlockDecomposer.decompose(problem, cfg);
        List<Block> blocks = decomposition.blocks();
        List<String> warnings = new ArrayList<>(decomposition.warnings());
        if (blocks.isEmpty()) {
            warnings.add("没有任何块可求解（学生或房间为空）");
        }

        long deadline = System.nanoTime() + cfg.getTimeBudgetMs() * 1_000_000L;
        List<BlockOutcome> outcomes = solveBlocks(blocks, scorer, cfg, deadline);

        // 合并
        Solution merged = Solution.empty();
        int maxBlockSize = 0;
        for (BlockOutcome o : outcomes) {
            maxBlockSize = Math.max(maxBlockSize, o.block().studentCount());
            for (Map.Entry<Long, List<Long>> e : o.solution().byRoom().entrySet()) {
                for (Long sid : e.getValue()) {
                    merged.assign(sid, e.getKey());
                }
            }
        }

        // 兜底补齐：任何仍未分配的学生，用 first-fit 塞进任意合规房间
        int rescued = rescueUnassigned(problem, scorer, merged);

        long elapsedMs = System.currentTimeMillis() - t0;
        List<Violation> violations = SolutionValidator.validate(problem, cfg, merged);
        int unassigned = (int) problem.students().stream().filter(s -> !merged.isAssigned(s.id())).count();
        double soft = scorer.softObjective(merged);
        int blocking = StabilityAudit.blockingPairCount(problem, merged);

        Map<String, Object> diag = buildDiagnostics(outcomes, decomposition, rescued, elapsedMs, cfg, scorer, merged);

        SolveResult result = new SolveResult(NAME, merged, elapsedMs, violations.size(), unassigned, soft,
                blocking, blocks.size(), maxBlockSize, List.copyOf(warnings), List.copyOf(violations), diag);
        return result;
    }

    // ---------------- 块并行 ----------------

    private List<BlockOutcome> solveBlocks(List<Block> blocks, RoomScorer scorer, ConstraintConfig cfg,
                                           long deadlineNanos) {
        int threads = cfg.getParallelThreads() > 0
                ? cfg.getParallelThreads()
                : Math.max(1, Runtime.getRuntime().availableProcessors());
        boolean parallel = cfg.isParallelBlocks() && blocks.size() > 1 && threads > 1;
        if (!parallel) {
            List<BlockOutcome> out = new ArrayList<>(blocks.size());
            for (Block b : blocks) {
                out.add(solveBlock(b, scorer, cfg, deadlineNanos));
            }
            return out;
        }
        ForkJoinPool pool = new ForkJoinPool(threads);
        try {
            return pool.submit(() -> blocks.parallelStream()
                    .map(b -> solveBlock(b, scorer, cfg, deadlineNanos))
                    .toList()).join();
        } finally {
            pool.shutdown();
        }
    }

    private BlockOutcome solveBlock(Block block, RoomScorer scorer, ConstraintConfig cfg, long deadlineNanos) {
        long t0 = System.currentTimeMillis();
        GreedyConstructor greedy = new GreedyConstructor(scorer);
        Solution sol = greedy.construct(block);
        long t1 = System.currentTimeMillis();

        // 每块独立随机种子：同一实例同一配置必须复现，但不能所有块用同一个序列
        Random rnd = new Random(cfg.getSeed() * 31L + block.key().hashCode());
        LocalSearchOptimizer ls = new LocalSearchOptimizer(scorer, cfg);
        LocalSearchOptimizer.Stats lsStats = ls.optimize(block, sol, deadlineNanos, rnd);
        long t2 = System.currentTimeMillis();

        BlockingPairRepair.Stats repairStats = new BlockingPairRepair.Stats(0, 0, 0, 0);
        if (cfg.isEnableBlockingPairRepair()) {
            BlockingPairRepair repair = new BlockingPairRepair(scorer, cfg);
            repairStats = repair.repair(block, sol, deadlineNanos);
        }
        long t3 = System.currentTimeMillis();

        int v = 0;
        for (Room r : block.rooms()) {
            v += scorer.violationUnits(r, sol.occupants(r.id()));
        }
        return new BlockOutcome(block, sol, t1 - t0, t2 - t1, t3 - t2, lsStats, repairStats, v);
    }

    // ---------------- 兜底 ----------------

    /** 把未分配的学生塞进任何仍未满且合规的房间；返回救回的人数。 */
    private int rescueUnassigned(ProblemInstance problem, RoomScorer scorer, Solution sol) {
        int rescued = 0;
        for (Student s : problem.students()) {
            if (sol.isAssigned(s.id())) {
                continue;
            }
            for (Room r : problem.rooms()) {
                if (r.gender() != s.gender()) {
                    continue;
                }
                List<Long> trial = RoomScorer.plus(sol.occupants(r.id()), s.id());
                if (scorer.hardOk(r, trial)) {
                    sol.assign(s.id(), r.id());
                    rescued++;
                    break;
                }
            }
        }
        return rescued;
    }

    // ---------------- 诊断 ----------------

    private Map<String, Object> buildDiagnostics(List<BlockOutcome> outcomes, BlockDecomposer.Result decomposition,
                                                 int rescued, long elapsedMs, ConstraintConfig cfg,
                                                 RoomScorer scorer, Solution sol) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("solver", NAME);
        d.put("runtimeMs", elapsedMs);
        d.put("timeBudgetMs", cfg.getTimeBudgetMs());
        d.put("blockCount", outcomes.size());
        d.put("unallocatedRooms", decomposition.unallocatedRooms());
        d.put("rescuedUnassigned", rescued);

        int maxBlock = 0;
        long sumGreedy = 0;
        long sumLs = 0;
        long sumRepair = 0;
        int lsAccepted = 0;
        int repairApplied = 0;
        int blockViolations = 0;
        Map<String, Integer> byPool = new LinkedHashMap<>();
        for (BlockOutcome o : outcomes) {
            maxBlock = Math.max(maxBlock, o.block().studentCount());
            sumGreedy += o.greedyMs();
            sumLs += o.lsMs();
            sumRepair += o.repairMs();
            lsAccepted += o.ls().accepted();
            repairApplied += o.repair().applied();
            blockViolations += o.violations();
            byPool.merge(BlockDecomposer.poolOf(o.block().key()), 1, Integer::sum);
        }
        d.put("maxBlockSize", maxBlock);
        d.put("greedyMsTotal", sumGreedy);
        d.put("localSearchMsTotal", sumLs);
        d.put("blockingRepairMsTotal", sumRepair);
        d.put("localSearchAcceptedMoves", lsAccepted);
        d.put("blockingRepairsApplied", repairApplied);
        d.put("violationsBeforeRescueInBlocks", blockViolations);
        d.put("blocksByPool", byPool);
        int small = 0;
        for (BlockOutcome o : outcomes) {
            if (o.block().studentCount() < cfg.getMinBlockSize()) {
                small++;
            }
        }
        d.put("smallBlocks", small);
        d.put("minBlockSize", cfg.getMinBlockSize());
        d.put("occupancy", SolutionValidator.occupancyStats(scorer.problem(), sol));
        return d;
    }
}
