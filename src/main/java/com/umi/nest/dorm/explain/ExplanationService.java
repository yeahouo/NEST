package com.umi.nest.dorm.explain;

import com.umi.nest.dorm.compat.CompatDimensions;
import com.umi.nest.dorm.compat.CompatibilityFunction;
import com.umi.nest.dorm.compat.RuleBasedCompatibility;
import com.umi.nest.dorm.config.ConstraintConfig;
import com.umi.nest.dorm.model.CompatBreakdown;
import com.umi.nest.dorm.model.Preference;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Room;
import com.umi.nest.dorm.model.Solution;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.model.StudentFeature;
import com.umi.nest.dorm.solver.Block;
import com.umi.nest.dorm.solver.BlockDecomposer;
import com.umi.nest.dorm.solver.RoomScorer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 四维可解释生成器。对应的创新点是"分配结果不仅要给出来，还要能被学生本人读懂、能质疑"。
 *
 * <p>四维分别是：约束层（我为什么受这些限制）、相容度层（我和室友具体哪里合/不合）、
 * 志愿层（我想同住的人满足了没有，没满足是为什么）、对比层（换个分法会怎样）。
 *
 * <p>这里刻意<b>不做任何"解释的黑箱"</b>：每一句话都从数据里直接推导出来，
 * 没有大模型参与。理由很实际——申诉是要改变分配结果的，
 * 如果理由是大模型编的，学生一质疑就崩。
 */
public final class ExplanationService {

    private final ProblemInstance problem;
    private final ConstraintConfig cfg;
    private final RoomScorer scorer;
    private final CompatibilityFunction compat;
    private final Map<Long, String> blockKeyOfStudent = new HashMap<>();
    private final Map<Long, List<Long>> blockStudents = new HashMap<>();

    public ExplanationService(ProblemInstance problem, ConstraintConfig cfg) {
        this.problem = problem;
        this.cfg = cfg;
        this.scorer = new RoomScorer(problem, cfg, compatOf(problem, cfg));
        this.compat = scorer.compat();

        // 块的归属：解释"为什么不能和某某同住"时必须区分
        // "硬约束不允许" 与 "分块分解没让他在我这一块"，这两句话的可申诉性完全不同。
        BlockDecomposer.Result dec = BlockDecomposer.decompose(problem, cfg);
        for (Block b : dec.blocks()) {
            List<Long> ids = b.studentIds();
            for (Long id : ids) {
                blockKeyOfStudent.put(id, b.key());
            }
            for (Long a : ids) {
                blockStudents.put(a, ids);
            }
        }
    }

    private static CompatibilityFunction compatOf(ProblemInstance p, ConstraintConfig cfg) {
        return new RuleBasedCompatibility(p.observations(), cfg.getCompatWeights());
    }

    public ProblemInstance problem() {
        return problem;
    }

    public Map<Long, Explanation> explainAll(Solution sol) {
        Map<Long, Explanation> out = new HashMap<>();
        for (Student s : problem.students()) {
            out.put(s.id(), explain(s.id(), sol));
        }
        return out;
    }

    /**
     * 只算指定学生。<b>用于万人规模</b>：逐人解释里"对比层"要做块内两两交换试算，
     * 整批算一次是 O(块²)，一万人的块根本算不完；单个人现场算则完全可接受。
     */
    public Map<Long, Explanation> explainAll(Solution sol, java.util.Collection<Long> studentIds) {
        Map<Long, Explanation> out = new HashMap<>();
        for (Long id : studentIds) {
            if (problem.byId().containsKey(id)) {
                out.put(id, explain(id, sol));
            }
        }
        return out;
    }

    public Explanation explain(long studentId, Solution sol) {
        Student s = problem.byId().get(studentId);
        if (s == null) {
            throw new IllegalArgumentException("学生不存在: " + studentId);
        }
        long roomId = sol.roomOf(studentId);
        Room room = roomId == -1 ? null : problem.roomById().get(roomId);
        List<Long> members = roomId == -1 ? List.of() : sol.occupants(roomId);

        List<Explanation.DimensionNote> dims = buildDimensions(studentId, members);
        List<Explanation.PreferenceNote> prefs = buildPreferences(studentId, sol);
        List<Explanation.ConstraintNote> cons = buildConstraints(s, room, roomId, sol);
        List<Explanation.ComparativeNote> comps = buildComparatives(studentId, sol, roomId);

        // 有几对室友是「双方都有特征数据」的。缺数据的那几对在求解器的评分里按 0 计，
        // 但在**解释**里不能混进来：只要有数据的一对都没有，就不能报「平均相容度 0.00」
        // ——那不是测出来的 0，是压根没测。所以这里只对可评估的对求均值与最差，
        // 并把 (可评估对数 / 总对数) 一起告诉学生。
        int pairs = members.size() >= 2 ? members.size() * (members.size() - 1) / 2 : 0;
        int evaluated = 0;
        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < members.size(); i++) {
            for (int j = i + 1; j < members.size(); j++) {
                long a = members.get(i);
                long b = members.get(j);
                if (!hasFeature(a) || !hasFeature(b)) {
                    continue;
                }
                double c = compat.score(a, b);
                sum += c;
                min = Math.min(min, c);
                evaluated++;
            }
        }
        double mean = evaluated > 0 ? sum / evaluated : Double.NaN;
        double worst = evaluated > 0 ? min : Double.NaN;
        String narrative = buildNarrative(s, room, members, dims, prefs, mean, worst, evaluated, pairs);

        return new Explanation(
                studentId, s.name(), roomId,
                room == null ? "未分配" : room.label(),
                room == null ? "" : room.buildingId(),
                mean, worst, evaluated, pairs, dims, prefs, cons, comps, narrative);
    }

    /** 这个学生有没有特征行。没有 = 完全没数据，不是"数据为 0"。 */
    private boolean hasFeature(long id) {
        return compat instanceof RuleBasedCompatibility rbc && rbc.featureOf(id) != null;
    }

    // ---------------- ② 相容度层 ----------------

    private List<Explanation.DimensionNote> buildDimensions(long studentId, List<Long> members) {
        List<Explanation.DimensionNote> out = new ArrayList<>();
        if (!(compat instanceof RuleBasedCompatibility rbc)) {
            return out;
        }
        StudentFeature mine = rbc.featureOf(studentId);
        if (mine == null) {
            return out;
        }
        for (Long other : members) {
            if (other == studentId) {
                continue;
            }
            StudentFeature theirs = rbc.featureOf(other);
            if (theirs == null) {
                continue;
            }
            CompatBreakdown bd = compat.explain(studentId, other);
            String otherName = nameOf(other);
            double[] a = mine.toArray();
            double[] b = theirs.toArray();
            for (Map.Entry<String, Double> e : bd.orderedByImpact().entrySet()) {
                String dim = e.getKey();
                int idx = featureIndex(dim);
                double mv = idx < 0 ? Double.NaN : a[idx];
                double tv = idx < 0 ? Double.NaN : b[idx];
                double gap = (Double.isNaN(mv) || Double.isNaN(tv)) ? Double.NaN : Math.abs(mv - tv);
                out.add(new Explanation.DimensionNote(
                        dim, other, otherName, mv, tv, gap, e.getValue(),
                        commentFor(dim, gap, e.getValue())));
            }
        }
        out.sort(Comparator.comparingDouble(Explanation.DimensionNote::contribution));
        return out;
    }

    private static int featureIndex(String dimension) {
        if (CompatDimensions.SLEEP.equals(dimension)) {
            return 0;
        }
        if (CompatDimensions.SMOKING.equals(dimension)) {
            return 2;
        }
        if (CompatDimensions.CLEANLINESS.equals(dimension)) {
            return 3;
        }
        if (CompatDimensions.EXTROVERSION.equals(dimension)) {
            return 4;
        }
        if (CompatDimensions.STUDY_NOISE.equals(dimension)) {
            return 5;
        }
        if (CompatDimensions.GAMING_NOISE.equals(dimension)) {
            return 9;
        }
        if (CompatDimensions.SOCIAL.equals(dimension)) {
            return 7;
        }
        if (CompatDimensions.SHARING.equals(dimension)) {
            return 8;
        }
        return -1;
    }

    private static String commentFor(String dimension, double gap, double contribution) {
        if (Double.isNaN(gap)) {
            // 以前这里写的是「已按全体均值补齐后计算」——那正是被删掉的做法。
            // 均值补齐会让两个都没填的人被算成差异 0.00、显示「很合」，等于编造证据。
            return "未知（这一项你们至少有一方没填，不计入相容度）";
        }
        String level;
        if (contribution >= 0.12) {
            level = "很合";
        } else if (contribution >= 0.04) {
            level = "还合";
        } else if (contribution >= -0.02) {
            level = "一般";
        } else if (contribution >= -0.10) {
            level = "有冲突";
        } else {
            level = "冲突明显";
        }
        return String.format("%s（差异 %.2f）", level, gap);
    }

    // ---------------- ③ 志愿层 ----------------

    private List<Explanation.PreferenceNote> buildPreferences(long studentId, Solution sol) {
        List<Explanation.PreferenceNote> out = new ArrayList<>();
        Preference p = problem.preferenceOf(studentId);
        long myRoom = sol.roomOf(studentId);
        for (Long other : p.preferredRoommates()) {
            long otherRoom = sol.roomOf(other);
            boolean ok = myRoom != -1 && myRoom == otherRoom;
            out.add(new Explanation.PreferenceNote(
                    other, nameOf(other), "想同住", ok, ok ? "已和你在同一间房" : whyNotTogether(studentId, other, sol)));
        }
        for (Long other : p.avoidRoommates()) {
            long otherRoom = sol.roomOf(other);
            boolean ok = !(myRoom != -1 && myRoom == otherRoom);
            out.add(new Explanation.PreferenceNote(
                    other, nameOf(other), "不想同住", ok, ok ? "已和你不在一间房" : "本次分配没能避开，可在申诉里提出"));
        }
        return out;
    }

    private String whyNotTogether(long me, long other, Solution sol) {
        Student a = problem.byId().get(me);
        Student b = problem.byId().get(other);
        if (b == null) {
            return "该同学不在本次分配的名单里";
        }
        if (!sol.isAssigned(other)) {
            return "该同学本次尚未分配到房间（床位不足或数据缺失）";
        }
        if (a != null && a.gender() != b.gender()) {
            return "性别不同，属于硬约束，任何方案都不能把你们分到一间房";
        }
        String ka = blockKeyOfStudent.get(me);
        String kb = blockKeyOfStudent.get(other);
        if (ka != null && kb != null && !ka.equals(kb)) {
            return "你们分属不同的分块（" + ka + " / " + kb + "），本版分配在块内独立求解，"
                    + "跨块组合需要走申诉的块级试算";
        }
        return "你们在同一块内，但分到同一间房会让整体方案变差，"
                + "系统选择了总分更高的分法；如果你更看重同住，可以申诉并指定你愿意付出的代价";
    }

    // ---------------- ① 约束层 ----------------

    private List<Explanation.ConstraintNote> buildConstraints(Student s, Room room, long roomId, Solution sol) {
        List<Explanation.ConstraintNote> out = new ArrayList<>();
        if (room == null) {
            out.add(new Explanation.ConstraintNote("床位容量", true,
                    "本次没有给你分到房间，请马上联系宿管；这属于应当修复的异常"));
            return out;
        }
        boolean genderBinding = true;
        out.add(new Explanation.ConstraintNote("性别与楼栋匹配", genderBinding,
                "你被分到 " + room.label() + "，该楼为" + (room.gender() == com.umi.nest.dorm.model.Gender.MALE ? "男生" : "女生") + "楼"));

        if (cfg.isEnforceGradeHomogeneous()) {
            out.add(new Explanation.ConstraintNote("年级不混住", true,
                    "你的年级是 " + s.grade() + "，同房间室友均为同年级"));
        }
        if (room.gradeRestriction() != null && !room.gradeRestriction().isEmpty()) {
            out.add(new Explanation.ConstraintNote("房间年级限制", true,
                    "该房间限 " + room.gradeRestriction() + " 级使用"));
        }
        if (room.forSpecialNeeds() || s.isSpecial()) {
            out.add(new Explanation.ConstraintNote("特殊需求房间", true,
                    s.isSpecial()
                            ? "你登记了特殊需求（" + s.specialNeed() + "），已安排到对应的特殊需求房间"
                            : "该房间为特殊需求专用房"));
        }
        if (cfg.isEnforceCapacity()) {
            out.add(new Explanation.ConstraintNote("房间容量", false,
                    "该房间容量 " + room.capacity() + " 人，当前 " + sol.occupancy(roomId) + " 人"));
        }
        Preference p = problem.preferenceOf(s.id());
        if (!p.preferredBuildings().isEmpty()) {
            boolean hit = p.preferredBuildings().contains(room.buildingId());
            out.add(new Explanation.ConstraintNote("楼栋志愿", hit,
                    hit ? "已按你的楼栋意愿安排到 " + room.buildingId()
                            : "你填了楼栋意愿 " + p.preferredBuildings() + "，但该楼容量不足，未能满足"));
        }
        return out;
    }

    // ---------------- ④ 对比层 ----------------

    private List<Explanation.ComparativeNote> buildComparatives(long studentId, Solution sol, long myRoom) {
        List<Explanation.ComparativeNote> out = new ArrayList<>();
        List<Long> blockIds = blockStudents.getOrDefault(studentId, List.of());
        if (myRoom == -1 || blockIds.isEmpty()) {
            return out;
        }
        List<Long> mine = sol.occupants(myRoom);
        Room roomA = scorer.room(myRoom);
        if (roomA == null) {
            return out;
        }
        double baseA = scorer.softScore(myRoom, mine);

        List<Explanation.ComparativeNote> best = new ArrayList<>();
        for (Long other : blockIds) {
            if (other == studentId) {
                continue;
            }
            long roomB = sol.roomOf(other);
            if (roomB == -1 || roomB == myRoom) {
                continue;
            }
            Room rb = scorer.room(roomB);
            if (rb == null) {
                continue;
            }
            List<Long> theirs = sol.occupants(roomB);
            List<Long> na = RoomScorer.replaced(mine, studentId, other);
            List<Long> nb = RoomScorer.replaced(theirs, other, studentId);
            if (!scorer.hardOk(roomA, na) || !scorer.hardOk(rb, nb)) {
                continue;
            }
            double delta = (scorer.softScore(myRoom, na) + scorer.softScore(roomB, nb))
                    - (baseA + scorer.softScore(roomB, theirs));
            if (delta < 0) {
                best.add(new Explanation.ComparativeNote(
                        "和「" + nameOf(other) + "」互换（你搬到 " + rb.label() + "）",
                        delta,
                        "会让整体方案下降 " + String.format("%.3f", -delta)
                                + " 分，所以系统没有这么分；若你更想和 " + nameOf(other) + " 的室友同住，可申诉"));
            }
        }
        best.sort(Comparator.comparingDouble(Explanation.ComparativeNote::delta).reversed());
        out.addAll(best.subList(0, Math.min(3, best.size())));
        if (out.isEmpty()) {
            out.add(new Explanation.ComparativeNote("本块内可替代方案", 0.0,
                    "在本块内没有找到既满足硬约束、又能提升总分的替代分法，说明当前分法在你的块里是局部最优的"));
        }
        return out;
    }

    // ---------------- 自然语言小结 ----------------

    private String buildNarrative(Student s, Room room, List<Long> members,
                                  List<Explanation.DimensionNote> dims,
                                  List<Explanation.PreferenceNote> prefs,
                                  double mean, double worst, int evaluated, int pairs) {
        if (room == null) {
            return s.name() + " 同学本次未能分配到房间，这属于异常情况，请立即联系宿管。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("你被分配到 ").append(room.label()).append("。");
        if (members.size() < 2) {
            sb.append("本房间目前只有你一人，建议尽早补齐床位。");
        } else if (evaluated == 0) {
            // 绝对不能在这里报「平均相容度 0.00」：0 是"测得不好"，这里的情况是"没测"。
            // 早期无数据时正是显示 0.00，读起来像"你的宿舍关系很差"。
            sb.append("本房间有 ").append(pairs).append(" 对室友，但双方都还没有心理特征数据，"
                    + "系统无法给出相容度——这不代表合不来，只是还没有依据。"
                    + "填完问卷后这里会显示具体差异。");
        } else {
            sb.append("本房间平均相容度 ").append(String.format("%.2f", mean)).append("（满分 1，负分表示明显冲突）");
            sb.append("，最需要留意的一对是 ").append(String.format("%.2f", worst)).append("。");
            if (evaluated < pairs) {
                sb.append("（").append(pairs).append(" 对室友里只有 ").append(evaluated)
                        .append(" 对双方都有数据、可以评估，其余几对没填的这一项不计入。）");
            }
        }
        // dims 已按 contribution 升序排好。跳过未知项（gap 为 null）再取最差的那一维，
        // 否则「冲突最明显的是『吸烟』…未知（双方都没填）」这种自相矛盾的话会出现在学生页面上。
        Explanation.DimensionNote w = null;
        for (Explanation.DimensionNote d : dims) {
            if (d.gap() != null) {
                w = d;
                break;
            }
        }
        if (w != null) {
            sb.append("其中和你冲突最明显的是「").append(w.dimension()).append("」这一项（与 ").append(w.otherName())
                    .append("，").append(w.comment()).append("）。");
        }
        long unmet = prefs.stream().filter(p -> "想同住".equals(p.kind()) && !p.satisfied()).count();
        long met = prefs.stream().filter(p -> "想同住".equals(p.kind()) && p.satisfied()).count();
        if (met + unmet > 0) {
            sb.append("你想同住的意愿满足了 ").append(met).append(" / ").append(met + unmet).append(" 条。");
            if (unmet > 0) {
                Explanation.PreferenceNote first = prefs.stream()
                        .filter(p -> "想同住".equals(p.kind()) && !p.satisfied())
                        .findFirst().orElse(null);
                if (first != null) {
                    sb.append("未满足的原因（以").append(first.otherName()).append("为例）：")
                            .append(first.reason()).append("。");
                }
            }
        }
        return sb.toString();
    }

    private String nameOf(long id) {
        Student s = problem.byId().get(id);
        return s == null ? ("#" + id) : s.name();
    }
}
