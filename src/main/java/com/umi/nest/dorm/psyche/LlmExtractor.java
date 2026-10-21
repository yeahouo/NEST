package com.umi.nest.dorm.psyche;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.umi.nest.dorm.model.StudentFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * 用云端大模型从自由文本里提取心理与生活习惯特征。
 *
 * <p>这是申请书创新点 4 的主体。它比 {@link RuleBasedExtractor} 强的地方只有一个，
 * 但很关键：<b>能读懂反讽、条件句和言外之意</b>。
 * 「我平时挺安静的，除非室友先动手」——规则提取器会把"安静"和"动手"都记上，
 * 大模型知道后半句是玩笑。宿舍冲突往往就藏在这些地方。
 *
 * <p>三条纪律：
 * <ol>
 *   <li><b>要证据</b>。提示词里明确要求每一维附上原文片段，
 *       拿不到原文的维度必须返回 null。宁可少提取，不许编。</li>
 *   <li><b>要严格 JSON</b>。走 {@code response_format=json_object}，
 *       再兜一层去掉 markdown 围栏——模型偶尔仍会画蛇添足。</li>
 *   <li><b>失败就降级，不许抛到调用方</b>。这个类返回 {@code null} 表示"这次没成功"，
 *       由 {@link EnsembleExtractor} 决定用规则结果兜底。
 *       一次网络抖动不应该让学生填的卷子白填。</li>
 * </ol>
 */
@Component
public class LlmExtractor implements FeatureExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmExtractor.class);

    public static final String NAME = "LLM";

    /** 素材太短就没必要调模型——几十个字里挖不出人格，只是白花钱。 */
    private static final int MIN_TEXT_CHARS = 30;

    private final PsycheProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Semaphore gate;

    public LlmExtractor(PsycheProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.gate = new Semaphore(Math.max(1, props.getLlm().getMaxConcurrent()));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean available() {
        return props.llmReady();
    }

    /** 是否值得为这份输入调用模型。 */
    public boolean worthCalling(ExtractionInput input) {
        return available() && input != null && input.textChars() >= MIN_TEXT_CHARS;
    }

    @Override
    public ExtractionResult extract(ExtractionInput input) {
        if (!worthCalling(input)) {
            return null;
        }
        int samples = Math.max(1, props.getLlm().getSamples());
        long t0 = System.nanoTime();
        List<ExtractionResult> got = new ArrayList<>();
        int ptTotal = 0;
        int ctTotal = 0;
        for (int i = 0; i < samples; i++) {
            // 第 1 次恒为 0 度：要一个确定的基线。后续加温度制造多样性，
            // 否则多次采样必然逐字相同，测出的"分歧"永远是 0，等于没测。
            double temperature = i == 0 ? 0.0 : props.getLlm().getSampleTemperature();
            ExtractionResult r = extractOnce(input, temperature);
            if (r != null) {
                got.add(r);
                ptTotal += r.promptTokens();
                ctTotal += r.completionTokens();
            }
        }
        long elapsed = (System.nanoTime() - t0) / 1_000_000L;
        if (got.isEmpty()) {
            return null;
        }
        if (got.size() == 1) {
            ExtractionResult s = got.get(0);
            // 只有一个样本时"样本间分歧"这个概念不成立，不是"分歧为 0"
            return new ExtractionResult(s.method(), s.llmUsed(), s.features(), s.confidence(), s.bigFive(),
                    s.evidence(), s.followUps(), s.narrative(), s.textChars(), s.answeredItems(),
                    elapsed, ptTotal, ctTotal, Map.of());
        }
        return merge(got, elapsed, ptTotal, ctTotal);
    }

    /** 单次调用。返回 null 表示这次没成功。 */
    private ExtractionResult extractOnce(ExtractionInput input, double temperature) {
        String body = buildRequestBody(input, temperature);
        if (body == null) {
            return null;
        }
        boolean acquired = false;
        try {
            gate.acquire();
            acquired = true;
            String raw = post(body);
            if (raw == null) {
                return null;
            }
            JsonNode root = mapper.readTree(raw);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                log.warn("LLM 返回没有 choices：{}", truncate(raw, 400));
                return null;
            }
            String content = choices.get(0).path("message").path("content").asText("");
            JsonNode parsed = mapper.readTree(stripFences(content));
            int pt = root.path("usage").path("prompt_tokens").asInt(0);
            int ct = root.path("usage").path("completion_tokens").asInt(0);
            return toResult(parsed, input, 0L, pt, ct);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("LLM 提取失败，将由规则提取兜底：{}", e.toString());
            return null;
        } finally {
            if (acquired) {
                gate.release();
            }
        }
    }

    /**
     * 合并多次采样。
     *
     * <p>核心规则只有一条：<b>分歧越大，置信度越低</b>。
     * 两次都说"凌晨两点睡"，和一次说"十点"一次说"两点"，对分配算法是完全不同的信号——
     * 后者必须让这一维变成"不确定"（置信度掉到阈值以下被丢弃），
     * 否则等于把模型的一次口误当成学生的真实作息，然后按这个错误作息去配室友。
     *
     * <p>证据取"取值最接近均值"的那一次，这样页面上展示的原话与展示的数值
     * 来自同一份输出，不会出现"证据写着两点、数值写着零点"的错位。
     */
    private ExtractionResult merge(List<ExtractionResult> samples, long elapsedMs, int pt, int ct) {
        double[] values = new double[StudentFeature.DIM];
        double[] conf = new double[StudentFeature.DIM];
        Arrays.fill(values, Double.NaN);
        Arrays.fill(conf, Double.NaN);
        List<Evidence> evidence = new ArrayList<>();
        Map<String, Double> spread = new LinkedHashMap<>();

        for (PsycheDimension d : PsycheDimension.values()) {
            List<Double> vals = new ArrayList<>();
            List<Double> confs = new ArrayList<>();
            List<Integer> owners = new ArrayList<>();
            for (int i = 0; i < samples.size(); i++) {
                ExtractionResult s = samples.get(i);
                double v = s.valueOf(d);
                if (!Double.isNaN(v)) {
                    vals.add(v);
                    confs.add(s.confidenceOf(d));
                    owners.add(i);
                }
            }
            if (vals.isEmpty()) {
                continue;
            }
            double sum = 0;
            for (double v : vals) {
                sum += v;
            }
            double mean = sum / vals.size();

            int best = 0;
            double bestGap = Double.MAX_VALUE;
            for (int i = 0; i < vals.size(); i++) {
                double g = Math.abs(vals.get(i) - mean);
                if (g < bestGap) {
                    bestGap = g;
                    best = i;
                }
            }
            evidence.addAll(samples.get(owners.get(best)).evidenceFor(d));

            double baseConf = 0;
            for (double c : confs) {
                baseConf += Double.isNaN(c) ? 0.6 : c;
            }
            baseConf /= confs.size();

            if (vals.size() >= 2) {
                double lo = Double.MAX_VALUE;
                double hi = -Double.MAX_VALUE;
                for (double v : vals) {
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                }
                double range = hi - lo;
                spread.put(d.label(), round4(range));
                // 归一化尺度：小时维差 2 小时算"完全分歧"，0..1 维差 0.5 算"完全分歧"
                double scale = d.hourScale() ? 2.0 : 0.5;
                double agree = 1.0 - Math.min(1.0, range / scale);
                baseConf = baseConf * (0.30 + 0.70 * agree);
            }
            values[d.index()] = mean;
            conf[d.index()] = clamp01(baseConf);
        }

        BigFive bf = meanBigFive(samples);
        List<String> followUps = new ArrayList<>();
        for (ExtractionResult s : samples) {
            for (String f : s.followUps()) {
                if (!followUps.contains(f)) {
                    followUps.add(f);
                }
            }
        }

        ExtractionResult r = new ExtractionResult(NAME + "×" + samples.size(), true, values, conf, bf,
                evidence, followUps, samples.get(0).narrative(), samples.get(0).textChars(),
                samples.get(0).answeredItems(), elapsedMs, pt, ct, spread);
        if (r.valueCount() == 0) {
            log.warn("LLM 多次采样合起来一个维度都没给出依据，视为失败并降级");
            return null;
        }
        return r;
    }

    private static BigFive meanBigFive(List<ExtractionResult> samples) {
        double o = 0;
        double c = 0;
        double e = 0;
        double a = 0;
        double n = 0;
        for (ExtractionResult s : samples) {
            BigFive b = s.bigFive();
            o += b.openness();
            c += b.conscientiousness();
            e += b.extraversion();
            a += b.agreeableness();
            n += b.neuroticism();
        }
        int k = samples.size();
        return new BigFive(o / k, c / k, e / k, a / k, n / k);
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** 逐维评分锚点，拼进系统提示词。 */
    private static String anchorLines() {
        StringBuilder sb = new StringBuilder("评分锚点（每个维度都必须对齐到这些具体描述）：\n");
        for (PsycheDimension d : PsycheDimension.values()) {
            sb.append("- ").append(d.label()).append('：').append(d.anchors()).append('\n');
        }
        return sb.toString();
    }

    // ────────────────────────── 请求 ──────────────────────────

    private String buildRequestBody(ExtractionInput in, double temperature) {
        String system = """
                你是高校宿舍分配系统的心理特征提取组件。你的任务是把学生自己写的文字，\
                转换成 11 个可量化维度上的取值，供后续宿舍匹配算法使用。

                绝对规则：
                1. 只依据学生原话。任何一维如果没有原文支撑，value 必须返回 null，不许推测、不许用"一般来说"。
                2. 每一维给出原文片段（evidence）和理由（reasoning）。找不到片段就等于没有依据。
                3. 反讽、玩笑、条件句要按真实语义理解。「我很安静，除非室友先动手」里的"动手"不是暴力倾向。
                4. 只输出一个 JSON 对象，不要 markdown 围栏、不要任何解释文字。

                输出 JSON 的格式（dimensions 的键必须完全使用下列中文名）：
                {
                  "bigFive": {"openness":0-1, "conscientiousness":0-1, "extraversion":0-1, "agreeableness":0-1, "neuroticism":0-1},
                  "dimensions": {
                    "入睡时刻": {"value": 小时数, "confidence": 0-1, "evidence": "原文片段", "reasoning": "为什么"},
                    "起床时刻": {"value": 小时数, "confidence": 0-1, "evidence": "原文片段", "reasoning": "为什么"},
                    "吸烟":     {"value": 0-1, "confidence": 0-1, "evidence": "原文片段", "reasoning": "为什么"},
                    "卫生":     {"value": 0-1, "confidence": 0-1, "evidence": "原文片段", "reasoning": "为什么"},
                    "性格外向度": {"value": 0-1, "confidence": 0-1, "evidence": "原文片段", "reasoning": "为什么"},
                    "在房间学习": {"value": 0-1, "confidence": 0-1, "evidence": "原文片段", "reasoning": "为什么"},
                    "噪声容忍度": {"value": 0-1, "confidence": 0-1, "evidence": "原文片段", "reasoning": "为什么"},
                    "社交活跃度": {"value": 0-1, "confidence": 0-1, "evidence": "原文片段", "reasoning": "为什么"},
                    "物品共享":  {"value": 0-1, "confidence": 0-1, "evidence": "原文片段", "reasoning": "为什么"},
                    "游戏":     {"value": 0-1, "confidence": 0-1, "evidence": "原文片段", "reasoning": "为什么"},
                    "学业拼搏":  {"value": 0-1, "confidence": 0-1, "evidence": "原文片段", "reasoning": "为什么"}
                  },
                  "followUps": ["还需要问学生的问题"],
                  "narrative": "两三句话总结这个学生的住宿相关特点"
                }

                维度取值口径：
                - 「入睡时刻」「起床时刻」用 24 小时制的连续小时数，但跨零点的作息不取模：
                  23:30 记 23.5，凌晨 1:00 记 25.0，凌晨 3:00 记 27.0；早上 7:30 记 7.5。
                - 其余各维一律 0 到 1：0 表示该倾向极弱/极低，1 表示极强/极高。
                  例如「吸烟」0=从不吸烟，1=每天吸；「卫生」0=完全不收拾，1=非常爱干净；
                  「噪声容忍度」0=一点声音就受不了，1=完全不受影响。
                - confidence 反映你有多确定：原话直说给 0.8 以上，靠语气推断不要超过 0.5。

                """ + anchorLines() + """
                最重要的纪律：不要把所有维度都打在中段。
                这份输出会被拿去计算学生之间的生活习惯距离。如果 11 个维度全挤在 0.4~0.6，
                所有人的画像就长得一模一样，分配算法等于在瞎猜，这个模块就白做了。
                只在原话确实模糊时才给 0.5；只要原话给了明确信息，就按上面的锚点把值拉开。
                """;

        StringBuilder sb = new StringBuilder();
        sb.append("学生信息：");
        sb.append("年级=").append(nz(in.grade()));
        sb.append("，专业大类=").append(nz(in.majorCluster()));
        sb.append("\n\n");

        if (in.hasAnswers()) {
            sb.append("【学生已经填好的问卷作答（数值已是维度原生值，作息是小时，其余 0-1）】\n");
            for (Map.Entry<String, Double> e : in.answers().entrySet()) {
                Questionnaire.Item item = Questionnaire.byCode(e.getKey());
                String prompt = item == null ? e.getKey() : item.prompt();
                sb.append("- ").append(prompt).append(" → ").append(fmt(e.getValue())).append('\n');
            }
            sb.append('\n');
        }

        int budget = props.getLlm().getMaxTextChars();
        if (in.hasText()) {
            // 若素材来自聊天记录，DialogueParser 已经把他人与助手的发言剔除了。
            // 这一句必须告诉模型：否则它会把"助理说该用户很外向"当成学生自述，
            // 形成"模型自己夸自己"的幻觉回路。
            sb.append("【以下是学生本人的原话。若来自聊天记录，他人的发言与助手的发言已被剔除，");
            sb.append("所以这里的每一句都是这个学生自己说的。】\n\n");
        }
        for (ExtractionInput.TextSample t : in.texts()) {
            if (t.content() == null || t.content().isBlank()) {
                continue;
            }
            String c = t.content().length() > budget ? t.content().substring(0, budget) : t.content();
            sb.append("【学生写的原文 · 类型 ").append(nz(t.kind())).append("】\n");
            sb.append(c).append("\n\n");
        }
        sb.append("请按要求输出 JSON。");

        ObjectNode req = mapper.createObjectNode();
        req.put("model", props.getLlm().getModel());
        req.put("temperature", temperature);
        req.put("stream", false);
        ArrayNode msgs = req.putArray("messages");
        msgs.addObject().put("role", "system").put("content", system);
        msgs.addObject().put("role", "user").put("content", sb.toString());
        ObjectNode rf = req.putObject("response_format");
        rf.put("type", "json_object");
        req.put("max_tokens", 2000);

        try {
            return mapper.writeValueAsString(req);
        } catch (Exception e) {
            log.warn("构造请求体失败：{}", e.toString());
            return null;
        }
    }

    private String post(String jsonBody) {
        String url = props.getLlm().getBaseUrl();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        url = url + "/chat/completions";

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(props.getLlm().getTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.getLlm().getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, java.nio.charset.StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(
                    java.nio.charset.StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                log.warn("LLM 返回 HTTP {}：{}", resp.statusCode(), truncate(resp.body(), 400));
                return null;
            }
            return resp.body();
        } catch (Exception e) {
            log.warn("LLM 请求异常：{}", e.toString());
            return null;
        }
    }

    // ────────────────────────── 响应解析 ──────────────────────────

    private ExtractionResult toResult(JsonNode parsed, ExtractionInput in, long elapsedMs, int pt, int ct) {
        if (parsed == null || parsed.isMissingNode()) {
            return null;
        }
        double[] values = new double[StudentFeature.DIM];
        double[] conf = new double[StudentFeature.DIM];
        Arrays.fill(values, Double.NaN);
        Arrays.fill(conf, Double.NaN);
        List<Evidence> evidence = new ArrayList<>();

        JsonNode dims = parsed.path("dimensions");
        for (PsycheDimension d : PsycheDimension.values()) {
            JsonNode node = dims.path(d.label());
            if (node.isMissingNode() || node.isNull()) {
                continue;
            }
            JsonNode vn = node.path("value");
            if (vn.isMissingNode() || vn.isNull()) {
                continue; // 模型自己承认没依据
            }
            double v = vn.asDouble(Double.NaN);
            if (Double.isNaN(v)) {
                continue;
            }
            double c = node.path("confidence").asDouble(0.6);
            String quote = node.path("evidence").asText("");
            String reasoning = node.path("reasoning").asText("");
            if (quote.isBlank()) {
                // 没有原文片段的取值，按纪律丢掉
                continue;
            }
            if (d.hourScale()) {
                v = normalizeHour(v, d);
                if (Double.isNaN(v)) {
                    continue;
                }
            } else {
                v = clamp01(v);
            }
            values[d.index()] = v;
            conf[d.index()] = clamp01(c);
            evidence.add(Evidence.llm(d.label(), truncate(quote, 160),
                    reasoning.isBlank() ? "大模型依据原文推断" : truncate(reasoning, 220), clamp01(c)));
        }

        BigFive bf = BigFive.neutral();
        JsonNode b = parsed.path("bigFive");
        if (b.isObject()) {
            bf = new BigFive(
                    clamp01(b.path("openness").asDouble(0.5)),
                    clamp01(b.path("conscientiousness").asDouble(0.5)),
                    clamp01(b.path("extraversion").asDouble(0.5)),
                    clamp01(b.path("agreeableness").asDouble(0.5)),
                    clamp01(b.path("neuroticism").asDouble(0.5)));
        }

        List<String> followUps = new ArrayList<>();
        JsonNode fu = parsed.path("followUps");
        if (fu.isArray()) {
            for (JsonNode n : fu) {
                String s = n.asText("");
                if (!s.isBlank()) {
                    followUps.add(truncate(s, 120));
                }
            }
        }

        String narrative = truncate(parsed.path("narrative").asText(""), 600);
        if (narrative.isBlank()) {
            narrative = "大模型未给出总结。";
        }

        ExtractionResult r = new ExtractionResult(NAME, true, values, conf, bf, evidence, followUps,
                "大模型提取：" + narrative, in.textChars(), in.answers().size(), elapsedMs, pt, ct);
        if (r.valueCount() == 0) {
            log.warn("LLM 这次一个维度都没给出依据，视为失败并降级");
            return null;
        }
        return r;
    }

    private static double normalizeHour(double v, PsycheDimension d) {
        double lo = d == PsycheDimension.SLEEP_START ? 20.0 : 4.0;
        double hi = d == PsycheDimension.SLEEP_START ? 29.0 : 14.0;
        if (v >= lo && v <= hi) {
            return v;
        }
        // 模型有时会给 0..23 而不是连续小时；跨零点的作息补 24
        if (d == PsycheDimension.SLEEP_START && v >= 0 && v <= 5) {
            return v + 24;
        }
        return Double.NaN;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return Double.NaN;
        }
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    static String stripFences(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) {
                t = t.substring(nl + 1);
            }
            int end = t.lastIndexOf("```");
            if (end >= 0) {
                t = t.substring(0, end);
            }
        }
        return t.trim();
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "未知" : s;
    }

    private static String fmt(double v) {
        if (Double.isNaN(v)) {
            return "-";
        }
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /** 给管理端做连通性自检用：直接问一句话，返回原始响应。 */
    public Map<String, Object> probe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", available());
        out.put("baseUrl", props.getLlm().getBaseUrl());
        out.put("model", props.getLlm().getModel());
        if (!available()) {
            out.put("ok", false);
            out.put("message", "未启用或未配置 api-key（见 config/local.properties）");
            return out;
        }
        ObjectNode req = mapper.createObjectNode();
        req.put("model", props.getLlm().getModel());
        req.put("max_tokens", 16);
        req.put("temperature", 0.0);
        ArrayNode msgs = req.putArray("messages");
        msgs.addObject().put("role", "user").put("content", "只回复两个字：在线");
        long t0 = System.nanoTime();
        try {
            String raw = post(mapper.writeValueAsString(req));
            JsonNode root = raw == null ? null : mapper.readTree(raw);
            String content = root == null ? null
                    : root.path("choices").path(0).path("message").path("content").asText(null);
            out.put("ok", content != null);
            out.put("reply", content);
            out.put("elapsedMs", (System.nanoTime() - t0) / 1_000_000L);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", e.toString());
        }
        return out;
    }
}
