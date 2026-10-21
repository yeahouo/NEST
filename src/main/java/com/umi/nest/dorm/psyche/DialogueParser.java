package com.umi.nest.dorm.psyche;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多人对话解析：把一份聊天记录拆成带说话人的轮次，并只保留<b>本人</b>的发言。
 *
 * <p>这是"从聊天记录推断性格"能被做对的前提。聊天记录里混着三类发言：
 * 本人的、室友的、以及助手/AI 的。如果整段丢给提取器，室友说
 * "我天天打游戏到三点"会被算到这个学生头上——一个从不玩游戏的人
 * 因此被分进游戏房，这类错误的代价是真实的宿舍冲突。
 *
 * <p>三类角色的处理规则不同，不能一刀切：
 * <ul>
 *   <li>{@link Role#SELF} 本人的发言——唯一的推断依据。</li>
 *   <li>{@link Role#OTHER} 他人的发言——丢弃。他人对本人性格的描述
 *       理论上也可用（"你太爱干净了"），但这是二手信息且容易反讽，
 *       当前版本一律不用，宁可少拿信息也不引入串味。</li>
 *   <li>{@link Role#AGENT} 助手/AI 的发言——丢弃，且必须能识别出来：
 *       大模型自己的总结里常出现"该用户表现出高开放性"这类
 *       人格判断词，被当成本人原话会形成自我强化的幻觉回路。</li>
 * </ul>
 *
 * <p>无法识别出任何说话人的纯文本（自我介绍、作文、辅导员记录）会退化成
 * "一段只有本人发言的对话"，行为与旧版本一致——这个类不会让既有素材失效。
 */
public final class DialogueParser {

    public enum Role {
        SELF, OTHER, AGENT
    }

    /**
     * 一轮发言。
     *
     * @param speaker 说话人标签原文
     * @param role    角色判定
     * @param text    发言内容（保留原文，作息线索如"凌晨两点"不能被吃掉）
     * @param session 第几段会话，从 1 开始
     */
    public record Turn(String speaker, Role role, String text, int session) {
        public Turn {
            speaker = speaker == null ? "" : speaker;
            text = text == null ? "" : text;
        }
    }

    /**
     * 解析结果。
     */
    public record Parsed(List<Turn> turns, int sessions, int selfTurns, int otherTurns, int agentTurns,
                         List<String> speakers, boolean dialogue) {

        public Parsed {
            turns = turns == null ? List.of() : List.copyOf(turns);
            speakers = speakers == null ? List.of() : List.copyOf(speakers);
        }

        public static Parsed empty() {
            return new Parsed(List.of(), 0, 0, 0, 0, List.of(), false);
        }

        /** 只保留本人发言拼接成的文本——这是真正送进提取器的东西。 */
        public String selfText() {
            StringBuilder sb = new StringBuilder();
            for (Turn t : turns) {
                if (t.role() != Role.SELF || t.text().isBlank()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(t.text());
            }
            return sb.toString();
        }

        public int selfChars() {
            return selfText().length();
        }

        public boolean hasSelfSpeech() {
            return selfChars() > 0;
        }

        public String summary() {
            if (!dialogue) {
                return "不是多人对话，按本人自述处理（" + selfChars() + " 字）。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("共 ").append(turns.size()).append(" 轮 / ").append(sessions).append(" 段会话；")
                    .append("本人 ").append(selfTurns).append(" 轮、他人 ").append(otherTurns)
                    .append(" 轮、助手 ").append(agentTurns).append(" 轮。");
            sb.append("只采用本人的 ").append(selfTurns).append(" 轮（").append(selfChars()).append(" 字）参与特征提取。");
            if (selfTurns == 0) {
                sb.append("⚠ 这份记录里没有识别到本人的发言，无法提取任何特征。");
            }
            if (!speakers.isEmpty()) {
                sb.append(" 说话人：").append(String.join("、", speakers)).append("。");
            }
            return sb.toString();
        }

        /** 给管理端表格用的角色分布。 */
        public List<Map<String, Object>> speakerStats() {
            Map<String, int[]> agg = new LinkedHashMap<>();
            for (Turn t : turns) {
                agg.computeIfAbsent(t.speaker(), k -> new int[2])[0]++;
                if (t.role() == Role.SELF) {
                    agg.get(t.speaker())[1]++;
                }
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Turn t : turns) {
                if (!agg.containsKey(t.speaker())) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("speaker", t.speaker());
                m.put("role", t.role().name());
                m.put("turns", agg.remove(t.speaker())[0]);
                m.put("used", t.role() == Role.SELF);
                out.add(m);
            }
            return out;
        }
    }

    /** 说话人前缀：可选 [23:30] 时间戳 + 标签 + 冒号。标签不能含空白和冒号。 */
    private static final Pattern TURN = Pattern.compile(
            "^\\s*(?:\\[\\d{1,2}:\\d{2}(?::\\d{2})?\\]\\s*)?([^\\s:：]{1,14})\\s*[:：]\\s*(.*)$");

    /** 会话分隔：分割线、日期行、第 N 天。 */
    private static final Pattern SESSION_BREAK = Pattern.compile(
            "^\\s*(?:[=\\-—*]{3,}|\\d{4}\\s*[-/年.]\\s*\\d{1,2}\\s*[-/月.]\\s*\\d{1,2}\\s*日?.*|第\\s*\\d+\\s*[天段场次].*)$");

    /** 纯数字/时间样式的"说话人"是误匹配，不是人。 */
    private static final Pattern NOT_A_SPEAKER = Pattern.compile("^[0-9]{1,4}(?::[0-9]{2})?$");

    private static final Set<String> SELF_WORDS = new LinkedHashSet<>(List.of(
            "我", "本人", "自己", "学生", "本人发言", "我的", "me", "self", "myself",
            "user", "用户", "提问者", "甲方"));

    private static final Set<String> AGENT_WORDS = new LinkedHashSet<>(List.of(
            "助手", "智能助手", "小助手", "ai", "机器人", "客服", "系统", "系统提示",
            "assistant", "bot", "chatgpt", "gpt", "deepseek", "claude", "模型", "回答者"));

    private DialogueParser() {
    }

    public static Parsed parse(String raw) {
        return parse(raw, null);
    }

    /**
     * @param raw      原始记录
     * @param selfName 本人姓名（可为 null）；出现的同名标签视为本人
     */
    public static Parsed parse(String raw, String selfName) {
        if (raw == null || raw.isBlank()) {
            return Parsed.empty();
        }
        String[] lines = raw.split("\\r?\\n");

        // 第一遍：这份文本到底是不是对话？只要有一行像"某人：内容"就算。
        boolean looksDialogue = false;
        for (String line : lines) {
            Matcher m = TURN.matcher(line);
            if (m.matches() && plausibleSpeaker(m.group(1))) {
                looksDialogue = true;
                break;
            }
        }

        if (!looksDialogue) {
            String whole = raw.strip();
            List<Turn> one = whole.isEmpty() ? List.of() : List.of(new Turn("我", Role.SELF, whole, 1));
            int c = whole.isEmpty() ? 0 : 1;
            return new Parsed(one, c == 0 ? 0 : 1, c, 0, 0, List.of("我"), false);
        }

        List<Turn> turns = new ArrayList<>();
        LinkedHashSet<String> speakers = new LinkedHashSet<>();
        int session = 1;
        Turn cur = null;

        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            if (SESSION_BREAK.matcher(line).matches()) {
                session++;
                cur = null;
                continue;
            }
            Matcher m = TURN.matcher(line);
            if (m.matches() && plausibleSpeaker(m.group(1))) {
                String sp = m.group(1).trim();
                Role role = roleOf(sp, selfName);
                speakers.add(sp);
                cur = new Turn(sp, role, m.group(2).trim(), session);
                turns.add(cur);
            } else if (cur != null) {
                // 续行：聊天记录里的换行是真实存在的，不能丢
                String merged = cur.text().isEmpty() ? line.strip() : cur.text() + " " + line.strip();
                cur = new Turn(cur.speaker(), cur.role(), merged, cur.session());
                turns.set(turns.size() - 1, cur);
            }
            // 没有任何前置轮次且不像说话人前缀的行：忽略。出现说明第一行是标题之类。
        }

        int self = 0;
        int other = 0;
        int agent = 0;
        for (Turn t : turns) {
            switch (t.role()) {
                case SELF -> self++;
                case OTHER -> other++;
                case AGENT -> agent++;
            }
        }
        int sessions = turns.isEmpty() ? 0 : turns.get(turns.size() - 1).session();
        return new Parsed(turns, sessions, self, other, agent, new ArrayList<>(speakers), true);
    }

    /** 这份素材看起来是不是多人对话（用于入库时决定要不要解析）。 */
    public static boolean isDialogue(String raw) {
        return parse(raw).dialogue();
    }

    private static boolean plausibleSpeaker(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        if (t.isEmpty() || t.length() > 14) {
            return false;
        }
        if (NOT_A_SPEAKER.matcher(t).matches()) {
            return false;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        return !lower.startsWith("http") && !lower.contains("://");
    }

    private static Role roleOf(String speaker, String selfName) {
        String t = speaker.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        if (selfName != null && !selfName.isBlank() && t.equals(selfName.trim())) {
            return Role.SELF;
        }
        if (SELF_WORDS.contains(lower) || SELF_WORDS.contains(t)) {
            return Role.SELF;
        }
        if (AGENT_WORDS.contains(lower) || AGENT_WORDS.contains(t)) {
            return Role.AGENT;
        }
        // "小助手""AI助手"这类前后缀
        if (lower.startsWith("小助手") || lower.startsWith("ai") || lower.endsWith("助手")
                || lower.endsWith("机器人")) {
            return Role.AGENT;
        }
        return Role.OTHER;
    }
}
