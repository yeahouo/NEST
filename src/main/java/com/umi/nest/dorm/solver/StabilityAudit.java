package com.umi.nest.dorm.solver;

import com.umi.nest.dorm.model.Preference;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Solution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 稳定性审计：数"阻塞对"（blocking pair）。
 *
 * <p>定义：{@code a} 与 {@code b} <b>互相</b>把对方写进"想同住"名单，却没有住在一起。
 * 这是设计文档里唯一的<b>算法可直接观测</b>的稳定性指标（用的是志愿，不是隐真值），
 * 也是求解器要主动优化的量——"近稳定"就是"阻塞对尽量少且可量化"。
 *
 * <p>注意：容量 4 的宿舍里"零阻塞对"通常不可达（互相想住的人可能多于 4 个，
 * 或者两个人都想住但房间结构不允许），判定本身是 NP-hard，
 * 所以这里只数、不承诺归零。
 */
public final class StabilityAudit {

    private StabilityAudit() {
    }

    /** 全体互相点名的配对（无论是否同住）。 */
    public static List<long[]> mutualPairs(ProblemInstance p) {
        List<long[]> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (var e : p.preferences().entrySet()) {
            long a = e.getKey();
            for (Long b : e.getValue().preferredRoommates()) {
                if (a >= b || seen.contains(b)) {
                    continue;
                }
                Preference pb = p.preferenceOf(b);
                if (pb.preferredRoommates().contains(a)) {
                    out.add(new long[]{Math.min(a, b), Math.max(a, b)});
                }
            }
        }
        return out;
    }

    /** 阻塞对：互相点名但没住在一起（含未分配）。 */
    public static List<long[]> blockingPairs(ProblemInstance p, Solution s) {
        List<long[]> out = new ArrayList<>();
        for (long[] pair : mutualPairs(p)) {
            long ra = s.roomOf(pair[0]);
            long rb = s.roomOf(pair[1]);
            if (ra == -1 || rb == -1 || ra != rb) {
                out.add(pair);
            }
        }
        return out;
    }

    public static int blockingPairCount(ProblemInstance p, Solution s) {
        return blockingPairs(p, s).size();
    }

    /** 限定在某个学生子集内（块内修复用）的阻塞对。 */
    public static List<long[]> blockingPairsWithin(ProblemInstance p, Solution s, Collection<Long> subset) {
        Set<Long> set = new HashSet<>(subset);
        List<long[]> out = new ArrayList<>();
        for (long[] pair : mutualPairs(p)) {
            if (!set.contains(pair[0]) || !set.contains(pair[1])) {
                continue;
            }
            long ra = s.roomOf(pair[0]);
            long rb = s.roomOf(pair[1]);
            if (ra == -1 || rb == -1 || ra != rb) {
                out.add(pair);
            }
        }
        return out;
    }

    /**
     * 志愿满足率（学生视角）：每个学生的"想同住"名单里，
     * 实际住在一起的人占比，再对所有学生取平均。
     */
    public static double preferenceSatisfactionRate(ProblemInstance p, Solution s) {
        double acc = 0;
        int counted = 0;
        for (var st : p.students()) {
            List<Long> want = p.preferenceOf(st.id()).preferredRoommates();
            if (want.isEmpty()) {
                continue;
            }
            long myRoom = s.roomOf(st.id());
            int hit = 0;
            for (Long other : want) {
                if (myRoom != -1 && s.roomOf(other) == myRoom) {
                    hit++;
                }
            }
            acc += (double) hit / want.size();
            counted++;
        }
        return counted == 0 ? Double.NaN : acc / counted;
    }

    /** 未被满足的志愿条数（可解释模块的"志愿层"要用）。 */
    public static int unmetPreferenceLinks(ProblemInstance p, Solution s) {
        int unmet = 0;
        for (var st : p.students()) {
            List<Long> want = p.preferenceOf(st.id()).preferredRoommates();
            long myRoom = s.roomOf(st.id());
            for (Long other : want) {
                if (myRoom == -1 || s.roomOf(other) != myRoom) {
                    unmet++;
                }
            }
        }
        return unmet;
    }
}
