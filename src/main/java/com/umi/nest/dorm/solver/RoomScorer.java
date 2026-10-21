package com.umi.nest.dorm.solver;

import com.umi.nest.dorm.model.Preference;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Room;
import com.umi.nest.dorm.model.Solution;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.config.ConstraintConfig;
import com.umi.nest.dorm.compat.CompatibilityFunction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 房间级打分器——贪心构造、局部搜索、阻塞对修复三者共用同一个"分数"定义。
 * <p>把目标函数写成<b>房间局部量之和</b>是整套算法的性能关键：
 * <pre>
 *   F(解) = Σ_房间 房间分(房间)  −  penalty × 未分配人数
 * </pre>
 * 于是任何"交换两个人 / 搬一个人"的增量只需要重算受影响的 2 个房间，
 * 而不是整个解。局部搜索因此才能在上亿次候选评估里跑得动。
 * <p>房间分包含四部分：
 * <ol>
 *   <li>整体质量：房内两两相容度之和</li>
 *   <li>木桶：Σ softmin(相容度, θ)，把最差的那几对往上抬（θ 见配置 bucketThreshold）</li>
 *   <li>志愿：想同住且同住 / 想分的分开了 / 楼栋志愿命中</li>
 *   <li>硬约束罚项：一旦违规就巨亏，保证可行解恒优于不可行解</li>
 * </ol>
 */
public final class RoomScorer {

    private final ProblemInstance problem;
    private final ConstraintConfig cfg;
    private final CompatibilityFunction compat;
    private final Map<Long, Room> roomById;
    private final Map<Long, Student> studentById;

    public RoomScorer(ProblemInstance problem, ConstraintConfig cfg, CompatibilityFunction compat) {
        this.problem = problem;
        this.cfg = cfg;
        this.compat = compat;
        this.roomById = problem.roomById();
        this.studentById = problem.byId();
    }

    public CompatibilityFunction compat() {
        return compat;
    }

    public ConstraintConfig config() {
        return cfg;
    }

    public Room room(long roomId) {
        return roomById.get(roomId);
    }

    public Student student(long studentId) {
        return studentById.get(studentId);
    }

    public ProblemInstance problem() {
        return problem;
    }

    public Preference preferenceOf(long studentId) {
        return problem.preferenceOf(studentId);
    }

    // ---------------- 房间分 ----------------

    /** 完整房间分（含硬约束罚项）。 */
    public double scoreRoom(long roomId, List<Long> members) {
        Room room = roomById.get(roomId);
        if (room == null) {
            return -cfg.getWeights().getPenaltyHardViolation() * Math.max(1, members.size());
        }
        return qualityTerm(members) + preferenceTerm(room, members) - avoidTerm(members)
                - penaltyFor(room, members);
    }

    /** 只含软目标（整体质量 + 木桶 + 志愿 − 违背），不含硬约束罚项。 */
    public double softScore(long roomId, List<Long> members) {
        Room room = roomById.get(roomId);
        if (room == null) {
            return 0.0;
        }
        return qualityTerm(members) + preferenceTerm(room, members) - avoidTerm(members);
    }

    public double qualityTerm(List<Long> members) {
        int m = members.size();
        if (m < 2) {
            return 0.0;
        }
        ConstraintConfig.SolveWeights w = cfg.getWeights();
        double sum = 0;
        double deficit = 0;
        for (int i = 0; i < m; i++) {
            for (int j = i + 1; j < m; j++) {
                double c = compat.score(members.get(i), members.get(j));
                sum += c;
                // 木桶项：只看"低于门槛的对"，并且是亏空平方。
                // 为什么必须这样写（这是一个踩过的坑）：
                //   1) 原来的 min(c, θ) 是**上限**，它惩罚"房间里有好友"、却对差对毫无压力，
                //      实际近似一个常数，木桶指标反而被局部搜索搞坏（最差那对从 -0.02 掉到 -0.18）。
                //   2) 平方让"深亏空"的边际惩罚迅速放大，于是搜索会优先去救最差的那一对，
                //      这才是 min(两两相容度) 这个不可导目标的可用光滑代理。
                double gap = w.getBucketThreshold() - c;
                if (gap > 0) {
                    deficit += gap * gap;
                }
            }
        }
        return w.getSum() * sum - w.getBucket() * deficit;
    }

    /** 志愿满足项：想同住且同住、楼栋志愿命中。 */
    public double preferenceTerm(Room room, List<Long> members) {
        if (members.size() < 2 && room == null) {
            return 0.0;
        }
        ConstraintConfig.SolveWeights w = cfg.getWeights();
        double v = 0;
        // 互相/单向"想同住"且真的同住了
        Set<Long> set = new HashSet<>(members);
        for (Long a : members) {
            Preference pa = problem.preferenceOf(a);
            for (Long b : pa.preferredRoommates()) {
                if (a < b && set.contains(b)) {
                    // 双向都点名 → 额外加权（阻塞对修复要优先救这种）
                    boolean both = problem.preferenceOf(b).preferredRoommates().contains(a);
                    v += both ? 1.5 : 1.0;
                }
            }
        }
        // 楼栋志愿
        if (room != null && !room.buildingId().equals("-")) {
            List<String> bs = problem.preferenceOf(members.get(0)).preferredBuildings();
            for (Long s : members) {
                List<String> pref = problem.preferenceOf(s).preferredBuildings();
                if (!pref.isEmpty() && pref.contains(room.buildingId())) {
                    v += 0.5;
                }
            }
        }
        return w.getPreference() * v;
    }

    /** 明确"不想同住"却住一起。 */
    public double avoidTerm(List<Long> members) {
        if (members.size() < 2) {
            return 0.0;
        }
        ConstraintConfig.SolveWeights w = cfg.getWeights();
        double v = 0;
        Set<Long> set = new HashSet<>(members);
        for (Long a : members) {
            for (Long b : problem.preferenceOf(a).avoidRoommates()) {
                if (set.contains(b)) {
                    v += 1.0;
                }
            }
        }
        return w.getAvoid() * v;
    }

    /** 硬约束罚项（只罚这一间房内的问题）。 */
    public double penaltyFor(Room room, List<Long> members) {
        return cfg.getWeights().getPenaltyHardViolation() * violationUnits(room, members);
    }

    /**
     * 房间内硬约束的"违规单位数"（按涉及的学生数计）。
     * <p>0 表示这间房完全合规。求解过程中用它做快速可行性判定。
     */
    public int violationUnits(Room room, List<Long> members) {
        if (room == null) {
            return members.size();
        }
        int v = 0;
        // 容量
        if (cfg.isEnforceCapacity() && members.size() > room.capacity()) {
            v += members.size() - room.capacity();
        }
        String roomGrade = null;
        for (Long sid : members) {
            Student s = studentById.get(sid);
            if (s == null) {
                v++;
                continue;
            }
            // 性别
            if (cfg.isEnforceGenderMatch() && s.gender() != room.gender()) {
                v++;
            }
            // 特殊需求（双向：特殊学生必须进特殊房，普通学生不得占特殊房）
            if (cfg.isEnforceSpecialNeeds()) {
                if (s.isSpecial() && !room.forSpecialNeeds()) {
                    v++;
                }
                if (!s.isSpecial() && room.forSpecialNeeds()) {
                    v++;
                }
            }
            // 房间自带年级限制
            if (cfg.isEnforceRoomGradeRestriction() && room.gradeRestriction() != null
                    && !room.gradeRestriction().equals(s.grade())) {
                v++;
            }
            // 年级不混住（特殊需求房豁免：需求优先于年级策略）
            if (cfg.isEnforceGradeHomogeneous() && !room.forSpecialNeeds()) {
                if (roomGrade == null) {
                    roomGrade = s.grade();
                } else if (!roomGrade.equals(s.grade())) {
                    v++;
                }
            }
        }
        return v;
    }

    /** 快速可行性判定：新成员能否放进这间房而不违反任何硬约束。 */
    public boolean hardOk(Room room, List<Long> members) {
        return violationUnits(room, members) == 0;
    }

    /**
     * 房间内两两相容度的"最小值"——木桶指标，评估与可解释都要用。
     */
    public double worstPairCompat(List<Long> members) {
        double worst = Double.POSITIVE_INFINITY;
        int m = members.size();
        for (int i = 0; i < m; i++) {
            for (int j = i + 1; j < m; j++) {
                worst = Math.min(worst, compat.score(members.get(i), members.get(j)));
            }
        }
        return m < 2 ? Double.NaN : worst;
    }

    /** 房内平均相容度。 */
    public double meanPairCompat(List<Long> members) {
        int m = members.size();
        if (m < 2) {
            return Double.NaN;
        }
        double sum = 0;
        int cnt = 0;
        for (int i = 0; i < m; i++) {
            for (int j = i + 1; j < m; j++) {
                sum += compat.score(members.get(i), members.get(j));
                cnt++;
            }
        }
        return sum / cnt;
    }

    /** 除某个成员之外的其余成员。 */
    public static List<Long> without(List<Long> members, long removed) {
        List<Long> out = new ArrayList<>(members.size());
        for (Long m : members) {
            if (m != removed) {
                out.add(m);
            }
        }
        return out;
    }

    /** 用新成员替换旧成员。 */
    public static List<Long> replaced(List<Long> members, long oldOne, long newOne) {
        List<Long> out = new ArrayList<>(members.size());
        for (Long m : members) {
            out.add(m == oldOne ? newOne : m);
        }
        return out;
    }

    /** 追加一个成员。 */
    public static List<Long> plus(List<Long> members, long add) {
        List<Long> out = new ArrayList<>(members.size() + 1);
        out.addAll(members);
        out.add(add);
        return out;
    }

    /** 整体解的软目标（只算软项，硬约束违规单列）。 */
    public double softObjective(Solution s) {
        double total = 0;
        for (Map.Entry<Long, List<Long>> e : s.byRoom().entrySet()) {
            if (!e.getValue().isEmpty()) {
                total += softScore(e.getKey(), e.getValue());
            }
        }
        return total;
    }

    /** 整体解的目标函数（软项 − 硬约束罚项），未分配按满罚计。 */
    public double objective(Solution s) {
        double total = softObjective(s);
        int unassigned = problem.studentCount() - s.assignedCount();
        total -= cfg.getWeights().getPenaltyHardViolation() * unassigned;
        return total;
    }
}
