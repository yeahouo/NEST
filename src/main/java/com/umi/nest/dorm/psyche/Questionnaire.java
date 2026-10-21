package com.umi.nest.dorm.psyche;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对话式问卷题库：15 道题覆盖 11 个维度，外加一道自由描述题。
 *
 * <p>两条设计决定：
 * <ol>
 *   <li><b>答案是"维度原生值"而不是 1..5 分</b>：作息题直接给小时（22 / 23.5 / 26），
 *       其余题给 0..1。选完之后不需要再经过任何映射尺度，
 *       直接就是 {@code student_feature} 里那一列该有的数。
 *       如果这里存 Likert 分，下游就得知道"3 分在这道题上等于 23:45"这种映射，
 *       换一版题目就全错位——把语义钉在题库里比钉在下游稳。</li>
 *   <li><b>同一维可以有多道题</b>（卫生、噪声容忍、社交、学业各 2 道）：
 *       取均值能压掉单题的措辞偏差。一道题的作答不足以定一个人。</li>
 * </ol>
 *
 * <p>{@link #nextItems} 是"对话式"那部分：每轮只问没覆盖到的维度，
 * 不做一次性 15 连问。学生答几题就能拿到有效画像，剩下的按需追加。
 */
public final class Questionnaire {

    private Questionnaire() {
    }

    public enum Kind {
        /** 李克特量表，5 档。 */
        SCALE,
        /** 单选，选项自带维度原生值。 */
        CHOICE,
        /** 自由文本，不产生数值，但会喂给文本提取器。 */
        TEXT
    }

    /**
     * @param label 选项文案
     * @param value 维度原生值（作息是小时，其余是 0..1）
     */
    public record Option(String label, double value) {
    }

    /**
     * @param code      稳定标识，落进 {@code dorm_psyche_answer.item_code}，改题面不改它
     * @param no        展示序号
     * @param dimension 指向的维度；自由文本题为 null
     * @param kind      题型
     * @param options   选项（TEXT 为空）
     * @param hint      给学生的一句补充说明
     */
    public record Item(String code, int no, PsycheDimension dimension, String prompt, Kind kind,
                       List<Option> options, String hint) {

        /** 没有补充说明的题目走这个重载，省得每处都写一个空字符串。 */
        public Item(String code, int no, PsycheDimension dimension, String prompt, Kind kind,
                    List<Option> options) {
            this(code, no, dimension, prompt, kind, options, "");
        }

        public boolean scored() {
            return dimension != null && kind != Kind.TEXT;
        }

        public boolean isText() {
            return kind == Kind.TEXT;
        }
    }

    private static final List<Item> ITEMS = build();

    /** 自由描述题的 code，前端识别它来渲染多行输入框。 */
    public static final String FREE_TEXT_CODE = "self_intro";

    public static List<Item> items() {
        return ITEMS;
    }

    public static List<Item> scoredItems() {
        List<Item> out = new ArrayList<>();
        for (Item i : ITEMS) {
            if (i.scored()) {
                out.add(i);
            }
        }
        return out;
    }

    public static List<Item> textItems() {
        List<Item> out = new ArrayList<>();
        for (Item i : ITEMS) {
            if (i.isText()) {
                out.add(i);
            }
        }
        return out;
    }

    public static Item byCode(String code) {
        if (code == null) {
            return null;
        }
        for (Item i : ITEMS) {
            if (i.code().equals(code)) {
                return i;
            }
        }
        return null;
    }

    public static Item byNo(int no) {
        for (Item i : ITEMS) {
            if (i.no() == no) {
                return i;
            }
        }
        return null;
    }

    public static PsycheDimension dimensionOf(String code) {
        Item i = byCode(code);
        return i == null ? null : i.dimension();
    }

    /** 某一维的首选题（nextItems 用它做追问）。 */
    public static List<Item> itemsOf(PsycheDimension d) {
        List<Item> out = new ArrayList<>();
        for (Item i : ITEMS) {
            if (i.scored() && i.dimension() == d) {
                out.add(i);
            }
        }
        return out;
    }

    /** 已经作答覆盖到的维度。 */
    public static Set<PsycheDimension> coveredBy(Set<String> answeredCodes) {
        Set<PsycheDimension> out = new HashSet<>();
        if (answeredCodes == null) {
            return out;
        }
        for (String code : answeredCodes) {
            PsycheDimension d = dimensionOf(code);
            if (d != null) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * 下一轮该问哪些题：优先没被覆盖的维度，同维度内按序号。
     * <p>这样学生答 3 题就能拿到最缺的那几维，而不是被 15 道题拦住。
     *
     * @param answeredCodes 已作答的 item_code 集合
     * @param limit         最多返回几道
     */
    public static List<Item> nextItems(Set<String> answeredCodes, int limit) {
        Set<String> answered = answeredCodes == null ? Set.of() : answeredCodes;
        Set<PsycheDimension> covered = coveredBy(answered);
        List<Item> uncovered = new ArrayList<>();
        List<Item> rest = new ArrayList<>();
        for (Item i : scoredItems()) {
            if (answered.contains(i.code())) {
                continue;
            }
            if (covered.contains(i.dimension())) {
                rest.add(i);
            } else {
                uncovered.add(i);
            }
        }
        List<Item> out = new ArrayList<>(uncovered);
        out.addAll(rest);
        return out.size() <= limit ? out : new ArrayList<>(out.subList(0, limit));
    }

    /**
     * 把作答聚合到维度：同一维多题取均值。
     *
     * @param answers item_code → 维度原生值
     * @return 维度 → 均值（只含已作答的维度）
     */
    public static Map<PsycheDimension, Double> score(Map<String, Double> answers) {
        Map<PsycheDimension, List<Double>> bucket = new LinkedHashMap<>();
        if (answers != null) {
            for (Map.Entry<String, Double> e : answers.entrySet()) {
                Item item = byCode(e.getKey());
                if (item == null || !item.scored() || e.getValue() == null || Double.isNaN(e.getValue())) {
                    continue;
                }
                bucket.computeIfAbsent(item.dimension(), k -> new ArrayList<>()).add(e.getValue());
            }
        }
        Map<PsycheDimension, Double> out = new LinkedHashMap<>();
        for (Map.Entry<PsycheDimension, List<Double>> e : bucket.entrySet()) {
            List<Double> vs = e.getValue();
            double s = 0;
            for (double v : vs) {
                s += v;
            }
            out.put(e.getKey(), s / vs.size());
        }
        return out;
    }

    /** 完整题库的全维度覆盖率（分母是 11 维）。 */
    public static double fullCoverage() {
        Set<PsycheDimension> all = new HashSet<>();
        for (Item i : scoredItems()) {
            all.add(i.dimension());
        }
        return all.size() / (double) PsycheDimension.values().length;
    }

    private static List<Item> build() {
        List<Item> r = new ArrayList<>();

        // 作息两题用 CHOICE 直接给小时：量表拿不到钟点，而钟点恰恰是室友冲突的第一大来源
        r.add(new Item("bed_time", 1, PsycheDimension.SLEEP_START,
                "平时你一般几点上床睡觉？", Kind.CHOICE, List.of(
                new Option("22 点前", 22.0),
                new Option("22–23 点", 22.5),
                new Option("23–24 点", 23.5),
                new Option("0–1 点", 24.5),
                new Option("1 点以后", 26.0)),
                "按没早八的工作日算，周末不算。"));

        r.add(new Item("wake_time", 2, PsycheDimension.SLEEP_END,
                "平时你一般几点起床？", Kind.CHOICE, List.of(
                new Option("6 点前", 5.5),
                new Option("6–7 点", 6.5),
                new Option("7–8 点", 7.5),
                new Option("8–9 点", 8.5),
                new Option("9 点以后", 10.0)),
                "按没早八的日子算。"));

        r.add(new Item("smoke_now", 3, PsycheDimension.SMOKING,
                "你吸烟吗？", Kind.CHOICE, List.of(
                new Option("从不", 0.0),
                new Option("几乎不，只在特定场合", 0.15),
                new Option("偶尔", 0.4),
                new Option("经常", 0.7),
                new Option("每天都吸", 1.0)),
                "这一项会直接影响能不能和室友住到一起，请如实填。"));

        r.add(new Item("tidy", 4, PsycheDimension.CLEANLINESS,
                "你的桌面和床铺通常是？", Kind.SCALE, scale(
                "一直很整齐", "多数时候整齐", "看心情", "经常乱", "几乎不收拾"),
                "没有标准答案，找到习惯相近的人比找到「干净的人」更有用。"));

        r.add(new Item("clean_freq", 5, PsycheDimension.CLEANLINESS,
                "你多久打扫一次宿舍？", Kind.SCALE, scale(
                "每天", "每周两三次", "每周一次", "想起来才扫", "基本不扫")));

        r.add(new Item("extro", 6, PsycheDimension.EXTROVERSION,
                "空闲的周末你更想？", Kind.SCALE, scale(
                "出去找人玩", "约一两个朋友", "看情况", "待在宿舍", "一个人待着最舒服")));

        r.add(new Item("study_room", 7, PsycheDimension.STUDY_IN_ROOM,
                "你平时自习主要在哪？", Kind.SCALE, scale(
                "基本都在宿舍", "一半在宿舍", "一半一半", "多数在图书馆", "基本不在宿舍")));

        r.add(new Item("noise_tol", 8, PsycheDimension.NOISE_TOLERANCE,
                "室友打游戏、看视频的声音，你能接受吗？", Kind.SCALE, scale(
                "完全不受影响", "大多能接受", "看情况", "有点难受", "一点声音就受不了")));

        r.add(new Item("quiet_need", 9, PsycheDimension.NOISE_TOLERANCE,
                "你需要很安静才能睡着吗？", Kind.SCALE, scale(
                "不需要，什么环境都能睡", "基本不需要", "看情况", "比较需要", "必须绝对安静")));

        r.add(new Item("social_freq", 10, PsycheDimension.SOCIAL_ACTIVITY,
                "你多久参加一次聚会或集体活动？", Kind.SCALE, scale(
                "每周多次", "基本每周", "每月几次", "很少", "几乎不")));

        r.add(new Item("share_items", 11, PsycheDimension.SHARE_ITEMS,
                "室友借用你的东西，你的态度是？", Kind.SCALE, scale(
                "随便用，不用打招呼", "提前说一声就行", "看是什么东西", "不太希望别人动", "很介意，各用各的")));

        r.add(new Item("game_hours", 12, PsycheDimension.GAMING,
                "你平均每天花多少时间在游戏上？", Kind.CHOICE, List.of(
                new Option("不玩", 0.0),
                new Option("1 小时以内", 0.2),
                new Option("1–2 小时", 0.4),
                new Option("2–4 小时", 0.7),
                new Option("4 小时以上", 0.95)),
                "包括手游和端游。"));

        r.add(new Item("ambition", 13, PsycheDimension.AMBITION,
                "你对考研 / 保研 / 竞赛这类目标的态度是？", Kind.SCALE, scale(
                "已经在全力准备", "有明确打算", "在考虑", "暂时没想", "顺其自然")));

        r.add(new Item("goal_clarity", 14, PsycheDimension.AMBITION,
                "你每天在学业上投入的时间大概是？", Kind.SCALE, scale(
                "6 小时以上", "4–6 小时", "2–4 小时", "1–2 小时", "1 小时以内")));

        // 注意：这一题 dimension 故意为 null。
        // 它问的是"规律性"（0..1 的离散度），而 SLEEP_START 那一列存的是小时。
        // 若把它也挂到 SLEEP_START 上，score() 取均值会把 0.5 和 23.5 平均成 12，
        // 直接把作息变成一个不存在的钟点。规律性只作为背景信息保留，不参与数值聚合。
        r.add(new Item("sleep_regular", 15, null,
                "你的作息规律吗？", Kind.SCALE, scale(
                "非常规律", "比较规律", "一般", "经常变", "完全随缘"),
                "这一题不参与打分，只用来判断你的作息是「睡得晚」还是「睡得乱」。"));

        // 自由描述：不产生数值，但它是文本提取器和 LLM 的主素材
        r.add(new Item(FREE_TEXT_CODE, 16, null,
                "用几句话描述一下你自己，以及你希望室友是什么样的人。", Kind.TEXT, List.of(),
                "想到什么写什么。你写的越具体，建议越准；写「随便」就只能按平均情况安排。"));

        return List.copyOf(r);
    }

    /** 5 档李克特量表：从左到右 0、0.25、0.5、0.75、1。 */
    private static List<Option> scale(String... labels) {
        if (labels.length != 5) {
            throw new IllegalArgumentException("李克特量表必须是 5 档，收到 " + labels.length);
        }
        List<Option> out = new ArrayList<>(5);
        for (int i = 0; i < labels.length; i++) {
            out.add(new Option(labels[i], i / 4.0));
        }
        return List.copyOf(out);
    }
}
