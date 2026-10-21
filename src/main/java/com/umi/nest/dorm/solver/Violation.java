package com.umi.nest.dorm.solver;

/**
 * 硬约束违规的一条记录，用于"违规数必须恒为 0"的自证与排查。
 *
 * @param type      违规类型：UNASSIGNED / CAPACITY / GENDER / SPECIAL / GRADE / UNKNOWN_ROOM
 * @param studentId 相关学生（无则 -1）
 * @param roomId    相关房间（无则 -1）
 * @param message   人读说明
 */
public record Violation(String type, long studentId, long roomId, String message) {

    public static Violation of(String type, long studentId, long roomId, String message) {
        return new Violation(type, studentId, roomId, message);
    }

    @Override
    public String toString() {
        return "[" + type + "] student=" + studentId + " room=" + roomId + " " + message;
    }
}
