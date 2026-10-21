package com.umi.nest.dorm.config;

import com.umi.nest.dorm.model.ProblemInstance;

/**
 * 一次求解请求：问题实例 + 约束配置 + 可选的求解器名字。
 *
 * @param problem  待分配的问题
 * @param config   约束/权重配置
 * @param solver   求解器标识（"nest" / "b0-legacy" / "b1-random" / "b2-greedy" / "b3-cpsat"）
 */
public record SolveRequest(ProblemInstance problem, ConstraintConfig config, String solver) {

    public static SolveRequest nest(ProblemInstance problem) {
        ConstraintConfig c = ConstraintConfig.defaults();
        c.validate();
        return new SolveRequest(problem, c, "nest");
    }

    public static SolveRequest nest(ProblemInstance problem, ConstraintConfig config) {
        config.validate();
        return new SolveRequest(problem, config, "nest");
    }

    public static SolveRequest of(ProblemInstance problem, ConstraintConfig config, String solver) {
        config.validate();
        return new SolveRequest(problem, config, solver);
    }
}
