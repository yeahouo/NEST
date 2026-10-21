package com.umi.nest.dorm.data;

import com.umi.nest.dorm.model.ProblemInstance;

/**
 * 一份合成数据集 = 算法看得见的 {@link ProblemInstance} + 只有评估器看得见的 {@link GroundTruth}。
 * <p>把两者绑在同一个对象里、但用不同字段暴露，是为了在代码层面就无法"不小心"让求解器读到真值。
 */
public record SyntheticDataset(ProblemInstance problem, GroundTruth truth, GenParams params) {
}
