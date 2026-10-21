package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.AwardPunishment;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.entity.User;
import com.umi.nest.mapper.AwardPunishmentMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import com.umi.nest.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 学工平台·奖励与处分服务（P5）。
 *
 * <p>奖惩是「已发生的事实」，不是流程：只有辅导员/管理员能录入、修改、删除，学生端只读自己的记录。
 * 因此这里没有审批状态机，权限与可见范围就是全部规则：
 * 学生只看自己；teacher 只看自己名下的学生（{@code student_profile.counselor_id}，未分派的学生也放行）；
 * admin 看全校。</p>
 */
@Service
public class AwardPunishmentService {

    private static final Set<String> STAFF_ROLES = new HashSet<>(Arrays.asList("teacher", "admin"));
    private static final Set<String> KINDS = new LinkedHashSet<>(Arrays.asList("award", "punishment"));
    private static final Set<String> LEVELS = new LinkedHashSet<>(Arrays.asList(
            "national", "provincial", "school", "college"));

    private static final int MAX_TITLE = 128;
    private static final int MAX_REASON = 500;
    private static final int MAX_DOC_NO = 64;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999.99");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private AwardPunishmentMapper awardMapper;

    @Autowired
    private StudentProfileMapper profileMapper;

    @Autowired
    private UserMapper userMapper;

    /** 学生看自己的奖惩记录 + 汇总。 */
    public Result<?> mine(Long userId, int page, int size) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        int p = Math.max(1, page);
        int s = clamp(size, 1, 100);
        int offset = (p - 1) * s;
        List<AwardPunishment> items = awardMapper.list(userId, null, null, null, null, offset, s);
        int total = awardMapper.count(userId, null, null, null, null);
        Map<String, Object> data = page(items, total, p, s);
        data.put("stats", statsOf(userId, null));
        return Result.success(data);
    }

    /**
     * 辅导员/管理员查奖惩记录。
     *
     * @param studentId 指定学生（可空）；scope=all 时管理员/辅导员看全校
     */
    public Result<?> list(Long actorId, String role, String kind, String level, String keyword,
                          Long studentId, String scope, int page, int size) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限查看奖惩记录");
        }
        if (!trim(kind).isEmpty() && !KINDS.contains(trim(kind))) {
            return Result.error("奖惩类型筛选不正确");
        }
        if (!trim(level).isEmpty() && !LEVELS.contains(trim(level))) {
            return Result.error("级别筛选不正确");
        }
        Long scopeCounselorId = scopeOf(actorId, role, scope);
        if (studentId != null && !canTouch(actorId, role, studentId)) {
            return Result.error(403, "该学生不在你的名下");
        }
        int p = Math.max(1, page);
        int s = clamp(size, 1, 200);
        int offset = (p - 1) * s;
        List<AwardPunishment> items = awardMapper.list(studentId, emptyToNull(kind), emptyToNull(level),
                emptyToNull(keyword), scopeCounselorId, offset, s);
        int total = awardMapper.count(studentId, emptyToNull(kind), emptyToNull(level),
                emptyToNull(keyword), scopeCounselorId);
        Map<String, Object> data = page(items, total, p, s);
        data.put("stats", statsOf(studentId, scopeCounselorId));
        data.put("scope", scopeCounselorId == null ? "all" : "mine");
        return Result.success(data);
    }

    /** 新增一条奖惩记录。 */
    public Result<?> create(Long actorId, String role, Map<String, String> body) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限录入奖惩记录");
        }
        AwardPunishment record = new AwardPunishment();
        Long studentId = parseId(body == null ? null : body.get("studentId"));
        if (studentId == null) {
            return Result.error("请选择学生");
        }
        User student = userMapper.findById(studentId);
        if (student == null || !"student".equals(student.getRole())) {
            return Result.error("指定的学生不存在");
        }
        if (!canTouch(actorId, role, studentId)) {
            return Result.error(403, "该学生不在你的名下");
        }
        record.setStudentId(studentId);
        record.setOperatorId(actorId);
        Result<?> bad = fill(record, body);
        if (bad != null) {
            return bad;
        }
        record.setDeleted(0);
        awardMapper.insert(record);
        return Result.success("已录入", awardMapper.findById(record.getId()));
    }

    /** 修改一条奖惩记录（学生不可换人，避免把记录搬到别人名下）。 */
    public Result<?> update(Long actorId, String role, Long id, Map<String, String> body) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限修改奖惩记录");
        }
        if (body == null || body.isEmpty()) {
            return Result.error("没有要保存的内容");
        }
        AwardPunishment record = awardMapper.findById(id);
        if (record == null) {
            return Result.error(404, "奖惩记录不存在");
        }
        if (!canTouch(actorId, role, record.getStudentId())) {
            return Result.error(403, "该学生不在你的名下");
        }
        Result<?> bad = fill(record, body);
        if (bad != null) {
            return bad;
        }
        if (awardMapper.update(record) == 0) {
            return Result.error("保存失败，记录可能已被删除");
        }
        return Result.success("已保存", awardMapper.findById(id));
    }

    /** 删除（软删）一条奖惩记录。 */
    public Result<?> delete(Long actorId, String role, Long id) {
        if (actorId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限删除奖惩记录");
        }
        AwardPunishment record = awardMapper.findById(id);
        if (record == null) {
            return Result.error(404, "奖惩记录不存在");
        }
        if (!canTouch(actorId, role, record.getStudentId())) {
            return Result.error(403, "该学生不在你的名下");
        }
        awardMapper.softDelete(id);
        return Result.success("已删除", null);
    }

    /** 奖惩汇总：award / punishment 各自条数。 */
    public Map<String, Object> statsOf(Long studentId, Long scopeCounselorId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("award", 0);
        stats.put("punishment", 0);
        stats.put("total", 0);
        for (Map<String, Object> row : awardMapper.countGroupByKind(studentId, scopeCounselorId)) {
            String kind = String.valueOf(row.get("kind"));
            int cnt = ((Number) row.get("cnt")).intValue();
            if (KINDS.contains(kind)) {
                stats.put(kind, cnt);
            }
            stats.put("total", ((Number) stats.get("total")).intValue() + cnt);
        }
        return stats;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 把 body 里的可编辑字段写进 record；校验失败返回带原因的 Result，成功返回 null。 */
    private Result<?> fill(AwardPunishment record, Map<String, String> body) {
        if (body == null || body.isEmpty()) {
            return Result.error("没有要保存的内容");
        }
        String kind = trim(body.get("kind"));
        if (!kind.isEmpty()) {
            if (!KINDS.contains(kind)) {
                return Result.error("奖惩类型不正确（可选：奖励 / 处分）");
            }
            record.setKind(kind);
        }
        if (record.getKind() == null) {
            return Result.error("请选择奖惩类型");
        }
        if (body.containsKey("level")) {
            String level = trim(body.get("level"));
            if (!level.isEmpty() && !LEVELS.contains(level)) {
                return Result.error("级别不正确（可选：国家级 / 省级 / 校级 / 院级）");
            }
            record.setLevel(emptyToNull(level));
        }
        if (body.containsKey("title")) {
            String title = trim(body.get("title"));
            if (title.isEmpty()) {
                return Result.error("请填写奖惩名称");
            }
            if (title.length() > MAX_TITLE) {
                return Result.error("奖惩名称不能超过 " + MAX_TITLE + " 个字");
            }
            record.setTitle(title);
        }
        if (record.getTitle() == null) {
            return Result.error("请填写奖惩名称");
        }
        if (body.containsKey("reason")) {
            String reason = trim(body.get("reason"));
            if (reason.length() > MAX_REASON) {
                return Result.error("事由不能超过 " + MAX_REASON + " 个字");
            }
            record.setReason(emptyToNull(reason));
        }
        if (body.containsKey("docNo")) {
            String docNo = trim(body.get("docNo"));
            if (docNo.length() > MAX_DOC_NO) {
                return Result.error("文号不能超过 " + MAX_DOC_NO + " 个字");
            }
            record.setDocNo(emptyToNull(docNo));
        }
        // 处分不涉及金额，显式清空，避免处分记录上挂着奖学金金额
        if ("punishment".equals(record.getKind())) {
            record.setAmount(null);
        } else if (body.containsKey("amount")) {
            String raw = trim(body.get("amount"));
            if (raw.isEmpty()) {
                record.setAmount(null);
            } else {
                try {
                    BigDecimal amount = new BigDecimal(raw);
                    if (amount.signum() < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
                        return Result.error("金额不正确");
                    }
                    record.setAmount(amount);
                } catch (NumberFormatException e) {
                    return Result.error("金额不正确");
                }
            }
        }
        if (body.containsKey("grantedOn")) {
            Result<?> bad = parseDate(body.get("grantedOn"), "日期");
            if (bad != null) {
                return bad;
            }
            record.setGrantedOn(date(body.get("grantedOn")));
        }
        if (body.containsKey("revokedOn")) {
            Result<?> bad = parseDate(body.get("revokedOn"), "解除日期");
            if (bad != null) {
                return bad;
            }
            record.setRevokedOn(date(body.get("revokedOn")));
        }
        if (record.getGrantedOn() == null) {
            record.setGrantedOn(LocalDate.now());
        }
        return null;
    }

    private static Result<?> parseDate(String raw, String label) {
        String s = trim(raw);
        if (s.isEmpty()) {
            return null;
        }
        if (s.length() > 10 || date(s) == null) {
            return Result.error(label + "格式不正确（应为 2026-09-01）");
        }
        return null;
    }

    private static LocalDate date(String raw) {
        String s = trim(raw);
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s, DATE);
        } catch (Exception ignore) {
            return null;
        }
    }

    /** admin 或 scope=all → 全校；teacher 且被分派了学生 → 只看名下；teacher 未分派 → 全校。 */
    private Long scopeOf(Long actorId, String role, String scope) {
        if ("admin".equals(role) || "all".equals(trim(scope))) {
            return null;
        }
        return profileMapper.countByCounselor(actorId) > 0 ? actorId : null;
    }

    /** admin 全过；teacher 对未分派的学生放行，已分派必须是本人。 */
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

    private static Long parseId(String raw) {
        String s = trim(raw);
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
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
