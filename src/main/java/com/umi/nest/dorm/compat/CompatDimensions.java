package com.umi.nest.dorm.compat;

/**
 * 相容度分解的维度名。统一在这里定义，保证可解释报告与日志用同一套词。
 */
public final class CompatDimensions {

    public static final String SLEEP = "作息";
    public static final String SMOKING = "吸烟";
    public static final String CLEANLINESS = "卫生";
    public static final String EXTROVERSION = "性格外向度";
    public static final String STUDY_NOISE = "安静/学习";
    public static final String GAMING_NOISE = "游戏噪声";
    public static final String SOCIAL = "社交活跃度";
    public static final String SHARING = "物品共享";

    private CompatDimensions() {
    }
}
