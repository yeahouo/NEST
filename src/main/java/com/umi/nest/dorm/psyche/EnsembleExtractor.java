package com.umi.nest.dorm.psyche;

import com.umi.nest.dorm.model.StudentFeature;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则与大模型两路融合，也是系统默认使用的提取器。
 *
 * <p>融合规则，按优先级从高到低：
 * <ol>
 *   <li><b>问卷作答永远优先</b>。学生自己勾的选项不需要任何模型来"理解"。
 *       大模型读文本读得再好，也不该推翻学生对自己作息的直接陈述。</li>
 *   <li><b>两路都有值时按置信度取高者</b>；置信度接近（差 &lt; 0.10）就取均值——
 *       两个方法独立地给出接近的数，本身就是一个可靠性信号。</li>
 *   <li><b>只有一路有值就用那一路</b>。大模型没提的维度，规则往往也没提，
 *       那就留 NaN 交给下游插补，绝不硬凑。</li>
 * </ol>
 *
 * <p>失败语义很明确：大模型调不通（超时、没配 key、返回不合规 JSON）
 * 时直接返回规则结果，且 {@code method} 保持 {@code "RULE"} 不变。
 * <b>不把降级结果伪装成融合结果</b>——数据库里记的必须是真实发生过的事，
 * 否则实验章节没法解释"为什么这批数据全是规则提取"。
 */
@Component
public class EnsembleExtractor implements FeatureExtractor {

    public static final String NAME = "ENSEMBLE";

    /** 两路置信度差在这个范围内就取均值。 */
    private static final double AGREEMENT_TOLERANCE = 0.10;

    private final RuleBasedExtractor rule;
    private final LlmExtractor llm;

    public EnsembleExtractor(LlmExtractor llm) {
        this.rule = new RuleBasedExtractor();
        this.llm = llm;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean available() {
        return rule.available() || (llm != null && llm.available());
    }

    /** 大模型这一路当前是否可用（管理端展示用）。 */
    public boolean llmAvailable() {
        return llm != null && llm.available();
    }

    @Override
    public ExtractionResult extract(ExtractionInput input) {
        ExtractionResult r = rule.extract(input);
        if (input == null || input.isEmpty()) {
            return r;
        }
        if (llm == null || !llm.worthCalling(input)) {
            return r; // 素材太短或没配 key，规则结果即最终结果，method 保持 RULE
        }
        ExtractionResult l = llm.extract(input);
        if (l == null || l.valueCount() == 0) {
            return r; // 大模型失败，如实退回 RULE
        }
        return merge(r, l, input);
    }

    private ExtractionResult merge(ExtractionResult ruleRes, ExtractionResult llmRes, ExtractionInput in) {
        int dim = StudentFeature.DIM;
        double[] values = new double[dim];
        double[] conf = new double[dim];
        Arrays.fill(values, Double.NaN);
        Arrays.fill(conf, Double.NaN);

        double fromQuestionnaire = 0;
        double fromLlm = 0;
        double fromRule = 0;

        for (PsycheDimension d : PsycheDimension.values()) {
            int i = d.index();
            boolean answered = hasQuestionnaireEvidence(ruleRes, d);
            double rv = ruleRes.features()[i];
            double lv = llmRes.features()[i];

            if (answered) {
                values[i] = rv;                       // 规则结果里已含作答值
                conf[i] = ruleRes.confidence()[i];
                fromQuestionnaire++;
                continue;
            }
            boolean hasR = !Double.isNaN(rv);
            boolean hasL = !Double.isNaN(lv);
            if (hasR && hasL) {
                double rc = safe(ruleRes.confidence()[i]);
                double lc = safe(llmRes.confidence()[i]);
                if (Math.abs(rc - lc) < AGREEMENT_TOLERANCE) {
                    values[i] = (rv + lv) / 2.0;
                } else {
                    values[i] = rc >= lc ? rv : lv;
                }
                conf[i] = Math.max(rc, lc);
                fromRule++;
                fromLlm++;
            } else if (hasL) {
                values[i] = lv;
                conf[i] = safe(llmRes.confidence()[i]);
                fromLlm++;
            } else if (hasR) {
                values[i] = rv;
                conf[i] = safe(ruleRes.confidence()[i]);
                fromRule++;
            }
        }

        List<Evidence> evidence = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Evidence e : ruleRes.evidence()) {
            if (seen.add(e.dimension() + "|" + e.source() + "|" + e.quote())) {
                evidence.add(e);
            }
        }
        for (Evidence e : llmRes.evidence()) {
            if (seen.add(e.dimension() + "|" + e.source() + "|" + e.quote())) {
                evidence.add(e);
            }
        }

        List<String> followUps = new ArrayList<>(ruleRes.followUps());
        for (String f : llmRes.followUps()) {
            if (!followUps.contains(f)) {
                followUps.add(f);
            }
        }

        BigFive bf = llmRes.bigFive();
        if (bf == null || bf.equals(BigFive.neutral())) {
            bf = ruleRes.bigFive();
        }

        String narrative = "规则 + 大模型融合提取（" + in.textChars() + " 字文本）。"
                + "其中 " + (int) fromQuestionnaire + " 维来自学生自陈问卷（权重最高），"
                + (int) fromLlm + " 维有大模型依据，"
                + (int) fromRule + " 维由本地规则命中。" + llmRes.narrative();

        return new ExtractionResult(NAME, true, values, conf, bf, evidence, followUps,
                narrative, in.textChars(), in.answers().size(),
                ruleRes.elapsedMs() + llmRes.elapsedMs(), llmRes.promptTokens(), llmRes.completionTokens());
    }

    private static boolean hasQuestionnaireEvidence(ExtractionResult r, PsycheDimension d) {
        for (Evidence e : r.evidence()) {
            if (Evidence.SOURCE_QUESTIONNAIRE.equals(e.source()) && d.label().equals(e.dimension())) {
                return true;
            }
        }
        return false;
    }

    private static double safe(double v) {
        return Double.isNaN(v) ? 0.0 : v;
    }

    /** 供管理端展示两路各自的口径。 */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ensemble", NAME);
        m.put("rule", rule.name());
        m.put("llm", llm == null ? "-" : llm.name());
        m.put("llmAvailable", llmAvailable());
        m.put("questionnaireItems", Questionnaire.scoredItems().size());
        m.put("dimensions", PsycheDimension.values().length);
        return m;
    }
}
