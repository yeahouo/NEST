package com.umi.nest.dorm.psyche;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提取器的输入：一个学生的全部原始素材。
 *
 * <p>三类素材刻意放在一起交给提取器，因为它们的可信度不同、覆盖的维度也不同：
 * 量表作答是结构化的、可信但覆盖面窄；聊天记录/自我介绍是自由的、覆盖全但噪声大。
 * 让提取器同时看到两者，才能做"结构化数据优先、文本数据补缺"的取舍。
 *
 * @param studentId     dorm_student.id
 * @param studentNo     学号
 * @param name          姓名
 * @param grade         年级
 * @param majorCluster  专业大类
 * @param texts         文本素材（可能为空列表）
 * @param answers       量表作答：item_code → 归一化到 0..1 的值
 */
public record ExtractionInput(
        long studentId,
        String studentNo,
        String name,
        String grade,
        String majorCluster,
        List<TextSample> texts,
        Map<String, Double> answers
) {

    public ExtractionInput {
        studentNo = studentNo == null ? "" : studentNo;
        name = name == null ? "" : name;
        grade = grade == null ? "" : grade;
        majorCluster = majorCluster == null ? "" : majorCluster;
        texts = texts == null ? List.of() : List.copyOf(texts);
        answers = answers == null ? Map.of() : Map.copyOf(answers);
    }

    public static ExtractionInput of(long studentId, List<TextSample> texts, Map<String, Double> answers) {
        return new ExtractionInput(studentId, "", "", "", "", texts, answers);
    }

    /** 一段文本素材。 */
    public record TextSample(String kind, String content) {
        public TextSample {
            kind = kind == null ? "SELF_INTRO" : kind;
            content = content == null ? "" : content;
        }
    }

    /** 全部文本拼起来（按素材原始顺序），给词库和 LLM 共用。 */
    public String combinedText() {
        StringBuilder sb = new StringBuilder();
        for (TextSample t : texts) {
            if (t.content().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(t.content());
        }
        return sb.toString();
    }

    public int textChars() {
        int n = 0;
        for (TextSample t : texts) {
            n += t.content().length();
        }
        return n;
    }

    public boolean hasText() {
        return textChars() > 0;
    }

    public boolean hasAnswers() {
        return !answers.isEmpty();
    }

    public boolean isEmpty() {
        return !hasText() && !hasAnswers();
    }

    /** 按素材类型返回，供解释里标注"来自聊天记录 / 来自自我介绍"。 */
    public Map<String, List<String>> textsByKind() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (TextSample t : texts) {
            m.computeIfAbsent(t.kind(), k -> new ArrayList<>()).add(t.content());
        }
        return m;
    }

    /**
     * 只保留学生本人的发言。
     *
     * <p>聊天记录里混着两类必须剔除的东西：其他同学的发言，
     * 以及助手自己的总结句。后者尤其危险——助手那句「该用户表现出较高的外向性」
     * 一旦被当成本人原话送进提取器，就形成了「模型引用自己」的幻觉回路，
     * 而且这条线索会被当成学生亲口说的，置信度还会很高。
     *
     * <p>不是对话的素材（自我介绍、作文、辅导员记录）会被原样保留，
     * 所以这条规则不会让既有素材失效。
     */
    public ExtractionInput selfOnly() {
        List<TextSample> kept = new ArrayList<>();
        for (TextSample t : texts) {
            // 把学生本人的姓名传进去：有的聊天记录里本人的标签就是真名（"王小明：…"），
            // 不告诉解析器真名是什么，这些发言会被当成"他人"整段丢掉。
            String s = DialogueParser.parse(t.content(), name).selfText();
            if (s != null && !s.isBlank()) {
                kept.add(new TextSample(t.kind(), s));
            }
        }
        return new ExtractionInput(studentId, studentNo, name, grade, majorCluster, kept, answers);
    }

    /**
     * 出站前脱敏。
     *
     * <p>脱敏点放在这里而不是入库点，是一个有意的选择：学校本地库本来就不出校，
     * 真正需要防的是把手机号、学号、姓名发给云端大模型。
     * 所以卡在"即将离开本机"的这一步，既保住了管理端排查问题时要看的原文，
     * 又保证大模型永远看不到身份信息。
     *
     * @param knownNames 调用方明确知道的姓名（通常是学生自己）。不做词表猜测——
     *                   把一个普通词当成人名替换掉，比不脱敏更糟。
     */
    public ExtractionInput scrubbed(java.util.Collection<String> knownNames) {
        List<TextSample> kept = new ArrayList<>();
        for (TextSample t : texts) {
            String s = PrivacyScrubber.scrub(t.content(), knownNames).text();
            if (s != null && !s.isBlank()) {
                kept.add(new TextSample(t.kind(), s));
            }
        }
        return new ExtractionInput(studentId, studentNo, name, grade, majorCluster, kept, answers);
    }

    /** 既剔除他人发言、又脱敏——生产路径用的就是这个。 */
    public ExtractionInput sanitized(java.util.Collection<String> knownNames) {
        return selfOnly().scrubbed(knownNames);
    }
}
