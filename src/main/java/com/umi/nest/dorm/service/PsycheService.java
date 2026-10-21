package com.umi.nest.dorm.service;

import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.psyche.BigFive;
import com.umi.nest.dorm.psyche.EnsembleExtractor;
import com.umi.nest.dorm.psyche.Evidence;
import com.umi.nest.dorm.psyche.ExtractionInput;
import com.umi.nest.dorm.psyche.ExtractionResult;
import com.umi.nest.dorm.psyche.FeatureExtractor;
import com.umi.nest.dorm.psyche.LlmExtractor;
import com.umi.nest.dorm.psyche.PsycheDimension;
import com.umi.nest.dorm.psyche.PsycheProperties;
import com.umi.nest.dorm.psyche.Questionnaire;
import com.umi.nest.dorm.psyche.RuleBasedExtractor;
import com.umi.nest.dorm.repo.DormPsycheRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 心理特征提取模块的服务层（申请书创新点 4：智能体驱动的信息采集与特征理解）。
 *
 * <p>对外只暴露「给素材 → 出特征 → 采用」三步，中间的规则/大模型/融合对调用方不可见。
 * 求解器只知道 {@code dorm_student_feature} 这张表，不知道值是怎么来的 ——
 * 这是刻意的：换提取策略不该动求解器一行代码。
 *
 * <p>三条不可让步的纪律（写在这个类的门口，因为它就是执行点）：
 * <ol>
 *   <li><b>没有证据就不给值。</b>提取器返回 null 的维度一律留空，走均值插补，绝不用 0 顶上。</li>
 *   <li><b>低置信度不采用。</b>只有过了阈值的维度才会写进特征表影响分配。</li>
 *   <li><b>提取不等于生效。</b>提取结果先落 {@code dorm_psyche_profile} 快照，
 *       必须有人（或学生本人确认）调用 {@link #apply} 才会改写特征表。</li>
 * </ol>
 */
// 显式 bean 名：与学工域的 com.umi.nest.service.PsycheService 简单类名相同，默认 bean 名会撞（同上，2026-10-16）。
@Service("dormPsycheService")
public class PsycheService {

    /** 一次批量提取的默认上限，防止误触发把全校学生都送去调大模型。 */
    private static final int DEFAULT_BATCH_LIMIT = 200;
    /** 单次提取允许的最大批量。 */
    private static final int MAX_BATCH_LIMIT = 2000;

    private final DormPsycheRepository repo;
    private final PsycheProperties props;
    private final EnsembleExtractor ensemble;
    private final LlmExtractor llm;
    private final RuleBasedExtractor rule = new RuleBasedExtractor();

    public PsycheService(DormPsycheRepository repo, PsycheProperties props,
                         EnsembleExtractor ensemble, LlmExtractor llm) {
        this.repo = repo;
        this.props = props;
        this.ensemble = ensemble;
        this.llm = llm;
    }

    // ==================================================================
    // 配置与题库自述
    // ==================================================================

    /** 模块当前状态。前端首页用它显示"大模型那一路开没开"。 */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("module", "心理特征提取（申请书创新点 4）");
        m.put("llmReady", props.llmReady());
        m.put("llmEnabled", props.getLlm().isEnabled());
        m.put("llmBaseUrl", props.getLlm().getBaseUrl());
        m.put("llmModel", props.getLlm().getModel());
        m.put("llmMaxConcurrent", props.getLlm().getMaxConcurrent());
        m.put("llmMaxTextChars", props.getLlm().getMaxTextChars());
        m.put("minConfidence", props.getApply().getMinConfidence());
        m.put("extractor", ensemble.describe());
        m.put("dimensions", dimensionCatalog());
        m.put("questionnaireItems", Questionnaire.items().size());
        m.put("questionnaireScored", Questionnaire.scoredItems().size());
        m.put("questionnaireFullCoverage", Questionnaire.fullCoverage());
        m.put("textCount", repo.countTexts());
        m.put("textCountWithConsent", repo.countTextsWithConsent());
        m.put("answerCount", repo.countAnswers());
        m.put("profileCount", repo.countProfiles());
        m.put("appliedCount", repo.countApplied());
        m.put("pipeline", List.of(
                "① 收集素材：自述/聊天记录/辅导员观察 + 量表作答（需学生授权，consent=1）",
                "② 特征提取：本地规则（可解释、可复现）与大模型（理解口语化表述）双路并行",
                "③ 融合：量表优先；两路都有值时按置信度取高者，接近时取均值",
                "④ 落快照：结果存 dorm_psyche_profile，附逐维证据原文，可人工复核",
                "⑤ 采用：过阈值的维度写回 dorm_student_feature，此后才会影响宿舍分配",
                "⑥ 分配：求解器读特征表算相容度；缺失维走均值插补，低置信维不参与"));
        m.put("notice", "分配只使用被「采用」的特征。提取结果本身不影响任何人，"
                + "这是有意设计的——决定谁和谁住的值必须能追溯到证据。");
        return m;
    }

    private List<Map<String, Object>> dimensionCatalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PsycheDimension d : PsycheDimension.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", d.name());
            m.put("label", d.label());
            m.put("index", d.index());
            m.put("hourScale", d.hourScale());
            m.put("usedBySolver", d != PsycheDimension.AMBITION);
            m.put("scale", d.hourScale() ? "小时（跨零点不取模，凌晨 1:00 = 25.0）" : "0..1");
            out.add(m);
        }
        return out;
    }

    /** 题库全文，学生端渲染表单用。 */
    public Map<String, Object> questionnaire() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Questionnaire.Item it : Questionnaire.items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", it.code());
            m.put("no", it.no());
            m.put("dimension", it.dimension() == null ? null : it.dimension().label());
            m.put("prompt", it.prompt());
            m.put("kind", it.kind().name());
            m.put("hint", it.hint());
            m.put("scored", it.scored());
            List<Map<String, Object>> opts = new ArrayList<>();
            for (Questionnaire.Option o : it.options()) {
                Map<String, Object> om = new LinkedHashMap<>();
                om.put("label", o.label());
                om.put("value", o.value());
                opts.add(om);
            }
            m.put("options", opts);
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fullCoverage", Questionnaire.fullCoverage());
        out.put("items", items);
        out.put("note", "作息两题存的是小时（22 / 22.5 / 23.5 / 24.5 / 26），"
                + "其余题存 0..1。第 15 题问规律性，刻意不挂到入睡时刻那一维——"
                + "把 0.5 和 23.5 平均会得到 12 点，一个不存在的钟点。");
        return out;
    }

    // ==================================================================
    // 素材
    // ==================================================================

    public List<Map<String, Object>> overview(String keyword, int limit) {
        return repo.studentPsycheOverview(keyword, limit <= 0 ? 200 : limit);
    }

    public Map<String, Object> detail(long studentId) {
        Student s = repo.studentById(studentId);
        if (s == null) {
            throw new IllegalArgumentException("宿舍名单里没有 id=" + studentId + " 的学生");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("student", studentMap(s));
        out.put("texts", repo.allTextsOf(studentId));
        out.put("answers", repo.listAnswers(studentId));
        out.put("profile", repo.profileOf(studentId));
        out.put("appliedFeatures", repo.currentFeatures(studentId));
        return out;
    }

    public long addText(long studentId, String kind, String content, Boolean consent) {
        requireStudent(studentId);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("素材内容不能为空");
        }
        int max = props.getLlm().getMaxTextChars();
        String body = content.length() > max * 4 ? content.substring(0, max * 4) : content;
        return repo.saveText(studentId, kind, body, consent == null || consent, "MANUAL");
    }

    public int deleteText(long id) {
        return repo.deleteText(id);
    }

    // ==================================================================
    // 量表作答
    // ==================================================================

    /**
     * 保存作答。值是数字或字符串：数字型题存 {@code answer_num}，
     * 文本题（`self_intro`）存 {@code answer_text}。
     *
     * <p>刻意不在这里校验"答案是否在选项范围内"：题库会改版，旧答案必须仍能读出来。
     * 校验放在前端和 {@link Questionnaire#score} 里。
     */
    public Map<String, Object> submitAnswers(long studentId, Map<String, Object> answers) {
        requireStudent(studentId);
        if (answers == null || answers.isEmpty()) {
            throw new IllegalArgumentException("没有提交任何作答");
        }
        int saved = 0;
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, Object> e : answers.entrySet()) {
            Questionnaire.Item item = Questionnaire.byCode(e.getKey());
            if (item == null) {
                unknown.add(e.getKey());
                continue;
            }
            String dim = item.dimension() == null ? null : item.dimension().label();
            Object v = e.getValue();
            Double num = null;
            String text = null;
            if (v == null) {
                continue;
            }
            if (item.isText() || v instanceof String s && !isNumeric(s)) {
                text = String.valueOf(v);
            } else if (v instanceof Number n) {
                num = n.doubleValue();
            } else {
                String s = String.valueOf(v).trim();
                if (isNumeric(s)) {
                    num = Double.parseDouble(s);
                } else {
                    text = s;
                }
            }
            repo.saveAnswer(studentId, item.code(), item.no(), dim, num, text);
            saved++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("studentId", studentId);
        out.put("saved", saved);
        out.put("unknownItems", unknown);
        out.put("remaining", Questionnaire.nextItems(new HashSet<>(answers.keySet()), 5)
                .stream().map(Questionnaire.Item::code).toList());
        return out;
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        try {
            Double.parseDouble(s.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ==================================================================
    // 提取
    // ==================================================================

    /**
     * 运行一次提取。{@code studentId} 为空则按学号顺序批量跑。
     *
     * <p>大模型那一路失败时会退回规则结果，{@code method} 保持 {@code "RULE"} ——
     * 不把降级结果伪装成融合结果，数据库里记的必须是真实发生过的事。
     */
    public Map<String, Object> extract(Long studentId, Boolean useLlm, Integer limit) {
        FeatureExtractor extractor = Boolean.FALSE.equals(useLlm) ? rule : ensemble;
        List<Long> ids = studentId != null
                ? List.of(studentId)
                : repo.allStudentIds(Math.min(limit == null ? DEFAULT_BATCH_LIMIT : limit, MAX_BATCH_LIMIT));
        if (ids.isEmpty()) {
            throw new IllegalStateException("宿舍名单里还没有学生，请先生成或导入数据");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int llmUsed = 0;
        long started = System.currentTimeMillis();
        for (Long id : ids) {
            Map<String, Object> m = extractOne(id, extractor);
            results.add(m);
            if (Boolean.TRUE.equals(m.get("llmUsed"))) {
                llmUsed++;
            }
        }
        long elapsed = System.currentTimeMillis() - started;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("extractor", extractor.name());
        out.put("requested", ids.size());
        out.put("done", results.size());
        out.put("llmUsed", llmUsed);
        out.put("ruleOnly", results.size() - llmUsed);
        out.put("elapsedMs", elapsed);
        out.put("results", results.size() <= 50 ? results : results.subList(0, 50));
        out.put("truncated", results.size() > 50);
        out.put("note", "结果已存入 dorm_psyche_profile 快照，尚未影响分配。"
                + "确认无误后调用采用接口（apply）才会写回特征表。");
        return out;
    }

    private Map<String, Object> extractOne(long studentId, FeatureExtractor extractor) {
        ExtractionInput raw = buildInput(studentId);
        // 生产路径与评测路径走同一条清洗口径：先剔除他人/助手发言，再对即将出站的文本脱敏。
        // 网站上的"提取"按钮和大模型看到的东西必须与评测报告里那一栏是同一件事，
        // 否则报告再漂亮也证明不了线上行为。
        ExtractionInput input = raw.sanitized(List.of(raw.name()));
        ExtractionResult r;
        try {
            r = extractor.extract(input);
        } catch (RuntimeException e) {
            r = null;
        }
        if (r == null) {
            r = ExtractionResult.empty(extractor.name());
        }
        repo.saveProfile(studentId, r);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("studentId", studentId);
        m.put("studentNo", raw.studentNo());
        m.put("name", raw.name());
        m.put("method", r.method());
        m.put("llmUsed", r.llmUsed());
        m.put("coverage", round(r.coverage()));
        m.put("valueCount", r.valueCount());
        m.put("textChars", r.textChars());
        m.put("rawTextChars", raw.textChars());
        m.put("answeredItems", r.answeredItems());
        m.put("elapsedMs", r.elapsedMs());
        m.put("features", r.featuresByName());
        m.put("confidence", r.confidenceByName());
        m.put("bigFive", bigFiveMap(r.bigFive()));
        m.put("followUps", r.followUps());
        m.put("narrative", r.narrative());
        m.put("evidenceCount", r.evidence().size());
        return m;
    }

    private ExtractionInput buildInput(long studentId) {
        Student s = requireStudent(studentId);
        List<ExtractionInput.TextSample> texts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> t : repo.textsOf(studentId)) {
            String content = String.valueOf(t.get("content"));
            String kind = String.valueOf(t.get("kind"));
            if (seen.add(kind + "\u0000" + content)) {
                texts.add(new ExtractionInput.TextSample(kind, content));
            }
        }
        // 自由文本题本身就是一段自述，对提取器来说和手写素材没区别 ——
        // 但要去重：同一条内容被存过两次会让线索命中数翻倍，置信度虚高。
        for (String t : repo.answerTextsOf(studentId).values()) {
            if (t != null && !t.isBlank() && seen.add("SELF_INTRO\u0000" + t)) {
                texts.add(new ExtractionInput.TextSample("SELF_INTRO", t));
            }
        }
        Map<String, Double> answers = repo.answersOf(studentId);
        return new ExtractionInput(studentId, studentNoOf(studentId), s.name(), s.grade(),
                s.majorCluster(), texts, answers);
    }

    public Map<String, Object> profile(long studentId) {
        Map<String, Object> p = repo.profileOf(studentId);
        if (p == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("studentId", studentId);
            out.put("profile", null);
            out.put("message", "该学生还没有提取结果。先提交素材或量表作答，再运行提取。");
            return out;
        }
        return p;
    }

    // ==================================================================
    // 采用（提取结果 → 特征表）
    // ==================================================================

    /**
     * 把快照里过了置信度阈值的维度写回特征表，此后它们才会影响分配。
     *
     * @return 本次实际写入的维度、被低置信度拦下的维度，以及是否真的落库
     */
    public Map<String, Object> apply(long studentId, Double minConfidence) {
        Map<String, Object> prof = repo.profileOf(studentId);
        if (prof == null) {
            throw new IllegalStateException("该学生还没有提取结果，请先运行提取");
        }
        double minConf = minConfidence == null ? props.getApply().getMinConfidence() : minConfidence;
        Map<String, Object> confs = asMap(prof.get("confidence"));
        Map<String, Object> feats = asMap(prof.get("features"));

        Map<PsycheDimension, Double> values = new LinkedHashMap<>();
        List<Map<String, Object>> dropped = new ArrayList<>();
        for (PsycheDimension d : PsycheDimension.values()) {
            Double v = num(feats.get(d.label()));
            if (v == null) {
                continue;
            }
            Double c = num(confs.get(d.label()));
            double cc = c == null ? 0 : c;
            if (cc < minConf) {
                Map<String, Object> dm = new LinkedHashMap<>();
                dm.put("dimension", d.label());
                dm.put("value", round(v));
                dm.put("confidence", round(cc));
                dm.put("reason", "置信度低于阈值 " + round(minConf) + "，不参与分配");
                dropped.add(dm);
                continue;
            }
            values.put(d, v);
        }
        if (values.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("studentId", studentId);
            out.put("applied", 0);
            out.put("dropped", dropped);
            out.put("minConfidence", minConf);
            out.put("message", "没有任何维度达到置信度阈值，特征表未改动。"
                    + "这是正常结论：信息不足时宁可用均值插补，也不拿猜的值去决定谁和谁住。");
            return out;
        }
        String source = Boolean.TRUE.equals(prof.get("llmUsed")) ? "AGENT_LLM" : "RULE";
        int written = repo.applyProfile(studentId, values, source);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("studentId", studentId);
        out.put("applied", written);
        out.put("source", source);
        out.put("method", prof.get("method"));
        out.put("minConfidence", minConf);
        out.put("appliedDimensions", values.keySet().stream().map(PsycheDimension::label).toList());
        out.put("dropped", dropped);
        out.put("currentFeatures", repo.currentFeatures(studentId));
        out.put("message", "已写入特征表，下次分配会使用这些值。"
                + "需要让它们立刻生效，请重新运行一次「一键分配」。");
        return out;
    }

    // ==================================================================
    // 大模型连通性自检
    // ==================================================================

    public Map<String, Object> probe() {
        if (!props.llmReady()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("reason", "未配置 API Key。在 config/local.properties 里设置 nest.psyche.llm.api-key 后重启应用。");
            m.put("llmEnabled", props.getLlm().isEnabled());
            return m;
        }
        return llm.probe();
    }

    // ==================================================================
    // 账号绑定
    // ==================================================================

    public Map<String, Object> link(long studentId, Long userId, String studentNo) {
        Student s = requireStudent(studentId);
        Long target = userId;
        if (target == null && studentNo != null && !studentNo.isBlank()) {
            // 按学号绑给"名单先到、账号后开"的场景用：管理员邀请学生注册后再回填
            Long found = repo.userIdOfNo(studentNo);
            if (found == null) {
                throw new IllegalArgumentException("系统用户表里没有学号/用户名 " + studentNo);
            }
            target = found;
        }
        if (target == null) {
            throw new IllegalArgumentException("请提供 userId 或 studentNo 之一");
        }
        repo.linkUser(studentId, target);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("studentId", studentId);
        out.put("studentNo", studentNoOf(studentId));
        out.put("name", s.name());
        out.put("userId", target);
        out.put("message", "已绑定。该学生登录后可在门户看到自己的分配结果与解释。");
        return out;
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private Student requireStudent(long studentId) {
        Student s = repo.studentById(studentId);
        if (s == null) {
            throw new IllegalArgumentException("宿舍名单里没有 id=" + studentId + " 的学生");
        }
        return s;
    }

    private String studentNoOf(long studentId) {
        return repo.studentNoOf(studentId);
    }

    private static Map<String, Object> studentMap(Student s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("name", s.name());
        m.put("gender", s.gender().name());
        m.put("grade", s.grade());
        m.put("major", s.major());
        m.put("majorCluster", s.majorCluster());
        m.put("specialNeed", s.specialNeed().name());
        return m;
    }

    private static Map<String, Object> bigFiveMap(BigFive b) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (b == null) {
            return m;
        }
        m.put("开放性", round(b.openness()));
        m.put("尽责性", round(b.conscientiousness()));
        m.put("外向性", round(b.extraversion()));
        m.put("宜人性", round(b.agreeableness()));
        m.put("神经质", round(b.neuroticism()));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private static Double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    private static double round(double v) {
        if (Double.isNaN(v)) {
            return Double.NaN;
        }
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** 让 {@link Evidence} 在本类里有个引用点，避免 IDE 把 import 当未使用清掉（证据链是核心，不能丢）。 */
    @SuppressWarnings("unused")
    private static String evidenceHint(Evidence e) {
        return e.dimension() + "←" + e.quote();
    }
}
