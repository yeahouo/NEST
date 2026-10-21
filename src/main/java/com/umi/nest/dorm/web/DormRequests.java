package com.umi.nest.dorm.web;

import com.umi.nest.dorm.config.ConstraintConfig;

import java.util.List;

/**
 * 宿舍域 REST 请求体。
 *
 * <p>全部用 record：Jackson 直接反序列化，字段名即 JSON 键，没有 setter 也就没有
 * "某个字段被谁改过"的悬念。可空字段一律用包装类型，{@code null} 表示"用默认值"。
 */
public final class DormRequests {

    private DormRequests() {
    }

    /** 生成合成数据并入库。字段留 null 就用默认值。 */
    public record Synth(
            Integer students,
            Long seed,
            Double noise,
            Double missing,
            Double tension,
            Boolean gradeMix,
            Integer buildingCount,
            Integer roomCapacity) {
    }

    /** 一键分配。{@code config} 为 null 时用 {@link ConstraintConfig#defaults()}。 */
    public record Assign(
            String name,
            ConstraintConfig config,
            Boolean withExplanations) {
    }

    /** 学生申诉：想和谁住一起。 */
    public record Appeal(
            Long studentId,
            List<Long> wantRoomWith,
            Double costBudget) {
    }

    /** 采纳申诉时记录处理人。 */
    public record Apply(String handledBy) {
    }

    /** 真实数据导入：学生 + 房间一次性灌进来。 */
    public record ImportData(
            List<StudentDto> students,
            List<RoomDto> rooms,
            Boolean replaceAll) {
    }

    /**
     * 一行学生。
     *
     * <p>{@code feature} 按 {@code StudentFeature.toArray()} 的顺序给最多 11 个数（前两位是入睡与
     * 起床时刻，单位小时；其余 9 位是 0~1）。**某一维未知就把那一位写成 {@code null}**，
     * 数组也可以更短（后面几维视为未知），整段给 {@code null} 表示特征全缺。
     *
     * <p>为什么这里用 {@code Double[]} 而不是 {@code double[]}：真实问卷的缺失是零散的
     * （学生答了第 3 题、第 7 题，没答第 5 题），只有装箱数组才表达得了「这一维未知」。
     * 不要因为方便就拿 0 顶替缺失——「没回答」和「打 0 分」对求解器是两件事，
     * 用 0 顶替等于把「他什么也没说过」算成「他明确表示不介意」。
     *
     * <p>{@code wantRoommateWith} / {@code avoidRoommateWith} 用**学号字符串**引用同学，不是
     * 数据库 id——真实学号常带字母（如 {@code S20230101}），用数字表达不出来。解析顺序是
     * 「先当学号找，找不到再当数字 id 试一次」；两者都找不到就丢弃并记进导入体检报告，
     * **绝不把原始数字当 id 用**，那会指到一个真实存在但完全无关的人身上。
     */
    public record StudentDto(
            String studentNo,
            String name,
            String gender,
            String grade,
            String major,
            String majorCluster,
            String buildingPref,
            String specialNeed,
            Double[] feature,
            List<String> wantRoommateWith,
            List<String> avoidRoommateWith) {
    }

    public record RoomDto(
            String roomNo,
            String buildingId,
            String gender,
            Integer capacity,
            Boolean forSpecialNeeds,
            String gradeRestriction) {
    }
}
