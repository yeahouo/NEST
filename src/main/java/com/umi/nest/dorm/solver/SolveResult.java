package com.umi.nest.dorm.solver;

import com.umi.nest.dorm.model.Solution;

import java.util.List;
import java.util.Map;

/**
 * 一次求解的全部产出。除了分配方案本身，还带上自证材料：
 * 硬约束违规清单、未分配人数、块划分统计、各阶段耗时。
 * 实验与接口层都只依赖这个结构，不依赖求解器内部实现。
 */
public record SolveResult(
        String solver,
        Solution solution,
        long runtimeMs,
        int hardViolationCount,
        int unassignedCount,
        double softObjective,
        int blockingPairCount,
        int blockCount,
        int maxBlockSize,
        List<String> warnings,
        List<Violation> violations,
        Map<String, Object> diagnostics) {

    public boolean feasible() {
        return hardViolationCount == 0 && unassignedCount == 0;
    }

    /** 一行摘要，日志/接口都能直接用。 */
    public String summary() {
        return String.format(
                "%s | %d ms | 违规=%d 未分配=%d 阻塞对=%d | 软目标=%.2f | 块=%d(最大 %d)",
                solver, runtimeMs, hardViolationCount, unassignedCount, blockingPairCount,
                softObjective, blockCount, maxBlockSize);
    }
}
