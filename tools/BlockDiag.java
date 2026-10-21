import com.umi.nest.dorm.compat.RuleBasedCompatibility;
import com.umi.nest.dorm.config.ConstraintConfig;
import com.umi.nest.dorm.data.GenParams;
import com.umi.nest.dorm.data.SyntheticDataGenerator;
import com.umi.nest.dorm.data.SyntheticDataset;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Room;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.solver.Block;
import com.umi.nest.dorm.solver.BlockDecomposer;
import com.umi.nest.dorm.solver.NestSolver;
import com.umi.nest.dorm.solver.RoomScorer;
import com.umi.nest.dorm.solver.SolveResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一次性诊断探针：为什么某个块的 CP-SAT 模型会无解。
 *
 * <p>要回答的是——「建模过约束」还是「真实不可行」。
 * 做法是把每个块的<b>名义容量</b>（= Σ room.capacity()，分块器用它判断够不够）
 * 与<b>有效容量</b>（= Σ min(capacity, 该房实际可用学生数)，硬约束裁剪后的真实容量）
 * 并排打出来。两者一旦不等，就说明分块器高估了容量，模型无解是分块器的账算错了，
 * 不是硬约束本身不可行。
 *
 * 编译运行（不需要 OR-Tools）：
 *   javac -encoding UTF-8 -cp "target\classes;$cp" -d target\tools tools\BlockDiag.java
 *   java -cp "target\tools;target\classes;$cp" BlockDiag
 */
public class BlockDiag {

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 120;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 1L;
        GenParams gp = new GenParams(n, 4, 0.20, 0.0, 0.5, 3, 0.03, seed, true, 3);
        SyntheticDataset ds = SyntheticDataGenerator.generate(gp);
        ProblemInstance p = ds.problem();

        ConstraintConfig cfg = ConstraintConfig.defaults();
        cfg.setSeed(seed);
        cfg.validate();
        RoomScorer scorer = new RoomScorer(p, cfg,
                new RuleBasedCompatibility(p.observations(), cfg.getCompatWeights()));

        List<Block> blocks = BlockDecomposer.decompose(p, cfg).blocks();
        System.out.printf("实例 n=%d seed=%d：学生 %d / 房间 %d / 块 %d%n",
                n, seed, p.studentCount(), p.rooms().size(), blocks.size());
        System.out.println("（名义容量 = Σ room.capacity()，分块器用它判断够不够；"
                + "有效容量 = Σ min(capacity, 该房实际可用学生数)）");
        System.out.println();

        List<Block> bad = new ArrayList<>();
        for (Block b : blocks) {
            int eff = 0;
            for (Room r : b.rooms()) {
                int allowed = 0;
                for (Student s : b.students()) {
                    if (scorer.hardOk(r, List.of(s.id()))) {
                        allowed++;
                    }
                }
                eff += Math.min(r.capacity(), allowed);
            }
            String flag = eff < b.studentCount() ? "  <<< 有效容量不足，模型必然无解" : "";
            System.out.printf("%-24s 学生 %3d / 房 %2d / 名义 %3d / 有效 %3d%s%n",
                    b.key(), b.studentCount(), b.rooms().size(), b.capacity(), eff, flag);
            if (eff < b.studentCount()) {
                bad.add(b);
            }
        }

        if (bad.isEmpty()) {
            System.out.println("\n没有容量不足的块；模型无解的原因在容量之外（查年级同质或建模缺陷）。");
            return;
        }

        SolveResult sr = new NestSolver().solve(p, cfg);
        System.out.printf("%n=== NestSolver 全局结果：可行=%s 硬违规=%d 未分配=%d ===%n",
                sr.feasible(), sr.hardViolationCount(), sr.unassignedCount());

        for (Block b : bad) {
            System.out.printf("%n=== 解剖问题块 %s ===%n", b.key());
            Map<String, Integer> byGrade = new TreeMap<>();
            int special = 0;
            for (Student s : b.students()) {
                byGrade.merge(s.grade(), 1, Integer::sum);
                if (s.isSpecial()) {
                    special++;
                }
            }
            System.out.println("  学生年级分布 " + byGrade + "，特殊需求 " + special + " 人");

            for (Room r : b.rooms()) {
                int allowed = 0;
                Map<String, Integer> blockers = new LinkedHashMap<>();
                for (Student s : b.students()) {
                    if (scorer.hardOk(r, List.of(s.id()))) {
                        allowed++;
                    } else {
                        blockers.merge(reason(s, r, cfg), 1, Integer::sum);
                    }
                }
                System.out.printf("  房 %-6d 容量 %d 性别 %s 楼栋 %-3s 特殊房 %-5s 年级限制 %-5s → 可用 %2d (有效容量 %d) 阻断 %s%n",
                        r.id(), r.capacity(), r.gender(), r.buildingId(), r.forSpecialNeeds(),
                        r.gradeRestriction(), allowed, Math.min(r.capacity(), allowed), blockers);
            }

            Map<Integer, Integer> hist = new TreeMap<>();
            long outside = 0;
            for (Student s : b.students()) {
                int cnt = 0;
                for (Room r : b.rooms()) {
                    if (scorer.hardOk(r, List.of(s.id()))) {
                        cnt++;
                    }
                }
                hist.merge(cnt, 1, Integer::sum);
                long rid = sr.solution().roomOf(s.id());
                boolean inBlock = b.rooms().stream().anyMatch(r -> r.id() == rid);
                if (!inBlock) {
                    outside++;
                }
            }
            System.out.println("  每人可用房间数分布 " + hist + "（0 = 完全没地方去）");
            System.out.printf("  NestSolver 把 %d / %d 个学生放到了**本块之外**的房间%n", outside, b.studentCount());

            b.students().stream()
                    .sorted(Comparator.comparingLong(Student::id))
                    .limit(20)
                    .forEach(s -> {
                        long rid = sr.solution().roomOf(s.id());
                        boolean inBlock = b.rooms().stream().anyMatch(r -> r.id() == rid);
                        System.out.printf("    学生 %-6d 年级 %s 特殊 %-5s → 房 %-6d %s%n",
                                s.id(), s.grade(), s.isSpecial(), rid, inBlock ? "" : "<-- 块外");
                    });
        }
    }

    /** 单人进单房被拒的原因（用于把"可用 0"解释清楚）。 */
    private static String reason(Student s, Room r, ConstraintConfig cfg) {
        if (cfg.isEnforceCapacity() && r.capacity() <= 0) {
            return "容量为0";
        }
        if (cfg.isEnforceGenderMatch() && s.gender() != r.gender()) {
            return "性别不符";
        }
        if (cfg.isEnforceSpecialNeeds()) {
            if (s.isSpecial() && !r.forSpecialNeeds()) {
                return "特殊生缺特殊房";
            }
            if (!s.isSpecial() && r.forSpecialNeeds()) {
                return "普通生不得进特殊房";
            }
        }
        if (cfg.isEnforceRoomGradeRestriction() && r.gradeRestriction() != null
                && !r.gradeRestriction().equals(s.grade())) {
            return "房间年级限制 " + r.gradeRestriction();
        }
        return "其它";
    }
}
