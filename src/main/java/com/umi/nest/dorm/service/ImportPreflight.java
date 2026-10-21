package com.umi.nest.dorm.service;

import com.umi.nest.dorm.model.SpecialNeed;
import com.umi.nest.dorm.model.StudentFeature;
import com.umi.nest.dorm.web.DormRequests;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 真实数据导入前的体检。
 *
 * <p>存在的理由：合成数据是我们自己生成的，永远是对的；学校给的数据一定不对。
 * 没有体检时，脏数据的表现方式全都很糟糕——漏学号会变成「两个学生合成一个」，
 * 性别写「男」会抛异常但不说第几行，床位不够会在求解阶段变成一堆「未分配」，
 * 志愿指向不存在的人会被当成数据库 id 静默指到别人身上。这些都要在导入前一次说清。
 *
 * <p>体检本身**只读**：{@link #check} 不碰数据库、不写任何东西，所以可以先拿它做一次
 * 「试导入」，看看会报什么，再去改数据。
 *
 * <p>判定原则：
 * <ul>
 *   <li>{@code ERROR} = 导进去一定出问题（学号重复、性别无法识别、床位不够）。
 *       有 ERROR 时 {@link Report#ok()} 为 false，调用方应当拒绝导入。</li>
 *   <li>{@code WARN} = 能导，但结果会跟预期不一样（志愿指向不存在的人、特征长度不对、
 *       没人有特征）。要让人看得见，但不该拦着不让导——学校的名单经常就是缺字段的。</li>
 * </ul>
 *
 * <p>同类问题会合并计数并只举三个例子。一千行都缺年级时，人要看的是「有 1000 处，
 * 例如第 3、7、19 行」，不是一千行一样的字。
 */
public final class ImportPreflight {

    /** 导进去一定出问题。 */
    public static final String ERROR = "ERROR";
    /** 能导，但结果会跟预期不一样。 */
    public static final String WARN = "WARN";

    /**
     * 入睡时刻的合法区间（小时）。
     *
     * <p><b>这不是钟点</b>：{@code 24} 表示次日 0:00、{@code 26} 表示次日 2:00。
     * 区间取自 {@link com.umi.nest.dorm.model.StudentFeature} 的契约（20.0 ~ 26.0），
     * 与 {@code SyntheticDataGenerator} 的生成区间一致，两边必须同时改。
     *
     * <p>这里踩过一次：体检最初按钟点 0~24 去查，于是把 24.5（零点）和 25.0（凌晨一点）
     * ——恰恰是最该被识别的熬夜作息——误报成越界，而对真正越界的 26.5 反而放过。
     */
    private static final double SLEEP_START_LO = 20.0;
    private static final double SLEEP_START_HI = 26.0;
    /** 起床时刻的合法区间（小时），同样取自 {@code StudentFeature} 的契约（5.0 ~ 13.0）。 */
    private static final double SLEEP_END_LO = 5.0;
    private static final double SLEEP_END_HI = 13.0;

    private ImportPreflight() {
    }

    /** 一条体检结论。{@code where} 是定位串（`students[17]` / `容量/M` / `志愿`）。 */
    public record Issue(String severity, String where, String message) {

        public String line() {
            return "- **" + severity + "** `" + where + "` " + message;
        }
    }

    /**
     * 体检报告。
     *
     * @param students     学生行数
     * @param rooms        房间行数
     * @param beds         床位总数（仅统计请求里带的房间；没带房间时为 0）
     * @param withFeature  带特征的学生数
     * @param byGender     各性别学生数
     * @param bedsByGender 各性别床位总数
     * @param issues       全部结论
     */
    public record Report(int students, int rooms, int beds, int withFeature,
                         Map<String, Integer> byGender, Map<String, Integer> bedsByGender,
                         List<Issue> issues) {

        public long errors() {
            return issues.stream().filter(i -> ERROR.equals(i.severity())).count();
        }

        public long warnings() {
            return issues.stream().filter(i -> WARN.equals(i.severity())).count();
        }

        /** 没有 ERROR 才允许导入。 */
        public boolean ok() {
            return errors() == 0;
        }

        /** 给人看的文本。 */
        public String markdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("导入体检：学生 ").append(students).append(" 人，房间 ").append(rooms)
                    .append(" 间，床位 ").append(beds).append(" 个，带特征 ")
                    .append(withFeature).append("/").append(students).append(" 人。");
            if (!byGender.isEmpty()) {
                sb.append("\n- 学生性别分布：");
                sb.append(byGender);
                if (!bedsByGender.isEmpty()) {
                    sb.append("；床位分布：").append(bedsByGender);
                }
            }
            if (issues.isEmpty()) {
                sb.append("\n\n没有发现问题。");
                return sb.toString();
            }
            sb.append("\n- 问题 ").append(errors()).append(" 项会导致导入失败，")
                    .append(warnings()).append(" 项只是提醒。\n");
            for (Issue i : issues) {
                sb.append('\n').append(i.line());
            }
            return sb.toString();
        }
    }

    /** 同类问题合并计数，最多留三个例子。 */
    private static final class Agg {
        private final String severity;
        private final String where;
        private final String message;
        private final List<String> examples = new ArrayList<>();
        private int count;

        Agg(String severity, String where, String message) {
            this.severity = severity;
            this.where = where;
            this.message = message;
        }

        void hit(String example) {
            count++;
            if (examples.size() < 3) {
                examples.add(example);
            }
        }

        Issue toIssue() {
            StringBuilder sb = new StringBuilder(message);
            sb.append("（共 ").append(count).append(" 处");
            if (!examples.isEmpty()) {
                sb.append("，例如：").append(String.join("；", examples));
                if (count > examples.size()) {
                    sb.append(" 等");
                }
            }
            sb.append("）");
            return new Issue(severity, where, sb.toString());
        }
    }

    /**
     * 体检一份导入请求。不碰数据库，可以随便反复跑。
     *
     * @param req 导入请求（可为 null，会直接报错而不是抛异常）
     */
    public static Report check(DormRequests.ImportData req) {
        List<Issue> issues = new ArrayList<>();
        Map<String, Agg> aggs = new LinkedHashMap<>();
        List<DormRequests.StudentDto> students =
                req == null || req.students() == null ? List.of() : req.students();
        List<DormRequests.RoomDto> rooms =
                req == null || req.rooms() == null ? List.of() : req.rooms();

        if (students.isEmpty()) {
            issues.add(new Issue(ERROR, "students", "没有学生数据。导入什么都不会发生，"
                    + "如果本意只是更新房间，请把已有学生一起带上来。"));
        }

        // ---------------- 学生 ----------------
        Map<String, Integer> seenNo = new LinkedHashMap<>();
        Set<String> knownNo = new LinkedHashSet<>();
        Map<String, Integer> byGender = new TreeMap<>();
        Set<String> grades = new LinkedHashSet<>();

        Agg dupNo = new Agg(ERROR, "students", "学号重复。导入是按学号 upsert 的，"
                + "重复学号会让后面的行覆盖前面的行，等于悄悄少了一个人");
        Agg blankName = new Agg(WARN, "students", "姓名为空，分配结果和申诉界面里这个人只能显示学号");
        Agg badGender = new Agg(ERROR, "students", "性别无法识别，只支持 M/F 或 MALE/FEMALE");
        Agg blankGrade = new Agg(WARN, "students", "年级为空。分块是按年级切的，缺年级的人会和所有缺年级的人归到同一块");
        Agg badSpecial = new Agg(ERROR, "students", "特殊需求无法识别，可选 "
                + "NONE / WHEELCHAIR / LOW_FLOOR / QUIET");
        Agg badFeatureLen = new Agg(WARN, "students", "feature 给的个数比 " + StudentFeature.DIM
                + " 还多（应按 StudentFeature.toArray() 的顺序给），多出的会被丢掉");
        Agg badFeatureRange = new Agg(WARN, "students", "feature 第 0 位是入睡时刻，取值范围 20~26 小时"
                + "（24 表示次日 0:00、25 表示次日 1:00、26 表示次日 2:00，不是钟点 0~24）；"
                + "第 1 位是起床时刻，5~13 小时；其余 9 位是 0~1 的归一化值。这里出现了越界值");
        Agg badFeatureNum = new Agg(WARN, "students", "feature 里出现了非有限数值（Infinity/NaN），已按未知处理");
        Agg allZeroFeature = new Agg(WARN, "students", "feature 11 位全是 0。0 本身是合法取值（比如「从不吸烟」），"
                + "但如果这些位其实是「没回答」，请填 null 而不是 0——"
                + "「没回答」和「打 0 分」对求解器是两件事，用 0 顶替等于把「他什么也没说过」当成「他明确不介意」");

        int withFeature = 0;
        for (int i = 0; i < students.size(); i++) {
            int row = i + 1;
            DormRequests.StudentDto d = students.get(i);
            String no = trim(d.studentNo());
            if (no.isEmpty()) {
                issues.add(new Issue(ERROR, "students[" + row + "]",
                        "学号为空。没有学号就无法 upsert，也无法被别人的志愿引用。"));
                continue;
            }
            Integer prev = seenNo.put(no, row);
            if (prev != null) {
                dupNo.hit("第 " + prev + " 行与第 " + row + " 行都是 " + no);
            }
            knownNo.add(no);

            if (trim(d.name()).isEmpty()) {
                blankName.hit("第 " + row + " 行 " + no);
            }
            String g = genderOf(d.gender());
            if (g == null) {
                badGender.hit("第 " + row + " 行 " + no + " 写的是 \"" + trim(d.gender()) + "\"");
            } else {
                byGender.merge(g, 1, Integer::sum);
            }
            String gr = trim(d.grade());
            if (gr.isEmpty()) {
                blankGrade.hit("第 " + row + " 行 " + no);
            } else {
                grades.add(gr);
            }
            String sn = specialOf(d.specialNeed());
            if (sn == null) {
                badSpecial.hit("第 " + row + " 行 " + no + " 写的是 \"" + trim(d.specialNeed()) + "\"");
            }

            Double[] f = d.feature();
            if (f != null && f.length > 0) {
                if (f.length > StudentFeature.DIM) {
                    badFeatureLen.hit("第 " + row + " 行 " + no + " 给了 " + f.length + " 个数");
                }
                int known = 0;
                int zeros = 0;
                for (int k = 0; k < Math.min(f.length, StudentFeature.DIM); k++) {
                    Double boxed = f[k];
                    if (boxed == null) {
                        continue;
                    }
                    known++;
                    double v = boxed;
                    if (Double.isNaN(v)) {
                        continue;
                    }
                    if (Double.isInfinite(v)) {
                        badFeatureNum.hit("第 " + row + " 行 " + no + " 第 " + k + " 位是 " + v);
                        continue;
                    }
                    if (v == 0.0) {
                        zeros++;
                    }
                    double lo;
                    double hi;
                    if (k == 0) {
                        lo = SLEEP_START_LO;
                        hi = SLEEP_START_HI;
                    } else if (k == 1) {
                        lo = SLEEP_END_LO;
                        hi = SLEEP_END_HI;
                    } else {
                        lo = 0.0;
                        hi = 1.0;
                    }
                    if (v < lo || v > hi) {
                        badFeatureRange.hit("第 " + row + " 行 " + no + " 第 " + k + " 位 = " + v
                                + "（该位应在 " + lo + "~" + hi + "）");
                        break;
                    }
                }
                if (known > 0) {
                    withFeature++;
                    if (zeros == known && known == StudentFeature.DIM) {
                        allZeroFeature.hit("第 " + row + " 行 " + no);
                    }
                }
            }
        }

        if (!students.isEmpty() && withFeature == 0) {
            issues.add(new Issue(WARN, "特征",
                    "没有任何学生带特征。求解器会退化成只看性别、年级、楼栋和志愿，"
                    + "「相容度」这类指标会失去意义。特征可以先缺着，之后用心理问卷或大模型提取补上。"));
        }

        // ---------------- 志愿引用 ----------------
        Agg unknownMate = new Agg(WARN, "志愿", "志愿指向的学号不在本次导入的学生里，这条志愿会被忽略。"
                + "如果对方其实存在，通常是学号写错、或者应该把他一起导进来");
        Agg selfMate = new Agg(ERROR, "志愿", "把自己写进了想同住或不想同住的名单");
        Agg conflictMate = new Agg(WARN, "志愿", "同一个人既被写进想同住、又被写进不想同住，求解器会同时"
                + "收到正负两个信号，等于白填");

        for (int i = 0; i < students.size(); i++) {
            int row = i + 1;
            DormRequests.StudentDto d = students.get(i);
            String no = trim(d.studentNo());
            if (no.isEmpty()) {
                continue;
            }
            Set<String> want = clean(d.wantRoommateWith(), no, row, knownNo, unknownMate, selfMate);
            Set<String> avoid = clean(d.avoidRoommateWith(), no, row, knownNo, unknownMate, selfMate);
            Set<String> both = new LinkedHashSet<>(want);
            both.retainAll(avoid);
            for (String b : both) {
                conflictMate.hit("第 " + row + " 行 " + no + " 对 " + b);
            }
        }

        // ---------------- 房间 ----------------
        Map<String, Integer> bedsByGender = new TreeMap<>();
        Set<String> buildings = new LinkedHashSet<>();
        int beds = 0;
        int specialBeds = 0;
        int specialStudents = 0;
        for (DormRequests.StudentDto d : students) {
            String sn = specialOf(d.specialNeed());
            if (sn != null && !"NONE".equals(sn)) {
                specialStudents++;
            }
        }

        Agg dupRoom = new Agg(ERROR, "rooms", "同一栋楼里的房号重复，导入是按「楼栋+房号」upsert 的，"
                + "重复会让房间数比预期少，床位跟着少");
        Agg badRoomGender = new Agg(ERROR, "rooms", "房间性别无法识别，只支持 M/F 或 MALE/FEMALE");
        Agg badCapacity = new Agg(ERROR, "rooms", "房间容量不是正数");
        Agg hugeCapacity = new Agg(WARN, "rooms", "房间容量超过 8，请确认这不是把「楼栋床位数」填进了单间的容量");

        Map<String, Integer> seenRoom = new HashMap<>();
        for (int i = 0; i < rooms.size(); i++) {
            int row = i + 1;
            DormRequests.RoomDto r = rooms.get(i);
            String b = trim(r.buildingId());
            String rn = trim(r.roomNo());
            if (b.isEmpty() || rn.isEmpty()) {
                issues.add(new Issue(ERROR, "rooms[" + row + "]",
                        "buildingId 或 roomNo 为空，房间无法被唯一定位。"));
                continue;
            }
            buildings.add(b);
            Integer prev = seenRoom.put(b + "/" + rn, row);
            if (prev != null) {
                dupRoom.hit(b + "/" + rn + "（第 " + prev + " 行与第 " + row + " 行）");
            }
            int cap = r.capacity() == null ? 4 : r.capacity();
            if (cap <= 0) {
                badCapacity.hit(b + "/" + rn + " capacity=" + cap);
            }
            if (cap > 8) {
                hugeCapacity.hit(b + "/" + rn + " capacity=" + cap);
            }
            String g = genderOf(r.gender());
            if (g == null) {
                badRoomGender.hit("第 " + row + " 行 " + b + "/" + rn + " 写的是 \"" + trim(r.gender()) + "\"");
            } else {
                bedsByGender.merge(g, Math.max(0, cap), Integer::sum);
            }
            beds += Math.max(0, cap);
            if (Boolean.TRUE.equals(r.forSpecialNeeds())) {
                specialBeds += Math.max(0, cap);
            }
        }

        // 楼栋志愿指向不存在的楼栋（只有带了房间信息才判得出来）
        if (!rooms.isEmpty() && !buildings.isEmpty()) {
            Agg unknownBuilding = new Agg(WARN, "志愿", "楼栋志愿指向本次导入里不存在的楼栋，这条志愿会被忽略");
            for (int i = 0; i < students.size(); i++) {
                DormRequests.StudentDto d = students.get(i);
                String bp = trim(d.buildingPref());
                if (!bp.isEmpty() && !buildings.contains(bp)) {
                    unknownBuilding.hit("第 " + (i + 1) + " 行 " + trim(d.studentNo()) + " → " + bp);
                }
            }
            aggs.put("unknownBuilding", unknownBuilding);
        }

        // ---------------- 容量 ----------------
        // 只在请求里带了房间时才判：不带房间表示房间已经单独导好，这次只更新学生。
        if (!rooms.isEmpty()) {
            if (beds < students.size()) {
                issues.add(new Issue(ERROR, "容量",
                        "床位总数 " + beds + " 个，少于学生 " + students.size() + " 人，差 "
                                + (students.size() - beds) + " 个。硬约束要求每个人都有床位，"
                                + "导进去也无法完成分配。"));
            }
            for (Map.Entry<String, Integer> e : byGender.entrySet()) {
                int b = bedsByGender.getOrDefault(e.getKey(), 0);
                if (b < e.getValue()) {
                    issues.add(new Issue(ERROR, "容量/" + e.getKey(),
                            "「" + genderName(e.getKey()) + "」学生 " + e.getValue() + " 人，"
                                    + "但同性别床位只有 " + b + " 个，差 " + (e.getValue() - b)
                                    + " 个。男女不混住是硬约束，不可能靠调换房间化解。"));
                }
            }
            if (specialStudents > specialBeds) {
                issues.add(new Issue(ERROR, "容量/特殊需求",
                        "有特殊需求的学生 " + specialStudents + " 人，但 forSpecialNeeds 房间只有 "
                                + specialBeds + " 个床位。这是硬约束（特殊需求学生只进特殊房），"
                                + "超出的人一定分不到房间。"));
            }
        }

        aggs.put("dupNo", dupNo);
        aggs.put("blankName", blankName);
        aggs.put("badGender", badGender);
        aggs.put("blankGrade", blankGrade);
        aggs.put("badSpecial", badSpecial);
        aggs.put("badFeatureLen", badFeatureLen);
        aggs.put("badFeatureRange", badFeatureRange);
        aggs.put("badFeatureNum", badFeatureNum);
        aggs.put("unknownMate", unknownMate);
        aggs.put("selfMate", selfMate);
        aggs.put("conflictMate", conflictMate);
        aggs.put("dupRoom", dupRoom);
        aggs.put("badRoomGender", badRoomGender);
        aggs.put("badCapacity", badCapacity);
        aggs.put("hugeCapacity", hugeCapacity);

        for (Agg a : aggs.values()) {
            if (a.count > 0) {
                issues.add(a.toIssue());
            }
        }
        // ERROR 排前面：人先看拦路的
        issues.sort((x, y) -> {
            int c = Boolean.compare(!ERROR.equals(x.severity()), !ERROR.equals(y.severity()));
            return c != 0 ? c : x.where().compareTo(y.where());
        });
        return new Report(students.size(), rooms.size(), beds, withFeature,
                byGender, beds > 0 ? bedsByGender : Map.of(), issues);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 收集一条志愿名单，顺带记录「指向不存在的人」「指向自己」。返回清理后的学号集合。 */
    private static Set<String> clean(List<String> raw, String self, int row,
                                     Set<String> known, Agg unknown, Agg selfRef) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        for (String t : raw) {
            String s = trim(t);
            if (s.isEmpty()) {
                continue;
            }
            if (s.equals(self)) {
                selfRef.hit("第 " + row + " 行 " + self);
                continue;
            }
            if (!known.contains(s)) {
                unknown.hit("第 " + row + " 行 " + self + " → " + s);
                continue;
            }
            out.add(s);
        }
        return out;
    }

    /** 宽松解析：认不出来返回 null 而不是抛异常——体检的职责是报告，不是中途炸掉。 */
    static String genderOf(String g) {
        String u = trim(g).toUpperCase();
        if (u.isEmpty()) {
            return null;
        }
        if (u.startsWith("M") || u.contains("男")) {
            return "M";
        }
        if (u.startsWith("F") || u.contains("女")) {
            return "F";
        }
        return null;
    }

    static String specialOf(String s) {
        String u = trim(s).toUpperCase();
        if (u.isEmpty()) {
            return SpecialNeed.NONE.name();
        }
        try {
            return SpecialNeed.valueOf(u).name();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String genderName(String code) {
        return "F".equals(code) ? "女生" : "M".equals(code) ? "男生" : code;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
