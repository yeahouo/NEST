package com.umi.nest.dorm.psyche;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则提取器的离线自检入口：{@code java -cp target\classes com.umi.nest.dorm.psyche.PsycheCli [文本]}
 *
 * <p>不带参数时跑内置样例。<b>样例里每一条都是刻意挑的边界</b>：
 * 中文数字钟点、否定句、反讽、把室友的行为误算到自己头上。
 * 做规则提取最怕的就是"自己的测试集里全是干净句子"，
 * 上线后被真实文本一碰就碎。这些样例的作用是让退化点可见。
 *
 * <p>这个类不依赖 Spring，也不联网——规则提取器的价值就在于随时能跑。
 */
public final class PsycheCli {

    private PsycheCli() {
    }

    private record Sample(String title, String text, String expect) {
    }

    private static final List<Sample> SAMPLES = List.of(
            new Sample("典型熬夜 + 内向 + 怕吵",
                    "我一般凌晨两点才睡，早上七点半起。喜欢一个人待着，不太喜欢热闹。"
                            + "室友打游戏的声音我受不了，必须安静才能睡。我不抽烟，也不希望室友抽。"
                            + "桌面还行吧，一周收拾一次。",
                    "入睡≈26.0、起床≈7.5、外向低、噪声容忍低、吸烟0、卫生≈0.5"),

            new Sample("夜猫子 + 游戏 + 豪爽",
                    "我是夜猫子，天天熬夜打游戏到三点，经常开黑到天亮。"
                            + "宿舍几个人经常一起点外卖，东西随便用不用打招呼。我比较外向，朋友很多。",
                    "入睡偏高、游戏高、物品共享高、外向高、社交高"),

            new Sample("早睡早起 + 考研 + 洁癖",
                    "我十一点就睡了，六点半起床跑步。考研准备中，每天泡图书馆。"
                            + "有点洁癖，东西必须整整齐齐。我不太喜欢别人动我的东西。",
                    "入睡≈23.0、起床≈6.5、学业拼搏高、卫生高、物品共享低、在房间学习低"),

            new Sample("反讽与条件句（规则法必然失手）",
                    "我这个人特别外向，一天能跟一百个人说话，只不过都是在游戏里。"
                            + "我不玩游戏，真的，每天只玩十个小时而已。",
                    "规则法会把「外向」「玩游戏」都判高——这正是需要大模型那一路的原因"));

    public static void main(String[] args) {
        RuleBasedExtractor extractor = new RuleBasedExtractor();
        List<Sample> run;
        if (args.length > 0) {
            String text = String.join(" ", args);
            run = List.of(new Sample("命令行输入", text, ""));
        } else {
            run = SAMPLES;
        }

        for (Sample s : run) {
            System.out.println("══════════════════════════════════════════════════════════════");
            System.out.println("样例：" + s.title());
            System.out.println("原文：" + abbreviate(s.text(), 300));
            if (!s.expect().isEmpty()) {
                System.out.println("预期：" + s.expect());
            }
            System.out.println("──────────────────────────────────────────────────────────────");

            ExtractionResult r = extractor.extract(ExtractionInput.of(1L, List.of(
                    new ExtractionInput.TextSample("SELF_INTRO", s.text())), Map.of()));

            for (PsycheDimension d : PsycheDimension.values()) {
                double v = r.valueOf(d);
                double c = r.confidenceOf(d);
                String vs = Double.isNaN(v)
                        ? "   ——   "
                        : (d.hourScale() ? RuleBasedExtractor.hhmm(v) : String.format("%.3f", v));
                String cs = Double.isNaN(c) ? "  -  " : String.format("%.2f", c);
                System.out.printf("  %-12s %-10s 置信 %s%n", d.label(), vs, cs);
            }

            System.out.println("  ── 证据（" + r.evidence().size() + " 条）──");
            Map<String, List<Evidence>> byDim = new LinkedHashMap<>();
            for (Evidence e : r.evidence()) {
                byDim.computeIfAbsent(e.dimension(), k -> new ArrayList<>()).add(e);
            }
            for (Map.Entry<String, List<Evidence>> e : byDim.entrySet()) {
                System.out.println("   · " + e.getKey() + " ← " + e.getValue().get(0).quote());
                System.out.println("       " + e.getValue().get(0).reasoning());
            }
            System.out.println("  ── 叙述 ── " + r.narrative());
            System.out.println();
        }

        System.out.println("提示：这个入口只跑本地规则，不调用大模型。");
        System.out.println("大模型那一路要走 REST（POST /api/dorm/psyche/extract）或 PsycheLlmCli，"
                + "因为它们需要完整的 Spring classpath。");
    }

    private static String abbreviate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
