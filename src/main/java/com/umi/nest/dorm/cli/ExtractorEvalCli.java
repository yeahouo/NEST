package com.umi.nest.dorm.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.umi.nest.dorm.data.GenParams;
import com.umi.nest.dorm.data.GroundTruth;
import com.umi.nest.dorm.data.SyntheticDataGenerator;
import com.umi.nest.dorm.data.SyntheticDataset;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.model.StudentFeature;
import com.umi.nest.dorm.psyche.EnsembleExtractor;
import com.umi.nest.dorm.psyche.ExtractionInput;
import com.umi.nest.dorm.psyche.ExtractorEvaluator;
import com.umi.nest.dorm.psyche.FeatureExtractor;
import com.umi.nest.dorm.psyche.LlmExtractor;
import com.umi.nest.dorm.psyche.PsycheProperties;
import com.umi.nest.dorm.psyche.RuleBasedExtractor;
import com.umi.nest.dorm.psyche.SelfReportGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 心理特征提取器的量化评测入口。
 *
 * <p>回答的是答辩上一定会被问的那句话：<b>你怎么知道大模型从这个人的话里
 * 读出来的作息、卫生、性格是对的？</b>
 *
 * <p>做法不是举几个例子说"看着挺合理"，而是<b>反过来造题</b>：
 * {@link SyntheticDataGenerator} 本来就给每个虚拟学生抽了一套真实特征 z_i
 * （并且知道这套真值），{@link SelfReportGenerator} 再把 z_i 翻译成人话，
 * 于是"喂进去的文本"和"该提取出来的数值"就配成了一对。
 * 跑完提取器，逐维算误差、相关性和覆盖率，误差是算出来的，不是夸出来的。
 *
 * <p>这个评测能站得住，靠的是三条：
 * <ol>
 *   <li>文本里说的是人话（"我一般两三点才睡"），不是字段（"sleepStart=25.0"），
 *       所以测的是语义理解，不是抄写；</li>
 *   <li>聊天记录里故意埋了和真值相反的助手总结句与室友评价句，
 *       不剔除它们就会被拽偏——这样"只分析本人发言"才有可测的证据；</li>
 *   <li>一部分维度故意不说，用来测覆盖率，逼提取器该说不知道时就说不知道。</li>
 * </ol>
 *
 * <pre>
 *   # 纯规则，快，不需要 key
 *   java -cp target/classes com.umi.nest.dorm.cli.ExtractorEvalCli --students 60 --llm false
 *
 *   # 规则 + 大模型 + 融合，跑前 8 个学生（大模型调用有成本，样本数单独控制）
 *   java -cp target/classes com.umi.nest.dorm.cli.ExtractorEvalCli --students 60 --llmStudents 8
 * </pre>
 */
public final class ExtractorEvalCli {

    public static void main(String[] args) {
        Map<String, String> a = parse(args);

        int students = intOf(a, "students", 60);
        long seed = longOf(a, "seed", 20260927L);
        double missing = doubleOf(a, "missing", 0.20);
        double privacy = doubleOf(a, "privacy", 0.25);
        int llmStudents = intOf(a, "llmStudents", 6);
        boolean wantLlm = !"false".equalsIgnoreCase(a.getOrDefault("llm", "true"));
        boolean wantAblation = !"false".equalsIgnoreCase(a.getOrDefault("ablation", "true"));

        System.out.println("NEST 心理特征提取评测");
        System.out.println("  学生 " + students + " 人（种子 " + seed + "）"
                + "，缺说率 " + missing + "，身份信息注入率 " + privacy);
        System.out.println("  大模型样本 " + llmStudents + " 人"
                + (wantLlm ? "" : "（已关闭）"));
        System.out.println();

        // ---------- 1. 造数据：真值 → 人话 ----------
        GenParams gp = new GenParams(students, 4, 0.20, 0.0, 0.5, 3, 0.03, seed, true, 3);
        SyntheticDataset ds = SyntheticDataGenerator.generate(gp);
        GroundTruth truth = ds.truth();
        SelfReportGenerator.Config scfg = SelfReportGenerator.Config.defaults(seed).withMissingRate(missing);
        Random rnd = new Random(seed * 31 + 7);

        List<ExtractorEvaluator.Case> cases = new ArrayList<>();
        String previewIntro = null;
        String previewChat = null;
        for (Student s : ds.problem().students()) {
            StudentFeature z = truth.trueFeatureOf(s.id());
            if (z == null) {
                continue;
            }
            SelfReportGenerator.Sample smp = SelfReportGenerator.sample(s, z, scfg, rnd);
            String chat = smp.hasChat() ? smp.chatLog() : "";
            String intro = smp.selfIntro();
            String studentNo = "EIE" + (24000 + s.id());
            List<ExtractionInput.TextSample> texts = new ArrayList<>();
            if (!chat.isBlank()) {
                texts.add(new ExtractionInput.TextSample("CHAT_LOG", chat));
            }
            if (!intro.isBlank()) {
                texts.add(new ExtractionInput.TextSample("SELF_INTRO", intro));
            }
            ExtractionInput in = new ExtractionInput(s.id(), studentNo, smp.name(), s.grade(),
                    s.majorCluster(), texts, Map.of());
            cases.add(new ExtractorEvaluator.Case(s.id(), in, z));
            if (previewIntro == null) {
                previewIntro = intro;
                previewChat = chat;
            }
        }

        System.out.println("== 生成素材样例（学生 " + cases.get(0).studentId() + "，"
                + cases.get(0).input().name() + "）==");
        System.out.println("--- 自我介绍 ---");
        System.out.println(previewIntro);
        System.out.println("--- 聊天记录 ---");
        System.out.println(previewChat);
        System.out.println();

        // ---------- 2. 组装提取器 ----------
        RuleBasedExtractor rule = new RuleBasedExtractor();
        LlmExtractor llm = null;
        EnsembleExtractor ensemble = null;
        PsycheProperties props = null;
        if (wantLlm) {
            props = buildProperties(a);
            if (props.llmReady()) {
                llm = new LlmExtractor(props, new ObjectMapper());
                ensemble = new EnsembleExtractor(llm);
                System.out.println("大模型：已启用（model=" + props.getLlm().getModel()
                        + "，samples=" + props.getLlm().getSamples()
                        + "，第二次起温度 " + props.getLlm().getSampleTemperature() + "）");
            } else {
                System.out.println("大模型：未启用——config/local.properties 里没有 nest.psyche.llm.api-key，"
                        + "本次只评规则提取器");
            }
        }
        System.out.println();

        // ---------- 3. 评测 ----------
        int sub = Math.max(0, Math.min(llmStudents, cases.size()));
        List<ExtractorEvaluator.Case> subCases = cases.subList(0, sub);
        List<ExtractorEvaluator.Report> extraction = new ArrayList<>();
        extraction.add(ExtractorEvaluator.evaluate(RuleBasedExtractor.NAME, rule, subCases, true));
        if (llm != null) {
            extraction.add(ExtractorEvaluator.evaluate(LlmExtractor.NAME, llm, subCases, true));
        }
        if (ensemble != null) {
            extraction.add(ExtractorEvaluator.evaluate(EnsembleExtractor.NAME, ensemble, subCases, true));
        }

        System.out.println("== 表 1：提取器对比（" + sub + " 人，含聊天记录，已剔除他人与助手发言 + 已脱敏）==");
        System.out.println(ExtractorEvaluator.compareTable(extraction));
        System.out.println();

        ExtractorEvaluator.Report full = ExtractorEvaluator.evaluate(RuleBasedExtractor.NAME, rule, cases, true);
        System.out.println("== 表 2：全量规则提取（" + cases.size() + " 人）——样本大了误差才稳定 ==");
        System.out.println(ExtractorEvaluator.compareTable(List.of(full)));
        System.out.println();

        if (wantAblation) {
            List<ExtractorEvaluator.Report> abl = new ArrayList<>();
            abl.add(ExtractorEvaluator.evaluate(RuleBasedExtractor.NAME, rule, subCases, false));
            abl.add(ExtractorEvaluator.evaluate(RuleBasedExtractor.NAME, rule, subCases, true));
            if (llm != null) {
                abl.add(ExtractorEvaluator.evaluate(LlmExtractor.NAME, llm, subCases, false));
                abl.add(ExtractorEvaluator.evaluate(LlmExtractor.NAME, llm, subCases, true));
            }
            System.out.println("== 表 3：消融——不剔除他人与助手发言，会差多少 ==");
            System.out.println(ExtractorEvaluator.compareTable(abl));
            System.out.println();
        }

        ExtractorEvaluator.Report best = extraction.get(extraction.size() - 1);
        System.out.println("== 逐维明细（" + best.extractor() + "，" + best.cases() + " 人）==");
        System.out.println(ExtractorEvaluator.markdown(best));

        if (full.cases() != best.cases()) {
            System.out.println();
            System.out.println("== 逐维明细（" + full.extractor() + "，" + full.cases()
                    + " 人）——这一份样本大，逐维结论以它为准 ==");
            System.out.println(ExtractorEvaluator.markdown(full));
        }

        System.out.println();
        System.out.println("[完成] 上面的数就是" + best.extractor() + " 提取能力的证据；"
                + "收敛比越接近 1 说明它越敢把人和人分开，而不是全打中间分。");
        System.exit(0);
    }

    // ==================================================================
    // 配置装配
    // ==================================================================

    /**
     * 手工装一份 {@link PsycheProperties}——命令行不启动 Spring，读不到
     * {@code @ConfigurationProperties}，所以这里自己把 {@code config/local.properties}
     * 解析出来填进去。字段名与 {@code nest.psyche.*} 对齐。
     */
    private static PsycheProperties buildProperties(Map<String, String> a) {
        Map<String, String> local = loadLocal();
        PsycheProperties p = new PsycheProperties();
        PsycheProperties.Llm l = p.getLlm();
        l.setEnabled(!( "false".equalsIgnoreCase(a.getOrDefault("llm", "true"))));
        l.setApiKey(firstNonBlank(a.get("apiKey"), local.get("nest.psyche.llm.api-key"), ""));
        l.setBaseUrl(firstNonBlank(a.get("baseUrl"), local.get("nest.psyche.llm.base-url"),
                "https://api.deepseek.com"));
        l.setModel(firstNonBlank(a.get("model"), local.get("nest.psyche.llm.model"), "deepseek-chat"));
        l.setSamples(intOf(a, "samples", l.getSamples()));
        l.setSampleTemperature(doubleOf(a, "sampleTemperature", l.getSampleTemperature()));
        p.getApply().setMinConfidence(doubleOf(a, "minConfidence", p.getApply().getMinConfidence()));
        return p;
    }

    private static String firstNonBlank(String... xs) {
        for (String x : xs) {
            if (x != null && !x.isBlank()) {
                return x;
            }
        }
        return "";
    }

    private static Map<String, String> loadLocal() {
        Map<String, String> m = new LinkedHashMap<>();
        Path p = Path.of("config", "local.properties");
        if (!Files.exists(p)) {
            return m;
        }
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                int i = s.indexOf('=');
                if (i > 0) {
                    m.put(s.substring(0, i).trim(), s.substring(i + 1).trim());
                }
            }
        } catch (IOException e) {
            System.out.println("[警告] 读不了 " + p + "：" + e.getMessage());
        }
        return m;
    }

    // ==================================================================
    // 参数
    // ==================================================================

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String k = args[i];
            if (!k.startsWith("--")) {
                continue;
            }
            String key = k.substring(2);
            String val = (i + 1 < args.length && !args[i + 1].startsWith("--")) ? args[++i] : "true";
            m.put(key, val);
        }
        return m;
    }

    private static int intOf(Map<String, String> a, String k, int d) {
        return a.containsKey(k) ? Integer.parseInt(a.get(k)) : d;
    }

    private static long longOf(Map<String, String> a, String k, long d) {
        return a.containsKey(k) ? Long.parseLong(a.get(k)) : d;
    }

    private static double doubleOf(Map<String, String> a, String k, double d) {
        return a.containsKey(k) ? Double.parseDouble(a.get(k)) : d;
    }

    static String fmt(double v) {
        return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%.3f", v);
    }

    private ExtractorEvalCli() {
    }
}
