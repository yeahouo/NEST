package com.umi.nest.dorm.config;

import lombok.Data;

/**
 * 约束与求解的全部可调参数——系统"可配置化"的唯一入口。
 * <p>设计原则：<b>硬约束一律是开关</b>，不写死在求解器里；换学校/换政策只改这份配置。
 * <p>默认值对应设计文档里的华大式政策：男女分楼、年级不混住、特殊需求专用房。
 */
@Data
public class ConstraintConfig {

    // ---------------- 硬约束开关 ----------------

    /** 性别与房间匹配（默认开启）。 */
    private boolean enforceGenderMatch = true;

    /** 容量不超（默认开启，关掉没有意义，保留是为了消融实验）。 */
    private boolean enforceCapacity = true;

    /** 特殊需求学生必须入住特殊需求房间（默认开启）。 */
    private boolean enforceSpecialNeeds = true;

    /** 同一房间内年级必须一致（"年级不混住"，默认开启）。 */
    private boolean enforceGradeHomogeneous = true;

    /** 房间若声明了年级限制，则必须一致（默认开启）。 */
    private boolean enforceRoomGradeRestriction = true;

    // ---------------- 分块分解 ----------------

    /** 分块键是否包含专业大类（教学班集中）。 */
    private boolean blockByMajorCluster = true;

    /** 小于该人数的块会在诊断中标记为"过小"（不自动合并：合并会破坏专业集中度这一硬属性）。 */
    private int minBlockSize = 60;

    /** 块内是否并行求解。 */
    private boolean parallelBlocks = true;

    /** 并行线程数，0 = 可用核数。 */
    private int parallelThreads = 0;

    // ---------------- 求解预算 ----------------

    /** 整体求解时间预算（毫秒），超时后停止局部搜索并返回当前最好解。 */
    private long timeBudgetMs = 30_000L;

    /** 局部搜索的最大轮数（每轮扫一遍所有邻域移动）。 */
    private int localSearchRounds = 12;

    /** 局部搜索连续多少轮没有改进就提前停止。 */
    private int localSearchPatience = 3;

    /**
     * 局部搜索中每个学生采样的邻居数（交换候选 + 搬迁候选）。
     * <p>刻意不穷举整个块：块的规模决定穷举代价是 O(B²)，
     * 采样后是 O(B·k)。宿舍目标函数很平滑，k=8 与穷举的实际差距极小，
     * 省下的预算用来多跑几轮和后面的阻塞对修复更划算。
     */
    private int neighborSamples = 8;

    /** 是否执行阻塞对修复阶段。 */
    private boolean enableBlockingPairRepair = true;

    /** 阻塞对修复允许的最大目标函数损失（占块目标的比例）。 */
    private double blockingRepairTolerance = 0.02;

    /** 随机种子（可复现性）。 */
    private long seed = 20260927L;

    // ---------------- 目标函数权重 ----------------

    private SolveWeights weights = new SolveWeights();

    /** 相容度维度权重。 */
    private CompatWeights compatWeights = CompatWeights.defaults();

    /**
     * 综合目标函数：
     * <pre>
     *   F = wSum   * Σ 房内两两相容度
     *     - wBucket* Σ max(0, θ − 相容度)²    ← 木桶效应的光滑代理（只惩罚低于门槛的对）
     *     + wPref  * Σ 志愿满足（想同住且同住 / 想同楼栋且分到）
     *     - wAvoid * Σ 志愿违背（明确不想同住却同住）
     *     - penalty* 硬约束违规数
     * </pre>
     * 全程用 <b>算法可见的相容度</b> 计算；真值相容度只在评估阶段使用。
     */
    @Data
    public static class SolveWeights {
        /** 整体质量项权重。 */
        private double sum = 1.0;
        /** 木桶项权重（亏空平方，故量级比整体质量项大得多才有话语权）。 */
        private double bucket = 4.0;
        /** 木桶门槛 θ：低于它的"对"按亏空平方被抬升；高于它不再受罚。 */
        private double bucketThreshold = 0.25;
        /** 志愿满足权重。 */
        private double preference = 0.35;
        /** 志愿违背惩罚权重。 */
        private double avoid = 0.50;
        /** 硬约束违规惩罚——设得极大，保证任何可行解都优于不可行解。 */
        private double penaltyHardViolation = 1000.0;
    }

    public static ConstraintConfig defaults() {
        return new ConstraintConfig();
    }

    /**
     * 拷贝一份配置。
     *
     * <p>用途只有一个但很关键：<b>跨块试算要在副本上放宽政策约束</b>（例如临时允许不同专业大类同住），
     * 而正在服务其他请求的那份配置绝不能被动到。少写一个字段就会让试算悄悄用了放宽后的配置
     * 去算别的批次，所以这里逐字段写全，不用反射也不做"部分拷贝"。
     *
     * <p>{@code compatWeights} 直接共享引用：它是只读值对象，求解过程只读不写；
     * {@link #validate()} 里的 {@code normalized()} 是<b>给字段重新赋值</b>，作用在本副本上，不会波及原对象。
     */
    public ConstraintConfig copy() {
        ConstraintConfig c = new ConstraintConfig();
        c.enforceGenderMatch = enforceGenderMatch;
        c.enforceCapacity = enforceCapacity;
        c.enforceSpecialNeeds = enforceSpecialNeeds;
        c.enforceGradeHomogeneous = enforceGradeHomogeneous;
        c.enforceRoomGradeRestriction = enforceRoomGradeRestriction;
        c.blockByMajorCluster = blockByMajorCluster;
        c.minBlockSize = minBlockSize;
        c.parallelBlocks = parallelBlocks;
        c.parallelThreads = parallelThreads;
        c.timeBudgetMs = timeBudgetMs;
        c.localSearchRounds = localSearchRounds;
        c.localSearchPatience = localSearchPatience;
        c.neighborSamples = neighborSamples;
        c.enableBlockingPairRepair = enableBlockingPairRepair;
        c.blockingRepairTolerance = blockingRepairTolerance;
        c.seed = seed;
        SolveWeights w = new SolveWeights();
        if (weights != null) {
            w.setSum(weights.getSum());
            w.setBucket(weights.getBucket());
            w.setBucketThreshold(weights.getBucketThreshold());
            w.setPreference(weights.getPreference());
            w.setAvoid(weights.getAvoid());
            w.setPenaltyHardViolation(weights.getPenaltyHardViolation());
        }
        c.weights = w;
        c.compatWeights = compatWeights;
        return c;
    }

    /** 简单合法性校验，配置错误直接抛异常而不是静默跑出奇怪结果。 */
    public void validate() {
        if (minBlockSize < 0) {
            throw new IllegalArgumentException("minBlockSize 不能为负");
        }
        if (timeBudgetMs <= 0) {
            throw new IllegalArgumentException("timeBudgetMs 必须为正");
        }
        if (localSearchRounds < 0) {
            throw new IllegalArgumentException("localSearchRounds 不能为负");
        }
        if (neighborSamples < 1) {
            throw new IllegalArgumentException("neighborSamples 至少为 1");
        }
        if (blockingRepairTolerance < 0) {
            throw new IllegalArgumentException("blockingRepairTolerance 不能为负");
        }
        if (weights == null) {
            weights = new SolveWeights();
        }
        if (compatWeights == null) {
            compatWeights = CompatWeights.defaults();
        }
        compatWeights = compatWeights.normalized();
    }
}
