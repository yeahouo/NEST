package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.EvalBonus;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.EvalBonusMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 学工平台·综合素质表现得分 C 的加分申报与审核（P13）。
 *
 * <p><b>C 的口径只在这一处实现</b>（办法第十二条 / 《学生综合素质表现评分参考细则》）：
 * 先按六大类分别累计并各自封顶，再把各类合计相加，最后总封顶 8 分；
 * 惩处扣分（{@code penalty}）单独一类、可以为负、不设上限。</p>
 *
 * <p>大类与上限（可用 {@code nest.rank.bonus.cap.*} 覆盖，默认值取自细则）：
 * academic_competition 6、nonacademic_competition 4、research 4、quality 1、
 * media 4、service 3、penalty 不限。</p>
 *
 * <p>关于 quality 取 1：细则对「劳动技能证书 / 通用素质技能 / 第二课堂讲座（0.1 分每次）/ 打卡与优秀读者」
 * 是逐子项各限 1 分，没有给这一大类的总上限 —— 本实现按最保守的 1 分处理，宁可少算不多算。</p>
 */
@Service
public class EvalBonusService {

    /** 六大类 + 惩处扣分（顺序同时用于出参展示）。 */
    private static final List<String> CATEGORIES = Arrays.asList(
            "academic_competition", "nonacademic_competition", "research",
            "quality", "media", "service", "penalty");

    /** 学期格式，如 2025-2026-1（与 EvalService / WorkstudyService 同一口径）。 */
    private static final Pattern TERM_PATTERN = Pattern.compile("^\\d{4}-\\d{4}-[12]$");

    private static final Map<String, String> CATEGORY_TEXT = new LinkedHashMap<>();

    static {
        CATEGORY_TEXT.put("academic_competition", "学术科技竞赛");
        CATEGORY_TEXT.put("nonacademic_competition", "非学术科技竞赛");
        CATEGORY_TEXT.put("research", "科研成果");
        CATEGORY_TEXT.put("quality", "综合素质");
        CATEGORY_TEXT.put("media", "宣传文化作品");
        CATEGORY_TEXT.put("service", "社会工作");
        CATEGORY_TEXT.put("penalty", "惩处扣分");
    }

    /** 学生可改稿的状态（被驳回后也可以改）。 */
    private static final Set<String> EDITABLE_STATUSES = new LinkedHashSet<>(
            Arrays.asList("draft", "submitted", "rejected"));

    private static final Set<String> REVIEW_STATUSES = new LinkedHashSet<>(
            Arrays.asList("draft", "submitted", "counselor_approved", "rejected"));

    private static final Set<String> STAFF_ROLES = new HashSet<>(Arrays.asList("teacher", "admin"));

    private static final BigDecimal ZERO = new BigDecimal("0");
    private static final BigDecimal POINTS_MAX = new BigDecimal("99.99");
    private static final int MAX_ITEM_NAME = 128;
    private static final int MAX_EVIDENCE = 500;
    private static final int MAX_COMMENT = 255;
    private static final int MIN_REJECT_COMMENT = 4;

    @Value("${nest.rank.bonus.cap.academic-competition:6}")
    private int capAcademicCompetition;
    @Value("${nest.rank.bonus.cap.nonacademic-competition:4}")
    private int capNonacademicCompetition;
    @Value("${nest.rank.bonus.cap.research:4}")
    private int capResearch;
    @Value("${nest.rank.bonus.cap.quality:1}")
    private int capQuality;
    @Value("${nest.rank.bonus.cap.media:4}")
    private int capMedia;
    @Value("${nest.rank.bonus.cap.service:3}")
    private int capService;
    /** 办法第十二条：C 不得超过 8 分。 */
    @Value("${nest.rank.bonus.cap.total:8}")
    private int capTotal;

    @Autowired
    private EvalBonusMapper bonusMapper;

    @Autowired
    private StudentProfileMapper profileMapper;

    // =====================================================================
    // 一、C 的计算（供 RankService 与 /api/rank/my 共用）
    // =====================================================================

    /** 单个大类的累计结果。 */
    public static class CategorySum {
        public String category;
        public String categoryText;
        /** 该大类原始合计（可负，仅 penalty 可能为负）。 */
        public BigDecimal raw = ZERO;
        /** 计入 C 的合计（封顶后）。 */
        public BigDecimal counted = ZERO;
        public boolean capped;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", category);
            m.put("categoryText", categoryText);
            m.put("raw", raw);
            m.put("counted", counted);
            m.put("capped", capped);
            return m;
        }
    }

    /** 一个学生的 C：分类明细 + 总封顶后的合计。 */
    public static class StudentC {
        public BigDecimal total = ZERO;
        public boolean totalCapped;
        /** 被截过的类别（含类别键，总封顶被触发时额外含 "total"）。 */
        public Set<String> cappedCategories = new LinkedHashSet<>();
        public List<CategorySum> categories = new ArrayList<>();
    }

    /**
     * 按学期算出每个已通过申报的学生的 C。
     *
     * <p>一次取全学期已通过记录在内存里分组，避免按学生逐个查库；返回的 Map 里没有记录的学生
     * 视为 C=0（调用方用 {@code getOrDefault} 处理）。</p>
     */
    public Map<Long, StudentC> computeByStudent(String academicTerm) {
        Map<Long, StudentC> result = new LinkedHashMap<>();
        if (academicTerm == null || academicTerm.trim().isEmpty()) {
            return result;
        }
        List<EvalBonus> approved = bonusMapper.listApprovedByTerm(academicTerm.trim());
        if (approved == null || approved.isEmpty()) {
            return result;
        }
        // 先分组累计原始分：studentId → category → 合计
        Map<Long, Map<String, BigDecimal>> rawByStudent = new LinkedHashMap<>();
        for (EvalBonus b : approved) {
            if (b.getStudentId() == null) {
                continue;
            }
            String category = b.getCategory() == null ? "" : b.getCategory();
            BigDecimal points = b.getPoints() == null ? ZERO : b.getPoints();
            rawByStudent.computeIfAbsent(b.getStudentId(), k -> new LinkedHashMap<>())
                    .merge(category, points, BigDecimal::add);
        }
        for (Map.Entry<Long, Map<String, BigDecimal>> entry : rawByStudent.entrySet()) {
            StudentC sc = new StudentC();
            BigDecimal total = ZERO;
            Map<String, BigDecimal> raws = entry.getValue();
            for (String category : CATEGORIES) {
                if (!raws.containsKey(category)) {
                    continue;
                }
                CategorySum cs = new CategorySum();
                cs.category = category;
                cs.categoryText = CATEGORY_TEXT.get(category);
                cs.raw = scale2(raws.get(category));
                BigDecimal cap = capOf(category);
                cs.counted = cs.raw;
                if (cap != null && cs.raw.compareTo(cap) > 0) {
                    cs.counted = cap;
                    cs.capped = true;
                }
                if (cs.capped) {
                    sc.cappedCategories.add(category);
                }
                sc.categories.add(cs);
                total = total.add(cs.counted);
            }
            BigDecimal capAll = BigDecimal.valueOf(capTotal);
            if (total.compareTo(capAll) > 0) {
                total = capAll;
                sc.totalCapped = true;
                sc.cappedCategories.add("total");
            }
            sc.total = scale2(total);
            result.put(entry.getKey(), sc);
        }
        return result;
    }

    /** 一个学生的 C（没有已通过记录＝0）。 */
    public BigDecimal cOf(String academicTerm, Long studentId) {
        StudentC sc = computeByStudent(academicTerm).get(studentId);
        return sc == null ? ZERO : sc.total;
    }

    /** 该学期待审条数（统计用）。 */
    public int pendingCount(String academicTerm) {
        if (academicTerm == null || academicTerm.trim().isEmpty()) {
            return 0;
        }
        return bonusMapper.countSubmittedByTerm(academicTerm.trim());
    }

    // =====================================================================
    // 二、学生端：我的申报
    // =====================================================================

    /** 我的加分申报（含 draft / submitted / rejected / counselor_approved 全部状态）。 */
    public Result<?> my(Long actorId, String role, String academicTerm) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStudent(role)) {
            return Result.error(403, "仅学生可查看本人的加分申报");
        }
        List<EvalBonus> items = bonusMapper.listMine(actorId, trimToNull(academicTerm));
        return Result.success(fillText(items == null ? new ArrayList<EvalBonus>() : items));
    }

    /** 提交一条加分申报，落库即 submitted（不经草稿）。 */
    public Result<?> submit(Long actorId, String role, Map<String, Object> body) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStudent(role)) {
            return Result.error(403, "仅学生可提交加分申报");
        }
        if (body == null) {
            return Result.error(400, "缺少申报内容");
        }
        String academicTerm = trimToNull(str(body, "academicTerm"));
        if (academicTerm == null) {
            return Result.error(400, "请填写综测学期（如 2024-2025-1）");
        }
        if (academicTerm.length() > 16) {
            return Result.error(400, "综测学期不能超过 16 字");
        }
        if (!TERM_PATTERN.matcher(academicTerm).matches()) {
            return Result.error(400, "学期格式不正确（应为 2024-2025-1）");
        }
        String category = trimToNull(str(body, "category"));
        if (category == null || !CATEGORY_TEXT.containsKey(category)) {
            return Result.error(400, "请选择加分大类（" + String.join(" / ", CATEGORY_TEXT.values()) + "）");
        }
        String itemName = trimToNull(str(body, "itemName"));
        if (itemName == null) {
            return Result.error(400, "请填写加分项目名称");
        }
        if (itemName.length() > MAX_ITEM_NAME) {
            return Result.error(400, "加分项目名称不能超过 " + MAX_ITEM_NAME + " 字");
        }
        BigDecimal points = dec(body.get("points"));
        if (points == null) {
            return Result.error(400, "请填写申报分值");
        }
        String problem = pointsProblem(category, points);
        if (problem == null) {
            String evidence = trim(str(body, "evidence"));
            if (evidence != null && evidence.length() > MAX_EVIDENCE) {
                problem = "佐证材料说明不能超过 " + MAX_EVIDENCE + " 字";
            }
        }
        if (problem != null) {
            return Result.error(400, problem);
        }

        EvalBonus bonus = new EvalBonus();
        bonus.setStudentId(actorId);
        bonus.setAcademicTerm(academicTerm);
        bonus.setCategory(category);
        bonus.setItemName(itemName);
        bonus.setPoints(scale2(points));
        bonus.setEvidence(trimToNull(str(body, "evidence")));
        bonus.setStatus("submitted");
        bonusMapper.insert(bonus);
        return Result.success("加分申报已提交，等待辅导员审核", fillText(bonusMapper.findById(bonus.getId())));
    }

    /** 本人修改：草稿、待审核、被驳回三种状态可改（改完一律回到 submitted 重新排队）。 */
    public Result<?> update(Long actorId, String role, Long id, Map<String, Object> body) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStudent(role)) {
            return Result.error(403, "仅学生可修改加分申报");
        }
        if (id == null) {
            return Result.error(400, "申报 ID 不能为空");
        }
        EvalBonus bonus = bonusMapper.findById(id);
        if (bonus == null) {
            return Result.error(404, "加分申报不存在");
        }
        if (!actorId.equals(bonus.getStudentId())) {
            return Result.error(403, "只能修改本人的加分申报");
        }
        if (!EDITABLE_STATUSES.contains(bonus.getStatus())) {
            return Result.error(400, "该申报当前是「" + statusText(bonus.getStatus()) + "」，不能修改");
        }
        if (body != null) {
            if (body.containsKey("academicTerm")) {
                String term = trimToNull(str(body, "academicTerm"));
                if (term == null) {
                    return Result.error(400, "综测学期不能为空");
                }
                if (term.length() > 16) {
                    return Result.error(400, "综测学期不能超过 16 字");
                }
                if (!TERM_PATTERN.matcher(term).matches()) {
                    return Result.error(400, "学期格式不正确（应为 2024-2025-1）");
                }
                bonus.setAcademicTerm(term);
            }
            if (body.containsKey("category")) {
                String category = trimToNull(str(body, "category"));
                if (category == null || !CATEGORY_TEXT.containsKey(category)) {
                    return Result.error(400, "请选择加分大类（" + String.join(" / ", CATEGORY_TEXT.values()) + "）");
                }
                bonus.setCategory(category);
            }
            if (body.containsKey("itemName")) {
                String itemName = trimToNull(str(body, "itemName"));
                if (itemName == null) {
                    return Result.error(400, "请填写加分项目名称");
                }
                if (itemName.length() > MAX_ITEM_NAME) {
                    return Result.error(400, "加分项目名称不能超过 " + MAX_ITEM_NAME + " 字");
                }
                bonus.setItemName(itemName);
            }
            if (body.containsKey("points")) {
                BigDecimal points = dec(body.get("points"));
                if (points == null) {
                    return Result.error(400, "请填写申报分值");
                }
                String problem = pointsProblem(bonus.getCategory(), points);
                if (problem != null) {
                    return Result.error(400, problem);
                }
                bonus.setPoints(scale2(points));
            }
            if (body.containsKey("evidence")) {
                String evidence = trim(str(body, "evidence"));
                if (evidence != null && evidence.length() > MAX_EVIDENCE) {
                    return Result.error(400, "佐证材料说明不能超过 " + MAX_EVIDENCE + " 字");
                }
                bonus.setEvidence(trimToNull(evidence));
            }
        }
        String fromStatus = bonus.getStatus();
        int rows = bonusMapper.updateMine(bonus, fromStatus);
        if (rows != 1) {
            return Result.error(400, "申报状态已变化，请刷新后重试");
        }
        return Result.success("申报已修改，已重新提交审核", fillText(bonusMapper.findById(id)));
    }

    /** 撤回：submitted → draft。 */
    public Result<?> withdraw(Long actorId, String role, Long id) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStudent(role)) {
            return Result.error(403, "仅学生可撤回加分申报");
        }
        if (id == null) {
            return Result.error(400, "申报 ID 不能为空");
        }
        EvalBonus bonus = bonusMapper.findById(id);
        if (bonus == null) {
            return Result.error(404, "加分申报不存在");
        }
        if (!actorId.equals(bonus.getStudentId())) {
            return Result.error(403, "只能撤回本人的加分申报");
        }
        if (!"submitted".equals(bonus.getStatus())) {
            return Result.error(400, "只有待审核的申报可以撤回（当前：" + statusText(bonus.getStatus()) + "）");
        }
        int rows = bonusMapper.withdraw(id, actorId, "submitted");
        if (rows != 1) {
            return Result.error(400, "申报状态已变化，请刷新后重试");
        }
        return Result.success("已撤回", fillText(bonusMapper.findById(id)));
    }

    // =====================================================================
    // 三、教师端：审核
    // =====================================================================

    /** 待审/已审列表：辅导员只看名下学生的申报（名下无人时按 AidService 的口径放开看全校）。 */
    public Result<?> list(Long actorId, String role, String academicTerm, String status,
                          String keyword, int page, int size) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStaff(role)) {
            return Result.error(403, "无权限查看加分审核列表");
        }
        String st = trimToNull(status);
        if (st != null && !REVIEW_STATUSES.contains(st)) {
            return Result.error(400, "状态筛选不正确");
        }
        int p = Math.max(1, page);
        int s = clamp(size, 1, 100);
        Long scope = scopeCounselor(actorId, role);
        List<EvalBonus> items = bonusMapper.listReview(trimToNull(academicTerm), st, trimToNull(keyword),
                scope, (p - 1) * s, s);
        int total = bonusMapper.countReview(trimToNull(academicTerm), st, trimToNull(keyword), scope);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", fillText(items == null ? new ArrayList<EvalBonus>() : items));
        data.put("total", total);
        data.put("page", p);
        data.put("size", s);
        return Result.success(data);
    }

    /**
     * 审核一条申报。
     *
     * <p>辅导员只能审自己名下（{@code student_profile.counselor_id}）的学生，admin 不受限；
     * 驳回必须写清原因（去空白后至少 4 个字），否则学生不知道该改什么。</p>
     */
    public Result<?> review(Long actorId, String role, Long id, Map<String, Object> body) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!isStaff(role)) {
            return Result.error(403, "无权限审核加分申报");
        }
        if (id == null) {
            return Result.error(400, "申报 ID 不能为空");
        }
        EvalBonus bonus = bonusMapper.findById(id);
        if (bonus == null) {
            return Result.error(404, "加分申报不存在");
        }
        if (!"submitted".equals(bonus.getStatus())) {
            return Result.error(400, "该申报当前是「" + statusText(bonus.getStatus()) + "」，不能审核");
        }
        if (!isAdmin(role)) {
            StudentProfile profile = profileMapper.findByUserId(bonus.getStudentId());
            if (profile == null || profile.getCounselorId() == null
                    || !profile.getCounselorId().equals(actorId)) {
                return Result.error(403, "无权处理该学生的加分申报");
            }
        }
        String verdict = trimToNull(str(body == null ? null : body, "verdict"));
        if (!"pass".equals(verdict) && !"reject".equals(verdict)) {
            return Result.error(400, "审核结论只能是 pass 或 reject");
        }
        String comment = trimToNull(str(body, "comment"));
        if (comment != null && comment.length() > MAX_COMMENT) {
            return Result.error(400, "审核意见不能超过 " + MAX_COMMENT + " 字");
        }
        if ("reject".equals(verdict)) {
            if (comment == null || comment.length() < MIN_REJECT_COMMENT) {
                return Result.error(400, "驳回必须写清原因（至少 " + MIN_REJECT_COMMENT + " 个字）");
            }
        }
        boolean pass = "pass".equals(verdict);
        int rows = bonusMapper.review(id, "submitted", pass ? "counselor_approved" : "rejected",
                pass ? null : comment, actorId);
        if (rows != 1) {
            return Result.error(400, "申报状态已变化，请刷新后重试");
        }
        return Result.success(pass ? "已通过，计入综合素质表现得分 C" : "已驳回",
                fillText(bonusMapper.findById(id)));
    }

    // =====================================================================
    // 小工具
    // =====================================================================

    /** 大类上限；penalty 没有上限（返回 null）。 */
    private BigDecimal capOf(String category) {
        switch (category) {
            case "academic_competition":
                return BigDecimal.valueOf(capAcademicCompetition);
            case "nonacademic_competition":
                return BigDecimal.valueOf(capNonacademicCompetition);
            case "research":
                return BigDecimal.valueOf(capResearch);
            case "quality":
                return BigDecimal.valueOf(capQuality);
            case "media":
                return BigDecimal.valueOf(capMedia);
            case "service":
                return BigDecimal.valueOf(capService);
            default:
                return null;
        }
    }

    /** 分值合法性：惩处扣分必须为负，其余大类必须为正，绝对值不超过 99.99。 */
    private static String pointsProblem(String category, BigDecimal points) {
        if (points.compareTo(ZERO) == 0) {
            return "申报分值不能为 0";
        }
        if (points.abs().compareTo(POINTS_MAX) > 0) {
            return "申报分值不能超过 " + POINTS_MAX.toPlainString() + " 分";
        }
        if ("penalty".equals(category)) {
            if (points.compareTo(ZERO) > 0) {
                return "惩处扣分请填写负数（如 -2）";
            }
        } else if (points.compareTo(ZERO) < 0) {
            return "加分分值请填写正数（扣分请选「惩处扣分」大类）";
        }
        return null;
    }

    /**
     * 补上页面直接渲染用的中文名（{@code categoryText} / {@code statusText}，实体非表字段）。
     *
     * <p>列表与详情都过这一道，前端就不必自己维护一份映射（它会退化成本地兜底）。</p>
     */
    public static List<EvalBonus> fillText(List<EvalBonus> rows) {
        if (rows != null) {
            for (EvalBonus row : rows) {
                fillText(row);
            }
        }
        return rows;
    }

    public static EvalBonus fillText(EvalBonus row) {
        if (row != null) {
            row.setCategoryText(categoryText(row.getCategory()));
            row.setStatusText(statusText(row.getStatus()));
        }
        return row;
    }

    /** 大类中文名（未知值原样返回）。 */
    public static String categoryText(String category) {
        String text = CATEGORY_TEXT.get(category);
        return text == null ? category : text;
    }

    /** 状态中文名（未知值原样返回）。 */
    public static String statusText(String status) {
        if (status == null) {
            return "";
        }
        switch (status) {
            case "draft":
                return "草稿";
            case "submitted":
                return "待审核";
            case "counselor_approved":
                return "已通过";
            case "rejected":
                return "已驳回";
            default:
                return status;
        }
    }

    /**
     * 辅导员可见范围：admin 不限；teacher 名下有人时只看名下，名下无人时按 AidService 的既有口径放开看全校。
     */
    private Long scopeCounselor(Long actorId, String role) {
        if (isAdmin(role)) {
            return null;
        }
        return profileMapper.countByCounselor(actorId) > 0 ? actorId : null;
    }

    private static boolean isAdmin(String role) {
        return "admin".equals(role);
    }

    private static boolean isStaff(String role) {
        return role != null && STAFF_ROLES.contains(role);
    }

    private static boolean isStudent(String role) {
        return "student".equals(role);
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) {
            return min;
        }
        return Math.min(v, max);
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null) {
            return null;
        }
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static BigDecimal dec(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String trimToNull(String s) {
        String t = trim(s);
        return (t == null || t.isEmpty()) ? null : t;
    }
}
