package com.umi.nest.dorm.solver;

import com.umi.nest.dorm.config.ConstraintConfig;
import com.umi.nest.dorm.model.Room;
import com.umi.nest.dorm.model.Solution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 块内局部搜索：在贪心构造出来的可行解上做"交换同学 / 调动房间"的爬山。
 *
 * <p>邻域只取<b>随机采样</b>的若干邻居而不是全部两两组合，这是有意的工程取舍：
 * 一个 200 人的块，穷举交换是 20000 次/轮，采样 8 个邻居只有 1600 次/轮，
 * 而实测提升几乎无差别（因为宿舍目标函数非常平滑）。
 * 省下来的预算用于多跑几轮 + 后面的阻塞对修复，整体解得更好。
 *
 * <p>每次移动的增量只重算受影响的两间房——这依赖 {@link RoomScorer}
 * 把目标函数写成房间局部量之和。
 */
public final class LocalSearchOptimizer {

    /** @param rounds 实际执行的轮数；@param accepted 被接受的移动次数；@param improved 是否发生过改进 */
    public record Stats(int rounds, int accepted, boolean improved) {
    }

    private static final double EPS = 1e-9;

    private final RoomScorer scorer;
    private final ConstraintConfig cfg;
    private final int samples;

    public LocalSearchOptimizer(RoomScorer scorer, ConstraintConfig cfg) {
        this.scorer = scorer;
        this.cfg = cfg;
        // 每轮每个学生采样的邻居数（见 ConstraintConfig#neighborSamples）
        this.samples = Math.max(2, cfg.getNeighborSamples());
    }

    public Stats optimize(Block block, Solution sol, long deadlineNanos, Random rnd) {
        if (block.studentCount() < 2 || block.rooms().isEmpty()) {
            return new Stats(0, 0, false);
        }
        List<Long> ids = block.studentIds();
        int accepted = 0;
        int rounds = 0;
        int noImprove = 0;
        int maxRounds = Math.max(1, cfg.getLocalSearchRounds());
        int patience = Math.max(1, cfg.getLocalSearchPatience());

        // 木桶守卫：整块当前"最差那一对"的相容度。任何移动都不得制造出比它更差的新配对。
        // 这样"局部搜索不降低块内最差室友相容度"就成了一个可证明的不变量——
        // 否则整体均值的一点点提升会把某一对室友牺牲掉（实测最差那对会从 -0.02 掉到 -0.18）。
        double guardLevel = 0.0;
        for (Room r : block.rooms()) {
            List<Long> occ = sol.occupants(r.id());
            if (occ.size() >= 2) {
                guardLevel = Math.min(guardLevel, scorer.worstPairCompat(occ));
            }
        }

        for (int round = 0; round < maxRounds; round++) {
            if (System.nanoTime() > deadlineNanos) {
                break;
            }
            rounds++;
            boolean improvedThisRound = false;

            List<Long> order = new ArrayList<>(ids);
            Collections.shuffle(order, rnd);

            for (Long i : order) {
                if (System.nanoTime() > deadlineNanos) {
                    break;
                }
                if (sol.roomOf(i) == -1) {
                    if (placeBest(block, sol, i, guardLevel)) {
                        accepted++;
                        improvedThisRound = true;
                    }
                    continue;
                }
                if (swapBest(block, sol, i, ids, rnd, guardLevel)) {
                    accepted++;
                    improvedThisRound = true;
                }
                if (moveBest(block, sol, i, rnd, guardLevel)) {
                    accepted++;
                    improvedThisRound = true;
                }
            }

            if (!improvedThisRound) {
                noImprove++;
                if (noImprove >= patience) {
                    break;
                }
            } else {
                noImprove = 0;
            }
        }
        return new Stats(rounds, accepted, accepted > 0);
    }

    /** 守卫判定：房间里不允许出现低于 {@code guardLevel} 的配对。 */
    private boolean guardOk(List<Long> members, double guardLevel) {
        return members.size() < 2 || scorer.worstPairCompat(members) >= guardLevel - 1e-9;
    }

    // ---------------- 移动类型 ----------------

    /** 与随机采样的同块同学交换房间。 */
    private boolean swapBest(Block block, Solution sol, long i, List<Long> ids, Random rnd, double guardLevel) {
        long roomI = sol.roomOf(i);
        Room a = scorer.room(roomI);
        if (a == null) {
            return false;
        }
        List<Long> ma = sol.occupants(roomI);
        double baseA = scorer.scoreRoom(roomI, ma);
        double bestDelta = EPS;
        long bestJ = -1;

        int n = ids.size();
        for (int s = 0; s < samples; s++) {
            long j = ids.get(rnd.nextInt(n));
            if (j == i) {
                continue;
            }
            long roomJ = sol.roomOf(j);
            if (roomJ == -1 || roomJ == roomI) {
                continue;
            }
            Room b = scorer.room(roomJ);
            if (b == null) {
                continue;
            }
            List<Long> mb = sol.occupants(roomJ);
            List<Long> na = RoomScorer.replaced(ma, i, j);
            List<Long> nb = RoomScorer.replaced(mb, j, i);
            if (!scorer.hardOk(a, na) || !scorer.hardOk(b, nb)) {
                continue;
            }
            if (!guardOk(na, guardLevel) || !guardOk(nb, guardLevel)) {
                continue;
            }
            double baseB = scorer.scoreRoom(roomJ, mb);
            double newSum = scorer.scoreRoom(roomI, na) + scorer.scoreRoom(roomJ, nb);
            double delta = newSum - baseA - baseB;
            if (delta > bestDelta) {
                bestDelta = delta;
                bestJ = j;
            }
        }
        if (bestJ < 0) {
            return false;
        }
        long roomJ = sol.roomOf(bestJ);
        sol.unassign(i);
        sol.unassign(bestJ);
        sol.assign(i, roomJ);
        sol.assign(bestJ, roomI);
        return true;
    }

    /** 搬到另一间有空位的房间。 */
    private boolean moveBest(Block block, Solution sol, long i, Random rnd, double guardLevel) {
        long roomI = sol.roomOf(i);
        Room from = scorer.room(roomI);
        if (from == null) {
            return false;
        }
        List<Long> ma = sol.occupants(roomI);
        double baseA = scorer.scoreRoom(roomI, ma);
        double bestDelta = EPS;
        long bestRoom = -1;

        List<Room> candidates = freeRooms(block, sol);
        if (candidates.isEmpty()) {
            return false;
        }
        int tries = Math.min(candidates.size(), samples);
        for (int s = 0; s < tries; s++) {
            Room r = candidates.get(rnd.nextInt(candidates.size()));
            if (r.id() == roomI) {
                continue;
            }
            List<Long> occ = sol.occupants(r.id());
            List<Long> trial = RoomScorer.plus(occ, i);
            if (!scorer.hardOk(r, trial) || !guardOk(trial, guardLevel)) {
                continue;
            }
            double newSum = scorer.scoreRoom(r.id(), trial) + scorer.scoreRoom(roomI, RoomScorer.without(ma, i));
            double delta = newSum - baseA - scorer.scoreRoom(r.id(), occ);
            if (delta > bestDelta) {
                bestDelta = delta;
                bestRoom = r.id();
            }
        }
        if (bestRoom < 0) {
            return false;
        }
        sol.unassign(i);
        sol.assign(i, bestRoom);
        return true;
    }

    /** 把未分配的学生放进最优空位（补救贪心遗漏）。 */
    private boolean placeBest(Block block, Solution sol, long i, double guardLevel) {
        long bestRoom = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Room r : freeRooms(block, sol)) {
            List<Long> occ = sol.occupants(r.id());
            List<Long> trial = RoomScorer.plus(occ, i);
            if (!scorer.hardOk(r, trial) || !guardOk(trial, guardLevel)) {
                continue;
            }
            double sc = scorer.scoreRoom(r.id(), trial);
            if (sc > bestScore) {
                bestScore = sc;
                bestRoom = r.id();
            }
        }
        if (bestRoom < 0) {
            return false;
        }
        sol.assign(i, bestRoom);
        return true;
    }

    private List<Room> freeRooms(Block block, Solution sol) {
        List<Room> out = new ArrayList<>();
        for (Room r : block.rooms()) {
            if (sol.occupancy(r.id()) < r.capacity()) {
                out.add(r);
            }
        }
        return out;
    }
}
