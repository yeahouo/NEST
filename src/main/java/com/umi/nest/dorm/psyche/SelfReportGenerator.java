package com.umi.nest.dorm.psyche;

import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.model.StudentFeature;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 用"真值特征"反写出一份像人写的自述/聊天记录。
 *
 * <p>存在的理由：{@link LlmExtractor} 这类提取器如果只演示"抽一个学生，结果看着挺合理"，
 * 是没法写进结题报告的——评委一定会问"你怎么知道它准"。
 * 要回答这个问题，必须有一个<b>知道标准答案</b>的输入。
 * {@code SyntheticDataGenerator} 已经为每个虚拟学生生成了真特征 {@code z_i}
 * （还带一个算法永远看不到的隐变量，见 {@code GroundTruth}），
 * 这个类把 {@code z_i} 翻译成一段学生口吻的文字，于是"喂进去的文字"和"该提取出的值"
 * 就配成了一对，可以做有监督的误差统计。
 *
 * <p>三条设计约束，都是为了不让评测变成走过场：
 * <ol>
 *   <li><b>说人话，不说字段</b>。绝不能写成"入睡时刻=25.0"。学生说的是
 *       "我一般两三点才睡"，提取器要做的是从这句话里把 25.0 还原出来。
 *       如果直接给数值，测的就不是语义理解而是抄写能力。</li>
 *   <li><b>聊天记录里埋反例</b>。助手的总结句和室友的评价句会被写成与真值
 *       <b>相反</b>的内容。不剔除这两类发言的提取器会被拽偏，
 *       剔除之后误差应该明显下降——这样"只分析本人发言"这条规则才有可测的证据。</li>
 *   <li><b>可以不说</b>。以 {@code missingRate} 的概率整段丢掉某一维的描述，
 *       用来测覆盖率：提取器<b>该说"不知道"的时候有没有说不知道</b>。
 *       一个见到任何文本都硬凑出 11 个值的提取器，在这里会暴露。</li>
 * </ol>
 */
public final class SelfReportGenerator {

    private SelfReportGenerator() {
    }

    /**
     * @param missingRate 每一维被整段省略的概率（0 = 每一维都有描述）
     * @param privacyRate 素材里掺入手机号/学号/姓名的概率（用来验证脱敏）
     * @param seed        随机种子
     */
    public record Config(double missingRate, double privacyRate, long seed) {

        public static Config defaults(long seed) {
            return new Config(0.20, 0.25, seed);
        }

        public Config withMissingRate(double v) {
            return new Config(v, privacyRate, seed);
        }
    }

    /** 一个学生的素材：一段自我介绍、一段聊天记录、以及标准答案。 */
    public record Sample(long studentId, String name, String selfIntro, String chatLog,
                         StudentFeature truth) {

        public boolean hasChat() {
            return chatLog != null && !chatLog.isBlank();
        }
    }

    // ==================================================================
    // 对外入口
    // ==================================================================

    public static Sample sample(Student s, StudentFeature z, Config cfg, Random rnd) {
        String intro = selfIntro(s, z, cfg, rnd, true);
        String chat = chatLog(s, z, cfg, rnd);
        return new Sample(s.id(), s.name(), intro, chat, z);
    }

    /**
     * 自我介绍 / 手写自述。
     *
     * @param withIdentity 是否在开头自报姓名学号（用来喂给脱敏器）
     */
    public static String selfIntro(Student s, StudentFeature z, Config cfg, Random rnd, boolean withIdentity) {
        StringBuilder sb = new StringBuilder();
        if (withIdentity) {
            sb.append("老师好，我是").append(s.name()).append("，学号").append(fakeStudentNo(s, rnd));
            if (rnd.nextDouble() < cfg.privacyRate()) {
                sb.append("，手机是").append(fakePhone(rnd));
            }
            sb.append("。");
        }
        sb.append("要分宿舍了，我说说自己的情况吧。\n");

        for (String line : describeAll(z, cfg, rnd)) {
            sb.append(line).append('\n');
        }
        sb.append("大概就是这样，希望能遇到合得来的室友。");
        return sb.toString();
    }

    /**
     * 聊天记录：学生和"宿舍助手"聊天，中间宿舍友插话。
     *
     * <p>助手那句总结和室友那句评价都<b>故意写成与真值相反</b>。
     * 它们是这场评测里唯一的"污染源"：不剔除就得错，剔除就变准。
     */
    public static String chatLog(Student s, StudentFeature z, Config cfg, Random rnd) {
        StringBuilder sb = new StringBuilder();
        sb.append("【与宿舍助手的对话】\n");
        sb.append('[').append(clock(rnd, 20, 0)).append("] 我：你好，想问下宿舍怎么安排的？\n");
        sb.append('[').append(clock(rnd, 20, 1)).append("] 助手：你好！我会根据你的生活习惯帮你匹配室友。先聊聊你自己吧？\n");

        List<String> lines = describeAll(z, cfg, rnd);
        // 学生分几次说，不要一口气说完
        int cut = Math.max(1, lines.size() / 2);
        for (int i = 0; i < cut; i++) {
            sb.append('[').append(clock(rnd, 20, 2 + i)).append("] 我：").append(lines.get(i)).append('\n');
        }

        // 助手的错误总结（把真值反着说）
        sb.append('[').append(clock(rnd, 20, 20)).append("] 助手：明白了。综合来看，")
                .append(oppositeSummary(z, rnd)).append('\n');

        // 室友的插话（也把真值反着说）
        sb.append('[').append(clock(rnd, 20, 22)).append("] 室友").append(otherName(rnd)).append("：")
                .append(oppositeSummary(z, rnd)).append('\n');

        for (int i = cut; i < lines.size(); i++) {
            sb.append('[').append(clock(rnd, 20, 25 + i)).append("] 我：").append(lines.get(i)).append('\n');
        }
        sb.append('[').append(clock(rnd, 20, 40)).append("] 助手：好的，已记录。谢谢你的配合！\n");
        return sb.toString();
    }

    // ==================================================================
    // 逐维描述
    // ==================================================================

    private static List<String> describeAll(StudentFeature z, Config cfg, Random rnd) {
        List<String> out = new ArrayList<>();
        // 作息两句通常连着说，所以它们共用一个"被省略"的抽签机会
        if (keep(cfg, rnd)) {
            out.add(sleepStartLine(z.sleepStart(), rnd));
            out.add(sleepEndLine(z.sleepEnd(), rnd));
        }
        if (keep(cfg, rnd)) {
            out.add(smokingLine(z.smoking(), rnd));
        }
        if (keep(cfg, rnd)) {
            out.add(cleanlinessLine(z.cleanliness(), rnd));
        }
        if (keep(cfg, rnd)) {
            out.add(extroversionLine(z.extroversion(), rnd));
        }
        if (keep(cfg, rnd)) {
            out.add(studyLine(z.studyInRoom(), rnd));
        }
        if (keep(cfg, rnd)) {
            out.add(noiseLine(z.noiseTolerance(), rnd));
        }
        if (keep(cfg, rnd)) {
            out.add(socialLine(z.socialActivity(), rnd));
        }
        if (keep(cfg, rnd)) {
            out.add(shareLine(z.shareItems(), rnd));
        }
        if (keep(cfg, rnd)) {
            out.add(gamingLine(z.gaming(), rnd));
        }
        if (keep(cfg, rnd)) {
            out.add(ambitionLine(z.ambition(), rnd));
        }
        if (out.isEmpty()) {
            out.add("我没什么特别的，都还行吧，随大流。");
        }
        return out;
    }

    private static boolean keep(Config cfg, Random rnd) {
        return rnd.nextDouble() >= cfg.missingRate();
    }

    private static String sleepStartLine(double v, Random rnd) {
        if (v >= 25.5) {
            return pick(rnd, "我是夜猫子，两三点才睡是常事，有时候四点。",
                    "作息很乱，经常熬到凌晨三四点才躺下。");
        }
        if (v >= 24.5) {
            return pick(rnd, "我一般凌晨一点左右睡。",
                    "睡得挺晚的，一点多才上床。");
        }
        if (v >= 23.5) {
            return pick(rnd, "基本零点前后上床吧。",
                    "我十二点左右睡，不算早也不算晚。");
        }
        if (v >= 22.5) {
            return pick(rnd, "我一般十一点多就睡了。",
                    "睡得比较早，十一点半左右。");
        }
        return pick(rnd, "我十点就睡了，熄灯前肯定上床。",
                "我作息特别规律，十点多就休息。");
    }

    private static String sleepEndLine(double v, Random rnd) {
        if (v >= 9.5) {
            return pick(rnd, "没课能睡到十点，闹钟都叫不醒我。",
                    "早上基本不起，睡到快中午的时候也有。");
        }
        if (v >= 8.5) {
            return pick(rnd, "早上九点左右起。",
                    "我一般睡到八点半九点。");
        }
        if (v >= 7.5) {
            return pick(rnd, "早上八点左右起床。",
                    "差不多八点起，正常上课时间。");
        }
        if (v >= 6.75) {
            return pick(rnd, "我七点多就起来了。",
                    "起得比较早，七点半左右。");
        }
        return pick(rnd, "我六点多就起，习惯早起。",
                "早上六点半起床，从不赖床。");
    }

    private static String smokingLine(double v, Random rnd) {
        if (v >= 0.75) {
            return pick(rnd, "我抽烟，一天一包左右，戒不掉。",
                    "烟瘾比较大，基本一小时一根。");
        }
        if (v >= 0.45) {
            return pick(rnd, "我抽烟，一天几根吧。",
                    "有烟瘾，一天三四根。");
        }
        if (v >= 0.20) {
            return pick(rnd, "偶尔抽一根，压力大的时候，平时不抽。",
                    "很少抽，一个月也就几次。");
        }
        return pick(rnd, "我不抽烟，也闻不了烟味，希望能安排不抽烟的室友。",
                "我完全不抽烟，对烟味挺敏感的。");
    }

    private static String cleanlinessLine(double v, Random rnd) {
        if (v >= 0.78) {
            return pick(rnd, "我见不得乱，每天都收拾，桌面必须整整齐齐。",
                    "我特别爱干净，一天不擦桌子就难受。");
        }
        if (v >= 0.58) {
            return pick(rnd, "我挺注意卫生的，两三天收拾一次。",
                    "还算爱干净，东西摆得比较整齐。");
        }
        if (v >= 0.38) {
            return pick(rnd, "卫生一般吧，想起来才收拾一下。",
                    "不算邋遢也不算特别干净，一周收拾一两次。");
        }
        if (v >= 0.20) {
            return pick(rnd, "我桌子比较乱，东西随手放。",
                    "不太爱收拾，能用就行。");
        }
        return pick(rnd, "我基本不收拾，东西堆着，乱习惯了。",
                "我比较随性，屋里乱一点无所谓。");
    }

    private static String extroversionLine(double v, Random rnd) {
        if (v >= 0.75) {
            return pick(rnd, "我话挺多的，喜欢热闹，安静下来反而难受。",
                    "我性格很外向，跟谁都能聊起来。");
        }
        if (v >= 0.55) {
            return pick(rnd, "我算是比较开朗的，熟了话就多。",
                    "我还挺好相处的，平时爱聊天。");
        }
        if (v >= 0.40) {
            return pick(rnd, "我性格一般，不太内向也不太外向。",
                    "看情况，跟熟悉的人话多，陌生人就少。");
        }
        if (v >= 0.22) {
            return pick(rnd, "我比较闷，话不多。",
                    "我偏内向，不太主动找人说话。");
        }
        return pick(rnd, "我很内向，基本不说话，喜欢一个人待着。",
                "我不太会聊天，人多的时候会很累。");
    }

    private static String studyLine(double v, Random rnd) {
        if (v >= 0.75) {
            return pick(rnd, "我基本都在宿舍看书做作业，很少去图书馆。",
                    "我习惯在宿舍学习，桌上东西都是书。");
        }
        if (v >= 0.45) {
            return pick(rnd, "有时候在宿舍学，有时候去自习室。",
                    "在哪学都行，宿舍也能看进去。");
        }
        return pick(rnd, "我在宿舍基本不学习，都是去图书馆或者教室。",
                "回宿舍就是休息，书都不往宿舍带。");
    }

    private static String noiseLine(double v, Random rnd) {
        if (v >= 0.78) {
            return pick(rnd, "我睡觉很沉，再吵都能睡，打呼噜都不影响我。",
                    "我对声音完全不敏感，戴不戴耳机都行。");
        }
        if (v >= 0.58) {
            return pick(rnd, "我一般不太受影响，稍微有点声音也能睡。",
                    "还算能忍，除非特别吵。");
        }
        if (v >= 0.38) {
            return pick(rnd, "声音大的话我会睡不着，但正常说话没事。",
                    "一般般，太吵了会烦。");
        }
        if (v >= 0.20) {
            return pick(rnd, "我睡觉比较轻，有动静就容易醒。",
                    "不太能接受吵，晚上希望安静点。");
        }
        return pick(rnd, "我一点声音都受不了，必须绝对安静才睡得着。",
                "我对声音特别敏感，翻书声都能吵醒我。");
    }

    private static String socialLine(double v, Random rnd) {
        if (v >= 0.75) {
            return pick(rnd, "我社团活动特别多，经常出去，宿舍待的时间不多。",
                    "我很爱玩，基本天天有局。");
        }
        if (v >= 0.45) {
            return pick(rnd, "偶尔参加活动，不算特别活跃。",
                    "朋友有一些，周末会出去。");
        }
        return pick(rnd, "我很少参加社团活动，基本不出去。",
                "我社交很少，下了课就回宿舍。");
    }

    private static String shareLine(double v, Random rnd) {
        if (v >= 0.75) {
            return pick(rnd, "我东西室友随便用，不用跟我说。",
                    "我比较大方的，零食都是共享的。");
        }
        if (v >= 0.45) {
            return pick(rnd, "一般的东西借了没问题，说一声就行。",
                    "看是什么东西，大部分都能借。");
        }
        return pick(rnd, "我不太喜欢别人动我的东西，用之前最好问一下。",
                "我比较介意私人物品被碰，希望各用各的。");
    }

    private static String gamingLine(double v, Random rnd) {
        if (v >= 0.75) {
            return pick(rnd, "我经常打游戏，会开黑到很晚，不过我戴耳机。",
                    "我基本天天开黑，声音会控制一下。");
        }
        if (v >= 0.45) {
            return pick(rnd, "偶尔打打游戏，周末玩得多一些。",
                    "会玩，但不沉迷，一般一两个小时。");
        }
        return pick(rnd, "我基本不玩游戏。",
                "我不打游戏，对那个没兴趣。");
    }

    private static String ambitionLine(double v, Random rnd) {
        if (v >= 0.75) {
            return pick(rnd, "我打算考研，成绩挺重要的，平时会一直泡在自习室。",
                    "我想保研，学习抓得很紧，绩点看得比较重。");
        }
        if (v >= 0.45) {
            return pick(rnd, "学习还行吧，该上课上课，成绩中等。",
                    "我属于正常水平，不挂科就行，也想过考。");
        }
        return pick(rnd, "我对成绩没什么要求，及格就行，主要想干点别的。",
                "我不太在乎绩点，能毕业就好。");
    }

    // ==================================================================
    // 反例：助手/他人发言（与真值相反）
    // ==================================================================

    /** 一段与真值相反的总结。不剔除它的提取器会被拽偏。 */
    private static String oppositeSummary(StudentFeature z, Random rnd) {
        List<String> claims = new ArrayList<>();
        claims.add(z.sleepStart() >= 24.5 ? "作息非常规律，睡得很早" : "作息很晚，经常熬夜");
        claims.add(z.cleanliness() >= 0.6 ? "卫生习惯一般，东西比较随意" : "特别爱干净，每天都收拾");
        claims.add(z.extroversion() >= 0.6 ? "性格偏内向，不太爱说话" : "性格非常外向，话很多");
        claims.add(z.noiseTolerance() >= 0.6 ? "对声音比较敏感，需要安静" : "对声音完全不敏感");
        claims.add(z.gaming() >= 0.6 ? "基本不玩游戏" : "经常打游戏到很晚");
        StringBuilder sb = new StringBuilder();
        int n = 2 + rnd.nextInt(2);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append('、');
            }
            sb.append(claims.remove(rnd.nextInt(claims.size())));
        }
        sb.append("。");
        return sb.toString();
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private static String pick(Random rnd, String... options) {
        return options[rnd.nextInt(options.length)];
    }

    private static String fakeStudentNo(Student s, Random rnd) {
        // 形如 EIE24037：字母 + 5~7 位数字，正好命中 PrivacyScrubber 的学号规则
        StringBuilder sb = new StringBuilder();
        String major = s.majorCluster() == null ? "EIE" : s.majorCluster();
        for (int i = 0; i < 3 && i < major.length(); i++) {
            char c = major.charAt(i);
            sb.append(Character.isLetter(c) ? Character.toUpperCase(c) : (char) ('A' + rnd.nextInt(26)));
        }
        while (sb.length() < 3) {
            sb.append((char) ('A' + rnd.nextInt(26)));
        }
        sb.append(20 + rnd.nextInt(6));
        for (int i = 0; i < 3; i++) {
            sb.append(rnd.nextInt(10));
        }
        return sb.toString();
    }

    private static String fakePhone(Random rnd) {
        StringBuilder sb = new StringBuilder("1");
        sb.append(3 + rnd.nextInt(6));
        for (int i = 0; i < 9; i++) {
            sb.append(rnd.nextInt(10));
        }
        return sb.toString();
    }

    private static final String[] OTHER_NAMES = {"小明", "阿杰", "老王", "小林", "阿豪"};

    private static String otherName(Random rnd) {
        return OTHER_NAMES[rnd.nextInt(OTHER_NAMES.length)];
    }

    private static String clock(Random rnd, int hour, int minute) {
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute + rnd.nextInt(4));
    }
}
