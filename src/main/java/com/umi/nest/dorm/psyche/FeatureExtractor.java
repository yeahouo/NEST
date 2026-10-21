package com.umi.nest.dorm.psyche;

/**
 * 基于学生原始素材提取 11 维软特征。
 *
 * <p>做成接口是为了让"用不用大模型"变成一个部署决策，而不是架构决策：
 * <ul>
 *   <li>{@link RuleBasedExtractor} —— 纯本地、零依赖、确定性、可单元测试。</li>
 *   <li>{@link LlmExtractor} —— 走云端大模型，能读懂反讽、隐含表达、上下文。</li>
 *   <li>{@link EnsembleExtractor} —— 两者融合，缺一个也能跑。</li>
 * </ul>
 * 学校完全可以只用第一个（数据不出校，符合设计文档的隐私 Must）；
 * 也可以三个都开，用第二个处理自由文本、第一个兜底。
 *
 * <p>求解器不知道也不关心用的是哪一个——它只读 {@code dorm_student_feature}。
 * 这正是设计文档里"求解器与数据来源解耦"的落点。
 */
public interface FeatureExtractor {

    /** 提取器标识，会写进 {@code dorm_psyche_profile.method} 与 {@code dorm_student_feature.source}。 */
    String name();

    /**
     * 当前环境能不能跑。例如 LLM 提取器在没配 api-key 时返回 false。
     * <p>调用方应当据此降级，而不是让整个请求失败。
     */
    boolean available();

    ExtractionResult extract(ExtractionInput input);
}
