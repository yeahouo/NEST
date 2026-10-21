package com.umi.nest.dorm.eval;

import com.umi.nest.dorm.compat.RuleBasedCompatibility;
import com.umi.nest.dorm.config.ConstraintConfig;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Room;
import com.umi.nest.dorm.model.Solution;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.solver.Block;
import com.umi.nest.dorm.solver.BlockDecomposer;
import com.umi.nest.dorm.solver.GreedyConstructor;
import com.umi.nest.dorm.solver.RoomScorer;
import com.umi.nest.dorm.solver.SolveResult;
import com.umi.nest.dorm.solver.SolutionValidator;
import com.umi.nest.dorm.solver.StabilityAudit;
import com.umi.nest.dorm.solver.Violation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 四条基线与"分级消融"。
 *
 * <p>设计文档要求与现有系统正面对比，所以 B0 是<b>主对手</b>：
 * 现在学校用的规则就是"按学号/行政班顺序，同专业同年级往一间房里塞"。
 * 这个规则在硬约束上完全没问题（性别、年级、专业集中都照顾到了），
 * 唯一的缺陷是<b>它根本不知道人和人合不合得来</b>——这正是本课题要证明的差距所在。
 *
 * <p>四条线刻意共享同一套分块与房间分配，只改变"决定谁和谁住"的那一步：
 * <pre>
 *   B0 学号顺序填充    —— 现有系统规则（主对手）
 *   B1 块内随机填充    —— 随机室友下界
 *   B2 块内贪心构造    —— 只用相容度贪心，无局部搜索、无阻塞对修复
 *   NEST 贪心+局部搜索+阻塞对修复 —— 本课题方案
 * </pre>
 * 这样对比出来的差异只能归因于算法，不会掺进"可行性处理不同"的噪声。
 */
public final class BaselineSolvers {

    public static final String B0 = "B0-学号顺序(现有系统)";
    public static final String B1 = "B1-块内随机";
    public static final String B2 = "B2-贪心构造";

    private BaselineSolvers() {
    }

    /** B0：按学号升序（等价于现有系统的名册顺序）。 */
    public static SolveResult ruleByStudentId(ProblemInstance p, ConstraintConfig cfg) {
        long t0 = System.currentTimeMillis();
        List<Block> blocks = BlockDecomposer.decompose(p, cfg).blocks();
        Comparator<Student> byId = Comparator.comparingLong(Student::id);
        Solution sol = fill(blocks, block -> block.students().stream().sorted(byId).toList(), p, cfg);
        return result(B0, p, cfg, sol, System.currentTimeMillis() - t0, Map.of());
    }

    /** B1：块内随机打乱（同一分块结构下的随机室友）。 */
    public static SolveResult randomFill(ProblemInstance p, ConstraintConfig cfg) {
        long t0 = System.currentTimeMillis();
        List<Block> blocks = BlockDecomposer.decompose(p, cfg).blocks();
        Random rnd = new Random(cfg.getSeed());
        Solution sol = fill(blocks, block -> {
            List<Student> l = new ArrayList<>(block.students());
            Collections.shuffle(l, rnd);
            return l;
        }, p, cfg);
        return result(B1, p, cfg, sol, System.currentTimeMillis() - t0, Map.of());
    }

    /** B2：块内贪心构造，不做局部搜索与阻塞对修复。 */
    public static SolveResult greedyOnly(ProblemInstance p, ConstraintConfig cfg) {
        long t0 = System.currentTimeMillis();
        RuleBasedCompatibility compat = new RuleBasedCompatibility(p.observations(), cfg.getCompatWeights());
        RoomScorer scorer = new RoomScorer(p, cfg, compat);
        GreedyConstructor greedy = new GreedyConstructor(scorer);
        List<Block> blocks = BlockDecomposer.decompose(p, cfg).blocks();
        Solution sol = Solution.empty();
        for (Block b : blocks) {
            Solution part = greedy.construct(b);
            for (Map.Entry<Long, List<Long>> e : part.byRoom().entrySet()) {
                for (Long sid : e.getValue()) {
                    sol.assign(sid, e.getKey());
                }
            }
        }
        rescue(p, scorer, sol);
        return result(B2, p, cfg, sol, System.currentTimeMillis() - t0, Map.of());
    }

    // ---------------- 内部 ----------------

    /** 按给定学生顺序，逐房间填满（房间按楼栋/编号顺序，模拟人工排房的确定性顺序）。 */
    private static Solution fill(List<Block> blocks, java.util.function.Function<Block, List<Student>> order,
                                 ProblemInstance p, ConstraintConfig cfg) {
        RuleBasedCompatibility compat = new RuleBasedCompatibility(p.observations(), cfg.getCompatWeights());
        RoomScorer scorer = new RoomScorer(p, cfg, compat);
        Solution sol = Solution.empty();
        for (Block block : blocks) {
            List<Room> rooms = new ArrayList<>(block.rooms());
            rooms.sort(Comparator.comparing(Room::buildingId).thenComparingLong(Room::id));
            List<Student> students = order.apply(block);
            int cursor = 0;
            for (Room room : rooms) {
                int placed = 0;
                while (cursor < students.size() && placed < room.capacity()) {
                    Student s = students.get(cursor);
                    List<Long> trial = RoomScorer.plus(sol.occupants(room.id()), s.id());
                    if (scorer.hardOk(room, trial)) {
                        sol.assign(s.id(), room.id());
                        placed++;
                        cursor++;
                    } else {
                        // 这间房放不下这个学生，换下一间（顺序扫描时几乎不会发生）
                        break;
                    }
                }
            }
            // 兜底：块内剩余学生找任意合规位置
            while (cursor < students.size()) {
                Student s = students.get(cursor++);
                for (Room room : rooms) {
                    List<Long> trial = RoomScorer.plus(sol.occupants(room.id()), s.id());
                    if (scorer.hardOk(room, trial)) {
                        sol.assign(s.id(), room.id());
                        break;
                    }
                }
            }
        }
        rescue(p, scorer, sol);
        return sol;
    }

    private static void rescue(ProblemInstance p, RoomScorer scorer, Solution sol) {
        for (Student s : p.students()) {
            if (sol.isAssigned(s.id())) {
                continue;
            }
            for (Room r : p.rooms()) {
                if (r.gender() != s.gender()) {
                    continue;
                }
                List<Long> trial = RoomScorer.plus(sol.occupants(r.id()), s.id());
                if (scorer.hardOk(r, trial)) {
                    sol.assign(s.id(), r.id());
                    break;
                }
            }
        }
    }

    private static SolveResult result(String name, ProblemInstance p, ConstraintConfig cfg, Solution sol,
                                      long ms, Map<String, Object> diag) {
        List<Violation> vs = SolutionValidator.validate(p, cfg, sol);
        Map<String, Object> d = new LinkedHashMap<>(diag);
        d.put("occupancy", SolutionValidator.occupancyStats(p, sol));
        return new SolveResult(name, sol, ms, vs.size(),
                (int) p.students().stream().filter(s -> !sol.isAssigned(s.id())).count(),
                Double.NaN, StabilityAudit.blockingPairCount(p, sol),
                BlockDecomposer.decompose(p, cfg).blocks().size(), 0, List.of(), vs, d);
    }
}
