package com.umi.nest.dorm.solver;

import com.umi.nest.dorm.config.ConstraintConfig;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.Room;
import com.umi.nest.dorm.model.Solution;
import com.umi.nest.dorm.model.Student;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 硬约束校验器。设计文档的硬性验收判据是"违规数恒为 0"，
 * 所以校验必须独立于求解器实现——求解器说合规不算数，这里说合规才算。
 */
public final class SolutionValidator {

    private SolutionValidator() {
    }

    public static List<Violation> validate(ProblemInstance p, ConstraintConfig c, Solution s) {
        List<Violation> out = new ArrayList<>();
        Map<Long, Room> rooms = p.roomById();
        Map<Long, Student> students = p.byId();

        // 1) 未分配
        for (Student st : p.students()) {
            if (!s.isAssigned(st.id())) {
                out.add(Violation.of("UNASSIGNED", st.id(), -1, "学生未被分配"));
            }
        }

        // 2) 逐房间检查
        for (Map.Entry<Long, List<Long>> e : s.byRoom().entrySet()) {
            long roomId = e.getKey();
            List<Long> members = e.getValue();
            if (members.isEmpty()) {
                continue;
            }
            Room room = rooms.get(roomId);
            if (room == null) {
                out.add(Violation.of("UNKNOWN_ROOM", -1, roomId, "房间不存在于实例中"));
                continue;
            }
            if (c.isEnforceCapacity() && members.size() > room.capacity()) {
                out.add(Violation.of("CAPACITY", -1, roomId,
                        "超员 " + members.size() + "/" + room.capacity()));
            }
            String grade = null;
            for (Long sid : members) {
                Student st = students.get(sid);
                if (st == null) {
                    out.add(Violation.of("UNKNOWN_ROOM", sid, roomId, "学生不存在于实例中"));
                    continue;
                }
                if (c.isEnforceGenderMatch() && st.gender() != room.gender()) {
                    out.add(Violation.of("GENDER", sid, roomId, "性别与房间不符"));
                }
                if (c.isEnforceSpecialNeeds()) {
                    if (st.isSpecial() && !room.forSpecialNeeds()) {
                        out.add(Violation.of("SPECIAL", sid, roomId, "特殊需求学生被分到普通房间"));
                    }
                    if (!st.isSpecial() && room.forSpecialNeeds()) {
                        out.add(Violation.of("SPECIAL", sid, roomId, "普通学生占用特殊需求房间"));
                    }
                }
                if (c.isEnforceRoomGradeRestriction() && room.gradeRestriction() != null
                        && !room.gradeRestriction().equals(st.grade())) {
                    out.add(Violation.of("GRADE", sid, roomId, "房间年级限制不符"));
                }
                if (c.isEnforceGradeHomogeneous() && !room.forSpecialNeeds()) {
                    if (grade == null) {
                        grade = st.grade();
                    } else if (!grade.equals(st.grade())) {
                        out.add(Violation.of("GRADE", sid, roomId,
                                "年级混住（房间内已有 " + grade + "，该生为 " + st.grade() + "）"));
                    }
                }
            }
        }
        return out;
    }

    /** 只要违规计数，用于实验矩阵里的快速断言。 */
    public static int count(ProblemInstance p, ConstraintConfig c, Solution s) {
        return validate(p, c, s).size();
    }

    /** 按类型汇总，便于日志与报告。 */
    public static Map<String, Integer> summary(List<Violation> vs) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Violation v : vs) {
            m.merge(v.type(), 1, Integer::sum);
        }
        return m;
    }

    /** 房间占用统计：{"已用床位", "总床位", "空房间数"} */
    public static Map<String, Integer> occupancyStats(ProblemInstance p, Solution s) {
        int used = s.assignedCount();
        int total = p.totalCapacity();
        int emptyRooms = 0;
        for (Room r : p.rooms()) {
            if (s.occupancy(r.id()) == 0) {
                emptyRooms++;
            }
        }
        Map<String, Integer> m = new HashMap<>();
        m.put("usedBeds", used);
        m.put("totalBeds", total);
        m.put("emptyRooms", emptyRooms);
        return m;
    }
}
