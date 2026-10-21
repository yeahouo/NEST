package com.umi.nest.dorm.solver;

import com.umi.nest.dorm.model.Preference;
import com.umi.nest.dorm.model.Room;
import com.umi.nest.dorm.model.Solution;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.model.StudentFeature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 贪心构造：块内的"初始可行解"。
 *
 * <p>三步走，顺序是有讲究的：
 * <ol>
 *   <li><b>志愿簇预分组</b>（{@link PreferenceSeeder}）：互相点名的一群人直接占一间房。
 *       志愿是算法能直接读到的强信号，先用它，再谈特征。</li>
 *   <li><b>最难的人先安置</b>：剩下的学生按"挑剔程度"排序——明确不想同住的人越多越先放，
 *       互相点名的越多越先放，特征越极端越先放。</li>
 *   <li><b>边际收益贪心填满</b>：每次挑"放进去使这间房分数涨最多"的同学，直到房间满。</li>
 * </ol>
 * 这样构造出来的解不但可行，起点已经把志愿和相容度都用上了，
 * 局部搜索只需做修补而不是从零重排。
 */
public final class GreedyConstructor {

    private final RoomScorer scorer;

    public GreedyConstructor(RoomScorer scorer) {
        this.scorer = scorer;
    }

    /** 构造结果：解本身 + 预分组用了多少间房（诊断用）。 */
    public record Built(Solution solution, int seededRooms) {
    }

    public Solution construct(Block block) {
        return constructDetailed(block).solution();
    }

    public Built constructDetailed(Block block) {
        Solution sol = Solution.empty();
        if (block.students().isEmpty()) {
            return new Built(sol, 0);
        }

        // 0) 志愿簇预分组
        int seeded = PreferenceSeeder.seed(block, sol, scorer);

        // 1) 挑剔程度排序（用输入顺序建立确定性，同一实例结果可复现）
        double[] mean = blockMean(block);
        List<Student> order = new ArrayList<>(block.students());
        order.sort(Comparator.comparingDouble((Student s) -> -pickiness(s, block, mean)));

        List<Room> rooms = new ArrayList<>(block.rooms());
        rooms.sort(Comparator.comparingInt(Room::capacity).reversed());

        for (Room room : rooms) {
            List<Long> members = new ArrayList<>(sol.occupants(room.id()));
            if (members.size() >= room.capacity()) {
                continue;
            }
            if (members.isEmpty()) {
                Long seed = firstUnassigned(order, sol);
                if (seed == null) {
                    break;
                }
                sol.assign(seed, room.id());
                members.add(seed);
            }
            while (members.size() < room.capacity()) {
                Long best = null;
                double bestGain = Double.NEGATIVE_INFINITY;
                for (Student cand : order) {
                    if (sol.isAssigned(cand.id())) {
                        continue;
                    }
                    List<Long> trial = RoomScorer.plus(members, cand.id());
                    if (!scorer.hardOk(room, trial)) {
                        continue;
                    }
                    double gain = scorer.softScore(room.id(), trial) - scorer.softScore(room.id(), members);
                    if (gain > bestGain + 1e-12) {
                        bestGain = gain;
                        best = cand.id();
                    }
                }
                if (best == null) {
                    break;
                }
                sol.assign(best, room.id());
                members.add(best);
            }
        }

        // 2) 兜底：把没安置进去的人塞进任何还有床位的房间
        for (Student s : block.students()) {
            if (sol.isAssigned(s.id())) {
                continue;
            }
            for (Room room : rooms) {
                List<Long> trial = RoomScorer.plus(sol.occupants(room.id()), s.id());
                if (scorer.hardOk(room, trial)) {
                    sol.assign(s.id(), room.id());
                    break;
                }
            }
        }
        return new Built(sol, seeded);
    }

    // ---------------- 内部 ----------------

    private static Long firstUnassigned(List<Student> order, Solution sol) {
        for (Student s : order) {
            if (!sol.isAssigned(s.id())) {
                return s.id();
            }
        }
        return null;
    }

    /** 挑剔程度：避免链接权重最高，其次是互相点名，最后是特征极端程度。 */
    private double pickiness(Student s, Block block, double[] mean) {
        Preference p = scorer.preferenceOf(s.id());
        double v = p.avoidRoommates().size() * 10.0;
        for (Long other : p.preferredRoommates()) {
            if (scorer.preferenceOf(other).preferredRoommates().contains(s.id())) {
                v += 3.0;
            }
        }
        if (scorer.compat() instanceof com.umi.nest.dorm.compat.RuleBasedCompatibility r) {
            StudentFeature f = r.featureOf(s.id());
            if (f != null) {
                double[] a = f.toArray();
                double d = 0;
                for (int i = 0; i < a.length && i < mean.length; i++) {
                    double x = Double.isNaN(a[i]) ? mean[i] : a[i];
                    d += Math.abs(x - mean[i]);
                }
                v += d;
            }
        }
        return v;
    }

    private double[] blockMean(Block block) {
        List<StudentFeature> fs = new ArrayList<>();
        if (scorer.compat() instanceof com.umi.nest.dorm.compat.RuleBasedCompatibility r) {
            for (Student s : block.students()) {
                StudentFeature f = r.featureOf(s.id());
                if (f != null) {
                    fs.add(f);
                }
            }
        }
        return StudentFeature.meanOf(fs);
    }

    /** 未被使用（供调试）。 */
    @SuppressWarnings("unused")
    private static Map<Long, Integer> indexOf(Block block) {
        Map<Long, Integer> m = new HashMap<>();
        for (int i = 0; i < block.students().size(); i++) {
            m.put(block.students().get(i).id(), i);
        }
        return m;
    }
}
