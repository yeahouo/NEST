package com.umi.nest.dorm.explain;

import com.umi.nest.dorm.compat.RuleBasedCompatibility;
import com.umi.nest.dorm.config.ConstraintConfig;
import com.umi.nest.dorm.model.Preference;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Room;
import com.umi.nest.dorm.model.Solution;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.model.StudentFeature;
import com.umi.nest.dorm.solver.Block;
import com.umi.nest.dorm.solver.BlockDecomposer;
import com.umi.nest.dorm.solver.NestSolver;
import com.umi.nest.dorm.solver.RoomScorer;
import com.umi.nest.dorm.solver.SolutionValidator;
import com.umi.nest.dorm.solver.SolveResult;
import com.umi.nest.dorm.solver.Violation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨块试算：申诉的<b>第三级</b>，也是范围最大、代价最重的一级。
 *
 * <p>{@link AppealService} 只搜「目标房间内部」和「你所在的块」。搜不到时它的回答是
 * 「在我搜过的范围里没找到」，并且明确告诉学生可以把范围扩大到跨块。这个类就是那个承诺的兑现。
 *
 * <p><b>跨块是什么意思</b>：两个人分不到同一间房，原因只有两种，而这个类只对其中一种有办法。
 * <ul>
 *   <li><b>物理不可能</b>：性别不同、特殊需求登记不同（要住不同类型的房间）。
 *       这类诉求放宽任何政策都满足不了，必须如实说「不可能」，而不是让学生反复试。</li>
 *   <li><b>政策不允许</b>：年级不混住（{@code enforceGradeHomogeneous}）、
 *       同专业大类集中住宿（{@code blockByMajorCluster}）。这两个是<b>学校可以决定的政策</b>，
 *       所以跨块试算的实质是：<b>把这两条政策临时关掉，看能不能满足，并算清代价</b>。</li>
 * </ul>
 *
 * <p><b>为什么必须异步</b>：关掉「同专业大类集中」之后，合并后的块不再是几十人，
 * 而是「同性别的整个年级」——几千人、上千间房。这一级不是毫秒级，是秒到分钟级，
 * 放在 HTTP 请求线程里会把线程占住，所以由 {@code AppealTrialService} 丢到后台线程执行。
 *
 * <p><b>它改动的范围比前两级大得多</b>：前两级是「两个人对调」，这一级是整块重排。
 * 所以结果里必须同时给出「要放宽哪条政策」「会动多少人」「目标函数变成多少」，
 * 让管理员在采纳前看清楚代价，而不是只看到一句「可以满足」。
 */
public final class CrossBlockAppeal {

    /** 搜索范围标签，会写进 {@link AppealResult#scope()}，对学生与管理员都透明。 */
    public static final String SCOPE = "跨块级（合并块 + 放宽政策约束后重解）";

    /** 跨块重解的时间预算上限。这一级本来就是"慢"的那一级，但要有个封顶。 */
    public static final long SOLVE_BUDGET_MS = 20_000L;

    private final ProblemInstance problem;
    private final ConstraintConfig cfg;
    private final RoomScorer scorer;

    public CrossBlockAppeal(ProblemInstance problem, ConstraintConfig cfg) {
        this.problem = problem;
        this.cfg = cfg;
        this.scorer = new RoomScorer(problem, cfg,
                new RuleBasedCompatibility(problem.observations(), cfg.getCompatWeights()));
    }

    /** 为满足诉求而必须放宽的一条政策约束。 */
    public record Relaxation(String flag, String label, String why) {
    }

    /**
     * 一次跨块试算的完整产出。
     *
     * @param result          复用 {@link AppealResult} 的形状，好让「采纳申诉」那条链路不用改
     * @param relaxations     必须放宽的政策（空列表 = 不需要放宽，那就不该走到这一级）
     * @param mergedStudents  合并后参与重解的人数
     * @param mergedRooms     合并后参与重解的房间数
     * @param objectiveBefore 重解前、整个实例上的软目标
     * @param objectiveAfter  重解后、整个实例上的软目标
     * @param stillBlocked    放宽政策后仍然不可能满足的诉求及其原因
     */
    public record Trial(
            AppealResult result,
            List<Relaxation> relaxations,
            int mergedStudents,
            int mergedRooms,
            double objectiveBefore,
            double objectiveAfter,
            List<String> stillBlocked) {

        public boolean satisfied() {
            return result != null && AppealResult.SATISFIED.equals(result.status());
        }

        public double objectiveDelta() {
            return objectiveAfter - objectiveBefore;
        }
    }

    /**
     * 试算「在放宽政策约束的前提下，能不能让你和这些人住到一起」。
     *
     * @param current      这次批次的当前分配结果（试算只读它，不修改）
     * @param studentId    申诉人
     * @param wantRoomWith 希望同住的同学
     */
    public Trial trySatisfy(Solution current, long studentId, Collection<Long> wantRoomWith) {
        long t0 = System.nanoTime();
        Student me = problem.byId().get(studentId);
        if (me == null) {
            return dead(AppealResult.UNSATISFIABLE, "名单里没有这位同学", List.of(), 0, 0, t0);
        }
        Set<Long> wants = new LinkedHashSet<>(wantRoomWith);
        wants.remove(studentId);
        if (wants.isEmpty()) {
            return dead(AppealResult.UNSATISFIABLE, "没有指定希望同住的同学", List.of(), 0, 0, t0);
        }

        // 已经在同一间房：不用重解，直接告诉他
        long myRoom = current.roomOf(studentId);
        if (myRoom != -1 && wants.stream().allMatch(w -> current.roomOf(w) == myRoom)) {
            return new Trial(new AppealResult(AppealResult.SATISFIED,
                    "你希望同住的同学已经全部和你住在一起，无需调整。",
                    "无需搜索（现状已满足）", List.of(), 0.0, 0.0, elapsed(t0)),
                    List.of(), 0, 0, 0.0, 0.0, List.of());
        }

        // ---- 第一步：把「物理不可能」和「政策不允许」分开 ----
        List<String> stillBlocked = new ArrayList<>();
        Set<Long> policyBlocked = new LinkedHashSet<>();
        boolean needRelaxGrade = false;
        boolean needRelaxCluster = false;
        for (Long w : wants) {
            Student o = problem.byId().get(w);
            if (o == null) {
                stillBlocked.add("学号 " + w + " 不在名单里");
                continue;
            }
            if (o.gender() != me.gender()) {
                stillBlocked.add(nameOf(w) + " 与你性别不同——一间房只能住同一个性别，放宽任何政策都改不了");
                continue;
            }
            if (o.isSpecial() != me.isSpecial()) {
                stillBlocked.add(nameOf(w) + " 的特殊需求登记与你不同，要住不同类型的房间，"
                        + "这不是政策问题，是房型问题");
                continue;
            }
            if (!current.isAssigned(w)) {
                stillBlocked.add(nameOf(w) + " 本次未分配房间，无法作为同住目标");
                continue;
            }
            if (cfg.isEnforceGradeHomogeneous() && !o.grade().equals(me.grade())) {
                needRelaxGrade = true;
            }
            if (cfg.isBlockByMajorCluster() && !o.majorCluster().equals(me.majorCluster())) {
                needRelaxCluster = true;
            }
            policyBlocked.add(w);
        }

        if (policyBlocked.isEmpty()) {
            return dead(AppealResult.UNSATISFIABLE,
                    "这条诉求在你的硬约束下不可能满足：" + String.join("；", stillBlocked)
                            + "。跨块试算也改不了这类约束，所以不用再排这一级。",
                    stillBlocked, 0, 0, t0);
        }

        // ---- 第二步：放宽对政策开关，重新分块 ----
        ConstraintConfig relaxed = cfg.copy();
        List<Relaxation> relaxations = new ArrayList<>();
        if (needRelaxGrade) {
            relaxed.setEnforceGradeHomogeneous(false);
            relaxations.add(new Relaxation("enforceGradeHomogeneous", "年级不混住",
                    "你和希望同住的同学不在同一个年级，而当前策略要求同一房间内年级一致。"));
        }
        if (needRelaxCluster) {
            relaxed.setBlockByMajorCluster(false);
            relaxations.add(new Relaxation("blockByMajorCluster", "同专业大类集中住宿",
                    "你和希望同住的同学不是同一个专业大类，而当前策略按专业大类分块，"
                            + "不同专业大类不会被分到同一间房。"));
        }

        // ---- 第三步：确定要一起重解的「人」和「房」----
        // 房间池必须从「当前解」里取，不能从重新分解取。
        // 重新分解给出的是「按新政策这个块应该住哪些房间」，而当前解里这批人可能住在另外
        // 一批房间里（分块策略一变，块与房间的配对也跟着变）。拿新池子当子问题的房间，
        // 池子里正住着的人对子求解器是隐形的，重解就会把人塞进去、造成超容量——而超容量在
        // softObjective 里看不见，只会表现为目标值莫名其妙地变差。实测就是这么错的：
        // 40 间池子里当前住着 160 人，其中 120 人不在这个块里。
        Set<Long> group = new LinkedHashSet<>();
        for (Block b : BlockDecomposer.decompose(problem, cfg).blocks()) {
            if (b.studentIds().contains(studentId)
                    || b.studentIds().stream().anyMatch(policyBlocked::contains)) {
                group.addAll(b.studentIds());
            }
        }
        if (!group.contains(studentId) || !group.containsAll(policyBlocked)) {
            return dead(AppealResult.NOT_FOUND_IN_SCOPE,
                    "构造不出同时包含你和希望同住同学的搜索范围（房间容量可能已经按政策分给了别人）。",
                    stillBlocked, 0, 0, t0);
        }
        Set<Long> roomIds = new LinkedHashSet<>();
        for (Long sid : group) {
            long r = current.roomOf(sid);
            if (r != -1) {
                roomIds.add(r);
            }
        }
        // 闭包：现在住在这批房间里的人，必须一起参与重解——否则重解会把他挤掉。
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Long rid : new ArrayList<>(roomIds)) {
                for (Long occ : current.occupants(rid)) {
                    if (group.add(occ)) {
                        long r = current.roomOf(occ);
                        if (r != -1 && roomIds.add(r)) {
                            grew = true;
                        }
                    }
                }
            }
        }
        // 再补上「完全没人住的空房间」，给重解一点回旋余地。
        // 有人住的房间一律不补：理由同上，住在里面的人不在这次重解的范围里。
        for (Block b : BlockDecomposer.decompose(problem, relaxed).blocks()) {
            if (!b.studentIds().contains(studentId)) {
                continue;
            }
            for (Room r : b.rooms()) {
                if (current.occupancy(r.id()) == 0) {
                    roomIds.add(r.id());
                }
            }
        }
        List<Long> ids = new ArrayList<>(group);
        List<Room> rooms = new ArrayList<>(roomIds.size());
        for (Long rid : roomIds) {
            Room r = scorer.room(rid);
            if (r != null) {
                rooms.add(r);
            }
        }

        // ---- 第三步：只在这一块上重解 ----
        Map<Long, Student> byId = problem.byId();
        List<Student> subStudents = new ArrayList<>(ids.size());
        Map<Long, StudentFeature> subObs = new LinkedHashMap<>();
        Map<Long, Preference> subPrefs = new LinkedHashMap<>();
        for (Long id : ids) {
            Student s = byId.get(id);
            if (s == null) {
                continue;
            }
            subStudents.add(s);
            StudentFeature f = problem.observations().get(id);
            if (f != null) {
                subObs.put(id, f);
            }
            Preference p = problem.preferences().get(id);
            if (p != null) {
                subPrefs.put(id, p);
            }
        }
        // 申诉是一条「假设性诉求」，它并不在学生的已存志愿里。若不给子问题带上这条诉求，
        // 重解就只是在合并块里重排一遍：碰巧把两人排到一间房就报「可以满足」，没排到一起就报
        // 「找不到」——成不成功取决于运气，而不是取决于「到底有没有解」。所以这里把诉求本身
        // 注入子问题：申诉人想和每一位被政策挡住的同学同住，对方也回选申诉人；双向选择在
        // 目标函数里权重更高（互选 +1.5 对单向 +1.0），求解器才有动力去拆开别人把你俩凑一起。
        // 代价仍然在**完整原始配置**上评估（见下方 scorer.softObjective），这些注入不会漏进报价。
        for (Long id : ids) {
            Preference old = subPrefs.get(id);
            List<Long> want = new ArrayList<>(old == null ? List.of() : old.preferredRoommates());
            boolean changed = false;
            if (id == studentId) {
                for (Long w : policyBlocked) {
                    if (!want.contains(w)) {
                        want.add(w);
                        changed = true;
                    }
                }
            }
            if (policyBlocked.contains(id) && !want.contains(studentId)) {
                want.add(studentId);
                changed = true;
            }
            if (changed) {
                subPrefs.put(id, new Preference(id, want,
                        old == null ? List.of() : old.avoidRoommates(),
                        old == null ? List.of() : old.preferredBuildings()));
            }
        }
        ProblemInstance sub = ProblemInstance.of(subStudents, rooms, subObs, subPrefs);

        ConstraintConfig subCfg = relaxed.copy();
        // 只有一个块，开并行池只会白白多起线程
        subCfg.setParallelBlocks(false);
        subCfg.setTimeBudgetMs(Math.min(subCfg.getTimeBudgetMs(), SOLVE_BUDGET_MS));
        SolveResult solved = new NestSolver().solve(sub, subCfg);
        Solution after = solved.solution();

        if (!solved.feasible()) {
            return dead(AppealResult.NOT_FOUND_IN_SCOPE,
                    "跨块重解没有给出合规的完整方案（违规 " + solved.hardViolationCount()
                            + " 处、未分配 " + solved.unassignedCount() + " 人），因此不能作为建议。",
                    relaxations, stillBlocked, subStudents.size(), rooms.size(), t0);
        }

        long newRoom = after.roomOf(studentId);
        List<Long> unmet = policyBlocked.stream()
                .filter(w -> newRoom == -1 || after.roomOf(w) != newRoom)
                .toList();
        if (newRoom == -1 || !unmet.isEmpty()) {
            String why = newRoom == -1
                    ? "你在重解后没有被分配到房间"
                    : "重解后 " + names(unmet) + " 仍然没有和你住到一起";
            String relaxedText = relaxations.isEmpty()
                    ? "在现有政策下"
                    : "即使放宽了「"
                            + String.join("」「", relaxations.stream().map(Relaxation::label).toList())
                            + "」";
            return dead(AppealResult.NOT_FOUND_IN_SCOPE,
                    why + "。" + relaxedText + "、在 " + subStudents.size()
                            + " 人的合并块内重解，也排不出满足你诉求的方案——"
                            + "「在我搜过的范围里没找到」不等于「无解」，但这一级已经是本系统最大的搜索范围。",
                    relaxations, stillBlocked, subStudents.size(), rooms.size(), t0);
        }

        // ---- 第四步：把重解结果换算成「谁搬到哪」，并算清代价 ----
        double before = scorer.softObjective(current);
        Solution patched = current.copy();
        List<AppealResult.Swap> moves = new ArrayList<>();
        for (Long id : ids) {
            long from = current.roomOf(id);
            long to = after.roomOf(id);
            if (to == -1 || from == to) {
                continue; // 没动的人不写进建议，避免建议列表里全是噪声
            }
            patched.unassign(id);
            patched.assign(id, to);
            Room fr = from == -1 ? null : scorer.room(from);
            Room tr = scorer.room(to);
            moves.add(new AppealResult.Swap(id, nameOf(id), from, to,
                    fr == null ? "未分配" : fr.label(), tr == null ? "—" : tr.label(),
                    -1, "（跨块重解）", 0.0, "跨块重解后的新位置"));
        }
        double after2 = scorer.softObjective(patched);
        double delta = after2 - before;

        // 自检 + 口径校正。
        // 1) 整实例目标里 96% 的房间根本没动，会把这次改动的效果稀释掉，所以另算一个
        //    「只看这次真正碰过的那几十间房」的口径，否则 delta 是好是坏都看不出来。
        // 2) 一份会动 158 个人的建议，必须先自证是合法解：没有房间超容量、没有人被弄丢。
        //    超容量在 softObjective 里是看不见的（软目标不算硬罚项），但它是实实在在的错。
        double subBefore = 0;
        double subAfter = 0;
        int overCapacity = 0;
        int dropped = 0;
        for (Room r : rooms) {
            List<Long> b = current.occupants(r.id());
            if (!b.isEmpty()) {
                subBefore += scorer.softScore(r.id(), b);
            }
            List<Long> a = patched.occupants(r.id());
            if (!a.isEmpty()) {
                subAfter += scorer.softScore(r.id(), a);
            }
            if (a.size() > r.capacity()) {
                overCapacity++;
            }
        }
        for (Long id : ids) {
            if (!patched.isAssigned(id)) {
                dropped++;
            }
        }
        // 这批房间现在住着谁？其中有多少人不属于合并块？
        // 这一个是关键诊断量：子问题只把「合并块的学生」当成变量，但块分到的房间可能
        // 正住着块外的人（例如别的年级），那些人对子求解器是隐形的，于是重解会把新人塞进去，
        // 造成超容量——而超容量在 softObjective 里看不见，只会表现为目标值莫名其妙地变差。
        Set<Long> idSet = new LinkedHashSet<>(ids);
        int seated = 0;
        int stray = 0;
        for (Room r : rooms) {
            for (Long occ : current.occupants(r.id())) {
                seated++;
                if (!idSet.contains(occ)) {
                    stray++;
                }
            }
        }
        if (overCapacity > 0 || dropped > 0) {
            return dead(AppealResult.NOT_FOUND_IN_SCOPE,
                    "跨块重解给出的方案自检不通过（超容量房间 " + overCapacity + " 间、未分配 " + dropped
                            + " 人；这批房间当前住着 " + seated + " 人，其中 " + stray
                            + " 人不在合并块里），已丢弃、不作为建议。"
                            + "这说明合并后的块与房间的对应关系有问题，请人工复核——"
                            + "不能把一份会挤爆房间的建议发给学生。",
                    relaxations, stillBlocked, subStudents.size(), rooms.size(), t0);
        }

        // 最强的一道自检：把补好的整份分配拿回「原始配置」下验证一遍。
        // 上面两项只查「房间超容量」和「人丢了」；这一道还能连放宽政策引起的其它硬约束违反
        // 一起抓住——放宽政策不等于可以违反硬约束，这是两条不同的线。
        List<Violation> full = SolutionValidator.validate(problem, cfg, patched);
        if (!full.isEmpty()) {
            return dead(AppealResult.NOT_FOUND_IN_SCOPE,
                    "跨块重解给出的方案在整份分配上违反 " + full.size() + " 条硬约束（"
                            + SolutionValidator.summary(full) + "），已丢弃、不作为建议。"
                            + "放宽政策不等于可以违反硬约束，请人工复核。",
                    relaxations, stillBlocked, subStudents.size(), rooms.size(), t0);
        }

        String relaxText = relaxations.isEmpty()
                ? "无需放宽政策"
                : "需要放宽：" + String.join("、", relaxations.stream().map(Relaxation::label).toList());
        String message = "可以满足。" + relaxText + "。"
                + "跨块级试算的做法是把你的块和对方所在的块合并后整块重解，"
                + "因此这次要动 " + moves.size() + " 位同学（合并块共 " + subStudents.size()
                + " 人 / " + rooms.size() + " 间房），"
                + "全实例软目标 " + String.format("%.2f", before) + " → " + String.format("%.2f", after2)
                + String.format("（%+.2f）", delta) + "；"
                + "只看这次被碰过的 " + rooms.size() + " 间房是 "
                + String.format("%.2f → %.2f（%+.2f）", subBefore, subAfter, subAfter - subBefore) + "。"
                + "已核对：无房间超容量、无学生被弄丢。"
                + "采纳之后这些人的房间都会变，不只是你和对方两个人——请确认这个代价可以接受。";

        AppealResult result = new AppealResult(AppealResult.SATISFIED, message,
                SCOPE + "；合并块 " + subStudents.size() + " 人 / " + rooms.size() + " 间房；"
                        + "耗时 " + solved.runtimeMs() + " ms",
                List.copyOf(moves), delta, 0.0, elapsed(t0));
        return new Trial(result, List.copyOf(relaxations), subStudents.size(), rooms.size(),
                before, after2, List.copyOf(stillBlocked));
    }

    // ---------------- 小工具 ----------------

    private Trial dead(String status, String message, List<String> stillBlocked,
                       int mergedStudents, int mergedRooms, long t0) {
        return dead(status, message, List.of(), stillBlocked, mergedStudents, mergedRooms, t0);
    }

    /**
     * 失败路径也要把「已经放宽了哪几条政策」带回去。
     *
     * <p>原先这里写死 {@code List.of()}，于是 message 里那句「即使放宽上述政策」变成了一句
     * 指向不存在内容的说明——学生和管理员在界面上看到「上述政策」四个字，却没有任何一处列出它
     * 是哪一条。拒绝时把放宽项说清楚，比拒绝本身更重要：否则管理员无法判断这个「不可能」
     * 是「真的排不出来」还是「系统没想到还有别的政策可以放宽」。</p>
     */
    private Trial dead(String status, String message, List<Relaxation> relaxations,
                       List<String> stillBlocked,
                       int mergedStudents, int mergedRooms, long t0) {
        AppealResult r = new AppealResult(status, message, SCOPE, List.of(), 0.0, 0.0, elapsed(t0));
        return new Trial(r, List.copyOf(relaxations), mergedStudents, mergedRooms, 0.0, 0.0,
                List.copyOf(stillBlocked));
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
}
