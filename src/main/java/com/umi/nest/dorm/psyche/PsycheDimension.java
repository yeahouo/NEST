package com.umi.nest.dorm.psyche;

import com.umi.nest.dorm.model.StudentFeature;

/**
 * 11 个软特征维度的枚举——心理模块内部的"维度地址"。
 *
 * <p>存在的意义是把三处命名对起来：{@link StudentFeature#toArray()} 的数组下标、
 * {@code CompatDimensions} 的中文标签（给用户看的）、以及词库/量表/LLM 三套输入源。
 * 不做这层映射，很快就会出现"词库写的是 social、量表写的是 socialActivity、
 * 数组下标却填了 6"这种错——而且错了不会报错，只会让结果变噪音。
 */
public enum PsycheDimension {

    SLEEP_START("入睡时刻", 0, true),
    SLEEP_END("起床时刻", 1, true),
    SMOKING("吸烟", 2, false),
    CLEANLINESS("卫生", 3, false),
    EXTROVERSION("性格外向度", 4, false),
    STUDY_IN_ROOM("在房间学习", 5, false),
    NOISE_TOLERANCE("噪声容忍度", 6, false),
    SOCIAL_ACTIVITY("社交活跃度", 7, false),
    SHARE_ITEMS("物品共享", 8, false),
    GAMING("游戏", 9, false),
    AMBITION("学业拼搏", 10, false);

    private final String label;
    private final int index;
    private final boolean hourScale;

    PsycheDimension(String label, int index, boolean hourScale) {
        this.label = label;
        this.index = index;
        this.hourScale = hourScale;
    }

    public String label() {
        return label;
    }

    /** 在 {@link StudentFeature#toArray()} 里的下标。 */
    public int index() {
        return index;
    }

    /**
     * 该维度的量纲是不是"小时"。
     * <p>只有作息两维是小时（0..24 左右），其余都是 0..1 的强度。
     * 归一化和解释生成都必须按量纲分开处理——把 23.5 当 0..1 的强度去算
     * 会得到"入睡时刻 23.5 远高于平均值"这种荒谬结论。
     */
    public boolean hourScale() {
        return hourScale;
    }

    public static PsycheDimension byIndex(int index) {
        for (PsycheDimension d : values()) {
            if (d.index == index) {
                return d;
            }
        }
        throw new IllegalArgumentException("没有下标为 " + index + " 的特征维度");
    }

    public static PsycheDimension byLabel(String label) {
        for (PsycheDimension d : values()) {
            if (d.label.equals(label)) {
                return d;
            }
        }
        throw new IllegalArgumentException("没有名为「" + label + "」的特征维度");
    }

    public static PsycheDimension byNameOrLabel(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        for (PsycheDimension d : values()) {
            if (d.name().equalsIgnoreCase(t) || d.label.equals(t)) {
                return d;
            }
        }
        return null;
    }

    /**
     * 该维度的评分锚点示例。
     *
     * <p>为什么必须有锚点：只给大模型"0 到 1 表示强度"这种定义，它会习惯性地
     * 把所有维度都打在中段（0.4~0.6）——这叫集中趋势，是人格评分里最常见的失败模式。
     * 而我们的相容度函数是拿这些值算距离的：全部挤在中间意味着所有人的特征
     * 都长得一样，距离趋近于 0，分配算法就失去了区分能力。
     * 给出"0.1 是什么样的人 / 0.5 是什么样 / 0.9 是什么样"的具体描述，
     * 是把评分摊开的唯一可靠办法。
     */
    public String anchors() {
        return switch (this) {
            case SLEEP_START -> "22.0=十点就睡（极早）；23.5=十一点半（偏早）；24.0=零点前后（正常）；25.0=凌晨一点（偏晚）；26.0=凌晨两点及以后（极晚）";
            case SLEEP_END -> "6.0=六点起（极早）；7.0=七点（偏早）；7.5=七点半（正常）；9.0=九点（偏晚）；10.0=十点以后（极晚）";
            case SMOKING -> "0.1=从不吸且明确反感烟味；0.3=不吸但不太在意；0.5=只在应酬场合偶尔；0.7=有烟瘾但会避开别人；0.9=每天吸、室内也吸";
            case CLEANLINESS -> "0.1=基本不收拾，东西随手堆；0.3=想起来才收拾；0.5=一周收拾一两次；0.7=两三天一次；0.9=天天整理、见不得乱";
            case EXTROVERSION -> "0.1=极度内向，独处才回血；0.3=偏安静；0.5=看场合；0.7=偏外向；0.9=非常外向，朋友很多、喜欢热闹";
            case STUDY_IN_ROOM -> "0.1=几乎不在宿舍学，都去图书馆/教室；0.3=偶尔在宿舍看；0.5=一半一半；0.7=多在宿舍学；0.9=几乎只在宿舍学";
            case NOISE_TOLERANCE -> "0.1=一点声音就睡不着/学不进；0.3=对声音比较敏感；0.5=能忍一般动静；0.7=不太受影响；0.9=多吵都能睡";
            case SOCIAL_ACTIVITY -> "0.1=很少参加社交活动；0.3=偶尔；0.5=一般；0.7=比较活跃；0.9=每周多次聚会/社团/外出";
            case SHARE_ITEMS -> "0.1=私人物品一律不外借；0.3=关系好才借；0.5=熟人可以用；0.7=比较大方；0.9=随便用不用打招呼";
            case GAMING -> "0.1=完全不玩游戏；0.3=很少玩；0.5=偶尔玩一会儿；0.7=经常玩；0.9=每天玩到深夜、会开黑";
            case AMBITION -> "0.1=只求及格、没有规划；0.3=过得去就行；0.5=按部就班；0.7=有明确目标、比较投入；0.9=目标明确且投入极大（考研/竞赛/保研）";
        };
    }

    /** 拼成给大模型的锚点表。 */
    public static String anchorTable() {
        StringBuilder sb = new StringBuilder();
        for (PsycheDimension d : values()) {
            sb.append("- ").append(d.label()).append("（").append(d.key())
                    .append("）：").append(d.anchors()).append('\n');
        }
        return sb.toString();
    }

    /** 大模型提示词里使用的键名，与 {@code LlmExtractor} 约定的 JSON 字段一致。 */
    public String key() {
        return switch (this) {
            case SLEEP_START -> "sleep_start";
            case SLEEP_END -> "sleep_end";
            case SMOKING -> "smoking";
            case CLEANLINESS -> "cleanliness";
            case EXTROVERSION -> "extroversion";
            case STUDY_IN_ROOM -> "study_in_room";
            case NOISE_TOLERANCE -> "noise_tolerance";
            case SOCIAL_ACTIVITY -> "social_activity";
            case SHARE_ITEMS -> "share_items";
            case GAMING -> "gaming";
            case AMBITION -> "ambition";
        };
    }

    public static PsycheDimension byKey(String key) {
        if (key == null) {
            return null;
        }
        String t = key.trim();
        for (PsycheDimension d : values()) {
            if (d.key().equalsIgnoreCase(t)) {
                return d;
            }
        }
        return byNameOrLabel(t);
    }
}
