package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.AidApplication;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.AidApplicationMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 学工平台·资助申请服务（P5）。
 *
 * <p>状态机（唯一权威在这里）：
 * pending --批准--> approved；pending --驳回--> rejected；pending --学生撤回--> cancelled。
 * 每次流转都带 from 条件（{@link AidApplicationMapper#updateStatus}），命中 0 行说明状态已被别人改过 → 返回冲突。</p>
 *
 * <p>可见范围：学生只看自己；辅导员按 {@code student_profile.counselor_id} 只看名下学生（未分派则看全校）；admin 看全校。</p>
 */
@Service
public class AidService {

    private static final Set<String> STAFF_ROLES = new HashSet<>(Arrays.asList("teacher", "admin"));
    // 困难认定（need）由 P11 七步流程（AidReviewService / poverty 页面）独占，
    // 不再是 P5 可申请/可审批的项目，因此不在白名单里。
    private static final Set<String> AID_TYPES = new LinkedHashSet<>(Arrays.asList(
            "grant", "temporary", "workstudy"));
    /** P11 接管的行：aid_type 为 'need'（历史数据可能写成中文 '困难认定'）。 */
    private static final Set<String> REVIEW_TYPES = new LinkedHashSet<>(Arrays.asList(
            "need", "困难认定"));
    private static final String NEED_HINT = "困难认定请到「困难认定」页走七步流程";
    private static final Set<String> LEVELS = new LinkedHashSet<>(Arrays.asList(
            "general", "difficult", "special"));
    private static final Set<String> STATUSES = new LinkedHashSet<>(Arrays.asList(
            "pending", "approved", "rejected", "cancelled"));
    // 「在办/已批准」的判定在 SQL 里做（AidApplicationMapper.countOccupying），不放 Java 常量

    private static final int MIN_REASON = 4;
    private static final int MAX_REASON = 500;
    private static final int MAX_EVIDENCE = 255;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999.99");

    @Autowired
    private AidApplicationMapper aidMapper;

    @Autowired
    private StudentProfileMapper profileMapper;

    /** 学生看自己的资助申请 + 各状态计数。 */
    public Result<?> mine(Long userId, String status, int page, int size) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (!trim(status).isEmpty() && !STATUSES.contains(trim(status))) {
            return Result.error("状态筛选不正确");
        }
        int p = Math.max(1, page);
        int s = clamp(size, 1, 100);
        List<AidApplication> items = aidMapper.listMine(userId, emptyToNull(status), (p - 1) * s, s);
        int total = aidMapper.countMine(userId, emptyToNull(status));
        Map<String, Object> data = page(items, total, p, s);
        data.put("stats", statsOf(userId, null));
        return Result.success(data);
    }

    /** 学生发起一条资助申请。 */
    public Result<?> apply(Long userId, Map<String, String> body) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (body == null || body.isEmpty()) {
            return Result.error("没有要提交的内容");
        }
        String aidType = trim(body.get("aidType"));
        if (REVIEW_TYPES.contains(aidType)) {
            // 困难认定走 P11 的七步流程，不在这里新建，否则同一行会被两套状态机抢管
            return Result.error(NEED_HINT);
        }
        if (!AID_TYPES.contains(aidType)) {
            return Result.error("请选择资助项目（助学金 / 临时困难补助 / 勤工助学）");
        }
        String level = trim(body.get("level"));
        if (!level.isEmpty() && !LEVELS.contains(level)) {
            return Result.error("困难档次不正确（可选：一般困难 / 困难 / 特别困难）");
        }
        String reason = trim(body.get("reason"));
        if (reason.length() < MIN_REASON) {
            return Result.error("请把申请理由写清楚（至少 " + MIN_REASON + " 个字）");
        }
        if (reason.length() > MAX_REASON) {
            return Result.error("申请理由不能超过 " + MAX_REASON + " 个字");
        }
        String evidence = trim(body.get("evidence"));
        if (evidence.length() > MAX_EVIDENCE) {
            return Result.error("证明材料说明不能超过 " + MAX_EVIDENCE + " 个字");
        }
        String year = trim(body.get("academicYear"));
        if (year.isEmpty()) {
            year = currentAcademicYear();
        } else if (!year.matches("\\d{4}-\\d{4}")) {
            return Result.error("学年格式不正确（应为 2026-2027）");
        }
        BigDecimal amount = null;
        String rawAmount = trim(body.get("amount"));
        if (!rawAmount.isEmpty()) {
            try {
                amount = new BigDecimal(rawAmount);
            } catch (NumberFormatException e) {
                return Result.error("金额不正确");
            }
            if (amount.signum() < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
                return Result.error("金额不正确");
            }
        }
        // 同一学年同一项目只允许有一条在办/已批准的申请（在 SQL 里判定，不受历史条数上限影响）
        if (aidMapper.countOccupying(userId, aidType, year) > 0) {
            return Result.error("本学年该项目已有待审或已批准的申请，请勿重复提交");
        }
        AidApplication record = new AidApplication();
        record.setStudentId(userId);
        record.setAidType(aidType);
        record.setLevel(emptyToNull(level));
        record.setAmount(amount);
        record.setReason(reason);
        record.setEvidence(emptyToNull(evidence));
        record.setAcademicYear(year);
        record.setStatus("pending");
        record.setDeleted(0);
        aidMapper.insert(record);
        return Result.success("已提交，等待辅导员审批", aidMapper.findById(record.getId()));
    }

    /** 学生撤回自己的待审申请。 */
    public Result<?> cancel(Long userId, Long id) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        AidApplication record = aidMapper.findById(id);
        if (record == null) {
            return Result.error(404, "申请不存在");
        }
        if (!record.getStudentId().equals(userId)) {
            return Result.error(403, "无权限操作该申请");
        }
        if (isReviewRow(record)) {
            // 认定行归 P11 管；这里拦一道，避免学生撤回把七步流程的中点打断
            return Result.error(NEED_HINT);
        }
        if (aidMapper.cancel(id, userId) == 0) {
            return Result.error("只有待审批的申请可以撤回（当前：" + statusText(record.getStatus()) + "）");
        }
        return Result.success("已撤回", aidMapper.findById(id));
    }

    /** 辅导员/管理员的审批收件箱。 */
    public Result<?> inbox(Long actorId, String role, String status, String aidType, String keyword,
                           String scope, int page, int size) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限查看资助审批列表");
        }
        if (!trim(status).isEmpty() && !STATUSES.contains(trim(status))) {
            return Result.error("状态筛选不正确");
        }
        if (!trim(aidType).isEmpty() && !AID_TYPES.contains(trim(aidType))) {
            return Result.error("资助项目筛选不正确");
        }
        Long scopeCounselorId = scopeOf(actorId, role, scope);
        int p = Math.max(1, page);
        int s = clamp(size, 1, 200);
        List<AidApplication> items = aidMapper.listInbox(emptyToNull(status), emptyToNull(aidType),
                emptyToNull(keyword), scopeCounselorId, (p - 1) * s, s);
        int total = aidMapper.countInbox(emptyToNull(status), emptyToNull(aidType),
                emptyToNull(keyword), scopeCounselorId);
        Map<String, Object> data = page(items, total, p, s);
        data.put("stats", statsOf(null, scopeCounselorId));
        data.put("scope", scopeCounselorId == null ? "all" : "mine");
        return Result.success(data);
    }

    /** 审批：action = approve / reject。驳回必须写原因；批准可核定金额。 */
    public Result<?> decide(Long actorId, String role, Long id, String action, String note, String rawAmount) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限审批");
        }
        String act = trim(action);
        if (!"approve".equals(act) && !"reject".equals(act)) {
            return Result.error("操作不正确");
        }
        AidApplication record = aidMapper.findById(id);
        if (record == null) {
            return Result.error(404, "申请不存在");
        }
        if (!canTouch(actorId, role, record.getStudentId())) {
            return Result.error(403, "该学生不在你的名下");
        }
        if (isReviewRow(record)) {
            // 认定行只看 status 就批准/驳回会把七步流程里的行改成终态，这里必须挡掉
            return Result.error(NEED_HINT);
        }
        String text = trim(note);
        if ("reject".equals(act) && text.length() < 2) {
            return Result.error("驳回必须填写原因");
        }
        if (text.length() > 255) {
            return Result.error("审批意见不能超过 255 个字");
        }
        BigDecimal amount = null;
        String raw = trim(rawAmount);
        if ("approve".equals(act) && !raw.isEmpty()) {
            try {
                amount = new BigDecimal(raw);
            } catch (NumberFormatException e) {
                return Result.error("核定金额不正确");
            }
            if (amount.signum() < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
                return Result.error("核定金额不正确");
            }
        }
        String to = "approve".equals(act) ? "approved" : "rejected";
        if (aidMapper.updateStatus(id, "pending", to, actorId, emptyToNull(text), amount) == 0) {
            return Result.error("该申请当前是「" + statusText(record.getStatus()) + "」，不能执行此操作");
        }
        return Result.success("approve".equals(act) ? "已批准" : "已驳回", aidMapper.findById(id));
    }

    /** 资助态势：各状态条数 + 合计。studentId 与 scopeCounselorId 二选一（都空=全校）。 */
    public Map<String, Object> statsOf(Long studentId, Long scopeCounselorId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        for (String s : STATUSES) {
            stats.put(s, 0);
        }
        stats.put("total", 0);
        for (Map<String, Object> row : aidMapper.countGroupByStatus(studentId, scopeCounselorId)) {
            String status = String.valueOf(row.get("status"));
            int cnt = ((Number) row.get("cnt")).intValue();
            if (STATUSES.contains(status)) {
                stats.put(status, cnt);
            }
            stats.put("total", ((Number) stats.get("total")).intValue() + cnt);
        }
        return stats;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private Long scopeOf(Long actorId, String role, String scope) {
        if ("admin".equals(role) || "all".equals(trim(scope))) {
            return null;
        }
        return profileMapper.countByCounselor(actorId) > 0 ? actorId : null;
    }

    private boolean canTouch(Long actorId, String role, Long studentId) {
        if ("admin".equals(role)) {
            return true;
        }
        StudentProfile profile = profileMapper.findByUserId(studentId);
        if (profile == null || profile.getCounselorId() == null) {
            return true;
        }
        return profile.getCounselorId().equals(actorId);
    }

    /** 该行是否归 P11 困难认定七步流程管（aid_type = need / 困难认定）。 */
    private static boolean isReviewRow(AidApplication record) {
        return record != null && REVIEW_TYPES.contains(trim(record.getAidType()));
    }

    /** 学年以 9 月为界：2026 年 10 月 → 2026-2027。 */
    static String currentAcademicYear() {
        LocalDate now = LocalDate.now();
        int start = now.getMonthValue() >= 9 ? now.getYear() : now.getYear() - 1;
        return start + "-" + (start + 1);
    }

    private static String statusText(String status) {
        switch (status == null ? "" : status) {
            case "pending":
                return "待审批";
            case "approved":
                return "已批准";
            case "rejected":
                return "已驳回";
            case "cancelled":
                return "已撤回";
            default:
                return "未知";
        }
    }

    private static Map<String, Object> page(List<?> items, int total, int page, int size) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("total", total);
        data.put("page", page);
        data.put("size", size);
        data.put("pages", size > 0 ? (total + size - 1) / size : 0);
        return data;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String emptyToNull(String s) {
        String t = trim(s);
        return t.isEmpty() ? null : t;
    }
}
