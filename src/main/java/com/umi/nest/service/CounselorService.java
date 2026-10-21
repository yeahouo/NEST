package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.AidApplication;
import com.umi.nest.entity.AwardPunishment;
import com.umi.nest.entity.EvalSummary;
import com.umi.nest.entity.LeaveRequest;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.AidApplicationMapper;
import com.umi.nest.mapper.AwardPunishmentMapper;
import com.umi.nest.mapper.EvalSummaryMapper;
import com.umi.nest.mapper.LeaveRequestMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 学工平台 · 辅导员工作台（P7）。
 *
 * <p>全部数据都来自已落地的学工表，本模块不新增业务表，只做「聚合 + 判定」：</p>
 * <ul>
 *   <li>待办：待审批假条（leave_request pending）+ 待审批资助（aid_application pending）
 *       + 当期学期未出分（eval_summary 缺行）。</li>
 *   <li>预警：学籍异常 / 逾期未销假 / 请假频繁 / 综测未出分 / 资助待办积压 / 学生档案未建。</li>
 *   <li>概览：所辖人数、学籍与班级分布、待办数与预警分级计数。</li>
 *   <li>学生画像：一名学生的档案 + 假条统计 + 奖惩 + 资助 + 综测（当前学期与近几个学期）。</li>
 * </ul>
 *
 * <p>范围口径与 P3/P4/P5/P6 完全一致，禁止各模块自创：teacher 且已被分派学生 →
 * 只看名下（student_profile.counselor_id = 本人）；teacher 未分派 → 看全校；admin → 全校。
 * 未分派学生（counselor_id 为空）任何辅导员都可以查看。</p>
 *
 * <p>本模块只有读接口（GET），写操作一律走各业务模块自己的接口，
 * 因此审计拦截器（只记写操作）不会为工作台产生记录，也不需要新的审计类型。</p>
 */
@Service
public class CounselorService {

    private static final Set<String> STAFF_ROLES = new HashSet<>(Arrays.asList("teacher", "admin"));
    private static final Set<String> LEVELS = new HashSet<>(Arrays.asList("high", "medium", "low"));
    private static final Set<String> TODO_TYPES = new HashSet<>(Arrays.asList("all", "leave", "aid", "eval"));
    private static final String[] LEAVE_STATUS = {"pending", "approved", "rejected", "closed", "cancelled"};
    private static final String[] AID_STATUS = {"pending", "approved", "rejected", "cancelled"};
    private static final String[] PROFILE_STATUS = {"active", "suspended", "quit", "graduated"};

    private static final Pattern TERM = Pattern.compile("^\\d{4}-\\d{4}-[12]$");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 每类预警最多扫描多少条（工作台是概览，不做全量导出） */
    private static final int SCAN = 60;
    private static final int MAX_PAGE_SIZE = 100;
    /** 资助待办等待超过这么多天算积压 */
    private static final int STALE_DAYS = 7;
    /** 请假频繁的观察窗口（天）与门限（条） */
    private static final int FREQUENT_DAYS = 30;
    private static final int FREQUENT_MIN = 3;
    /** 学生画像里往回看几个学期（含当前学期） */
    private static final int HISTORY_TERMS = 4;

    private static final Map<String, String> AID_TEXT = new LinkedHashMap<>();

    static {
        AID_TEXT.put("need", "困难认定");
        AID_TEXT.put("grant", "助学金");
        AID_TEXT.put("temporary", "临时困难补助");
        AID_TEXT.put("workstudy", "勤工助学");
    }

    @Autowired
    private StudentProfileMapper profileMapper;

    @Autowired
    private LeaveRequestMapper leaveMapper;

    @Autowired
    private AwardPunishmentMapper awardMapper;

    @Autowired
    private AidApplicationMapper aidMapper;

    @Autowired
    private EvalSummaryMapper summaryMapper;

    // ------------------------------------------------------------------ 概览

    /** 工作台首页：所辖人数、学籍/班级分布、各类待办与预警分级计数 */
    public Result<?> overview(Long actorId, String role, String term) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限查看辅导员工作台");
        }
        Long scope = scopeOf(actorId, role);
        String t = term(term);

        int students = profileMapper.count(scope, null, null, "active");
        int pendingLeaves = leaveMapper.countInbox(scope, "pending", null);
        int pendingAids = aidMapper.countInbox("pending", null, null, scope);
        int scored = summaryMapper.countByTerm(t, null, scope);
        int missingEval = profileMapper.countMissingEval(t, scope);
        List<Map<String, Object>> alerts = collectAlerts(scope, role, t);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("term", t);
        data.put("scope", scope == null ? "all" : "mine");
        data.put("students", students);
        data.put("statusCounts", statCounts(profileMapper.countGroupByStatus(scope), PROFILE_STATUS));
        data.put("classCounts", classCounts(profileMapper.countGroupByClass(scope)));
        data.put("classes", profileMapper.listClassNames(scope));
        data.put("pendingLeaves", pendingLeaves);
        data.put("pendingAids", pendingAids);
        data.put("overdueLeaves", leaveMapper.countOverdue(scope, LocalDateTime.now()));
        data.put("evalScored", scored);
        data.put("missingEval", missingEval);
        data.put("missingProfiles", profileMapper.countMissingProfile());
        data.put("leaveStats", statCounts(leaveMapper.countGroupByStatus(scope), LEAVE_STATUS));
        data.put("aidStats", statCounts(aidMapper.countGroupByStatus(null, scope), AID_STATUS));
        data.put("todoTotal", pendingLeaves + pendingAids + missingEval);
        data.put("alertTotal", alerts.size());
        data.put("alertCounts", countByLevel(alerts));
        data.put("generatedAt", LocalDateTime.now().format(DATETIME));
        return Result.success(data);
    }

    // ------------------------------------------------------------------ 待办

    /**
     * 待办队列。type = all | leave | aid | eval。
     *
     * <p>单类型视图按业务表分页（精确）；合并视图（all）三类各取一页再按提交时间倒序截断，
     * 页脚显示的是三类待办总数。</p>
     */
    public Result<?> todo(Long actorId, String role, String type, String term, int page, int size) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限查看辅导员工作台");
        }
        String want = trim(type);
        if (want.isEmpty()) {
            want = "all";
        }
        if (!TODO_TYPES.contains(want)) {
            return Result.error("待办类型不正确（可选：all / leave / aid / eval）");
        }
        Long scope = scopeOf(actorId, role);
        String t = term(term);
        int p = Math.max(1, page);
        int s = clamp(size, 1, MAX_PAGE_SIZE);
        int offset = offsetOf(p, s);

        int leaveCount = leaveMapper.countInbox(scope, "pending", null);
        int aidCount = aidMapper.countInbox("pending", null, null, scope);
        int evalCount = profileMapper.countMissingEval(t, scope);

        int total;
        List<Map<String, Object>> items = new ArrayList<>();
        if ("leave".equals(want)) {
            total = leaveCount;
            items.addAll(leaveTodo(scope, offset, s));
        } else if ("aid".equals(want)) {
            total = aidCount;
            items.addAll(aidTodo(scope, offset, s));
        } else if ("eval".equals(want)) {
            total = evalCount;
            items.addAll(evalTodo(scope, t, offset, s));
        } else {
            total = leaveCount + aidCount + evalCount;
            items.addAll(leaveTodo(scope, 0, s));
            items.addAll(aidTodo(scope, 0, s));
            items.addAll(evalTodo(scope, t, 0, s));
            // at 是 "yyyy-MM-dd HH:mm" 定长串，字典序即时间序；空串（综测未出分没有时间）排到最后
            items.sort((x, y) -> str(y.get("at")).compareTo(str(x.get("at"))));
            if (items.size() > s) {
                items = new ArrayList<>(items.subList(0, s));
            }
        }

        Map<String, Object> data = page(items, total, p, s);
        data.put("term", t);
        data.put("type", want);
        data.put("scope", scope == null ? "all" : "mine");
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("leave", leaveCount);
        counts.put("aid", aidCount);
        counts.put("eval", evalCount);
        counts.put("total", leaveCount + aidCount + evalCount);
        data.put("counts", counts);
        return Result.success(data);
    }

    private List<Map<String, Object>> leaveTodo(Long scope, int offset, int limit) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (LeaveRequest lr : leaveMapper.listInbox(scope, "pending", null, offset, limit)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "leave");
            item.put("typeText", todoTypeText("leave"));
            item.put("id", lr.getId());
            item.put("studentId", lr.getStudentId());
            item.put("studentName", str(lr.getStudentName()));
            item.put("studentNo", str(lr.getStudentNo()));
            item.put("className", str(lr.getClassName()));
            item.put("title", LeaveService.typeText(lr.getType()) + " · " + days(lr.getDays()) + " 天");
            item.put("detail", date(lr.getStartAt()) + " 至 " + date(lr.getEndAt()) + "　" + clip(lr.getReason(), 60));
            item.put("at", dateTime(lr.getCreatedAt()));
            item.put("url", "leave-review.html?keyword=" + url(str(lr.getStudentNo())));
            items.add(item);
        }
        return items;
    }

    private List<Map<String, Object>> aidTodo(Long scope, int offset, int limit) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (AidApplication a : aidMapper.listInbox("pending", null, null, scope, offset, limit)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "aid");
            item.put("typeText", todoTypeText("aid"));
            item.put("id", a.getId());
            item.put("studentId", a.getStudentId());
            item.put("studentName", str(a.getStudentName()));
            item.put("studentNo", str(a.getStudentNo()));
            item.put("className", str(a.getClassName()));
            item.put("title", aidText(a.getAidType()) + " · " + str(a.getAcademicYear()));
            item.put("detail", clip(a.getReason(), 60));
            item.put("at", dateTime(a.getCreatedAt()));
            item.put("url", "aid-review.html?keyword=" + url(str(a.getStudentNo())));
            items.add(item);
        }
        return items;
    }

    private List<Map<String, Object>> evalTodo(Long scope, String term, int offset, int limit) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (StudentProfile sp : profileMapper.listMissingEval(term, scope, offset, limit)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "eval");
            item.put("typeText", todoTypeText("eval"));
            // 综测未出分没有业务主键，用学生 userId 当标识（前端只用它拼链接，不做写操作）
            item.put("id", sp.getUserId());
            item.put("studentId", sp.getUserId());
            item.put("studentName", name(sp));
            item.put("studentNo", str(sp.getStudentNo()));
            item.put("className", str(sp.getClassName()));
            item.put("title", term + " 综测未出分");
            item.put("detail", "该学期还没有综测汇总：学生自评未提交或辅导员未重算");
            item.put("at", "");
            item.put("url", "eval-review.html?term=" + term);
            items.add(item);
        }
        return items;
    }

    // ------------------------------------------------------------------ 预警

    /** 异动预警。level = 空 | high | medium | low */
    public Result<?> alerts(Long actorId, String role, String level, String term, int page, int size) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限查看辅导员工作台");
        }
        String want = trim(level);
        if (!want.isEmpty() && !LEVELS.contains(want)) {
            return Result.error("预警级别不正确（可选：high / medium / low）");
        }
        Long scope = scopeOf(actorId, role);
        String t = term(term);
        List<Map<String, Object>> all = collectAlerts(scope, role, t);
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> alert : all) {
            if (want.isEmpty() || want.equals(alert.get("level"))) {
                filtered.add(alert);
            }
        }
        int p = Math.max(1, page);
        int s = clamp(size, 1, MAX_PAGE_SIZE);
        int offset = offsetOf(p, s);
        List<Map<String, Object>> slice = offset >= filtered.size()
                ? new ArrayList<>()
                : new ArrayList<>(filtered.subList(offset, Math.min(filtered.size(), offset + s)));

        Map<String, Object> data = page(slice, filtered.size(), p, s);
        data.put("term", t);
        data.put("level", want.isEmpty() ? "all" : want);
        data.put("scope", scope == null ? "all" : "mine");
        data.put("counts", countByLevel(all));
        return Result.success(data);
    }

    /**
     * 六类预警。收集顺序就是展示顺序（级别由高到低），类内各自已排好序，
     * 因此 alerts 列表不需要再全局排序。
     */
    private List<Map<String, Object>> collectAlerts(Long scope, String role, String term) {
        List<Map<String, Object>> list = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // ① 学籍异常（休学 / 退学）——必须跟进
        for (String status : new String[]{"suspended", "quit"}) {
            for (StudentProfile sp : profileMapper.list(scope, null, null, status, 0, SCAN)) {
                list.add(alert("high", "status", sp.getUserId(), name(sp), str(sp.getStudentNo()), str(sp.getClassName()),
                        "学籍状态：" + statusText(status),
                        "该生档案状态是「" + statusText(status) + "」，请核实是否还在校、是否需要办理后续手续",
                        "counselor-students.html?keyword=" + url(str(sp.getStudentNo()))));
            }
        }

        // ② 逾期未销假——已批准但结束时间已过
        for (LeaveRequest lr : leaveMapper.listOverdue(scope, now, 0, SCAN)) {
            long over = lr.getEndAt() == null ? 0 : Math.max(0, Duration.between(lr.getEndAt(), now).toDays());
            list.add(alert("high", "leave_overdue", lr.getStudentId(), str(lr.getStudentName()), str(lr.getStudentNo()), str(lr.getClassName()),
                    "请假逾期未销假",
                    LeaveService.typeText(lr.getType()) + " " + date(lr.getStartAt()) + " 至 " + date(lr.getEndAt())
                            + "，已过 " + over + " 天仍未销假",
                    "leave-review.html?keyword=" + url(str(lr.getStudentNo()))));
        }

        // ③ 请假频繁
        for (Map<String, Object> row : leaveMapper.listFrequentStudents(scope, now.minusDays(FREQUENT_DAYS), FREQUENT_MIN)) {
            list.add(alert("medium", "leave_frequent", longOf(row.get("student_id")),
                    str(row.get("student_name")), str(row.get("student_no")), str(row.get("class_name")),
                    "近 " + FREQUENT_DAYS + " 天请假 " + str(row.get("cnt")) + " 次",
                    "请假频次偏高，建议了解是否遇到实际困难（最近一次 " + str(row.get("last_created")) + "）",
                    "leave-review.html?keyword=" + url(str(row.get("student_no")))));
        }

        // ④ 综测未出分
        for (StudentProfile sp : profileMapper.listMissingEval(term, scope, 0, SCAN)) {
            list.add(alert("medium", "eval_missing", sp.getUserId(), name(sp), str(sp.getStudentNo()), str(sp.getClassName()),
                    term + " 综测未出分",
                    "该学期还没有综测汇总：学生自评未提交或辅导员未重算",
                    "eval-review.html?term=" + term));
        }

        // ⑤ 资助待办积压（待审超过 STALE_DAYS 天）
        List<AidApplication> pending = new ArrayList<>(aidMapper.listInbox("pending", null, null, scope, 0, SCAN));
        pending.sort((x, y) -> {
            LocalDateTime ax = x.getCreatedAt();
            LocalDateTime ay = y.getCreatedAt();
            if (ax == null && ay == null) {
                return 0;
            }
            if (ax == null) {
                return 1;
            }
            if (ay == null) {
                return -1;
            }
            return ax.compareTo(ay);
        });
        for (AidApplication a : pending) {
            if (a.getCreatedAt() == null || a.getCreatedAt().isAfter(now.minusDays(STALE_DAYS))) {
                continue;
            }
            long waited = Math.max(0, Duration.between(a.getCreatedAt(), now).toDays());
            list.add(alert("medium", "aid_stale", a.getStudentId(), str(a.getStudentName()), str(a.getStudentNo()), str(a.getClassName()),
                    "资助申请已等待 " + waited + " 天",
                    aidText(a.getAidType()) + "（" + str(a.getAcademicYear()) + "）待审批 " + waited + " 天未处理",
                    "aid-review.html?keyword=" + url(str(a.getStudentNo()))));
        }

        // ⑥ 学生档案未建（只有管理员能补建，所以只给管理员看）
        if ("admin".equals(role)) {
            for (StudentProfile sp : profileMapper.listMissingProfile(0, SCAN)) {
                list.add(alert("low", "profile_missing", sp.getUserId(), name(sp), str(sp.getStudentNo()), str(sp.getClassName()),
                        "学生档案未建立",
                        "该生还没有学工档案，请在学生名册页用「补建档案」批量生成后再补全信息",
                        "counselor-students.html?keyword=" + url(str(sp.getStudentNo()))));
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ 学生画像

    /** 一名学生的画像：档案 + 假条统计 + 奖惩 + 资助 + 综测（当前学期与近几个学期） */
    public Result<?> portrait(Long actorId, String role, Long studentId) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限查看学生画像");
        }
        if (studentId == null) {
            return Result.error("学生标识不正确");
        }
        StudentProfile profile = profileMapper.findByUserId(studentId);
        if (profile == null) {
            return Result.error(404, "学生档案不存在");
        }
        if (!canTouch(profile, scopeOf(actorId, role))) {
            return Result.error(403, "该学生不在你的名下");
        }
        String t = term(null);

        // 请假：countGroupByStatus 是按辅导员范围统计的，单学生必须逐状态 count
        Map<String, Object> leaves = new LinkedHashMap<>();
        int leaveTotal = 0;
        for (String status : LEAVE_STATUS) {
            int c = leaveMapper.countByStudent(studentId, status);
            leaves.put(status, c);
            leaveTotal += c;
        }
        leaves.put("total", leaveTotal);

        // 奖惩：按 kind 分组一次拿到奖励/处分条数
        Map<String, Object> awards = new LinkedHashMap<>();
        awards.put("award", 0);
        awards.put("punishment", 0);
        int awardTotal = 0;
        List<Map<String, Object>> kindRows = awardMapper.countGroupByKind(studentId, null);
        if (kindRows != null) {
            for (Map<String, Object> row : kindRows) {
                int c = intOf(row.get("cnt"));
                awards.put(str(row.get("kind")), c);
                awardTotal += c;
            }
        }
        awards.put("total", awardTotal);

        Map<String, Object> eval = new LinkedHashMap<>();
        List<Map<String, Object>> history = new ArrayList<>();
        Map<String, Object> current = null;
        for (String term : recentTerms(t, HISTORY_TERMS)) {
            EvalSummary summary = summaryMapper.findByStudentTerm(studentId, term);
            if (summary == null) {
                continue;
            }
            Map<String, Object> item = summaryItem(summary, term);
            history.add(item);
            if (term.equals(t)) {
                current = item;
            }
        }
        eval.put("currentTerm", t);
        eval.put("current", current);
        eval.put("history", history);

        List<Map<String, Object>> recentLeaves = new ArrayList<>();
        for (LeaveRequest lr : leaveMapper.listByStudent(studentId, null, 0, 5)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", lr.getId());
            item.put("type", lr.getType());
            item.put("typeText", LeaveService.typeText(lr.getType()));
            item.put("startAt", date(lr.getStartAt()));
            item.put("endAt", date(lr.getEndAt()));
            item.put("days", days(lr.getDays()));
            item.put("status", lr.getStatus());
            item.put("statusText", leaveStatusText(lr.getStatus()));
            recentLeaves.add(item);
        }

        List<Map<String, Object>> recentAwards = new ArrayList<>();
        for (AwardPunishment ap : awardMapper.list(studentId, null, null, null, null, 0, 5)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", ap.getId());
            item.put("kind", ap.getKind());
            item.put("kindText", "award".equals(ap.getKind()) ? "奖励" : "处分");
            item.put("level", ap.getLevel());
            item.put("title", str(ap.getTitle()));
            item.put("amount", ap.getAmount() == null ? null : ap.getAmount().toPlainString());
            item.put("grantedOn", ap.getGrantedOn() == null ? "" : ap.getGrantedOn().format(DATE));
            item.put("revokedOn", ap.getRevokedOn() == null ? "" : ap.getRevokedOn().format(DATE));
            recentAwards.add(item);
        }

        List<Map<String, Object>> recentAids = new ArrayList<>();
        for (AidApplication a : aidMapper.listMine(studentId, null, 0, 5)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", a.getId());
            item.put("aidType", a.getAidType());
            item.put("aidTypeText", aidText(a.getAidType()));
            item.put("academicYear", str(a.getAcademicYear()));
            item.put("amount", a.getAmount() == null ? null : a.getAmount().toPlainString());
            item.put("status", a.getStatus());
            item.put("statusText", aidStatusText(a.getStatus()));
            recentAids.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profile", profile);
        data.put("term", t);
        data.put("leaves", leaves);
        data.put("awards", awards);
        data.put("aids", statCounts(aidMapper.countGroupByStatus(studentId, null), AID_STATUS));
        data.put("eval", eval);
        data.put("recentLeaves", recentLeaves);
        data.put("recentAwards", recentAwards);
        data.put("recentAids", recentAids);
        return Result.success(data);
    }

    private Map<String, Object> summaryItem(EvalSummary summary, String term) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("term", term);
        item.put("totalScore", summary.getTotalScore() == null ? null : summary.getTotalScore().toPlainString());
        item.put("rankInClass", summary.getRankInClass());
        item.put("gradeLevel", str(summary.getGradeLevel()));
        item.put("gradeText", gradeText(str(summary.getGradeLevel())));
        return item;
    }

    // ------------------------------------------------------------------ 范围与工具

    /** 范围口径：admin 全校；teacher 已分派看名下，未分派看全校 */
    private Long scopeOf(Long actorId, String role) {
        if (!"teacher".equals(role)) {
            return null;
        }
        return profileMapper.countByCounselor(actorId) > 0 ? actorId : null;
    }

    /** 未分派学生任何辅导员都可以查看（与 P3/P4/P5/P6 一致） */
    private static boolean canTouch(StudentProfile profile, Long scope) {
        if (scope == null) {
            return true;
        }
        return profile.getCounselorId() == null || scope.equals(profile.getCounselorId());
    }

    /** 学期：合法则原样用，否则回落到当前学期（口径同 EvalService.currentTerm） */
    private static String term(String value) {
        String v = trim(value);
        return TERM.matcher(v).matches() ? v : currentTerm();
    }

    /** 当前学期：9 月—次年 1 月为 -1，2—7 月为 -2 */
    private static String currentTerm() {
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        int month = today.getMonthValue();
        if (month >= 9) {
            return year + "-" + (year + 1) + "-1";
        }
        if (month >= 2) {
            return (year - 1) + "-" + year + "-2";
        }
        return (year - 1) + "-" + year + "-1";
    }

    /** 从 from 往回数 count 个学期（含 from 本身） */
    private static List<String> recentTerms(String from, int count) {
        List<String> out = new ArrayList<>();
        String cursor = from;
        for (int i = 0; i < count; i++) {
            out.add(cursor);
            int start = Integer.parseInt(cursor.substring(0, 4));
            int part = Integer.parseInt(cursor.substring(10));
            if (part == 2) {
                cursor = start + "-" + (start + 1) + "-1";
            } else {
                cursor = (start - 1) + "-" + start + "-2";
            }
        }
        return out;
    }

    private static Map<String, Object> alert(String level, String type, Long studentId, String studentName,
                                            String studentNo, String className, String title, String detail, String url) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("level", level);
        item.put("levelText", levelText(level));
        item.put("type", type);
        item.put("typeText", alertTypeText(type));
        item.put("studentId", studentId);
        item.put("studentName", studentName);
        item.put("studentNo", studentNo);
        item.put("className", className);
        item.put("title", title);
        item.put("detail", detail);
        item.put("url", url);
        return item;
    }

    private static Map<String, Object> countByLevel(List<Map<String, Object>> alerts) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("high", 0);
        out.put("medium", 0);
        out.put("low", 0);
        for (Map<String, Object> alert : alerts) {
            String level = str(alert.get("level"));
            out.put(level, intOf(out.get(level)) + 1);
        }
        out.put("total", alerts.size());
        return out;
    }

    private static Map<String, Object> statCounts(List<Map<String, Object>> rows, String[] keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : keys) {
            out.put(key, 0);
        }
        int total = 0;
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                int c = intOf(row.get("cnt"));
                out.put(str(row.get("status")), c);
                total += c;
            }
        }
        out.put("total", total);
        return out;
    }

    private static List<Map<String, Object>> classCounts(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("className", str(row.get("class_name")));
            item.put("count", intOf(row.get("cnt")));
            out.add(item);
        }
        return out;
    }

    private static Map<String, Object> page(List<Map<String, Object>> items, int total, int page, int size) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("total", total);
        data.put("page", page);
        data.put("size", size);
        data.put("pages", size > 0 ? (total + size - 1) / size : 0);
        return data;
    }

    private static String name(StudentProfile profile) {
        String name = str(profile.getName()).trim();
        return name.isEmpty() ? str(profile.getUsername()) : name;
    }

    /** 大 page 时 `(page - 1) * size` 会 int 溢出成负数（负数 OFFSET / `subList` 都会抛异常），这里用 long 算再夹上限。 */
    private static int offsetOf(int page, int size) {
        long offset = (long) (Math.max(1, page) - 1) * size;
        return offset > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) offset;
    }

    private static String statusText(String status) {
        if ("active".equals(status)) {
            return "在读";
        }
        if ("suspended".equals(status)) {
            return "休学";
        }
        if ("quit".equals(status)) {
            return "退学";
        }
        if ("graduated".equals(status)) {
            return "毕业";
        }
        return "未知";
    }

    private static String leaveStatusText(String status) {
        if ("pending".equals(status)) {
            return "待审批";
        }
        if ("approved".equals(status)) {
            return "已批准";
        }
        if ("rejected".equals(status)) {
            return "已驳回";
        }
        if ("closed".equals(status)) {
            return "已销假";
        }
        if ("cancelled".equals(status)) {
            return "已撤回";
        }
        return "未知";
    }

    private static String aidStatusText(String status) {
        if ("pending".equals(status)) {
            return "待审批";
        }
        if ("approved".equals(status)) {
            return "已批准";
        }
        if ("rejected".equals(status)) {
            return "已驳回";
        }
        if ("cancelled".equals(status)) {
            return "已撤回";
        }
        return "未知";
    }

    private static String aidText(String aidType) {
        String text = AID_TEXT.get(str(aidType));
        return text == null ? "资助项目" : text;
    }

    private static String gradeText(String grade) {
        if ("excellent".equals(grade)) {
            return "优秀";
        }
        if ("good".equals(grade)) {
            return "良好";
        }
        if ("pass".equals(grade)) {
            return "合格";
        }
        return "不合格";
    }

    private static String levelText(String level) {
        if ("high".equals(level)) {
            return "高";
        }
        if ("medium".equals(level)) {
            return "中";
        }
        return "低";
    }

    private static String alertTypeText(String type) {
        if ("status".equals(type)) {
            return "学籍异常";
        }
        if ("leave_overdue".equals(type)) {
            return "逾期未销假";
        }
        if ("leave_frequent".equals(type)) {
            return "请假频繁";
        }
        if ("eval_missing".equals(type)) {
            return "综测未出分";
        }
        if ("aid_stale".equals(type)) {
            return "资助积压";
        }
        if ("profile_missing".equals(type)) {
            return "档案未建";
        }
        return "异动";
    }

    /** 待办类型的中文小标（前端顶栏徽标直接用它，别再让页面自己拼）。 */
    private static String todoTypeText(String type) {
        if ("leave".equals(type)) {
            return "请假";
        }
        if ("aid".equals(type)) {
            return "资助";
        }
        if ("eval".equals(type)) {
            return "综测";
        }
        return "待办";
    }

    private static String date(LocalDateTime value) {
        return value == null ? "" : value.toLocalDate().format(DATE);
    }

    private static String dateTime(LocalDateTime value) {
        return value == null ? "" : value.format(DATETIME);
    }

    private static String days(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String str(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) value).toLocalDateTime().format(DATETIME);
        }
        // Map 结果集里 MySQL 的 DATETIME 常常直接是 LocalDateTime，String.valueOf 会带出 ISO 的 T
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).format(DATETIME);
        }
        return String.valueOf(value);
    }

    private static int intOf(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(str(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Long longOf(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(str(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String clip(String value, int max) {
        String text = trim(value);
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 链接里的关键字只保留安全字符（学号是数字，这里防手输关键字带特殊字符） */
    private static String url(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : str(value).toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
