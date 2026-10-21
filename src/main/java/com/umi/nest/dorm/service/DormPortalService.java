package com.umi.nest.dorm.service;

import com.umi.nest.dorm.config.ConstraintConfig;
import com.umi.nest.dorm.model.Gender;
import com.umi.nest.dorm.model.Preference;
import com.umi.nest.dorm.model.ProblemInstance;
import com.umi.nest.dorm.model.SpecialNeed;
import com.umi.nest.dorm.model.Student;
import com.umi.nest.dorm.psyche.PsycheDimension;
import com.umi.nest.dorm.psyche.Questionnaire;
import com.umi.nest.dorm.repo.DormPsycheRepository;
import com.umi.nest.dorm.repo.DormRepository;
import com.umi.nest.dorm.solver.BlockDecomposer;
import com.umi.nest.dorm.web.DormRequests;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 学生端门户服务：把"我的宿舍、我的室友、为什么这样分、我的画像、我想换"这五件事
 * 做成一个学生自己能看懂的接口集合。
 *
 * <p>与 {@link DormService} 的关键差别是<b>数据可见范围</b>。管理端能看全校，
 * 学生端只能看自己那一间房。所有方法的第一步都是把登录账号解析成宿舍名单里的
 * studentId，解析不出来就明确告诉前端"你还没绑定学号"，而不是猜一个。
 *
 * <p>这里刻意不隐藏"没满足你的志愿"这类坏消息：可解释性系统如果只报喜，
 * 学生会立刻学会不信任它。
 */
@Service
public class DormPortalService {

    private final DormRepository repo;
    private final DormPsycheRepository psycheRepo;
    private final DormService dormService;
    private final PsycheService psycheService;

    public DormPortalService(DormRepository repo, DormPsycheRepository psycheRepo,
                             DormService dormService, PsycheService psycheService) {
        this.repo = repo;
        this.psycheRepo = psycheRepo;
        this.dormService = dormService;
        this.psycheService = psycheService;
    }

    // ==================================================================
    // 身份
    // ==================================================================

    /** 登录账号 → 宿舍学生。未绑定时返回 null，由调用方给出友好的绑定指引。 */
    public Long studentIdOf(long userId) {
        return psycheRepo.studentIdOfUser(userId);
    }

    /**
     * 首页需要的全部信息，一次请求拿完。
     *
     * <p>刻意不拆成"先查学生再查房间再查解释"三个请求：学生端在手机上打开，
     * 每多一次往返就多一次白屏。
     */
    public Map<String, Object> me(long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Long sid = studentIdOf(userId);
        if (sid == null) {
            out.put("bound", false);
            out.put("message", "你的账号还没有和宿舍名单绑定。宿舍名单是按学号导入的，"
                    + "输入学号即可完成绑定（只绑一次）。");
            out.put("candidateStudents", psycheRepo.studentPsycheOverview(null, 5));
            return out;
        }
        out.put("bound", true);
        Student s = psycheRepo.studentById(sid);
        out.put("student", studentView(s, sid));
        out.put("batch", latestBatch());
        Map<String, Object> alloc = allocationOf(sid);
        out.putAll(alloc);
        return out;
    }

    /** 按学号绑定账号。 */
    public Map<String, Object> bind(long userId, String studentNo) {
        if (studentNo == null || studentNo.isBlank()) {
            throw new IllegalArgumentException("请输入学号");
        }
        Student s = psycheRepo.studentByNo(studentNo.trim());
        if (s == null) {
            throw new IllegalArgumentException("宿舍名单里没有学号 " + studentNo.trim()
                    + "。如果你还没被录入名单，请联系宿管老师。");
        }
        Long owner = psycheRepo.studentIdOfUser(userId);
        if (owner != null && owner != s.id()) {
            throw new IllegalArgumentException("你这个账号已经绑定了学号 "
                    + psycheRepo.studentNoOf(owner) + "，不能改绑。如确有错误请联系管理员解绑。");
        }
        psycheRepo.linkUser(s.id(), userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bound", true);
        out.put("studentId", s.id());
        out.put("studentNo", studentNo.trim());
        out.put("name", s.name());
        out.put("message", "绑定成功。");
        return out;
    }

    private Map<String, Object> latestBatch() {
        Long id = repo.latestBatchId();
        if (id == null) {
            return null;
        }
        Map<String, Object> b = repo.getBatch(id);
        if (b == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.get("id"));
        m.put("name", b.get("name"));
        m.put("status", b.get("status"));
        m.put("studentCount", b.get("student_count"));
        m.put("hardViolations", b.get("hard_violations"));
        m.put("createdAt", b.get("created_at"));
        m.put("finishedAt", b.get("finished_at"));
        return m;
    }

    /** 我的分配 + 室友 + 解释。没有分配时返回 allocated=false 并说明原因。 */
    public Map<String, Object> allocationOf(long studentId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Long batchId = repo.latestBatchId();
        if (batchId == null) {
            out.put("allocated", false);
            out.put("reason", "学校还没有发布本学期的宿舍分配结果。");
            return out;
        }
        Map<String, Object> a = repo.assignmentOf(batchId, studentId);
        if (a == null) {
            out.put("allocated", false);
            out.put("batchId", batchId);
            out.put("reason", "最近这一批分配里没有你的记录，可能是名单更新后还没重新分配。");
            return out;
        }
        out.put("allocated", true);
        out.put("batchId", batchId);
        Object roomId = a.get("room_id");
        out.put("room", roomView(a));
        out.put("roommates", roommates(batchId, studentId, roomId));
        try {
            out.put("explanation", dormService.explain(batchId, studentId));
        } catch (RuntimeException e) {
            // 解释是加分项，算不出来不能连"你住哪"都看不了
            out.put("explanation", null);
            out.put("explanationError", e.getMessage());
        }
        out.put("isAppealed", a.get("is_appealed"));
        return out;
    }

    private Map<String, Object> roomView(Map<String, Object> a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.get("room_id"));
        m.put("label", a.get("room_label"));
        m.put("buildingId", a.get("building_id"));
        return m;
    }

    private List<Map<String, Object>> roommates(long batchId, long studentId, Object roomId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (roomId == null) {
            return out;
        }
        for (Map<String, Object> row : repo.assignmentsOf(batchId, ((Number) roomId).longValue())) {
            Number sid = (Number) row.get("student_id");
            if (sid == null || sid.longValue() == studentId) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("studentId", sid.longValue());
            m.put("name", row.get("name"));
            m.put("grade", row.get("grade"));
            m.put("major", row.get("major"));
            m.put("majorCluster", row.get("major_cluster"));
            out.add(m);
        }
        return out;
    }

    /** 只暴露学生自己该知道的字段，不带 id 之外的内部信息。 */
    private Map<String, Object> studentView(Student s, long studentId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("studentNo", psycheRepo.studentNoOf(studentId));
        m.put("name", s.name());
        m.put("gender", s.gender().name());
        m.put("grade", s.grade());
        m.put("major", s.major());
        m.put("majorCluster", s.majorCluster());
        m.put("specialNeed", s.specialNeed().name());
        return m;
    }

    // ==================================================================
    // 心理画像（创新点 4 的学生端一半）
    // ==================================================================

    /** 我填了什么、系统读出了什么、哪些值真的影响了分配。 */
    public Map<String, Object> psyche(long studentId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> prof = psycheRepo.profileOf(studentId);
        out.put("hasProfile", prof != null);
        out.put("profile", prof);
        out.put("currentFeatures", psycheRepo.currentFeatures(studentId));
        out.put("texts", psycheRepo.allTextsOf(studentId));
        out.put("answers", psycheRepo.listAnswers(studentId));
        out.put("dimensionNotes", dimensionNotes());
        out.put("explain", "你写下的文字会被拆成 11 个可比较的特征（作息、吸烟、卫生、"
                + "性格外向度、噪声容忍度等）。系统只使用证据充分、置信度高的那些值；"
                + "拿不准的维度留空，分配时用同性别同年级同学的平均值代替——"
                + "宁可保守，也不拿猜的值决定你和谁住。");
        return out;
    }

    private List<Map<String, Object>> dimensionNotes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PsycheDimension d : PsycheDimension.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", d.label());
            m.put("usedBySolver", d != PsycheDimension.AMBITION);
            m.put("scale", d.hourScale() ? "小时" : "0..1");
            out.add(m);
        }
        return out;
    }

    /** 我的问卷：题目 + 我已有的作答 + 还差哪些维度。 */
    public Map<String, Object> questionnaire(long studentId) {
        Map<String, Object> out = psycheService.questionnaire();
        Map<String, Double> answered = psycheRepo.answersOf(studentId);
        out.put("myAnswers", answered);
        out.put("answeredCount", answered.size());
        out.put("remaining", Questionnaire.nextItems(answered.keySet(), 99)
                .stream().map(Questionnaire.Item::code).toList());
        return out;
    }

    public Map<String, Object> submitAnswers(long studentId, Map<String, Object> answers) {
        return psycheService.submitAnswers(studentId, answers);
    }

    public Map<String, Object> submitText(long studentId, String kind, String content, Boolean consent) {
        long id = psycheService.addText(studentId, kind, content, consent);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("textId", id);
        out.put("message", Boolean.FALSE.equals(consent)
                ? "已保存，但你没有授权用于特征提取，因此它不会影响分配。你可以随时回来授权。"
                : "已保存。系统会在下一次提取时读取它，提取出的特征需要你确认后才会影响分配。");
        return out;
    }

    // ==================================================================
    // 我的志愿
    //
    // 志愿是求解器权重最高的输入（互相填 = 双向 +1.5，单向 +1.0，不想同住 = 硬性避开项），
    // 但在这次改动之前学生端根本没有填报入口，只能由学校用 /api/dorm/data/import 批量灌。
    // 这里补的就是这条通道：学生自己填，只动他自己那几行。
    // ==================================================================

    /** 「想同住」最多填几位。 */
    static final int MAX_WANT = 5;
    /** 「不想同住」最多填几位。 */
    static final int MAX_AVOID = 5;
    /** 楼栋志愿最多填几个。 */
    static final int MAX_BUILDINGS = 3;
    /** 同学搜索一次最多返回几个。 */
    static final int SEARCH_LIMIT = 20;

    /** 我的志愿：已填内容 + 楼栋清单 + 每个志愿对象到底能不能和你住到一起。 */
    public Map<String, Object> preference(long studentId) {
        Student me = requireStudentInRoster(studentId);
        ConstraintConfig cfg = currentConfig();
        Preference p = repo.preferenceOf(studentId);
        List<Map<String, Object>> want = resolve(me, p.preferredRoommates(), cfg, true);
        List<Map<String, Object>> avoid = resolve(me, p.avoidRoommates(), cfg, false);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("myBlock", BlockDecomposer.blockKey(me, cfg));
        out.put("myBlockText", blockText(BlockDecomposer.blockKey(me, cfg)));
        out.put("wantRoommates", want);
        out.put("avoidRoommates", avoid);
        out.put("preferredBuildings", p.preferredBuildings());
        out.put("buildings", repo.listBuildings());
        out.put("maxRoomCapacity", repo.maxRoomCapacity());
        out.put("limits", Map.of("wantRoommates", MAX_WANT,
                "avoidRoommates", MAX_AVOID, "buildings", MAX_BUILDINGS));
        out.put("warnings", warningsFor(want, avoid));
        out.put("rules", List.of(
                "「想同住」：互相都填了对方时权重最高（双向 +1.5），会优先把你们排进同一间房。",
                "只有你填了对方（单向 +1.0）：仍然算数，但优先级低于互相填。",
                "「不想同住」：按硬性避开项处理，求解时会尽最大努力不让你们同房。",
                "「楼栋志愿」：命中一次加一点分，属于锦上添花，不是硬约束。",
                "跨块的人排不到一起：系统按 性别 × 专业大类 × 年级 分块独立求解，"
                        + "不在同一个块里的人，志愿填得再高也住不到一间房。"
                        + "填进名单的人和搜索结果里都会当场标出这种情况，不用等分配结果出来才发现。"));
        out.put("privacy", "你填的内容只用于排宿，不会展示给其他学生。"
                + "「对方是否也填了你」这种互相确认，只在你自己的列表里那几个人之间显示；"
                + "系统不提供「谁想跟我住」的查询——那是别人的隐私。");
        if (repo.latestBatchId() != null) {
            out.put("note", "本学期的分配结果已经发布，现在改志愿不会再改动它，"
                    + "新志愿从下一次分配开始生效。");
        }
        return out;
    }

    /**
     * 把分块键翻成人话。
     *
     * <p>键本身是 {@code F|N|经管类|2023} 这种给程序看的串。直接摆给学生看会被看错——竖线在中文
     * 语境里几乎被当成字母 I，实测截图里「F|N」就被读成了「FIN」。这里按
     * {@link BlockDecomposer#blockKey} 的拼装顺序<b>反向拆开</b>，而不是拿 {@link Student} 重新
     * 推导一遍：反向拆能保证「显示的东西」和「求解器用的东西」永远是同一个字符串，不会各自漂移。
     */
    static String blockText(String key) {
        if (key == null || key.isEmpty()) {
            return "未确定";
        }
        String[] parts = key.split("\\|");
        StringBuilder sb = new StringBuilder();
        appendWord(sb, "M".equals(parts[0]) ? "男生" : "F".equals(parts[0]) ? "女生" : parts[0]);
        if (parts.length > 1) {
            appendWord(sb, "S".equals(parts[1]) ? "特殊需求宿舍" : "普通宿舍");
        }
        if (parts.length > 2 && !parts[2].isEmpty()) {
            appendWord(sb, parts[2]);
        }
        if (parts.length > 3 && !parts[3].isEmpty()) {
            appendWord(sb, parts[3] + "级");
        }
        return sb.toString();
    }

    private static void appendWord(StringBuilder sb, String word) {
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(word);
    }

    /**
     * 保存志愿。
     *
     * <p>校验放在这里而不是前端：前端那些校验是给人看的，这里才是真的。
     * 前端被绕过、被改、被脚本直接调用都是常态。
     */
    public Map<String, Object> savePreference(long studentId, List<Long> want,
                                              List<Long> avoid, List<String> buildings) {
        requireStudentInRoster(studentId);
        List<Long> w = distinct(want);
        List<Long> a = distinct(avoid);
        List<String> b = distinctStrings(buildings);

        if (w.size() > MAX_WANT) {
            throw new IllegalArgumentException("「想同住」最多填 " + MAX_WANT + " 位同学。"
                    + "一间房连你在内最多住 " + repo.maxRoomCapacity()
                    + " 人，填太多会互相挤掉。");
        }
        if (a.size() > MAX_AVOID) {
            throw new IllegalArgumentException("「不想同住」最多填 " + MAX_AVOID + " 位同学。");
        }
        if (b.size() > MAX_BUILDINGS) {
            throw new IllegalArgumentException("楼栋志愿最多填 " + MAX_BUILDINGS + " 个。");
        }
        if (w.contains(studentId) || a.contains(studentId)) {
            throw new IllegalArgumentException("不能把自己填进志愿名单。");
        }
        List<Long> both = new ArrayList<>(w);
        both.retainAll(a);
        if (!both.isEmpty()) {
            throw new IllegalArgumentException("同一个人不能既填「想同住」又填「不想同住」，"
                    + "请先决定要哪一个。");
        }
        List<Long> all = new ArrayList<>(w);
        all.addAll(a);
        if (!all.isEmpty()) {
            Set<Long> exists = new HashSet<>();
            for (Map<String, Object> row : repo.studentsByIds(all)) {
                exists.add(((Number) row.get("id")).longValue());
            }
            for (Long id : all) {
                if (!exists.contains(id)) {
                    throw new IllegalArgumentException("志愿名单里有学号不在本学期的宿舍名单里，"
                            + "请重新搜索确认后再提交。");
                }
            }
        }
        List<String> known = repo.listBuildings();
        for (String x : b) {
            if (!known.contains(x)) {
                throw new IllegalArgumentException("楼栋「" + x + "」不在现有房间清单里。");
            }
        }

        repo.savePreferenceOf(studentId, w, a, b);

        Map<String, Object> out = preference(studentId);
        out.put("message", "志愿已保存。");
        return out;
    }

    /**
     * 按学号或姓名找同学，并直接告诉他「能不能和你住一间房」。
     *
     * <p>提前告知的意义：否则学生填了志愿，等到分配结果出来才发现"我们俩根本不在一个块"，
     * 那时他只会认为系统没理他——而系统其实一开始就知道做不到。
     */
    public Map<String, Object> searchClassmates(long studentId, String q) {
        Student me = requireStudentInRoster(studentId);
        ConstraintConfig cfg = currentConfig();
        String kw = q == null ? "" : q.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        if (kw.length() < 2) {
            out.put("results", List.of());
            out.put("hint", "至少输入 2 个字符（学号片段或姓名）再搜索。"
                    + "不在输入过程中返回整份名单，是为了不把宿舍名册做成一张可以随便翻的表。");
            return out;
        }
        List<Map<String, Object>> rows = repo.searchStudents(kw, SEARCH_LIMIT);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            if (id == studentId) {
                continue;
            }
            results.add(view(row, me, cfg, false));
        }
        out.put("results", results);
        out.put("count", results.size());
        out.put("truncated", rows.size() >= SEARCH_LIMIT);
        if (results.isEmpty()) {
            out.put("hint", "没找到。只能搜到本学期宿舍名单里的同学（按学号或姓名），"
                    + "名单里没有的人不会出现在结果里。");
        }
        return out;
    }

    /** 学生必须真的在宿舍名单里，否则给出明确原因，而不是返回一堆空数据。 */
    private Student requireStudentInRoster(long studentId) {
        Student s = psycheRepo.studentById(studentId);
        if (s == null) {
            throw new IllegalStateException("你绑定的学号不在本学期的宿舍名单里，"
                    + "请联系宿舍管理员核对后再填报志愿。");
        }
        return s;
    }

    /** 当前生效的求解配置。没有批次时用默认配置——否则学生看到的分块依据会凭空消失。 */
    private ConstraintConfig currentConfig() {
        Long batchId = repo.latestBatchId();
        return batchId == null ? ConstraintConfig.defaults() : repo.configOf(batchId);
    }

    /** 把 id 列表还原成学生能看懂的对象，并标出「能不能和你住一间房」。 */
    private List<Map<String, Object>> resolve(Student me, List<Long> ids,
                                              ConstraintConfig cfg, boolean revealMutual) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : repo.studentsByIds(ids)) {
            byId.put(((Number) row.get("id")).longValue(), row);
        }
        Set<Long> mutual = revealMutual
                ? new HashSet<>(repo.studentsWhoWantMe(me.id()))
                : Set.of();
        for (Long id : ids) {
            Map<String, Object> row = byId.get(id);
            if (row == null) {
                Map<String, Object> gone = new LinkedHashMap<>();
                gone.put("studentId", id);
                gone.put("name", "（已不在名单里）");
                gone.put("canShareRoom", false);
                gone.put("mutual", false);
                gone.put("why", "这个学号已经不在本学期的宿舍名单里，下一次分配时不会生效。");
                out.add(gone);
                continue;
            }
            out.add(view(row, me, cfg, mutual.contains(id)));
        }
        return out;
    }

    /**
     * 一个同学在学生眼里的样子。
     *
     * <p>「能不能和你住一间房」直接调 {@link BlockDecomposer#blockKey}，而不是另写一套判断：
     * 另写一套迟早会和求解器不一致，到那时告诉学生的就是假话。
     */
    private Map<String, Object> view(Map<String, Object> row, Student me,
                                     ConstraintConfig cfg, boolean mutual) {
        long id = ((Number) row.get("id")).longValue();
        String major = str(row.get("major"));
        String cluster = str(row.get("major_cluster"));
        if (cluster.isEmpty()) {
            cluster = major.isEmpty() ? "UNKNOWN" : major;
        }
        String grade = str(row.get("grade"));
        Student other = new Student(id, str(row.get("name")), gender(row.get("gender")),
                grade, major, cluster, null, specialNeed(row.get("special_need")));
        boolean same = BlockDecomposer.blockKey(me, cfg).equals(BlockDecomposer.blockKey(other, cfg));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("studentId", id);
        m.put("studentNo", row.get("student_no"));
        m.put("name", row.get("name"));
        m.put("gender", row.get("gender"));
        m.put("grade", grade);
        m.put("major", major);
        m.put("majorCluster", cluster);
        m.put("mutual", mutual);
        m.put("canShareRoom", same);
        m.put("why", same
                ? "和你分在同一个块里，正常分配时有可能住到一起。"
                : "和你不在同一个块（" + diffReason(me, other, cfg) + "），正常分配时分不到同一间房。"
                        + "仍然可以填，但只有走申诉的跨块试算才有可能满足。");
        return m;
    }

    /**
     * 两个人为什么不在同一个块里。
     *
     * <p>理由必须落到具体某一个条件上：只说「不行」而不说为什么，
     * 学生只能反复重填试运气，那比不给这个提示更糟。
     */
    private static String diffReason(Student a, Student b, ConstraintConfig cfg) {
        List<String> why = new ArrayList<>();
        if (a.gender() != b.gender()) {
            why.add("性别不同");
        }
        if (a.isSpecial() != b.isSpecial()) {
            why.add("一个住普通房、一个住特殊需求房");
        }
        if (cfg.isBlockByMajorCluster() && !a.majorCluster().equals(b.majorCluster())) {
            why.add("专业大类不同：" + a.majorCluster() + " / " + b.majorCluster());
        }
        if (cfg.isEnforceGradeHomogeneous() && !a.grade().equals(b.grade())) {
            why.add("年级不同：" + a.grade() + " / " + b.grade());
        }
        return why.isEmpty() ? "分块条件不同" : String.join("，", why);
    }

    /** 填报时就能给出的提醒。保存前看到，比等分配结果出来再解释有用得多。 */
    private static List<String> warningsFor(List<Map<String, Object>> want,
                                            List<Map<String, Object>> avoid) {
        List<String> ws = new ArrayList<>();
        long impossible = want.stream()
                .filter(m -> Boolean.FALSE.equals(m.get("canShareRoom")))
                .count();
        if (impossible > 0) {
            ws.add("你有 " + impossible + " 位「想同住」的同学和你不在同一个分块，"
                    + "正常分配满足不了他们。系统不会假装能分到，"
                    + "这类诉求只有走申诉的跨块试算才有可能。");
        }
        if (want.isEmpty()) {
            ws.add("「想同住」目前是空的。这是对分配结果影响最大的一个输入"
                    + "（互相填 = 双向加分，优先级最高）。");
        }
        return ws;
    }

    private static List<Long> distinct(List<Long> ids) {
        if (ids == null) {
            return new ArrayList<>();
        }
        List<Long> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long id : ids) {
            if (id != null && seen.add(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static List<String> distinctStrings(List<String> xs) {
        if (xs == null) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String x : xs) {
            String t = x == null ? "" : x.trim();
            if (!t.isEmpty() && seen.add(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /**
     * 从库里读出来的性别。解析不了就当空处理而不是抛异常：
     * 一条脏数据不该让整个搜索接口崩掉，分块键会因此算出「性别不同」——
     * 结果是保守的（告诉学生可能分不到），不是乐观的。
     */
    private static Gender gender(Object o) {
        try {
            return Gender.parse(str(o));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static SpecialNeed specialNeed(Object o) {
        String t = str(o).trim().toUpperCase();
        if (t.isEmpty()) {
            return SpecialNeed.NONE;
        }
        try {
            return SpecialNeed.valueOf(t);
        } catch (IllegalArgumentException e) {
            return SpecialNeed.NONE;
        }
    }

    // ==================================================================
    // 申诉
    // ==================================================================

    /** 提起"我想和某人住一起"。三级结论 + 范围透明 + 代价预算，全部来自 AppealService。 */
    public Map<String, Object> appeal(long studentId, List<Long> wantRoomWith, Double costBudget) {
        Long batchId = repo.latestBatchId();
        if (batchId == null) {
            throw new IllegalStateException("还没有分配结果，无法受理诉求");
        }
        DormRequests.Appeal req = new DormRequests.Appeal(studentId, wantRoomWith, costBudget);
        return dormService.appeal(batchId, req);
    }

    /** 我的申诉历史。看得到自己提过什么、结论是什么。 */
    public List<Map<String, Object>> myAppeals(long studentId) {
        return repo.studentAppeals(studentId);
    }

    /** 当前这批分配的配置摘要，给"为什么这样分"页面做背景说明。 */
    public Map<String, Object> policy() {
        Long batchId = repo.latestBatchId();
        ConstraintConfig cfg = batchId == null ? ConstraintConfig.defaults() : repo.configOf(batchId);
        ProblemInstance p = repo.loadProblem().problem();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchId", batchId);
        m.put("hardConstraints", List.of(
                "性别与楼栋匹配",
                "特殊需求房间只住有相应需求的学生",
                "同房间不混住不同年级（特殊需求房间除外）",
                "房间容量不可超"));
        m.put("objective", "在零硬约束违规的前提下，最大化房间内两两相容度的总和，"
                + "同时惩罚低于阈值 0.25 的差对（这是「木桶效应」项：不让任何一对室友过不下去），"
                + "并尽量满足你填的想同住志愿。");
        m.put("blockingPair", "「阻塞对」指两个人互相想住一起却没住成。"
                + "本系统把它压到尽可能低，但在房间必须住满、性别年级又必须分开的条件下，"
                + "降到 0 通常是不可能的——这一点我们如实报告，不假装做到了。");
        m.put("students", p.studentCount());
        m.put("rooms", p.rooms().size());
        m.put("studentSizeNote", "分块求解：按 性别 × 专业大类 × 年级 分成若干块独立求解，"
                + "所以跨块的两个人即使互相填了志愿也未必能住到一起——这类情况可以走申诉的块级试算。");
        return m;
    }
}
