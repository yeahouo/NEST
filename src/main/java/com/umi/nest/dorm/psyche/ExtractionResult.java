package com.umi.nest.dorm.psyche;

import com.umi.nest.dorm.model.StudentFeature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次特征提取的结果。
 *
 * <p>{@code features} 与 {@code confidence} 等长（都是 {@link StudentFeature#DIM}），
 * 缺失维在两者里都是 {@code NaN}。<b>缺失和 0 是两回事</b>：
 * 0 表示"提取到了，他就是极内向"，NaN 表示"没提取到，不知道"。
 * 下游的 {@code RuleBasedCompatibility} 对 NaN 走均值插补，对 0 当真值用——
 * 如果把"不知道"写成 0，等于把每个不说话的学生都判定成极端性格。
 */
public record ExtractionResult(
        String method,
        boolean llmUsed,
        double[] features,
        double[] confidence,
        BigFive bigFive,
        List<Evidence> evidence,
        List<String> followUps,
        String narrative,
        int textChars,
        int answeredItems,
        long elapsedMs,
        int promptTokens,
        int completionTokens,
        Map<String, Double> spread
) {

    /**
     * 不带分歧度的构造入口。
     *
     * <p>规则提取器、问卷路径、以及所有把大模型调用次数设为 1 的场景走这里——
     * 它们只有一个样本，"样本间分歧"这个概念不成立，不是"分歧为 0"。
     */
    public ExtractionResult(String method, boolean llmUsed, double[] features, double[] confidence,
                            BigFive bigFive, List<Evidence> evidence, List<String> followUps,
                            String narrative, int textChars, int answeredItems, long elapsedMs,
                            int promptTokens, int completionTokens) {
        this(method, llmUsed, features, confidence, bigFive, evidence, followUps, narrative,
                textChars, answeredItems, elapsedMs, promptTokens, completionTokens, Map.of());
    }

    public ExtractionResult {
        method = method == null ? "" : method;
        features = features == null ? new double[StudentFeature.DIM] : features.clone();
        confidence = confidence == null ? new double[StudentFeature.DIM] : confidence.clone();
        if (features.length != StudentFeature.DIM || confidence.length != StudentFeature.DIM) {
            throw new IllegalArgumentException("特征与置信度都必须有 " + StudentFeature.DIM + " 维");
        }
        bigFive = bigFive == null ? BigFive.neutral() : bigFive;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        followUps = followUps == null ? List.of() : List.copyOf(followUps);
        narrative = narrative == null ? "" : narrative;
        spread = spread == null ? Map.of() : Map.copyOf(spread);
    }

    public static ExtractionResult empty(String method) {
        double[] nan = new double[StudentFeature.DIM];
        java.util.Arrays.fill(nan, Double.NaN);
        return new ExtractionResult(method, false, nan, nan.clone(), BigFive.neutral(),
                List.of(), List.of(), "没有可用的原始素材，无法提取任何特征。", 0, 0, 0L, 0, 0);
    }

    public double valueOf(PsycheDimension d) {
        return features[d.index()];
    }

    public double confidenceOf(PsycheDimension d) {
        return confidence[d.index()];
    }

    public boolean hasValue(PsycheDimension d) {
        return !Double.isNaN(features[d.index()]);
    }

    /** 11 维里拿到了多少维（0..1）。这个数直接决定"这份画像够不够用"。 */
    public double coverage() {
        int n = 0;
        for (double v : features) {
            if (!Double.isNaN(v)) {
                n++;
            }
        }
        return (double) n / StudentFeature.DIM;
    }

    public int valueCount() {
        int n = 0;
        for (double v : features) {
            if (!Double.isNaN(v)) {
                n++;
            }
        }
        return n;
    }

    /** 某一维的全部证据，按分量降序。 */
    public List<Evidence> evidenceFor(PsycheDimension d) {
        List<Evidence> out = new ArrayList<>();
        for (Evidence e : evidence) {
            if (d.label().equals(e.dimension())) {
                out.add(e);
            }
        }
        out.sort((a, b) -> Double.compare(b.weight(), a.weight()));
        return out;
    }

    /** 可读的特征名 → 值，缺失维不出现在结果里。 */
    public Map<String, Double> featuresByName() {
        Map<String, Double> m = new LinkedHashMap<>();
        for (PsycheDimension d : PsycheDimension.values()) {
            double v = features[d.index()];
            if (!Double.isNaN(v)) {
                m.put(d.label(), v);
            }
        }
        return m;
    }

    public Map<String, Double> confidenceByName() {
        Map<String, Double> m = new LinkedHashMap<>();
        for (PsycheDimension d : PsycheDimension.values()) {
            double v = confidence[d.index()];
            if (!Double.isNaN(v)) {
                m.put(d.label(), v);
            }
        }
        return m;
    }

    /** 置信度低于阈值的维一律当缺失——这是"不确定就别用"的执行点。 */
    public ExtractionResult maskBelow(double minConfidence) {
        double[] f = features.clone();
        double[] c = confidence.clone();
        for (int i = 0; i < f.length; i++) {
            if (!Double.isNaN(f[i]) && (Double.isNaN(c[i]) || c[i] < minConfidence)) {
                f[i] = Double.NaN;
                c[i] = Double.NaN;
            }
        }
        return new ExtractionResult(method, llmUsed, f, c, bigFive, evidence, followUps,
                narrative, textChars, answeredItems, elapsedMs, promptTokens, completionTokens, spread);
    }

    /**
     * 某一维在多次采样之间的极差。
     *
     * <p>{@code NaN} 表示这一维没有做多次采样（或只有一路给出值）——
     * 与"采样结果完全一致，极差为 0"是两件不同的事，展示时必须区分，
     * 否则会把"只问了一次"说成"两次答案一模一样"。
     */
    public double spreadOf(PsycheDimension d) {
        Double v = spread.get(d.label());
        return v == null ? Double.NaN : v;
    }

    /** 有采样分歧记录的维度 → 极差。 */
    public Map<String, Double> spreadByName() {
        return spread;
    }
}
