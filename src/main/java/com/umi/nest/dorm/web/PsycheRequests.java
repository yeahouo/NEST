package com.umi.nest.dorm.web;

import java.util.List;
import java.util.Map;

/**
 * 心理特征模块的请求体定义。
 *
 * <p>与 {@link DormRequests} 一样只是数据形状，不含逻辑。刻意不用 {@code Map<String,Object>}
 * 收请求：字段名写错时要立刻报错，而不是安静地把 null 存进数据库。
 */
final class PsycheRequests {

    private PsycheRequests() {
    }

    /** 提交一段原始素材（聊天记录 / 自述 / 辅导员观察）。 */
    public record Text(long studentId, String kind, String content, Boolean consent) {
    }

    /** 提交量表作答。值是数字（维度原生值）或字符串（自由文本题）。 */
    public record Answers(long studentId, Map<String, Object> answers) {
    }

    /** 触发提取。{@code studentId} 为空则批量跑，{@code limit} 限制人数。 */
    public record Extract(Long studentId, Boolean useLlm, Integer limit, Boolean refresh) {
    }

    /** 把提取结果写回特征表，使其影响分配。 */
    public record Apply(long studentId, Double minConfidence) {
    }

    /** 绑定学生端账号。给 userId 就绑，给 studentNo 就按学号找。 */
    public record Link(long studentId, Long userId, String studentNo) {
    }

    /**
     * 跑一次提取器评测。
     *
     * <p>字段全部可空，缺省值由服务层给：调用方只想"看看现在的水平"，
     * 就不该被迫先读懂七八个参数。这些参数存在的意义是控制成本 ——
     * {@code useLlm} 一开就要按人头花钱，{@code students} 决定要造多少人。
     */
    public record Eval(Integer students, Long seed, Double missingRate, Double privacyRate,
                       Integer llmStudents, Boolean useLlm, Boolean ablation) {
    }

    /** 一次提交多道题。 */
    public record AnswerItem(String itemCode, Object value) {
    }

    /** 批量提交的封装，便于前端一次收一整页答案。 */
    public record AnswerBatch(long studentId, List<AnswerItem> items) {
    }
}
