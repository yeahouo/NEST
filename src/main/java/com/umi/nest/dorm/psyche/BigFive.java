package com.umi.nest.dorm.psyche;

import com.umi.nest.dorm.model.StudentFeature;

/**
 * 大五人格（Big Five / OCEAN）。
 *
 * <p>为什么中间要过一层大五，而不让模型直接吐 11 维数字：大五是心理学里
 * 被反复验证过的人格结构，模型的判断落在大五上更稳、也更容易被人核查；
 * 而"外向度""社交活跃度"这些是我为了宿舍场景自己拆的工程维度，
 * 彼此高度相关，直接让模型填会得到一堆自相矛盾的值。
 * 拆成"模型判人格 → 我按固定映射推工程维"两步之后，矛盾就消掉了，
 * 而且映射关系是可审计、可替换的（改映射不用重跑模型）。
 */
public record BigFive(
        double openness,
        double conscientiousness,
        double extraversion,
        double agreeableness,
        double neuroticism
) {

    public static final int DIM = 5;

    public static BigFive neutral() {
        return new BigFive(0.5, 0.5, 0.5, 0.5, 0.5);
    }

    public double[] toArray() {
        return new double[]{openness, conscientiousness, extraversion, agreeableness, neuroticism};
    }

    public static BigFive of(double[] v) {
        if (v == null || v.length < DIM) {
            return neutral();
        }
        return new BigFive(v[0], v[1], v[2], v[3], v[4]);
    }

    /** 五维里任意一维拿不到值就算不完整——不完整就不用来推工程维。 */
    public boolean isComplete() {
        for (double v : toArray()) {
            if (Double.isNaN(v)) {
                return false;
            }
        }
        return true;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /**
     * 大五 → 11 维里"性格可解释"的那 7 维；其余 4 维返回 {@code NaN}。
     *
     * <p><b>为什么只有 7 维：</b>作息（入睡/起床）、吸烟、游戏这四维是
     * <i>行为事实</i>，不是人格倾向。一个再随和的人也可能天天凌晨三点睡，
     * 一个再神经质的人也可能一口烟都不抽。硬要用人格去推这些，
     * 等于用"他大概是什么人"冒充"他实际做什么事"——在宿舍分配里，
     * 后者的破坏力远大于前者。所以这四维必须靠直接问、或从文本里明说的事情抽，
     * 拿不到就留空（{@code NaN}），由下游的均值插补去处理。
     *
     * <p>返回数组下标对齐 {@link StudentFeature#toArray()}。
     */
    public double[] toStudentFeaturePartial() {
        double[] out = new double[StudentFeature.DIM];
        for (int i = 0; i < out.length; i++) {
            out[i] = Double.NaN;
        }
        double o = clamp01(openness);
        double c = clamp01(conscientiousness);
        double e = clamp01(extraversion);
        double a = clamp01(agreeableness);
        double n = clamp01(neuroticism);

        // 卫生：尽责性的直接表达
        out[PsycheDimension.CLEANLINESS.index()] = c;
        // 性格外向度：就是外向性本身
        out[PsycheDimension.EXTROVERSION.index()] = e;
        // 在房间学习：尽责的人更坐得住，外向的人更往外跑
        out[PsycheDimension.STUDY_IN_ROOM.index()] = 0.70 * c + 0.30 * (1 - e);
        // 噪声容忍：情绪稳定（低神经质）的人不容易被吵到，外向的人本身就在制造声音
        out[PsycheDimension.NOISE_TOLERANCE.index()] = 0.60 * (1 - n) + 0.40 * e;
        // 社交活跃：外向为主，宜人性为辅
        out[PsycheDimension.SOCIAL_ACTIVITY.index()] = 0.70 * e + 0.30 * a;
        // 物品共享：宜人性的直接表达
        out[PsycheDimension.SHARE_ITEMS.index()] = a;
        // 学业拼搏：尽责为主，开放为辅（愿意尝试新方向）
        out[PsycheDimension.AMBITION.index()] = 0.70 * c + 0.30 * o;

        // 明确不推：SLEEP_START / SLEEP_END / SMOKING / GAMING —— 行为事实，见方法注释
        return out;
    }

    /** 给用户看的一句话画像。 */
    public String describe() {
        return "开放性 " + fmt(openness)
                + "、尽责性 " + fmt(conscientiousness)
                + "、外向性 " + fmt(extraversion)
                + "、宜人性 " + fmt(agreeableness)
                + "、神经质 " + fmt(neuroticism);
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "未知" : String.format("%.2f", v);
    }
}
