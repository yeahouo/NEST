package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.LeaveRequest;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.LeaveRequestMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 学工平台·请假 / 离校服务。
 *
 * <p>状态机（唯一权威在这里，不接受前端直接改状态）：
 * pending --批准--> approved --销假--> closed；
 * pending --驳回--> rejected；pending --学生撤回--> cancelled。
 * 每次流转都带 from 条件（{@code updateStatus}），命中 0 行即说明状态已被别人改过 → 返回冲突，不静默成功。</p>
 *
 * <p>可见范围：学生只看自己；teacher / admin 看全部（辅导员按 student_profile.counselor_id 分派，
 * 该字段非空时收件箱自动只显示其名下学生 —— 见 {@link #inbox}）。</p>
 */
@Service
public class LeaveService {

    private static final Logger log = LoggerFactory.getLogger(LeaveService.class);

    private static final Set<String> TYPES = new LinkedHashSet<>(Arrays.asList(
            "personal", "sick", "leave_school", "outing", "internship"));

    private static final Set<String> CAN_APPROVE_ROLES = new HashSet<>(Arrays.asList("teacher", "admin"));

    /** 待审 + 已批准都占用时间（不允许重叠申请） */
    private static final Set<String> OCCUPYING = new HashSet<>(Arrays.asList("pending", "approved"));

    private static final int MAX_DAYS = 30;
    private static final int MAX_DAYS_INTERNSHIP = 180;

    private static final DateTimeFormatter[] FORMATS = new DateTimeFormatter[]{
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    };

    @Autowired
    private LeaveRequestMapper leaveMapper;

    @Autowired
    private StudentProfileMapper profileMapper;

    // ---------------------------------------------------------------- 学生端

    /** 学生提交申请。返回 data = 新假条（含 id）。 */
    public Result<?> submit(Long studentId, Map<String, String> body) {
        if (studentId == null) {
            return Result.error(401, "未登录");
        }
        if (body == null) {
            return Result.error("参数为空");
        }
        String type = trim(body.get("type"));
        if (!TYPES.contains(type)) {
            return Result.error("请假类型不正确");
        }
        String reason = trim(body.get("reason"));
        if (reason.isEmpty() || reason.length() < 4) {
            return Result.error("请把请假事由写清楚（至少 4 个字）");
        }
        if (reason.length() > 500) {
            reason = reason.substring(0, 500);
        }
        String contact = trim(body.get("contact"));
        if (contact.isEmpty()) {
            return Result.error("请填写本人联系电话");
        }
        if (contact.length() > 64) {
            return Result.error("联系电话过长");
        }
        LocalDateTime start = parse(body.get("startAt"));
        LocalDateTime end = parse(body.get("endAt"));
        if (start == null || end == null) {
            return Result.error("请选择开始与结束时间");
        }
        if (!end.isAfter(start)) {
            return Result.error("结束时间必须晚于开始时间");
        }
        if (start.isBefore(LocalDateTime.now().minusHours(2))) {
            return Result.error("开始时间不能早于当前时间 2 小时（补报请走线下登记）");
        }
        if (end.isAfter(LocalDateTime.now().plusDays(200))) {
            return Result.error("结束时间过远");
        }
        BigDecimal days = days(start, end);
        int maxDays = "internship".equals(type) ? MAX_DAYS_INTERNSHIP : MAX_DAYS;
        if (days.compareTo(BigDecimal.valueOf(maxDays)) > 0) {
            return Result.error("单次申请不能超过 " + maxDays + " 天，更久的请分次申请");
        }
        if (overlaps(studentId, start, end)) {
            return Result.error("该时间段已有待审或已批准的申请，请勿重复提交");
        }
        String place = trim(body.get("place"));
        if (place.length() > 128) {
            place = place.substring(0, 128);
        }
        String attachment = trim(body.get("attachment"));
        if (attachment.length() > 255) {
            attachment = attachment.substring(0, 255);
        }

        LeaveRequest leave = new LeaveRequest();
        leave.setStudentId(studentId);
        leave.setType(type);
        leave.setStartAt(start);
        leave.setEndAt(end);
        leave.setDays(days);
        leave.setReason(reason);
        leave.setPlace(place.isEmpty() ? null : place);
        leave.setContact(contact);
        leave.setAttachment(attachment.isEmpty() ? null : attachment);
        leave.setStatus("pending");
        leaveMapper.insert(leave);
        log.info("leave submit student={} id={} type={} days={}", studentId, leave.getId(), type, days);
        return Result.success("已提交，等待辅导员审批", leaveMapper.findById(leave.getId()));
    }

    /** 学生的假条列表（分页）。 */
    public Result<?> myList(Long studentId, String status, int page, int size) {
        if (studentId == null) {
            return Result.error(401, "未登录");
        }
        if (!validStatusFilter(status)) {
            return Result.error("状态筛选不正确");
        }
        size = clamp(size, 1, 100);
        page = Math.max(page, 1);
        List<LeaveRequest> items = leaveMapper.listByStudent(studentId, emptyToNull(status), (page - 1) * size, size);
        int total = leaveMapper.countByStudent(studentId, emptyToNull(status));
        return Result.success(page(items, total, page, size));
    }

    /** 学生撤回自己的待审申请。 */
    public Result<?> cancel(Long studentId, Long id) {
        if (studentId == null) {
            return Result.error(401, "未登录");
        }
        LeaveRequest old = leaveMapper.findById(id);
        if (old == null) {
            return Result.error(404, "申请不存在");
        }
        if (!Objects.equals(old.getStudentId(), studentId)) {
            return Result.error(403, "只能撤回自己的申请");
        }
        if (!"pending".equals(old.getStatus())) {
            return Result.error("只有待审的申请可以撤回（当前：" + statusText(old.getStatus()) + "）");
        }
        if (leaveMapper.cancel(id, studentId) == 0) {
            return Result.error("撤回失败，状态已变化，请刷新后重试");
        }
        return Result.success("已撤回", leaveMapper.findById(id));
    }

    /** 学生自己的状态统计（角标）。 */
    public Result<?> myStats(Long studentId) {
        if (studentId == null) {
            return Result.error(401, "未登录");
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : new String[]{"pending", "approved", "rejected", "cancelled", "closed"}) {
            counts.put(s, leaveMapper.countByStudent(studentId, s));
        }
        return Result.success(counts);
    }

    // ------------------------------------------------------- 辅导员 / 管理端

    /**
     * 收件箱。role=admin 看全部；role=teacher 时若自己在 student_profile 里被点名当辅导员，
     * 则只看名下学生，否则（尚未分派）看全部 —— 避免 P3 之前辅导员看不到任何数据。
     */
    public Result<?> inbox(Long actorId, String role, String status, String keyword, int page, int size) {
        if (!CAN_APPROVE_ROLES.contains(role)) {
            return Result.error(403, "无权限查看审批列表");
        }
        if (!validStatusFilter(status)) {
            return Result.error("状态筛选不正确");
        }
        size = clamp(size, 1, 100);
        page = Math.max(page, 1);
        Long scope = null;
        if ("teacher".equals(role) && profileMapper.countByCounselor(actorId) > 0) {
            scope = actorId;
        }
        String kw = trim(keyword);
        List<LeaveRequest> items = leaveMapper.listInbox(scope, emptyToNull(status), emptyToNull(kw), (page - 1) * size, size);
        int total = leaveMapper.countInbox(scope, emptyToNull(status), emptyToNull(kw));
        Map<String, Object> data = page(items, total, page, size);
        data.put("stats", statsOf(scope));
        return Result.success(data);
    }

    /** 审批：approve 批准 / reject 驳回 / close 销假（close 只对已批准的假条）。 */
    public Result<?> decide(Long actorId, String role, Long id, String action, String note) {
        if (!CAN_APPROVE_ROLES.contains(role)) {
            return Result.error(403, "无权限审批");
        }
        LeaveRequest old = leaveMapper.findById(id);
        if (old == null) {
            return Result.error(404, "申请不存在");
        }
        // 数据级授权：与收件箱同口径 —— 收件箱里看不到的假条，按 id 直调也不能审批。
        if (!inInboxScope(actorId, role, old.getStudentId())) {
            return Result.error(403, "这条申请不在你的名下学生范围内");
        }
        String from;
        String to;
        switch (action == null ? "" : action) {
            case "approve" -> {
                from = "pending";
                to = "approved";
            }
            case "reject" -> {
                from = "pending";
                to = "rejected";
                if (trim(note).isEmpty()) {
                    return Result.error("驳回必须填写原因");
                }
            }
            case "close" -> {
                from = "approved";
                to = "closed";
            }
            default -> {
                return Result.error("审批动作不正确");
            }
        }
        if (!from.equals(old.getStatus())) {
            return Result.error("该申请当前是「" + statusText(old.getStatus()) + "」，不能执行此操作");
        }
        String text = trim(note);
        if (text.length() > 255) {
            text = text.substring(0, 255);
        }
        if (leaveMapper.updateStatus(id, from, to, actorId, text.isEmpty() ? null : text) == 0) {
            return Result.error("审批冲突：该申请已被他人处理，请刷新");
        }
        log.info("leave {} id={} by={} role={}", action, id, actorId, role);
        return Result.success("已处理", leaveMapper.findById(id));
    }

    /**
     * 该假条是否落在操作人的收件箱范围内（与 {@link #inbox} 的 scope 判定逐条一致）：
     * admin 全校；名下有学生的辅导员只限 student_profile.counselor_id = 本人；
     * 名下尚无学生的辅导员沿用收件箱的「看全部」口径。
     */
    private boolean inInboxScope(Long actorId, String role, Long studentId) {
        if (!"teacher".equals(role)) {
            return "admin".equals(role);
        }
        if (actorId == null) {
            return false;
        }
        if (profileMapper.countByCounselor(actorId) == 0) {
            return true;
        }
        StudentProfile profile = studentId == null ? null : profileMapper.findByUserId(studentId);
        return profile != null && Objects.equals(profile.getCounselorId(), actorId);
    }

    /** 收件箱统计（辅导员工作台用）。 */
    public Map<String, Object> statsOf(Long scopeCounselorId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        for (String s : new String[]{"pending", "approved", "rejected", "closed", "cancelled"}) {
            stats.put(s, 0);
        }
        for (Map<String, Object> row : leaveMapper.countGroupByStatus(scopeCounselorId)) {
            Object k = row.get("status");
            Object v = row.get("cnt");
            if (k != null && v != null) {
                stats.put(String.valueOf(k), ((Number) v).intValue());
            }
        }
        int pending = (int) stats.get("pending");
        int approved = (int) stats.get("approved");
        stats.put("pendingTotal", pending);
        stats.put("activeTotal", pending + approved);
        return stats;
    }

    // ------------------------------------------------------------------ 工具

    /** 学生「最近一条」是否正在请假中（学生端首页提示用）。 */
    public LeaveRequest currentOf(Long studentId) {
        if (studentId == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        for (LeaveRequest r : leaveMapper.listByStudent(studentId, null, 0, 50)) {
            if ("approved".equals(r.getStatus()) && r.getStartAt() != null && r.getEndAt() != null
                    && !now.isBefore(r.getStartAt()) && !now.isAfter(r.getEndAt())) {
                return r;
            }
        }
        return null;
    }

    public static String statusText(String status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case "pending":
                return "待审批";
            case "approved":
                return "已批准";
            case "rejected":
                return "已驳回";
            case "cancelled":
                return "已撤回";
            case "closed":
                return "已销假";
            default:
                return status;
        }
    }

    public static String typeText(String type) {
        if (type == null) {
            return "未知";
        }
        switch (type) {
            case "personal":
                return "事假";
            case "sick":
                return "病假";
            case "leave_school":
                return "离校";
            case "outing":
                return "外出";
            case "internship":
                return "实习";
            default:
                return type;
        }
    }

    private boolean overlaps(Long studentId, LocalDateTime start, LocalDateTime end) {
        for (LeaveRequest r : leaveMapper.listByStudent(studentId, null, 0, 200)) {
            if (r.getStartAt() == null || r.getEndAt() == null || !OCCUPYING.contains(r.getStatus())) {
                continue;
            }
            if (r.getStartAt().isBefore(end) && start.isBefore(r.getEndAt())) {
                return true;
            }
        }
        return false;
    }

    private static BigDecimal days(LocalDateTime start, LocalDateTime end) {
        long minutes = Duration.between(start, end).toMinutes();
        if (minutes < 1) {
            minutes = 1;
        }
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(1440), 1, RoundingMode.HALF_UP);
    }

    private static LocalDateTime parse(String text) {
        String s = trim(text);
        if (s.isEmpty()) {
            return null;
        }
        for (DateTimeFormatter f : FORMATS) {
            try {
                return LocalDateTime.parse(s, f);
            } catch (Exception ignore) {
                // 换下一个格式
            }
        }
        return null;
    }

    private static boolean validStatusFilter(String status) {
        String s = trim(status);
        return s.isEmpty() || OCCUPYING.contains(s) || "rejected".equals(s)
                || "cancelled".equals(s) || "closed".equals(s);
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
