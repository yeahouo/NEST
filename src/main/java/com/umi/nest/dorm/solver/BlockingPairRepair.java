package com.umi.nest.dorm.solver;

import com.umi.nest.dorm.config.ConstraintConfig;
import com.umi.nest.dorm.model.Room;
import com.umi.nest.dorm.model.Solution;

import java.util.ArrayList;
import java.util.List;

/**
 * 阻塞对修复——"近稳定"从口号变成可量化改进的那一步。
 *
 * <p>局部搜索优化的是"整体相容度之和"，而互相点名的两个人在数值上未必是
 * 最该在一起的（他们的相容度可能只是中等），于是被拆开也得不到惩罚。
 * 这一阶段显式地把阻塞对当成一等目标去救：对每一对互相点名却没住一起的人，
 * 枚举四种修复动作，选<b>代价最小</b>的那个执行：
 * <ol>
 *   <li>把人搬进对方房间（若有空床位）</li>
 *   <li>对方搬进他的房间</li>
 *   <li>与对方房间里的某人交换</li>
 *   <li>与自己房间里的某人交换（把对方换进来）</li>
 * </ol>
 *
 * <p>接受条件是"块内软目标下降不超过 {@code blockingRepairTolerance} 的比例"——
 * 也就是允许为了少一对阻塞对而牺牲一点点整体相容度。
 * 这个容忍度是可配置的：调到 0 就是"严格不损质量"，调大就更追求稳定性。
 */
public final class BlockingPairRepair {

    /** @param before 修复前块内阻塞对；@param after 修复后；@param applied 实际执行的修复动作数 */
    public record Stats(int before, int after, int applied, int passes) {
    }

    private static final double EPS = 1e-9;

    private final RoomScorer scorer;
    private final ConstraintConfig cfg;

    public BlockingPairRepair(RoomScorer scorer, ConstraintConfig cfg) {
        this.scorer = scorer;
        this.cfg = cfg;
    }

    public Stats repair(Block block, Solution sol, long deadlineNanos) {
        var problem = scorer.problem();
        List<long[]> pairs = StabilityAudit.blockingPairsWithin(problem, sol, block.studentIds());
        int before = pairs.size();
        if (before == 0) {
            return new Stats(0, 0, 0, 0);
        }
        double blockSoft = blockSoftScore(block, sol);
        double tolerance = Math.abs(blockSoft) * cfg.getBlockingRepairTolerance() + EPS;
        int applied = 0;
        int passes = 0;

        for (int pass = 0; pass < 3; pass++) {
            if (System.nanoTime() > deadlineNanos) {
                break;
            }
            passes++;
            boolean changed = false;
            for (long[] pair : new ArrayList<>(pairs)) {
                long a = pair[0];
                long b = pair[1];
                if (sol.roomOf(a) == -1 || sol.roomOf(b) == -1) {
                    continue;
                }
                if (sol.roomOf(a) == sol.roomOf(b)) {
                    continue;
                }
                Move best = bestMove(sol, a, b);
                if (best != null && best.delta() >= -tolerance) {
                    apply(sol, best);
                    applied++;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
            pairs = StabilityAudit.blockingPairsWithin(problem, sol, block.studentIds());
        }

        return new Stats(before, pairs.size(), applied, passes);
    }

    // ---------------- 内部 ----------------

    private record Move(long a, long b, long roomA, long roomB, long occupantA, long occupantB, double delta) {
    }

    /** 找出把 a、b 凑到一起代价最小的动作；{@code occupant}= -1 表示"搬过去"而不是"交换"。 */
    private Move bestMove(Solution sol, long a, long b) {
        Move best = null;
        // 1/2: 直接搬进对方房间
        best = better(best, tryMove(sol, a, b));           // b 搬进 a 房间
        best = better(best, tryMove(sol, b, a));           // a 搬进 b 房间
        // 3/4: 与对方房间成员交换
        for (long k : sol.occupants(sol.roomOf(b))) {
            best = better(best, trySwap(sol, a, b, k));    // a 进 b 房，换出 k
        }
        for (long k : sol.occupants(sol.roomOf(a))) {
            best = better(best, trySwap(sol, b, a, k));    // b 进 a 房，换出 k
        }
        return best;
    }

    private Move better(Move cur, Move cand) {
        if (cand == null) {
            return cur;
        }
        return cur == null || cand.delta() > cur.delta() ? cand : cur;
    }

    /** 把 {@code mover} 搬进 {@code target} 的房间。 */
    private Move tryMove(Solution sol, long mover, long target) {
        long roomTarget = sol.roomOf(target);
        long roomMover = sol.roomOf(mover);
        Room rt = scorer.room(roomTarget);
        Room rm = scorer.room(roomMover);
        if (rt == null || rm == null) {
            return null;
        }
        List<Long> occT = sol.occupants(roomTarget);
        if (occT.size() >= rt.capacity()) {
            return null;
        }
        List<Long> newT = RoomScorer.plus(occT, mover);
        List<Long> newM = RoomScorer.without(sol.occupants(roomMover), mover);
        if (!scorer.hardOk(rt, newT) || !scorer.hardOk(rm, newM)) {
            return null;
        }
        double delta = scorer.scoreRoom(roomTarget, newT) + scorer.scoreRoom(roomMover, newM)
                - scorer.scoreRoom(roomTarget, occT) - scorer.scoreRoom(roomMover, sol.occupants(roomMover));
        return new Move(mover, target, roomTarget, roomMover, -1, -1, delta);
    }

    /**
     * 把 {@code mover} 换进 {@code target} 的房间，{@code evict} 被换出到 mover 原房间。
     */
    private Move trySwap(Solution sol, long mover, long target, long evict) {
        long roomT = sol.roomOf(target);
        long roomM = sol.roomOf(mover);
        if (evict == mover || evict == target) {
            return null;
        }
        Room rt = scorer.room(roomT);
        Room rm = scorer.room(roomM);
        if (rt == null || rm == null) {
            return null;
        }
        List<Long> occT = sol.occupants(roomT);
        List<Long> occM = sol.occupants(roomM);
        List<Long> newT = RoomScorer.replaced(occT, evict, mover);
        List<Long> newM = RoomScorer.replaced(occM, mover, evict);
        if (!scorer.hardOk(rt, newT) || !scorer.hardOk(rm, newM)) {
            return null;
        }
        double delta = scorer.scoreRoom(roomT, newT) + scorer.scoreRoom(roomM, newM)
                - scorer.scoreRoom(roomT, occT) - scorer.scoreRoom(roomM, occM);
        return new Move(mover, target, roomT, roomM, evict, -1, delta);
    }

    private void apply(Solution sol, Move m) {
        if (m.occupantA() == -1) {
            // 搬运
            sol.unassign(m.a());
            sol.assign(m.a(), m.roomA());
        } else {
            // 交换：m.a() 进 roomA（替换 occupantA），occupantA 进 roomB
            long evict = m.occupantA();
            long roomA = m.roomA();
            long roomB = m.roomB();
            sol.unassign(m.a());
            sol.unassign(evict);
            sol.assign(m.a(), roomA);
            sol.assign(evict, roomB);
        }
    }

    private double blockSoftScore(Block block, Solution sol) {
        double v = 0;
        for (Room r : block.rooms()) {
            List<Long> occ = sol.occupants(r.id());
            if (!occ.isEmpty()) {
                v += scorer.softScore(r.id(), occ);
            }
        }
        return v;
    }
}
