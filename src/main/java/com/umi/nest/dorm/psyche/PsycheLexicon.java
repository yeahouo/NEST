package com.umi.nest.dorm.psyche;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 中文心理/习惯线索词库。
 *
 * <p>这是<b>不用大模型也能跑</b>的那条路。它当然读不懂反讽、读不懂言外之意，
 * 但它有一个模型比不了的优点：任何一次判定都能指回具体那个词，
 * 而且同样的输入永远给同样的输出——做消融实验时这是必需的。
 *
 * <p>三条实现规矩：
 * <ol>
 *   <li><b>长词优先 + 占用区间</b>：规则按词长降序匹配，命中后占用区间，
 *       后续规则不得重叠。所以「不抽烟」会吃掉「抽烟」，不会一边加一边减。
 *       这个顺序不能改，改了就会出现正负抵消导致全部判成中立。</li>
 *   <li><b>否定词检视</b>：没有被长词覆盖的命中，看它前面三个字里有没有否定词，
 *       有就把 delta 取反并把权重打折（打折不取反到 100%，因为"不太喜欢热闹"
 *       仍然比"喜欢热闹"更偏内向，但比"讨厌热闹"程度轻）。</li>
 *   <li><b>词库是数据不是逻辑</b>：加一条线索只是加一行，不需要动提取器。</li>
 * </ol>
 */
public final class PsycheLexicon {

    private PsycheLexicon() {
    }

    /**
     * 一条线索规则。
     *
     * @param phrase    中文短语
     * @param dimension 指向的维度
     * @param delta     该短语把这一维往哪边推：正=偏高，负=偏低，绝对值=强度
     * @param reason    给人看的理由，会原样出现在证据链里
     */
    public record Rule(String phrase, PsycheDimension dimension, double delta, String reason) {
    }

    /** 否定词。命中规则未被长词覆盖时，用它判断语义是否被翻转。 */
    public static final List<String> NEGATIONS = List.of(
            "不", "没", "无", "别", "未", "非", "讨厌", "反感", "受不了", "不爱", "不喜欢",
            "从不", "很少", "不太", "绝不", "决不", "拒绝");

    private static final List<Rule> RULES = build();

    public static List<Rule> rules() {
        return RULES;
    }

    public static List<Rule> rulesFor(PsycheDimension d) {
        List<Rule> out = new ArrayList<>();
        for (Rule r : RULES) {
            if (r.dimension() == d) {
                out.add(r);
            }
        }
        return out;
    }

    private static List<Rule> build() {
        List<Rule> r = new ArrayList<>();

        // ── 作息：只放"性质"词，具体钟点在 RuleBasedExtractor 里用正则抽 ──
        add(r, PsycheDimension.SLEEP_START, 1.0, "属于熬夜作息",
                "熬夜", "通宵", "很晚睡", "晚睡", "凌晨才睡", "睡得晚", "半夜才睡", "昼夜颠倒");
        add(r, PsycheDimension.SLEEP_START, -1.0, "属于早睡作息",
                "早睡", "十点就睡", "十一点前睡", "睡得早", "作息规律", "按时睡觉", "不熬夜");
        add(r, PsycheDimension.SLEEP_END, 1.0, "起床偏晚",
                "睡到中午", "赖床", "中午才起", "起得晚", "睡到下午");
        add(r, PsycheDimension.SLEEP_END, -1.0, "起床偏早",
                "早起", "六点就起", "起得早", "早八", "晨跑", "从不赖床");

        // ── 吸烟 ──
        add(r, PsycheDimension.SMOKING, 1.0, "明确吸烟",
                "抽烟", "吸烟", "电子烟", "烟瘾", "一天一包", "来一根", "烟民");
        add(r, PsycheDimension.SMOKING, -1.0, "明确不吸烟或反感烟味",
                "不抽烟", "不吸烟", "讨厌烟味", "反感吸烟", "受不了烟味", "从不抽烟", "闻不了烟味", "无烟");

        // ── 卫生 ──
        add(r, PsycheDimension.CLEANLINESS, 1.0, "在意整洁",
                "每天都收拾", "见不得乱", "一天不擦", "必须整整齐齐", "特别爱干净",
                "打扫", "收拾", "整洁", "爱干净", "一尘不染", "井井有条", "收纳", "整理", "干净");
        add(r, PsycheDimension.CLEANLINESS, -1.0, "不太在意整洁",
                "不太爱收拾", "乱一点无所谓", "比较乱", "随手放", "邋遢", "懒得收拾", "不怎么收拾",
                "从来不打扫", "乱七八糟", "乱糟糟", "堆着", "外卖盒", "不太注意卫生");

        // ── 性格外向度 ──
        add(r, PsycheDimension.EXTROVERSION, 1.0, "偏外向",
                "喜欢热闹", "爱交朋友", "朋友很多", "外向", "开朗", "活泼", "话多", "喜欢聊天", "自来熟", "爱说笑");
        add(r, PsycheDimension.EXTROVERSION, -1.0, "偏内向",
                "内向", "喜欢独处", "社恐", "不爱说话", "话少", "沉默", "慢热", "不喜欢社交", "一个人待着", "安静的人");

        // ── 在房间学习 ──
        add(r, PsycheDimension.STUDY_IN_ROOM, 1.0, "习惯在宿舍学习",
                "在宿舍学", "在寝室学", "窝在宿舍", "房间里学习", "宿舍自习", "在宿舍看书");
        add(r, PsycheDimension.STUDY_IN_ROOM, -1.0, "习惯去图书馆/自习室",
                "图书馆", "自习室", "去教室自习", "不在宿舍学", "在外面学");

        // ── 噪声容忍度 ──
        // 这一维最初的召回率是 0%：词库里写的是"睡得沉"，学生的原话是"我睡觉很沉"；
        // 写的是"必须安静"，原话是"必须绝对安静"。差一个字的线索词等于没有——
        // 覆盖率 0 不是"这维难"，是词库里根本没有能命中的词。下面按真实说法补齐。
        add(r, PsycheDimension.NOISE_TOLERANCE, 1.0, "对声音不敏感",
                "睡觉很沉", "再吵都能睡", "打呼噜都不影响", "完全不敏感", "声音不敏感",
                "戴不戴耳机都行", "不太受影响", "稍微有点声音也能睡", "能忍",
                "不怕吵", "不介意吵", "无所谓声音", "吵一点没关系", "不容易醒", "睡得沉", "什么环境都能睡");
        add(r, PsycheDimension.NOISE_TOLERANCE, -1.0, "对声音敏感",
                "一点声音都", "有动静就", "睡觉比较轻", "特别敏感", "翻书声", "太吵了会烦",
                "希望安静", "绝对安静", "睡不着",
                "怕吵", "需要安静", "一点声音就", "睡眠浅", "容易被吵醒", "耳塞", "戴耳机睡", "必须安静", "声音敏感");

        // ── 社交活跃度 ──
        add(r, PsycheDimension.SOCIAL_ACTIVITY, 1.0, "社交活跃",
                "活动特别多", "天天有局", "经常出去", "经常聚会", "喜欢聚餐", "参加活动", "社交达人",
                "经常出去玩", "一起开黑", "社团", "每周都出去");
        add(r, PsycheDimension.SOCIAL_ACTIVITY, -1.0, "社交较少",
                "基本不出去", "很少参加", "社交很少", "很少出门", "不参加活动", "不喜欢社交",
                "宅", "两点一线", "基本待在宿舍");

        // ── 物品共享 ──
        add(r, PsycheDimension.SHARE_ITEMS, 1.0, "不介意共享物品",
                "随便用", "一起用", "共享", "不分你我", "拿去用", "不用打招呼", "我的就是你的");
        add(r, PsycheDimension.SHARE_ITEMS, -1.0, "比较在意私人物品",
                "各用各的", "私人物品", "不喜欢别人动", "介意别人用", "别碰我东西", "有洁癖");

        // ── 游戏 ──
        add(r, PsycheDimension.GAMING, 1.0, "有游戏习惯",
                "打游戏", "玩游戏", "开黑", "联机", "王者", "吃鸡", "英雄联盟", "原神", "电竞", "游戏本", "天天打游戏");
        add(r, PsycheDimension.GAMING, -1.0, "不玩游戏",
                "不玩游戏", "不打游戏", "戒游戏", "对游戏没兴趣");

        // ── 学业拼搏 ──
        add(r, PsycheDimension.AMBITION, 1.0, "学业目标明确",
                "考研", "保研", "考公", "竞赛", "奖学金", "绩点", "科研", "实验室", "刷题", "冲刺", "目标明确");
        add(r, PsycheDimension.AMBITION, -1.0, "对学业目标不紧迫",
                "摆烂", "躺平", "混日子", "及格就行", "无所谓成绩", "不打算考研", "佛系");

        // 长词优先，保证「不抽烟」先于「抽烟」占用区间
        r.sort(Comparator.comparingInt((Rule x) -> x.phrase().length()).reversed());
        return List.copyOf(r);
    }

    private static void add(List<Rule> out, PsycheDimension d, double delta, String reason, String... phrases) {
        for (String p : phrases) {
            out.add(new Rule(p, d, delta, reason + "（线索词「" + p + "」）"));
        }
    }
}
