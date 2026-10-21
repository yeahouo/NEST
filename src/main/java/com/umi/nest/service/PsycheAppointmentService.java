package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.PsycheAppointment;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.PsycheAppointmentMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 学工 P14 心理健康 · 预约咨询服务（S16 / S17）。
 *
 * <p>口径出处（《学工平台功能对齐说明》§9）：</p>
 * <ul>
 *   <li>S16《心理健康服务中心预约方式与制度》：四条原则 = 保密性 / 自愿性 / 时间限定（每次 50 分钟）/ 信赖性；
 *       接受预约时间 = 周一至周五 8:00-17:00；非应激 = 必须「提前至少一个工作日」预约；预约需提供联系方式与主要咨询问题；</li>
 *   <li>S17《关于开展线上心理咨询服务的通知》：线上咨询提前半天短信确认；续约可直接向咨询师申请、免去网上预约程序
 *       ⇒ /renew 沿用原预约的 counselor_id 与 mode，新建 requested 并写 renewed_from，不再走「提前一个工作日」判定。</li>
 * </ul>
 *
 * <p>硬性口径：状态变更一律 CAS（影响行数 ≠ 1 → 400「状态已变化，请刷新后重试」）；
 * note 是中心内部备注，学生端不下发；列表也不下发 main_issue 全文；
 * 所有 now() 用 {@code LocalDateTime.now().withNano(0)}（MySQL DATETIME(0) 会四舍五入小数秒）。</p>
 */
@Service
public class PsycheAppointmentService {

    private static final Set<String> STAFF_ROLES = Set.of("teacher", "admin");

    /** office 面询（主5#118）/ online 线上（腾讯会议）。 */
    private static final Set<String> MODES = Set.of("office", "online");

    /** S16 时间限定：每次 50 分钟。 */
    private static final int DEFAULT_MINUTES = 50;

    /** 单次时长上限（防手滑填 8 小时；S16 只给 50 分钟口径，这里放宽到半天）。 */
    private static final int MAX_MINUTES = 240;

    /** S16 接受预约时间：周一至周五 8:00-17:00。 */
    private static final LocalTime WORK_START = LocalTime.of(8, 0);

    private static final LocalTime WORK_END = LocalTime.of(17, 0);

    /** S17：线上咨询提前半天确认（12 小时）——不足时确认成功但要在 message 里提示。 */
    private static final long HALF_DAY_HOURS = 12;

    private static final String ADVANCE_MSG =
            "请至少提前一个工作日预约（如有紧急情况请直接电话联系 0596-6288525）";

    private static final String WINDOW_MSG = "心理咨询预约时间为周一至周五 8:00-17:00";

    private static final String PENDING_MSG = "你已有待处理的预约";

    private static final String CAS_FAIL = "状态已变化，请刷新后重试";

    private static final String HALF_DAY_MSG = "线上咨询需提前半天确认（S17）";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private PsycheAppointmentMapper appointmentMapper;

    @Autowired
    private StudentProfileMapper profileMapper;

    // ---------------------------------------------------------------- 学生端

    /**
     * 申请预约（学生本人）。
     *
     * <p>① expectStart 必须提前至少一个工作日（工作日判定，跳过周六周日、不处理法定节假日）；
     * ② 必须落在周一至周五 8:00-17:00；③ duration 默认 50 分钟；④ 已有 requested/confirmed 时不允许重复建。</p>
     */
    public Result<?> create(Long actorId, String role, Map<String, Object> body) {
        Result<?> deny = guardStudent(actorId, role);
        if (deny != null) {
            return deny;
        }
        Map<String, Object> b = bodyOf(body);
        String mode = trimToNull(text(pick(b, "mode")));
        if (mode == null) {
            mode = "office";
        }
        if (!MODES.contains(mode)) {
            return Result.error("预约方式只接受 online / office");
        }
        String mainIssue = trimToNull(text(pick(b, "mainIssue", "main_issue")));
        if (mainIssue == null) {
            return Result.error("请填写主要咨询问题");
        }
        if (mainIssue.length() > 500) {
            return Result.error("主要咨询问题不能超过 500 个字符");
        }
        String contactPhone = trimToNull(text(pick(b, "contactPhone", "contact_phone")));
        if (contactPhone == null) {
            return Result.error("请填写联系方式");
        }
        if (contactPhone.length() > 32) {
            return Result.error("联系方式不能超过 32 个字符");
        }
        Object rawStart = pick(b, "expectStart", "expect_start");
        LocalDateTime expectStart = parseDateTime(rawStart);
        if (expectStart == null) {
            return Result.error("请选择期望咨询时间（yyyy-MM-dd HH:mm:ss）");
        }
        Integer duration = integer(pick(b, "expectedMinutes", "durationMinutes", "duration_minutes"));
        if (duration == null) {
            duration = DEFAULT_MINUTES;
        }
        Result<?> durationCheck = checkDuration(duration);
        if (durationCheck != null) {
            return durationCheck;
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // ① 提前至少一个工作日（非应激口径）
        if (expectStart.isBefore(addWorkingDays(now, 1))) {
            return Result.error(ADVANCE_MSG);
        }
        // ② 周一至周五 8:00-17:00
        if (!inServiceWindow(expectStart)) {
            return Result.error(WINDOW_MSG);
        }
        // ④ 不允许重复建
        if (appointmentMapper.countPendingByStudent(actorId) > 0) {
            return Result.error(PENDING_MSG);
        }
        StudentProfile profile = profileMapper.findByUserId(actorId);
        PsycheAppointment appointment = new PsycheAppointment();
        appointment.setStudentId(actorId);
        appointment.setCounselorId(profile == null ? null : profile.getCounselorId());
        appointment.setMode(mode);
        appointment.setMainIssue(mainIssue);
        appointment.setContactPhone(contactPhone);
        appointment.setExpectStart(expectStart);
        appointment.setExpectEnd(expectStart.plusMinutes(duration));
        appointment.setDurationMinutes(duration);
        appointment.setStatus("requested");
        appointmentMapper.insert(appointment);
        return Result.success(appointmentMapper.findById(appointment.getId()));
    }

    /** 我的预约（学生本人）：不下发 note（中心内部备注）。 */
    public Result<?> myList(Long actorId, String role) {
        Result<?> deny = guardStudent(actorId, role);
        if (deny != null) {
            return deny;
        }
        List<PsycheAppointment> rows = appointmentMapper.listByStudent(actorId);
        for (PsycheAppointment row : rows) {
            row.setNote(null);
        }
        return Result.success(rows);
    }

    /**
     * 续约（学生本人）：沿用原预约的 counselor_id 与 mode，新建一条 requested 并写 renewed_from。
     *
     * <p>S17：续约可以直接向咨询师申请，免去网上预约程序 ⇒ 不再做「提前一个工作日」判定，
     * 但仍要求落在周一至周五 8:00-17:00 的服务时段内；原预约必须存在且未被取消。</p>
     */
    public Result<?> renew(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> deny = guardStudent(actorId, role);
        if (deny != null) {
            return deny;
        }
        PsycheAppointment origin = appointmentMapper.findById(id);
        if (origin == null) {
            return Result.error(404, "预约不存在");
        }
        if (!origin.getStudentId().equals(actorId)) {
            return Result.error(403, "只能续约自己的预约");
        }
        if ("cancelled".equals(origin.getStatus())) {
            return Result.error("已取消的预约不能续约");
        }
        Map<String, Object> b = bodyOf(body);
        LocalDateTime expectStart = parseDateTime(pick(b, "expectStart", "expect_start"));
        if (expectStart == null) {
            return Result.error("请选择期望咨询时间（yyyy-MM-dd HH:mm:ss）");
        }
        if (!inServiceWindow(expectStart)) {
            return Result.error(WINDOW_MSG);
        }
        Integer duration = integer(pick(b, "expectedMinutes", "durationMinutes", "duration_minutes"));
        if (duration == null) {
            duration = origin.getDurationMinutes() == null ? DEFAULT_MINUTES : origin.getDurationMinutes();
        }
        Result<?> durationCheck = checkDuration(duration);
        if (durationCheck != null) {
            return durationCheck;
        }
        String mainIssue = trimToNull(text(pick(b, "mainIssue", "main_issue")));
        if (mainIssue == null) {
            mainIssue = origin.getMainIssue();
        }
        if (mainIssue != null && mainIssue.length() > 500) {
            return Result.error("主要咨询问题不能超过 500 个字符");
        }
        String contactPhone = trimToNull(text(pick(b, "contactPhone", "contact_phone")));
        if (contactPhone == null) {
            contactPhone = origin.getContactPhone();
        }
        if (contactPhone != null && contactPhone.length() > 32) {
            return Result.error("联系方式不能超过 32 个字符");
        }
        PsycheAppointment appointment = new PsycheAppointment();
        appointment.setStudentId(actorId);
        appointment.setCounselorId(origin.getCounselorId());
        appointment.setMode(origin.getMode());
        appointment.setMainIssue(mainIssue);
        appointment.setContactPhone(contactPhone);
        appointment.setExpectStart(expectStart);
        appointment.setExpectEnd(expectStart.plusMinutes(duration));
        appointment.setDurationMinutes(duration);
        appointment.setStatus("requested");
        appointment.setRenewedFrom(origin.getId());
        appointmentMapper.insert(appointment);
        return Result.success(appointmentMapper.findById(appointment.getId()));
    }

    // ---------------------------------------------------------------- 管理端

    /** 预约列表（admin：全部；teacher：名下学生或自己是咨询师）：列表脱敏，不含 main_issue 全文与 note。 */
    public Result<?> list(Long actorId, String role, String status, String mode, String keyword,
                          int page, int size) {
        Result<?> deny = guardStaff(actorId, role);
        if (deny != null) {
            return deny;
        }
        int p = page < 1 ? 1 : page;
        int s = size < 1 ? 20 : Math.min(size, 200);
        String statusValue = trimToNull(status);
        String modeValue = trimToNull(mode);
        String keywordValue = trimToNull(keyword);
        Long scope = isAdmin(role) ? null : actorId;
        List<PsycheAppointment> rows = appointmentMapper.list(statusValue, modeValue, keywordValue,
                scope, (p - 1) * s, s);
        int total = appointmentMapper.count(statusValue, modeValue, keywordValue, scope);
        for (PsycheAppointment row : rows) {
            row.setStudentName(maskName(row.getStudentName()));
            row.setStudentNo(tail4(row.getStudentNo()));
            row.setMainIssue(null);
            row.setNote(null);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", rows);
        data.put("total", total);
        data.put("page", p);
        data.put("size", s);
        return Result.success(data);
    }

    /**
     * 确认预约（admin/teacher，CAS requested→confirmed）。
     *
     * <p>S17：线上咨询需提前半天短信确认 ⇒ expect_start 距现在不足 12 小时时**仍然成功**，
     * 只在 message 里提示。</p>
     */
    public Result<?> confirm(Long actorId, String role, Long id) {
        Result<?> deny = guardStaff(actorId, role);
        if (deny != null) {
            return deny;
        }
        PsycheAppointment appointment = appointmentMapper.findById(id);
        if (appointment == null) {
            return Result.error(404, "预约不存在");
        }
        if (!canTouch(actorId, role, appointment)) {
            return Result.error(403, "只能处理名下学生的预约");
        }
        // 状态前置校验：只有 requested 能确认。
        // CAS 的 fromStatus 取自「刚读到的状态」，WHERE status=? 恒成立 ⇒ 单靠 CAS 挡不住非法迁移。
        if (appointment.getStatus() != null && !"requested".equals(appointment.getStatus())) {
            return Result.error(CAS_FAIL);
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        int rows = appointmentMapper.confirm(id, appointment.getStatus(), now, actorId);
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        PsycheAppointment updated = appointmentMapper.findById(id);
        boolean tooLate = appointment.getExpectStart() != null
                && appointment.getExpectStart().isBefore(now.plusHours(HALF_DAY_HOURS));
        if (tooLate) {
            return Result.success("已确认预约；" + HALF_DAY_MSG, updated);
        }
        return Result.success(updated);
    }

    /** 完成预约（admin/teacher，CAS confirmed→done，写 finished_at）。 */
    public Result<?> complete(Long actorId, String role, Long id) {
        Result<?> deny = guardStaff(actorId, role);
        if (deny != null) {
            return deny;
        }
        PsycheAppointment appointment = appointmentMapper.findById(id);
        if (appointment == null) {
            return Result.error(404, "预约不存在");
        }
        if (!canTouch(actorId, role, appointment)) {
            return Result.error(403, "只能处理名下学生的预约");
        }
        // 状态前置校验：只有 confirmed 能完成（done 是终态）。
        if (appointment.getStatus() != null && !"confirmed".equals(appointment.getStatus())) {
            return Result.error(CAS_FAIL);
        }
        int rows = appointmentMapper.complete(id, appointment.getStatus(), LocalDateTime.now().withNano(0));
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        return Result.success(appointmentMapper.findById(id));
    }

    /** 取消预约（学生本人 或 admin/teacher，CAS → cancelled，写 cancel_reason）。 */
    public Result<?> cancel(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> deny = guardLogin(actorId);
        if (deny != null) {
            return deny;
        }
        PsycheAppointment appointment = appointmentMapper.findById(id);
        if (appointment == null) {
            return Result.error(404, "预约不存在");
        }
        boolean owner = appointment.getStudentId() != null && appointment.getStudentId().equals(actorId);
        if (!owner && !isStaff(role)) {
            return Result.error(403, "无权取消该预约");
        }
        if (!owner && !canTouch(actorId, role, appointment)) {
            return Result.error(403, "只能处理名下学生的预约");
        }
        Map<String, Object> b = bodyOf(body);
        String reason = trimToNull(text(pick(b, "cancelReason", "cancel_reason", "reason")));
        if (reason == null) {
            reason = owner ? "学生取消" : "管理员取消";
        }
        if (reason.length() > 500) {
            return Result.error("取消原因不能超过 500 个字符");
        }
        // 状态前置校验：只有 requested / confirmed 能取消；done / cancelled / no_show 都是终态。
        if (appointment.getStatus() != null
                && !"requested".equals(appointment.getStatus())
                && !"confirmed".equals(appointment.getStatus())) {
            return Result.error(CAS_FAIL);
        }
        int rows = appointmentMapper.cancel(id, appointment.getStatus(), reason);
        if (rows != 1) {
            return Result.error(CAS_FAIL);
        }
        PsycheAppointment updated = appointmentMapper.findById(id);
        if (!isStaff(role)) {
            updated.setNote(null);
        }
        return Result.success(updated);
    }

    // ---------------------------------------------------------------- 内部：范围与校验

    /** 预约的读写范围：admin 全校；teacher 只能是该生辅导员，或该预约的咨询师是自己。 */
    private boolean canTouch(Long actorId, String role, PsycheAppointment appointment) {
        if (isAdmin(role)) {
            return true;
        }
        if (appointment.getCounselorId() != null && appointment.getCounselorId().equals(actorId)) {
            return true;
        }
        Long studentId = appointment.getStudentId();
        if (studentId == null) {
            return false;
        }
        StudentProfile profile = profileMapper.findByUserId(studentId);
        Long counselorId = profile == null ? null : profile.getCounselorId();
        return counselorId != null && counselorId.equals(actorId);
    }

    /** S16 接受预约时间：周一至周五 8:00-17:00（含 17:00 整）。 */
    private static boolean inServiceWindow(LocalDateTime time) {
        if (time == null) {
            return false;
        }
        DayOfWeek day = time.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime at = time.toLocalTime();
        return !at.isBefore(WORK_START) && !at.isAfter(WORK_END);
    }

    private static Result<?> checkDuration(Integer duration) {
        if (duration == null || duration <= 0 || duration > MAX_MINUTES) {
            return Result.error("单次咨询时长必须在 1~" + MAX_MINUTES + " 分钟之间（默认 " + DEFAULT_MINUTES + " 分钟）");
        }
        return null;
    }

    /**
     * 在工作日意义上加 days 天（跳过周六周日，**不处理法定节假日**）。
     *
     * <p>与 AidReviewService.java:973 / :990 的 addWorkingDays 同一口径（那是包内可见，
     * 这里按 P14 的交付要求照抄一份，保持「跳过周六周日」的判定一致）。</p>
     */
    static LocalDate addWorkingDays(LocalDate start, int days) {
        LocalDate date = start;
        int remain = days;
        while (remain > 0) {
            date = date.plusDays(1);
            DayOfWeek day = date.getDayOfWeek();
            if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
                remain--;
            }
        }
        return date;
    }

    /** 保留时分秒的工作日加法（与 AidReviewService.java:990 同口径）。 */
    static LocalDateTime addWorkingDays(LocalDateTime start, int days) {
        LocalDateTime base = start.withNano(0);
        return LocalDateTime.of(addWorkingDays(base.toLocalDate(), days), base.toLocalTime());
    }

    // ---------------------------------------------------------------- 内部：小工具

    private static Object pick(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            if (body.containsKey(key) && body.get(key) != null) {
                return body.get(key);
            }
        }
        return null;
    }

    private static Map<String, Object> bodyOf(Map<String, Object> body) {
        return body == null ? Collections.emptyMap() : body;
    }

    private static Result<?> guardLogin(Long actorId) {
        return actorId == null ? Result.error(401, "请先登录") : null;
    }

    private static Result<?> guardStaff(Long actorId, String role) {
        Result<?> deny = guardLogin(actorId);
        if (deny != null) {
            return deny;
        }
        return STAFF_ROLES.contains(role) ? null : Result.error(403, "无权访问");
    }

    private static Result<?> guardStudent(Long actorId, String role) {
        Result<?> deny = guardLogin(actorId);
        if (deny != null) {
            return deny;
        }
        return "student".equals(role) ? null : Result.error(403, "仅学生可用");
    }

    private static boolean isAdmin(String role) {
        return "admin".equals(role);
    }

    private static boolean isStaff(String role) {
        return STAFF_ROLES.contains(role);
    }

    /** 姓名脱敏：保留姓（首个字符），其余打码成 *（S16 保密性原则）。 */
    private static String maskName(String name) {
        if (name == null) {
            return "";
        }
        String value = name.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.codePointCount(0, value.length()) <= 1) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, 1)) + "*";
    }

    /** 学号只留后四位。 */
    private static String tail4(String studentNo) {
        if (studentNo == null) {
            return "";
        }
        String value = studentNo.trim();
        if (value.isEmpty()) {
            return "";
        }
        return value.length() <= 4 ? value : value.substring(value.length() - 4);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 支持 "yyyy-MM-dd HH:mm:ss" / ISO 的 "yyyy-MM-ddTHH:mm:ss" / "yyyy-MM-dd"；解析失败返回 null。 */
    private static LocalDateTime parseDateTime(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        s = s.replace('T', ' ');
        if (s.length() == 10) {
            s = s + " 00:00:00";
        } else if (s.length() == 16) {
            s = s + ":00";
        }
        if (s.length() < 19) {
            return null;
        }
        try {
            return LocalDateTime.parse(s.substring(0, 19), STAMP).withNano(0);
        } catch (Exception e) {
            return null;
        }
    }
}
