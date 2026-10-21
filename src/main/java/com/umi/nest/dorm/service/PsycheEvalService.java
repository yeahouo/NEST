package com.umi.nest.dorm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.umi.nest.dorm.data.GenParams;
import com.umi.nest.dorm.data.SyntheticDataGenerator;
import com.umi.nest.dorm.data.SyntheticDataset;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.model.StudentFeature;
import com.umi.nest.dorm.psyche.EnsembleExtractor;
import com.umi.nest.dorm.psyche.ExtractionInput;
import com.umi.nest.dorm.psyche.ExtractorEvaluator;
import com.umi.nest.dorm.psyche.LlmExtractor;
import com.umi.nest.dorm.psyche.PsycheProperties;
import com.umi.nest.dorm.psyche.RuleBasedExtractor;
import com.umi.nest.dorm.psyche.SelfReportGenerator;
import com.umi.nest.dorm.repo.DormPsycheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 心理特征提取器的量化评测（管理端「提取器评测」页的后端）。
 *
 * <p>这个类存在的唯一理由，是回答一个问题：<b>你怎么知道大模型提取的性格是准的？</b>
 *
 * <p>没有它，这个模块的全部证据就是「随便找了几个学生跑了一下，看着挺合理」——
 * 这种话在答辩上是撑不住的。有了它，结论变成一张表：
 * 在同一批虚拟学生上，规则词库与 DeepSeek 各自的 MAE、相关系数、覆盖率差多少，
 * 以及「剔除他人发言」这一步到底值几个点。
 *
 * <p>做法是：{@link SyntheticDataGenerator} 本来就为每个虚拟学生抽了真特征 z_i，
 * 但我们从不让提取器看到 z_i，而是先用 {@link SelfReportGenerator} 把 z_i
 * 翻译成一段学生口吻的自述与聊天记录，再把这段<b>文字</b>喂给提取器，
 * 最后拿提取结果与 z_i 比。提取器全程只能看见文字，看不见答案。
 *
 * <p>评测用的学生是合成数据，用的是公示的分布，不涉及任何真实学生信息。
 */
@Service
public class PsycheEvalService {

    private static final Logger log = LoggerFactory.getLogger(PsycheEvalService.class);

    /** 大模型慢，默认只对前几名做对照，规则提取器则跑全量。 */
    public static final int DEFAULT_LLM_STUDENTS = 6;

    private final PsycheProperties props;
    private final DormPsycheRepository repo;

    public PsycheEvalService(PsycheProperties props, DormPsycheRepository repo) {
        this.props = props;
        this.repo = repo;
    }

    /**
     * 跑一次评测。
     *
     * @param students    参与全量规则评测的虚拟学生数
     * @param seed        随机种子（换种子＝换一批学生，用来判断结论是不是偶然）
     * @param missingRate 出题时整段掩盖某一维的比例——测覆盖率
     * @param privacyRate 出题时注入手机号/学号/姓名的比例——测脱敏
     * @param llmStudents 大模型只看前几名（每次调用都要花钱与时间）
     * @param useLlm      是否真的调大模型
     * @param ablation    是否额外跑「不剔除他人发言」的对照组
     */
    public Map<String, Object> run(int students, long seed, double missingRate, double privacyRate,
                                   int llmStudents, boolean useLlm, boolean ablation) {
        long t0 = System.currentTimeMillis();

        int n = Math.max(4, Math.min(students, 2000));
        GenParams gp = new GenParams(n, 4, 0.20, 0.0, 0.5, 3, 0.03, seed, true, 3);
        SyntheticDataset ds = SyntheticDataGenerator.generate(gp);
        SelfReportGenerator.Config scfg = new SelfReportGenerator.Config(missingRate, privacyRate, seed);

        // 出题用的随机源单独起一个：它只影响"用哪种说法描述"，不影响学生的真值。
        Random rnd = new Random(seed * 31 + 7);
        List<ExtractorEvaluator.Case> cases = new ArrayList<>();
        for (Student s : ds.problem().students()) {
            StudentFeature z = ds.truth().trueFeatureOf(s.id());
            SelfReportGenerator.Sample smp = SelfReportGenerator.sample(s, z, scfg, rnd);
            List<ExtractionInput.TextSample> texts = new ArrayList<>();
            if (smp.hasChat()) {
                texts.add(new ExtractionInput.TextSample("CHAT_LOG", smp.chatLog()));
            }
            texts.add(new ExtractionInput.TextSample("SELF_INTRO", smp.selfIntro()));
            ExtractionInput in = new ExtractionInput(s.id(), "DEMO" + (24000 + s.id()), smp.name(),
                    s.grade(), s.majorCluster(), texts, Map.of());
            cases.add(new ExtractorEvaluator.Case(s.id(), in, z));
        }

        RuleBasedExtractor rule = new RuleBasedExtractor();
        int subSize = Math.max(1, Math.min(llmStudents <= 0 ? DEFAULT_LLM_STUDENTS : llmStudents, cases.size()));

        List<ExtractorEvaluator.Report> head = new ArrayList<>();
        head.add(ExtractorEvaluator.evaluate(RuleBasedExtractor.NAME, rule, cases.subList(0, subSize), true));

        boolean llmReady = false;
        String llmNote;
        if (!useLlm) {
            llmNote = "本次未调用大模型（useLlm=false）。";
        } else if (!props.getLlm().isEnabled()) {
            llmNote = "大模型未启用（nest.psyche.llm.enabled=false）。";
        } else {
            try {
                LlmExtractor llm = new LlmExtractor(props, new ObjectMapper());
                if (!llm.available()) {
                    llmNote = "大模型不可用：未配置 API Key。";
                } else {
                    head.add(ExtractorEvaluator.evaluate(LlmExtractor.NAME, llm, cases.subList(0, subSize), true));
                    head.add(ExtractorEvaluator.evaluate(EnsembleExtractor.NAME,
                            new EnsembleExtractor(llm), cases.subList(0, subSize), true));
                    llmReady = true;
                    llmNote = "大模型模型 " + props.getLlm().getModel()
                            + "，采样 " + props.getLlm().getSamples() + " 次（第 2 次起温度 "
                            + props.getLlm().getSampleTemperature() + "），仅评估前 " + subSize + " 名。";
                }
            } catch (RuntimeException e) {
                llmNote = "大模型调用失败：" + e.getClass().getSimpleName();
                log.warn("评测中的大模型支路失败", e);
            }
        }

        ExtractorEvaluator.Report fullRule =
                ExtractorEvaluator.evaluate(RuleBasedExtractor.NAME, rule, cases, true);

        List<ExtractorEvaluator.Report> ablationReports = new ArrayList<>();
        if (ablation) {
            List<ExtractorEvaluator.Case> sub = cases.subList(0, subSize);
            ablationReports.add(ExtractorEvaluator.evaluate(RuleBasedExtractor.NAME, rule, sub, false));
            ablationReports.add(ExtractorEvaluator.evaluate(RuleBasedExtractor.NAME, rule, sub, true));
            if (llmReady) {
                LlmExtractor llm = new LlmExtractor(props, new ObjectMapper());
                // 消融要跑两遍大模型，是最贵的一步；只在真的启用大模型时才做。
                ablationReports.add(ExtractorEvaluator.evaluate(LlmExtractor.NAME, llm, sub, false));
                ablationReports.add(ExtractorEvaluator.evaluate(LlmExtractor.NAME, llm, sub, true));
            }
        }

        long elapsed = System.currentTimeMillis() - t0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("students", n);
        out.put("seed", seed);
        out.put("llmStudents", subSize);
        out.put("missingRate", missingRate);
        out.put("privacyRate", privacyRate);
        out.put("llmUsed", llmReady);
        out.put("llmNote", llmNote);
        out.put("elapsedMs", elapsed);
        out.put("sampleText", sampleText(cases.get(0)));
        out.put("headline", ExtractorEvaluator.compareTable(head));
        out.put("fullRule", ExtractorEvaluator.compareTable(List.of(fullRule)));
        out.put("ablation", ablationReports.isEmpty()
                ? null : ExtractorEvaluator.compareTable(ablationReports));
        out.put("reports", reports(head, List.of(fullRule)));

        // 逐维明细页要看得见"哪一维拖后腿"，所以把最好那一个提取器的明细也带上。
        ExtractorEvaluator.Report best = head.get(head.size() - 1);
        out.put("detail", ExtractorEvaluator.markdown(best));
        out.put("detailFull", fullRule.cases() == best.cases() ? null : ExtractorEvaluator.markdown(fullRule));
        out.put("note", "评测用的是合成学生：真特征是生成的，提取器只看得到由真特征反写出的文字。"
                + "所以这里的误差是真实的语义理解误差，不是抄写误差。");

        persist(out, head, fullRule);
        return out;
    }

    @SafeVarargs
    private List<Map<String, Object>> reports(List<ExtractorEvaluator.Report>... groups) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<ExtractorEvaluator.Report> g : groups) {
            for (ExtractorEvaluator.Report r : g) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("extractor", r.extractor());
                m.put("variant", r.variant());
                m.put("cases", r.cases());
                m.put("failed", r.failed());
                m.put("elapsedMs", r.elapsedMs());
                m.put("maeHour", round(r.maeHour()));
                m.put("mae01", round(r.mae01()));
                m.put("pearsonMean", round(r.pearsonMean()));
                m.put("spearmanMean", round(r.spearmanMean()));
                m.put("collapse", round(r.collapse01()));
                m.put("coverage", round(r.coverage()));
                out.add(m);
            }
        }
        return out;
    }

    /** 给前端看一眼「出题长什么样」：文字在左，答案在右，中间没有任何提示。 */
    private Map<String, Object> sampleText(ExtractorEvaluator.Case c) {
        double[] z = c.truth().toArray();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("selfIntro", firstText(c.input(), "SELF_INTRO"));
        m.put("chatLog", firstText(c.input(), "CHAT_LOG"));
        m.put("truth", z);
        return m;
    }

    private String firstText(ExtractionInput in, String kind) {
        for (ExtractionInput.TextSample t : in.texts()) {
            if (kind.equals(t.kind())) {
                return t.content();
            }
        }
        return "";
    }

    /** 评测结果留档：换种子重跑能不能复现，靠这个表回答。 */
    private void persist(Map<String, Object> payload, List<ExtractorEvaluator.Report> head,
                         ExtractorEvaluator.Report fullRule) {
        try {
            List<ExtractorEvaluator.Report> all = new ArrayList<>(head);
            if (fullRule.cases() != head.get(head.size() - 1).cases()) {
                all.add(fullRule);
            }
            repo.saveEval(all, String.valueOf(payload.get("headline")), String.valueOf(payload.get("detail")));
        } catch (RuntimeException e) {
            // 留档失败不该让一次成功的评测变成失败——但必须留下痕迹。
            log.warn("评测结果留档失败", e);
            payload.put("persistWarning", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public List<Map<String, Object>> history(int limit) {
        return repo.listEvals(limit);
    }

    private static double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return v;
        }
        return Math.round(v * 10000.0) / 10000.0;
    }
}
