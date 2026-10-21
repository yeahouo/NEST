package com.umi.nest.dorm.psyche;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 隐私脱敏：把素材里的可识别个人信息替换成占位符，再交给提取器。
 *
 * <p>这不是"可选的好习惯"，而是这个模块能合法存在的前提。学生的聊天记录里
 * 天然带着手机号、学号、微信号，而素材会被送去第三方大模型 API。
 * 如果不脱敏就发出去，等于把能直接定位到个人的信息交给了外部服务——
 * 申请书里"本地部署、数据不出校"的承诺会当场作废。
 *
 * <p>规则顺序有讲究：<b>先长后短</b>。身份证 18 位必须先于手机号 11 位匹配，
 * 否则一个 18 位号码会被切掉前 11 位、留下 7 位残渣。
 *
 * <p>已知姓名的处理方式和其他规则不同：中文姓名没有可靠的纯规则识别方式，
 * 所以这里只替换<b>调用方明确告知的姓名</b>（通常是该学生自己、以及他名单里的室友）。
 * 不猜、不用词表硬套——猜错名字会把正常语句打烂，比不脱敏更糟。
 */
public final class PrivacyScrubber {

    public static final String MASK_PHONE = "[手机号]";
    public static final String MASK_ID_CARD = "[身份证]";
    public static final String MASK_EMAIL = "[邮箱]";
    public static final String MASK_CODE = "[学号或编号]";
    public static final String MASK_IM = "[社交账号]";
    public static final String MASK_NAME = "[姓名]";

    private PrivacyScrubber() {
    }

    private record Rule(String key, Pattern pattern, String mask) {
    }

    /** 顺序即优先级：先长后短，先结构强的后结构弱的。 */
    private static final List<Rule> RULES = List.of(
            new Rule("邮箱",
                    Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
                    MASK_EMAIL),
            new Rule("身份证",
                    Pattern.compile("(?<![0-9])[0-9]{17}[0-9Xx](?![0-9])"),
                    MASK_ID_CARD),
            new Rule("手机号",
                    Pattern.compile("(?<![0-9])1[3-9][0-9]{9}(?![0-9])"),
                    MASK_PHONE),
            new Rule("社交账号",
                    Pattern.compile("(?:QQ|qq|扣扣|微信|weixin|WeChat|WECHAT|WX|wx)\\s*(?:号|id|ID)?\\s*[:：]?\\s*[A-Za-z0-9_-]{5,20}"),
                    MASK_IM),
            new Rule("学号",
                    Pattern.compile("(?<![0-9A-Za-z])[A-Za-z][0-9]{5,12}(?![0-9A-Za-z])"),
                    MASK_CODE),
            new Rule("长编号",
                    Pattern.compile("(?<![0-9])[0-9]{8,14}(?![0-9])"),
                    MASK_CODE));

    /** 管理端展示用：规则清单。 */
    public static List<Map<String, Object>> ruleCatalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Rule r : RULES) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", r.key());
            m.put("mask", r.mask());
            m.put("pattern", r.pattern().pattern());
            out.add(m);
        }
        Map<String, Object> nameRule = new LinkedHashMap<>();
        nameRule.put("key", "姓名");
        nameRule.put("mask", MASK_NAME);
        nameRule.put("pattern", "调用方传入的已知姓名（不猜测）");
        out.add(nameRule);
        return out;
    }

    /**
     * 脱敏结果。
     *
     * @param text         脱敏后的文本（可直接入库、可发给大模型）
     * @param hits         规则名 → 命中次数
     * @param removedNames 实际被替换掉的姓名
     * @param total        替换总处数
     */
    public record Result(String text, Map<String, Integer> hits, List<String> removedNames, int total) {

        public Result {
            text = text == null ? "" : text;
            hits = hits == null ? Map.of() : Map.copyOf(hits);
            removedNames = removedNames == null ? List.of() : List.copyOf(removedNames);
        }

        public boolean changed() {
            return total > 0;
        }

        public String summary() {
            if (total == 0) {
                return "没有检测到需要脱敏的个人信息。";
            }
            StringBuilder sb = new StringBuilder("已脱敏 ").append(total).append(" 处：");
            boolean first = true;
            for (Map.Entry<String, Integer> e : hits.entrySet()) {
                if (!first) {
                    sb.append("、");
                }
                sb.append(e.getKey()).append(' ').append(e.getValue()).append(" 处");
                first = false;
            }
            if (!removedNames.isEmpty()) {
                sb.append("（替换掉的姓名不在此处回显）");
            }
            return sb.toString();
        }
    }

    public static Result scrub(String text) {
        return scrub(text, List.of());
    }

    public static Result scrub(String text, Collection<String> knownNames) {
        if (text == null || text.isEmpty()) {
            return new Result("", Map.of(), List.of(), 0);
        }
        String out = text;
        Map<String, Integer> hits = new LinkedHashMap<>();

        for (Rule r : RULES) {
            Matcher m = r.pattern().matcher(out);
            int c = 0;
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                c++;
                m.appendReplacement(sb, Matcher.quoteReplacement(r.mask()));
            }
            m.appendTail(sb);
            if (c > 0) {
                out = sb.toString();
                hits.merge(r.key(), c, Integer::sum);
            }
        }

        List<String> names = new ArrayList<>();
        if (knownNames != null) {
            for (String raw : knownNames) {
                if (raw == null) {
                    continue;
                }
                String n = raw.trim();
                // 少于两个字的名字不替换：单字替换会把正常句子打烂
                if (n.length() < 2 || n.equals(MASK_NAME)) {
                    continue;
                }
                int c = countOf(out, n);
                if (c > 0) {
                    out = out.replace(n, MASK_NAME);
                    hits.merge("姓名", c, Integer::sum);
                    names.add(n);
                }
            }
        }

        int total = 0;
        for (int v : hits.values()) {
            total += v;
        }
        return new Result(out, hits, names, total);
    }

    /** 只要文本时的便捷入口。 */
    public static String scrubText(String text) {
        return scrub(text).text();
    }

    private static int countOf(String haystack, String needle) {
        int c = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            c++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return c;
    }
}
