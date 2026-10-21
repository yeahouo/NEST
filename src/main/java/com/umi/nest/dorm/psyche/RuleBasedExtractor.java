package com.umi.nest.dorm.psyche;

import com.umi.nest.dorm.model.StudentFeature;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯本地的规则特征提取器：不联网、不调大模型、同样输入永远同样输出。
 *
 * <p>它在整个系统里的位置是"永远能用的那一路"：
 * 学校不允许学生数据出校时，这就是唯一的提取器；
 * 允许调大模型时，它是降级路径和交叉验证的对照组。
 * 做消融实验时也需要它——大模型每次的输出都有抖动，无法控制变量。
 *
 * <p>提取流程：
 * <ol>
 *   <li><b>量表作答优先</b>。学生自己选的答案比任何文本推断都硬，
 *       同一维有作答就直接采信，文本只作为补充证据留档（不参与数值）。</li>
 *   <li><b>明说的钟点</b>。用正则捞「凌晨两点睡」「7点半起」这类表述，
 *       直接得到小时数——这是全流程里唯一能拿到"真数"的地方。</li>
 *   <li><b>线索词</b>走 {@link PsycheLexicon}，长词优先、占用区间、否定词翻转。</li>
 *   <li><b>没有依据就留 NaN</b>。绝不写 0——0 是"他就是极内向"，NaN 才是"不知道"。
 *       写错了会让下游把他和另一群"极内向"的人塞在一间房。</li>
 * </ol>
 */
public final class RuleBasedExtractor implements FeatureExtractor {

    public static final String NAME = "RULE";

    /** 明说钟点：置信度高于所有形容词。 */
    private static final double CONF_EXPLICIT_HOUR = 0.75;
    /** 只说"熬夜/早睡"这类性质词。 */
    private static final double CONF_SLEEP_WORD = 0.45;
    /** 「玩到三点」这类间接表述。 */
    private static final double CONF_UNTIL = 0.60;
    /** 量表作答。 */
    private static final double CONF_ANSWER = 0.90;

    /** 否定翻转后的强度折扣：0.6 意味着「不太喜欢热闹」比「讨厌热闹」程度轻。 */
    private static final double NEGATION_DAMPING = 0.6;

    /**
     * 单条线索的收缩系数。
     *
     * <p>线索收敛用双曲正切压缩，但<b>不能一条线索就定论</b>。理由是在真数据上测出来的：
     * 「卫生一般吧，想起来才收拾一下」这句话里只有一个「收拾」，背后的人其实是中等的；
     * 若单条线索直接推到 0.88，这一维的误差比"什么都不说"还大。
     * 除以 1.5 之后，一条线索到 0.22/0.78（明确但留有余地），
     * 两条独立印证到 0.08/0.92，三条才接近满格——这也是"孤证不立"在数值上的落法。
     */
    private static final double SINGLE_CUE_SHRINK = 1.5;

    /**
     * 否定词的作用窗口（往前看几个字）。
     *
     * <p>起初写的是 3 个字，但「很少参加社团活动」里的「很少」和「社团」隔了两个字，
     * 窗口太小就漏掉了否定，一句话被读成了它的反面。中文口语里否定词和中心词
     * 中间常夹着动词（"不怎么爱收拾"），所以按 5 个字放宽。
     */
    private static final int NEGATION_WINDOW = 5;

    /** 作息钟点的物理范围，超出即视为解析错误而丢弃。 */
    private static final double SLEEP_START_MIN = 20.0;
    private static final double SLEEP_START_MAX = 29.0;
    private static final double SLEEP_END_MIN = 4.0;
    private static final double SLEEP_END_MAX = 14.0;

    // "睡觉" 必须排在 "睡" 前面，否则 "睡觉" 只会匹配到 "睡"，后面那个 "觉" 无人认领。
    // 小时与分钟都允许中文数字："凌晨两点睡"、"七点半起"、"十二点睡" —— 中文写数字比阿拉伯数字更常见，
    // 只认 \d 会让大部分真实文本一条钟点都抽不到。
    private static final String NUM = "([0-9]{1,2}|[一二三四五六七八九十两]{1,3})";

    private static final Pattern SLEEP_AT = Pattern.compile(
            "(凌晨|半夜|夜里|晚上|傍晚|中午|下午|早上|早晨|上午)?\\s*" + NUM + "\\s*(?:点|:|：)\\s*(半|" + NUM + ")?\\s*分?\\s*"
                    + "(?:左右|多)?\\s*(?:才|就)?\\s*(?:去)?\\s*(?:睡觉|睡了|睡|上床|休息|入睡)");

    private static final Pattern WAKE_AT = Pattern.compile(
            "(凌晨|半夜|夜里|晚上|傍晚|中午|下午|早上|早晨|上午)?\\s*" + NUM + "\\s*(?:点|:|：)\\s*(半|" + NUM + ")?\\s*分?\\s*"
                    + "(?:左右|多)?\\s*(?:才|就)?\\s*(?:起床|起来|醒了|醒|起)");

    /**
     * 「玩到三点」「学到十二点」这类表述。
     * <p>学生描述作息时，直接用「几点睡」的反而少，更多是「打游戏到三点」。
     * 这条只给 {@code SLEEP_START} 兜底，置信度低于明说动词的那条路。
     */
    private static final Pattern UNTIL_AT = Pattern.compile(
            "(?:玩|打游戏|刷|写|忙|学|看书|聊天|工作|复习|赶|熬|聊)到\\s*(凌晨|半夜|夜里|晚上)?\\s*"
                    + NUM + "\\s*[点:：]\\s*(半|" + NUM + ")?");

    /**
     * 「睡到八点半」「睡到十点」这类起床表述。
     *
     * <p>单独一条 pattern 是因为学生并不总说"起床"：问卷里更常见的写法是
     * "没课能睡到十点"，句子到"十点"就结束了，没有动词给 WAKE_AT 去咬。
     * 这种情况在真实数据上占相当比例——少一条召回，覆盖率就掉一截。
     */
    private static final Pattern WAKE_UP_TO = Pattern.compile(
            "睡到\\s*(凌晨|半夜|夜里|早上|早晨|上午|中午|下午)?\\s*"
                    + NUM + "\\s*(?:点|:|：)\\s*(半|" + NUM + ")?");

    /** 「睡到中午」这种没给钟点的写法。 */
    private static final Pattern WAKE_UP_TO_WORD = Pattern.compile("睡到\\s*(中午|下午)");

    /** 出现这些词说明句子主语多半不是学生本人。 */
    private static final List<String> OTHER_SUBJECTS = List.of("室友", "同学", "别人", "他人", "隔壁", "舍友");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public ExtractionResult extract(ExtractionInput input) {
        long t0 = System.nanoTime();
        int dim = StudentFeature.DIM;

        double[] value = new double[dim];
        double[] confidence = new double[dim];
        double[] net = new double[dim];      // 线索词的有向累加
        int[] hits = new int[dim];           // 命中条数，用来算置信度
        Arrays.fill(value, Double.NaN);
        Arrays.fill(confidence, Double.NaN);

        List<Evidence> evidence = new ArrayList<>();

        if (input == null || input.isEmpty()) {
            return ExtractionResult.empty(NAME);
        }

        // ── 1. 量表作答：最硬的那一档 ──
        Map<PsycheDimension, Double> answered = Questionnaire.score(input.answers());
        for (Map.Entry<PsycheDimension, Double> e : answered.entrySet()) {
            PsycheDimension d = e.getKey();
            value[d.index()] = e.getValue();
            confidence[d.index()] = CONF_ANSWER;
            evidence.add(Evidence.questionnaire(d.label(),
                    "量表作答：" + d.label() + " = " + fmt(e.getValue(), d),
                    "学生自陈，直接采信；同维度多题取均值。",
                    1.0));
        }

        // ── 2. 文本：钟点与线索词 ──
        String text = input.combinedText();
        int textChars = text.length();
        double[] hours = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        int skippedOthers = 0;
        if (!text.isBlank()) {
            hours = parseSleepHours(text);
            if (!Double.isNaN(hours[0]) && !answered.containsKey(PsycheDimension.SLEEP_START)) {
                value[PsycheDimension.SLEEP_START.index()] = hours[0];
                confidence[PsycheDimension.SLEEP_START.index()] = hours[2];
                evidence.add(Evidence.rule(PsycheDimension.SLEEP_START.label(),
                        "文本中的入睡时刻表述", "从明说的钟点解析得到 " + hhmm(hours[0]), hours[2]));
            }
            if (!Double.isNaN(hours[1]) && !answered.containsKey(PsycheDimension.SLEEP_END)) {
                value[PsycheDimension.SLEEP_END.index()] = hours[1];
                confidence[PsycheDimension.SLEEP_END.index()] = hours[3];
                evidence.add(Evidence.rule(PsycheDimension.SLEEP_END.label(),
                        "文本中的起床时刻表述", "从明说的钟点解析得到 " + hhmm(hours[1]), hours[3]));
            }

            boolean[] claimed = new boolean[text.length()];
            for (PsycheLexicon.Rule rule : PsycheLexicon.rules()) {
                if (answered.containsKey(rule.dimension())) {
                    continue; // 已有量表作答，文本不再参与数值，避免噪声稀释
                }
                int from = 0;
                while (true) {
                    int at = text.indexOf(rule.phrase(), from);
                    if (at < 0) {
                        break;
                    }
                    int end = at + rule.phrase().length();
                    from = at + 1;
                    if (overlaps(claimed, at, end)) {
                        continue; // 长词已经占了这个区间
                    }
                    mark(claimed, at, end);
                    if (aboutOthers(text, at)) {
                        // 「室友打游戏到三点」说的是室友，不是他自己。
                        // 把室友的习惯算到学生头上会造成实打实的伤害：
                        // 一个不抽烟的人会因为"室友抽烟"被分到吸烟房。
                        skippedOthers++;
                        continue;
                    }
                    boolean neg = negated(text, at);
                    double effective = neg ? -rule.delta() * NEGATION_DAMPING : rule.delta();
                    int di = rule.dimension().index();
                    net[di] += effective;
                    hits[di]++;
                    evidence.add(Evidence.rule(rule.dimension().label(), snippet(text, at, end),
                            (neg ? "否定表述，方向取反：" : "") + rule.reason(),
                            Math.abs(effective)));
                }
            }
        }

        // ── 3. 线索词分数 → 维度值 ──
        for (PsycheDimension d : PsycheDimension.values()) {
            int i = d.index();
            if (hits[i] == 0 || !Double.isNaN(value[i])) {
                continue;
            }
            if (d.hourScale()) {
                // 只说了"熬夜"没说钟点：以人群基线为锚，按 net 最多推 1.5 小时
                double base = d == PsycheDimension.SLEEP_START
                        ? StudentFeature.SLEEP_START_DEFAULT
                        : StudentFeature.SLEEP_END_DEFAULT;
                double shift = 1.5 * Math.tanh(net[i]);
                double v = d == PsycheDimension.SLEEP_START
                        ? clamp(base + shift, SLEEP_START_MIN, SLEEP_START_MAX)
                        : clamp(base + shift, SLEEP_END_MIN, SLEEP_END_MAX);
                value[i] = v;
                confidence[i] = CONF_SLEEP_WORD;
            } else {
                // 双曲正切压缩后再按 SINGLE_CUE_SHRINK 收缩：
                // 一条线索推到 0.22 / 0.78（明确但留有余地），
                // 两条独立印证推到 0.08 / 0.92。
                // 不用线性是因为线性会让"明确说不抽烟"只到 0.3——那不是"低"，
                // 那是"可能抽也可能不抽"，会把它当噪声插补掉。
                value[i] = 0.5 + 0.5 * Math.tanh(net[i] / SINGLE_CUE_SHRINK);
                confidence[i] = Math.min(0.90, 0.35 + 0.20 * hits[i]);
            }
        }

        // ── 4. 大五人格：由已得到的维度反推，推不出的留中立 ──
        BigFive bigFive = deriveBigFive(value);

        // ── 5. 还缺哪些维度 → 建议追问 ──
        List<String> followUps = new ArrayList<>();
        for (PsycheDimension d : PsycheDimension.values()) {
            if (Double.isNaN(value[d.index()])) {
                List<Questionnaire.Item> items = Questionnaire.itemsOf(d);
                if (!items.isEmpty()) {
                    followUps.add(items.get(0).prompt());
                }
            }
        }

        ExtractionResult partial = new ExtractionResult(NAME, false, value, confidence, bigFive,
                evidence, followUps, "", textChars, input.answers().size(), 0L, 0, 0);
        String narrative = narrate(partial, input, hours, skippedOthers);

        return new ExtractionResult(NAME, false, value, confidence, bigFive, evidence, followUps,
                narrative, textChars, input.answers().size(),
                (System.nanoTime() - t0) / 1_000_000L, 0, 0);
    }

    // ────────────────────────── 文本解析 ──────────────────────────

    /**
     * 从文本里解析作息钟点，返回 {@code [入睡, 起床, 入睡置信度, 起床置信度]}，解析不到为 NaN。
     * <p>同一维多次出现取均值——学生可能在不同段落说了两次，说两次说明他确实在意。
     */
    public static double[] parseSleepHours(String text) {
        double s = avgHours(SLEEP_AT, text, true);
        double sConf = CONF_EXPLICIT_HOUR;
        if (Double.isNaN(s)) {
            double u = avgUntilHours(text);
            if (!Double.isNaN(u)) {
                s = u;
                sConf = CONF_UNTIL;
            }
        }
        double w = avgHours(WAKE_AT, text, false);
        double wConf = CONF_EXPLICIT_HOUR;
        if (Double.isNaN(w)) {
            w = avgWakeUpTo(text);
            if (!Double.isNaN(w)) {
                wConf = CONF_UNTIL;
            }
        }

        if (!Double.isNaN(s) && (s < SLEEP_START_MIN || s > SLEEP_START_MAX)) {
            s = Double.NaN;
        }
        if (!Double.isNaN(w) && (w < SLEEP_END_MIN || w > SLEEP_END_MAX)) {
            w = Double.NaN;
        }
        return new double[]{s, w, sConf, wConf};
    }

    /** 「睡到X点」/「睡到中午」这两条兜底路的解析。 */
    private static double avgWakeUpTo(String text) {
        double sum = 0;
        int n = 0;
        Matcher m = WAKE_UP_TO.matcher(text);
        while (m.find()) {
            if (aboutOthers(text, m.start())) {
                continue;
            }
            double h = toHour(m.group(2), m.group(3), m.group(1), false);
            if (!Double.isNaN(h)) {
                sum += h;
                n++;
            }
        }
        Matcher w = WAKE_UP_TO_WORD.matcher(text);
        while (w.find()) {
            if (aboutOthers(text, w.start())) {
                continue;
            }
            sum += "中午".equals(w.group(1)) ? 12.0 : 14.0;
            n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    private static double avgUntilHours(String text) {
        Matcher m = UNTIL_AT.matcher(text);
        double sum = 0;
        int n = 0;
        while (m.find()) {
            if (aboutOthers(text, m.start())) {
                continue;
            }
            double h = toHour(m.group(2), m.group(3), m.group(1), true);
            if (!Double.isNaN(h)) {
                sum += h;
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    private static double avgHours(Pattern p, String text, boolean sleep) {
        Matcher m = p.matcher(text);
        double sum = 0;
        int n = 0;
        while (m.find()) {
            double h = toHour(m.group(2), m.group(3), m.group(1), sleep);
            if (!Double.isNaN(h)) {
                sum += h;
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /**
     * 把「几点几分 + 时段前缀」换算成连续小时。
     *
     * <p>这里有个中文特有的坑：「10 点睡」几乎一定指 22 点，不是上午 10 点；
     * 而「凌晨 2 点睡」是次日 2 点，记作 26 点（作息维度是连续量，跨零点不取模——
     * 26 和 2 在数学上差 24 小时，在"睡多晚"这件事上差得远）。
     */
    private static double toHour(String hGroup, String minGroup, String prefix, boolean sleep) {
        int h = parseNum(hGroup);
        if (h < 0) {
            return Double.NaN;
        }
        double minute = 0;
        if ("半".equals(minGroup)) {
            minute = 0.5;
        } else if (minGroup != null && !minGroup.isBlank()) {
            int m = parseNum(minGroup);
            if (m > 0 && m < 60) {
                minute = m / 60.0;
            }
        }
        if (h > 24) {
            return Double.NaN;
        }
        String p = prefix == null ? "" : prefix;

        double hour;
        if (sleep) {
            if (h == 12) {
                hour = 24;                       // 12 点睡 = 午夜
            } else if (h >= 1 && h <= 5) {
                hour = h + 24;                   // 凌晨 2 点睡 = 26 点
            } else if (h >= 6 && h <= 11) {
                hour = h + 12;                   // 10 点睡 = 22 点（不是上午 10 点）
            } else {
                hour = h;                        // 13..23 照原样
            }
        } else {
            if (h >= 1 && h <= 5) {
                hour = h + 24;                   // 凌晨 2 点（还醒着）
            } else {
                hour = h;
            }
            if (("早上".equals(p) || "早晨".equals(p) || "上午".equals(p)) && h >= 1 && h <= 5) {
                hour = h;                        // 「早上 5 点起」不是跨零点
            }
        }
        return hour + minute;
    }

    /**
     * 解析阿拉伯数字或中文数字。解析不了返回 -1。
     * <p>支持到 99：「十」=10、「十二」=12、「二十」=20、「两」=2。
     * 小时场景用不到「百」以上，超出的写法本来就是笔误。
     */
    static int parseNum(String s) {
        if (s == null || s.isBlank()) {
            return -1;
        }
        String t = s.trim();
        boolean allDigit = true;
        for (int i = 0; i < t.length(); i++) {
            if (!Character.isDigit(t.charAt(i))) {
                allDigit = false;
                break;
            }
        }
        if (allDigit) {
            try {
                return Integer.parseInt(t);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        int ten = t.indexOf('十');
        if (ten >= 0) {
            String left = t.substring(0, ten);
            String right = t.substring(ten + 1);
            int tens = left.isEmpty() ? 1 : cnDigit(left);
            int ones = right.isEmpty() ? 0 : cnDigit(right);
            if (tens < 0 || ones < 0) {
                return -1;
            }
            return tens * 10 + ones;
        }
        return cnDigit(t);
    }

    private static int cnDigit(String s) {
        if (s == null || s.length() != 1) {
            return -1;
        }
        return switch (s.charAt(0)) {
            case '零', '〇' -> 0;
            case '一' -> 1;
            case '二', '两' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> -1;
        };
    }

    private static boolean overlaps(boolean[] claimed, int from, int to) {
        for (int i = from; i < to; i++) {
            if (claimed[i]) {
                return true;
            }
        }
        return false;
    }

    private static void mark(boolean[] claimed, int from, int to) {
        for (int i = from; i < to; i++) {
            claimed[i] = true;
        }
    }

    /**
     * 看命中位置前面 {@link #NEGATION_WINDOW} 个字里有没有否定词。
     *
     * <p>用 {@code contains} 而不是 {@code endsWith}：中文里否定词和中心词中间常常夹着动词，
     * 「很少参加社团活动」的中心词是「社团」，而「很少」和它隔了「参加」两个字。
     * 只用 endsWith 就会把这句话读成"社交活跃"，方向整个反掉。
     * 放宽到这个程度偶尔会误伤（"不讨厌热闹"里的"讨厌"不是否定"热闹"），
     * 但那类双重否定的说法在问卷和聊天里很少见，而"否定词＋动词＋名词"是最常见的句式。
     */
    private static boolean negated(String text, int start) {
        int from = Math.max(0, start - NEGATION_WINDOW);
        String before = text.substring(from, start);
        for (String n : PsycheLexicon.NEGATIONS) {
            if (before.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 命中位置前面六个字里出现"室友/同学/别人"就认为这句话的主语不是学生本人。
     * <p>这是个粗糙但必要的保护：中文口语里省略主语极常见，
     * 「室友抽烟，我也没办法」把"抽烟"算到学生头上，会直接把他分到吸烟房。
     * 宁可漏掉一条真线索（还有大模型那一路兜底），也不能把无关的人的特征据为己有。
     */
    private static boolean aboutOthers(String text, int start) {
        int from = Math.max(0, start - 6);
        String before = text.substring(from, start);
        for (String s : OTHER_SUBJECTS) {
            if (before.contains(s)) {
                return true;
            }
        }
        return false;
    }

    private static String snippet(String text, int from, int to) {
        int a = Math.max(0, from - 12);
        int b = Math.min(text.length(), to + 12);
        return ("…" + text.substring(a, b) + "…").replaceAll("\\s+", " ").trim();
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ────────────────────────── 派生与叙述 ──────────────────────────

    /**
     * 由 11 维软特征反推大五人格。
     * <p>这与 {@link BigFive#toStudentFeaturePartial()} 是相反方向：
     * 那一个是"只有人格量表 → 推行为特征"，这一个才是主要路径。
     * 大五在这里不是最终产物，只是给人和给 LLM 看的一个中间语言。
     */
    public static BigFive deriveBigFive(double[] f) {
        double e = f[PsycheDimension.EXTROVERSION.index()];
        double a = f[PsycheDimension.SHARE_ITEMS.index()];
        double c1 = f[PsycheDimension.CLEANLINESS.index()];
        double c2 = f[PsycheDimension.AMBITION.index()];
        double noiseTol = f[PsycheDimension.NOISE_TOLERANCE.index()];

        double conscientiousness = mean(c1, c2);
        double extraversion = Double.isNaN(e) ? 0.5 : e;
        double agreeableness = Double.isNaN(a) ? 0.5 : a;
        double neuroticism = Double.isNaN(noiseTol) ? 0.5 : (1.0 - noiseTol);
        // 开放性与 11 维里任何一维都没有可靠对应，硬凑就是编，直接给中立
        return new BigFive(0.5, conscientiousness, extraversion, agreeableness, neuroticism);
    }

    private static double mean(double a, double b) {
        if (Double.isNaN(a)) {
            return Double.isNaN(b) ? 0.5 : b;
        }
        if (Double.isNaN(b)) {
            return a;
        }
        return (a + b) / 2.0;
    }

    private static String narrate(ExtractionResult r, ExtractionInput in, double[] hours, int skippedOthers) {
        StringBuilder sb = new StringBuilder();
        sb.append("本地规则提取（未调用大模型）：");
        sb.append("素材为 ").append(in.texts().size()).append(" 段文本（")
                .append(r.textChars()).append(" 字）与 ").append(r.answeredItems()).append(" 道问卷题；");
        sb.append("共提取到 ").append(r.valueCount()).append("/").append(StudentFeature.DIM)
                .append(" 维特征（覆盖率 ").append(pct(r.coverage())).append("）。");

        if (!Double.isNaN(hours[0]) || !Double.isNaN(hours[1])) {
            sb.append(" 作息是从明说的钟点解析出来的（");
            sb.append(Double.isNaN(hours[0]) ? "入睡未知" : "入睡 " + hhmm(hours[0]));
            sb.append(" / ");
            sb.append(Double.isNaN(hours[1]) ? "起床未知" : "起床 " + hhmm(hours[1]));
            sb.append("），这类硬数据比形容词可靠。");
        }

        if (skippedOthers > 0) {
            sb.append(" 另有 ").append(skippedOthers)
                    .append(" 处线索因为主语是室友/同学而被忽略——那描述的是别人，不是本人。");
        }

        if (!r.followUps().isEmpty()) {
            sb.append(" 还有 ").append(r.followUps().size())
                    .append(" 维没有足够依据，建议追问：").append(r.followUps().get(0));
        } else {
            sb.append(" 11 维已全部给出取值。");
        }
        sb.append(" 低于置信度阈值的维度会被丢弃，不参与分配——宁可留空，也不拿猜的值去决定谁和谁住。");
        return sb.toString();
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    private static String fmt(double v, PsycheDimension d) {
        return d.hourScale() ? hhmm(v) : String.format("%.2f", v);
    }

    /** 小时 → 24 小时制的 HH:mm，"26.5" 显示成 "02:30（次日）"。 */
    public static String hhmm(double h) {
        double x = h;
        boolean next = x >= 24;
        if (next) {
            x -= 24;
        }
        int hh = (int) Math.floor(x);
        int mm = (int) Math.round((x - hh) * 60);
        if (mm == 60) {
            mm = 0;
            hh++;
        }
        if (hh >= 24) {
            hh -= 24;
            next = true;
        }
        return String.format("%02d:%02d%s", hh, mm, next ? "（次日）" : "");
    }

    /** 调试用：把一次提取的全部取值打成一行。 */
    public static String dump(double[] features) {
        Map<String, String> m = new LinkedHashMap<>();
        for (PsycheDimension d : PsycheDimension.values()) {
            double v = features[d.index()];
            m.put(d.label(), Double.isNaN(v) ? "-" : fmt(v, d));
        }
        return m.toString();
    }
}
