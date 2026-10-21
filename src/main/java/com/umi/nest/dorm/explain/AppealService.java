package com.umi.nest.dorm.explain;

import com.umi.nest.dorm.config.ConstraintConfig;
import com.umi.nest.dorm.model.Preference;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Room;
import com.umi.nest.dorm.model.Solution;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.solver.Block;
import com.umi.nest.dorm.solver.BlockDecomposer;
import com.umi.nest.dorm.solver.RoomScorer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 申诉试算：固定其余人不动，只在<b>局部</b>重解，看能不能满足这条诉求。
 *
 * <p>为什么不做全局重解：一是慢（万级数据全局重解要几十秒，学生点一下等不起），
 * 二是<b>不公平</b>——为了一个人的诉求把全校重新洗一遍，等于把别人的分配当筹码。
 * 所以这里的方向是反的：先算"满足你的诉求要付多少代价"，代价在预算内才给方案。
 *
 * <p>试算分两层，范围对学生完全透明：
 * <ol>
 *   <li><b>房间级</b>：只在目标房间里做一对一对调，毫秒级；</li>
 *   <li><b>块级</b>：把搜索扩到整块（同性别、同级、同专业大类），数千人也是秒级。</li>
 * </ol>
 * 搜完两层还没找到，回答是"<b>在我搜过的范围里没找到</b>"，
 * 而不是"无解"——这两句话的含义完全不同，不能替系统吹牛。
 */
public final class AppealService {

    /** 默认代价预算：不超过本块软目标的 2%，且至少 0.5 分，避免"为了一个人牺牲一群人"。 */
    public static final double DEFAULT_BUDGET_RATIO = 0.02;

    private final ProblemInstance problem;
    private final ConstraintConfig cfg;
    private final RoomScorer scorer;
    private final Map<Long, String> blockKeyOf = new HashMap<>();
    private final Map<Long, List<Long>> blockStudents = new HashMap<>();
    private final Map<Long, Block> blockOf = new HashMap<>();

    public AppealService(ProblemInstance problem, ConstraintConfig cfg) {
        this.problem = problem;
        this.cfg = cfg;
        this.scorer = new RoomScorer(problem, cfg,
                new com.umi.nest.dorm.compat.RuleBasedCompatibility(problem.observations(), cfg.getCompatWeights()));
        BlockDecomposer.Result dec = BlockDecomposer.decompose(problem, cfg);
        for (Block b : dec.blocks()) {
            for (Long id : b.studentIds()) {
                blockKeyOf.put(id, b.key());
                blockOf.put(id, b);
                blockStudents.put(id, b.studentIds());
            }
        }
    }

    public RoomScorer scorer() {
        return scorer;
    }

    /** 按块软目标的 2% 估算代价预算。 */
    public double defaultBudget(Solution sol, long studentId) {
        Block b = blockOf.get(studentId);
        if (b == null) {
            return 0.5;
        }
        double soft = 0;
        for (Room r : b.rooms()) {
            soft += scorer.softScore(r.id(), sol.occupants(r.id()));
        }
        return Math.max(0.5, Math.abs(soft) * DEFAULT_BUDGET_RATIO);
    }

    /**
     * 试算"我想和这些人住一起"。
     *
     * @param wantRoomWith 希望同住的同学 id（至少要有一个已经在这次分配里有房间）
     */
    public AppealResult tryRoommateRequest(Solution sol, long studentId, Collection<Long> wantRoomWith) {
        long t0 = System.nanoTime();
        double budget = defaultBudget(sol, studentId);
        return tryRoommateRequest(sol, studentId, wantRoomWith, budget, t0);
    }

    public AppealResult tryRoommateRequest(Solution sol, long studentId, Collection<Long> wantRoomWith,
                                           double costBudget, long startNanos) {
        Student me = problem.byId().get(studentId);
        if (me == null) {
            return fail(AppealResult.UNSATISFIABLE, "名单里没有这位同学", "—", startNanos, costBudget);
        }
        Set<Long> wants = new LinkedHashSet<>(wantRoomWith);
        wants.remove(studentId);
        if (wants.isEmpty()) {
            return fail(AppealResult.UNSATISFIABLE, "没有指定希望同住的同学", "—", startNanos, costBudget);
        }

        // 已经在同一间房 —— 直接告诉他，不用试算
        long myRoom = sol.roomOf(studentId);
        if (myRoom != -1) {
            List<Long> unmet = wants.stream().filter(w -> sol.roomOf(w) != myRoom).toList();
            if (unmet.isEmpty()) {
                return new AppealResult(AppealResult.SATISFIED,
                        "你希望同住的 " + names(wants) + " 已经全部和你住在一起，无需调整",
                        "无需搜索", List.of(), 0.0, costBudget, elapsed(startNanos));
            }
        }

        // 硬约束先筛：哪些人根本不可能和你同住
        List<String> hardBlocks = new ArrayList<>();
        List<Long> feasibleWants = new ArrayList<>();
        for (Long w : wants) {
            Student other = problem.byId().get(w);
            if (other == null) {
                hardBlocks.add(nameOf(w) + " 不在名单里");
                continue;
            }
            if (other.gender() != me.gender()) {
                hardBlocks.add(nameOf(w) + " 与你性别不同（硬约束，任何方案都不可能同住）");
                continue;
            }
            if (other.isSpecial() != me.isSpecial()) {
                hardBlocks.add(nameOf(w) + " 的特殊需求登记与你不同，需住不同类型房间（硬约束）");
                continue;
            }
            if (cfg.isEnforceGradeHomogeneous() && !other.grade().equals(me.grade())) {
                hardBlocks.add(nameOf(w) + " 与你年级不同（硬约束：年级不混住）");
                continue;
            }
            if (!sol.isAssigned(w)) {
                hardBlocks.add(nameOf(w) + " 本次未分配房间，无法作为目标");
                continue;
            }
            feasibleWants.add(w);
        }
        if (feasibleWants.isEmpty()) {
            return fail(AppealResult.UNSATISFIABLE,
                    "这条诉求在你的硬约束下不可能满足：" + String.join("；", hardBlocks),
                    "房间级 + 块级（已先做硬约束可行性判定）", startNanos, costBudget);
        }

        // 目标房间：装着最多"想同住的人"的那间
        Map<Long, Integer> hits = new HashMap<>();
        for (Long w : feasibleWants) {
            hits.merge(sol.roomOf(w), 1, Integer::sum);
        }
        List<Long> targets = new ArrayList<>(hits.keySet());
        targets.sort(Comparator.comparingInt((Long r) -> -hits.get(r)));

        // ---- 第一层：房间级（只和该房间里的人对调，或直接搬进空床位） ----
        List<AppealResult.Swap> roomLevel = new ArrayList<>();
        for (Long target : targets) {
            roomLevel.addAll(candidatesInto(sol, studentId, target, sol.occupants(target)));
        }
        AppealResult.Swap pick = bestWithin(roomLevel, costBudget);
        if (pick != null) {
            return new AppealResult(AppealResult.SATISFIED,
                    "可以满足：把 " + pick.studentName() + " 与 " + pick.otherName() + " 对调即可（"
                            + pick.fromLabel() + " ⇄ " + pick.toLabel() + "）",
                    "房间级（只动目标房间内部，毫秒级）",
                    List.of(pick), pick.delta(), costBudget, elapsed(startNanos));
        }

        // ---- 第二层：块级（把搜索扩到整块） ----
        List<Long> blockIds = blockStudents.getOrDefault(studentId, List.of());
        List<AppealResult.Swap> blockLevel = new ArrayList<>(roomLevel);
        for (Long other : blockIds) {
            if (other == studentId || sol.roomOf(other) == -1) {
                continue;
            }
            long otherRoom = sol.roomOf(other);
            Room rb = scorer.room(otherRoom);
            if (rb == null || !hits.containsKey(otherRoom)) {
                // 只有当"对方所在房间"是含志愿对象的房间时，换过去才有意义
                continue;
            }
            blockLevel.addAll(candidatesInto(sol, studentId, otherRoom, sol.occupants(otherRoom)));
        }
        pick = bestWithin(blockLevel, costBudget);
        if (pick != null) {
            return new AppealResult(AppealResult.SATISFIED,
                    "可以满足：把 " + pick.studentName() + " 与 " + pick.otherName() + " 对调即可（"
                            + pick.fromLabel() + " ⇄ " + pick.toLabel() + "）",
                    "块级（在 " + blockIds.size() + " 人的整个块内搜索，秒级）",
                    List.of(pick), pick.delta(), costBudget, elapsed(startNanos));
        }

        // ---- 搜完了，但没找到不超预算的改法 ----
        String bestInfo = blockLevel.isEmpty()
                ? "块内没有任何一次对调能让你和 TA 同住"
                : "块内能让你和 TA 同住的改法，代价都超过了预算 " + String.format("%.2f", costBudget);
        String extra = hardBlocks.isEmpty() ? "" : "；另有部分诉求本身不可行：" + String.join("；", hardBlocks);
        return fail(AppealResult.NOT_FOUND_IN_SCOPE,
                bestInfo + extra + "。注意：这只说明在「房间级 + 你所在的块」范围内没找到，"
                        + "不代表全校范围内无解——你可以申请人工复核，把搜索范围扩大到跨块。",
                "房间级 + 块级（" + blockIds.size() + " 人）", startNanos, costBudget);
    }

    /** 枚举"把 appellant 塞进 targetRoom"的所有一对一改法（含空床位直接搬入）。 */
    private List<AppealResult.Swap> candidatesInto(Solution sol, long appellant, long targetRoom, List<Long> occ) {
        List<AppealResult.Swap> out = new ArrayList<>();
        Room tr = scorer.room(targetRoom);
        if (tr == null) {
            return out;
        }
        long myRoom = sol.roomOf(appellant);
        if (myRoom == targetRoom) {
            return out;
        }
        boolean hadRoom = myRoom != -1;
        Room fr = hadRoom ? scorer.room(myRoom) : null;
        List<Long> mine = hadRoom ? sol.occupants(myRoom) : List.of();

        // 情况一：目标房还有空床位 —— 直接搬进去
        if (occ.size() < tr.capacity()) {
            List<Long> after = RoomScorer.plus(occ, appellant);
            List<Long> before = hadRoom ? RoomScorer.without(mine, appellant) : List.of();
            if (scorer.hardOk(tr, after) && (!hadRoom || scorer.hardOk(fr, before))) {
                double delta = scorer.softScore(targetRoom, after) - scorer.softScore(targetRoom, occ);
                if (hadRoom) {
                    delta += scorer.softScore(myRoom, before) - scorer.softScore(myRoom, mine);
                }
                out.add(new AppealResult.Swap(appellant, nameOf(appellant), myRoom, targetRoom,
                        hadRoom ? fr.label() : "未分配", tr.label(), -1, "（空床位）", delta,
                        "目标房间有空床位，直接搬入"));
            }
        }

        // 情况二：和目标房里的每个人一对一调换
        for (Long other : occ) {
            if (other == appellant) {
                continue;
            }
            if (hadRoom) {
                List<Long> na = RoomScorer.replaced(mine, appellant, other);
                List<Long> nb = RoomScorer.replaced(occ, other, appellant);
                if (!scorer.hardOk(fr, na) || !scorer.hardOk(tr, nb)) {
                    continue;
                }
                double delta = (scorer.softScore(myRoom, na) + scorer.softScore(targetRoom, nb))
                        - (scorer.softScore(myRoom, mine) + scorer.softScore(targetRoom, occ));
                out.add(new AppealResult.Swap(appellant, nameOf(appellant), myRoom, targetRoom,
                        fr.label(), tr.label(), other, nameOf(other), delta,
                        "与 " + nameOf(other) + " 对调"));
            } else {
                // 申诉人本来就没房间：把对方挪走不合法（会让对方无房），所以只能看空床位
                break;
            }
        }
        return out;
    }

    /** 在候选里挑"代价最大（最接近 0）且不超预算"的那个。 */
    private AppealResult.Swap bestWithin(List<AppealResult.Swap> cands, double budget) {
        return cands.stream()
                .filter(s -> s.delta() >= -budget)
                .max(Comparator.comparingDouble(AppealResult.Swap::delta))
                .orElse(null);
    }

    /** 管理员采纳申诉：把方案改成试算给出的样子。 */
    public static Solution applySwap(Solution sol, AppealResult.Swap swap) {
        Solution next = sol.copy();
        long a = swap.studentId();
        long b = swap.otherId();
        if (b < 0) {
            next.unassign(a);
            next.assign(a, swap.toRoom());
            return next;
        }
        long roomA = next.roomOf(a);
        long roomB = next.roomOf(b);
        next.unassign(a);
        next.unassign(b);
        next.assign(a, roomB);
        next.assign(b, roomA);
        return next;
    }

    // ---------------- 小工具 ----------------

    private AppealResult fail(String status, String message, String scope, long startNanos, double budget) {
        return new AppealResult(status, message, scope, List.of(), 0.0, budget, elapsed(startNanos));
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private String nameOf(long id) {
        Student s = problem.byId().get(id);
        return s == null ? ("#" + id) : s.name();
    }

    private String names(Collection<Long> ids) {
        List<String> out = new ArrayList<>();
        for (Long id : ids) {
            out.add(nameOf(id));
        }
        return String.join("、", out);
    }

    /** 未使用，保留给"按楼栋申诉"的后续扩展。 */
    @SuppressWarnings("unused")
    private Preference preferenceOf(long id) {
        return problem.preferenceOf(id);
    }
}
