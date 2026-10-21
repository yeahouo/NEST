package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.entity.WorkstudyApplication;
import com.umi.nest.entity.WorkstudyPayroll;
import com.umi.nest.entity.WorkstudyPost;
import com.umi.nest.entity.WorkstudyTimesheet;
import com.umi.nest.mapper.StudentProfileMapper;
import com.umi.nest.mapper.WorkstudyApplicationMapper;
import com.umi.nest.mapper.WorkstudyPayrollMapper;
import com.umi.nest.mapper.WorkstudyPostMapper;
import com.umi.nest.mapper.WorkstudyTimesheetMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 勤工助学（学工 P10）业务服务。
 *
 * <p>规则出处：{@code docs/学工平台功能对齐说明.md} §3（《学生勤工助学管理办法》口径）。
 * 服务端是唯一权威，Controller 只做路由与参数透传。</p>
 *
 * <p>硬校验（阻断）：</p>
 * <ul>
 *   <li>同一学生同一学期原则上不能同时在两个及以上固定岗位在岗（第二十一条）</li>
 *   <li>时薪低于下限（临时岗 15 元/小时）、岗位工时上限（周 8 / 月 40）→ 保存但返回 warnings（不阻断）</li>
 *   <li>重复申请同一岗位、非 recruiting 岗位申请、名额已满 → 阻断</li>
 * </ul>
 *
 * <p>角色：学生（申请 / 查工时 / 辞职）、用工单位 = teacher（设岗 / 面试 / 录用 / 记工时 / 考核）、
 * 资助中心 = admin（审批岗位 / 汇总酬金 / 确认发放）。</p>
 */
@Service
public class WorkstudyService {

    /** 用工单位（可设岗、面试、记工时） */
    private static final Set<String> STAFF_ROLES = new HashSet<>(Arrays.asList("teacher", "admin"));

    private static final Set<String> POST_TYPES = new HashSet<>(Arrays.asList("fixed", "temporary"));

    /** 学生可申请的岗位状态（第二十条：招募公告） */
    private static final Set<String> APPLYABLE_STATUS = new HashSet<>(Arrays.asList("recruiting"));

    private static final Set<String> INTERVIEW_RESULTS = new HashSet<>(Arrays.asList("pass", "fail"));

    /** 面试/录用可发起的状态 */
    private static final Set<String> INTERVIEWABLE_STATUS = new HashSet<>(Arrays.asList("pending"));

    private static final Set<String> HIRABLE_STATUS = new HashSet<>(Arrays.asList("interview_passed"));

    private static final Set<String> REJECTABLE_STATUS =
            new HashSet<>(Arrays.asList("pending", "interview_passed", "interview_failed"));

    /** 学生可在这些状态下辞职（第三十一条：书面提前 7 个工作日） */
    private static final Set<String> RESIGNABLE_STATUS = new HashSet<>(Arrays.asList("hired", "interview_passed"));

    private static final Set<String> CONFIRMABLE_STATUS = new HashSet<>(Arrays.asList("submitted"));

    /** 学期格式 2025-2026-1 */
    private static final Pattern TERM_PATTERN = Pattern.compile("^\\d{4}-\\d{4}-[12]$");

    /** 月份格式 2026-10 */
    private static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    /** 临时岗时薪下限（元/小时，第二十三条） */
    private static final BigDecimal MIN_HOURLY_WAGE = new BigDecimal("15");

    /** 每周工时上限（小时，第十六条） */
    private static final BigDecimal MAX_HOURS_PER_WEEK = new BigDecimal("8");

    /** 每月工时上限（小时，第十六条） */
    private static final BigDecimal MAX_HOURS_PER_MONTH = new BigDecimal("40");

    /** 单次工时合理性上限（小时） */
    private static final BigDecimal MAX_SINGLE_HOURS = new BigDecimal("24");

    /** 页码安全上限：page 与 size 的乘积不得超过 int 范围（防 (page-1)*size 溢出为负数） */
    private static final int MAX_PAGE = 100_000_000;

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    @Autowired
    private WorkstudyPostMapper postMapper;

    @Autowired
    private WorkstudyApplicationMapper applicationMapper;

    @Autowired
    private WorkstudyTimesheetMapper timesheetMapper;

    @Autowired
    private WorkstudyPayrollMapper payrollMapper;

    @Autowired
    private StudentProfileMapper profileMapper;

    // =====================================================================
    // 学生端
    // =====================================================================

    /** 岗位列表：学生看招募中的公告（第二十条）；teacher/admin 可筛查全部 */
    public Result<?> posts(Long userId, String role, Integer page, Integer size,
                           String type, String term, String keyword) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        String postType = emptyToNull(type);
        if (postType != null && !POST_TYPES.contains(postType)) {
            return Result.error("岗位类型只支持 fixed / temporary");
        }
        String academicTerm = emptyToNull(term);
        if (academicTerm != null && !TERM_PATTERN.matcher(academicTerm).matches()) {
            return Result.error("学期格式应为 2025-2026-1");
        }
        int p = clampPage(page);
        int s = clamp(size == null ? 20 : size, 1, 100);
        boolean student = "student".equals(role);
        String status = student ? "recruiting" : null;

        int total = postMapper.count(postType, academicTerm, emptyToNull(keyword), status, null);
        List<WorkstudyPost> rows = total == 0 ? new ArrayList<WorkstudyPost>()
                : postMapper.list(postType, academicTerm, emptyToNull(keyword), status, null, s, offsetOf(p, s));

        Map<Long, WorkstudyApplication> mine = new HashMap<Long, WorkstudyApplication>();
        if (student) {
            for (WorkstudyApplication app : safe(applicationMapper.listByStudent(userId, 200, 0))) {
                if (!mine.containsKey(app.getPostId())) {
                    mine.put(app.getPostId(), app);
                }
            }
        }
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (WorkstudyPost post : safe(rows)) {
            items.add(postBrief(post, mine.get(post.getId())));
        }
        Map<String, Object> data = page(items, total, p, s);
        data.put("term", academicTerm == null ? currentTerm() : academicTerm);
        return Result.success(data);
    }

    /** 岗位详情：学生额外带自己的申请摘要（供前端按状态渲染按钮） */
    public Result<?> postDetail(Long userId, String role, Long postId) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (postId == null) {
            return Result.error("岗位不存在");
        }
        WorkstudyPost post = postMapper.findById(postId);
        if (post == null) {
            return Result.error(404, "岗位不存在");
        }
        if (!"recruiting".equals(post.getStatus())
                && !STAFF_ROLES.contains(role)
                && !userId.equals(post.getUnitUserId())) {
            return Result.error("该岗位当前不接受申请");
        }
        WorkstudyApplication my = null;
        if ("student".equals(role)) {
            for (WorkstudyApplication app : safe(applicationMapper.listByStudent(userId, 200, 0))) {
                if (postId.equals(app.getPostId())) {
                    my = app;
                    break;
                }
            }
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("post", postDetailMap(post));
        data.put("my", my == null ? null : applicationBrief(my));
        return Result.success(data);
    }

    /** 学生申请岗位：理由必填；重复 / 非招募中 / 名额满 / 同学期已有固定岗 → 400 */
    @Transactional
    public Result<?> apply(Long userId, String role, Long postId, Map<String, String> body) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (!"student".equals(role)) {
            return Result.error(403, "只有学生可以申请勤工助学岗位");
        }
        if (postId == null) {
            return Result.error(404, "岗位不存在");
        }
        WorkstudyPost post = postMapper.findById(postId);
        if (post == null) {
            return Result.error(404, "岗位不存在");
        }
        String reason = trim(body == null ? null : body.get("reason"));
        if (reason.isEmpty()) {
            return Result.error("请填写申请理由");
        }
        if (reason.length() > 500) {
            return Result.error("申请理由不能超过 500 字");
        }
        if (!APPLYABLE_STATUS.contains(post.getStatus())) {
            return Result.error("该岗位当前不接受申请");
        }
        if (post.getApplyDeadline() != null && LocalDateTime.now().isAfter(post.getApplyDeadline())) {
            return Result.error("该岗位申请已截止");
        }
        if (applicationMapper.countByPostStudent(postId, userId) > 0) {
            return Result.error("你已经申请过该岗位");
        }
        int headcount = post.getHeadcount() == null ? 0 : post.getHeadcount();
        if (applicationMapper.countByPostAndStatus(postId, "hired") >= headcount) {
            return Result.error("该岗位已招满");
        }
        if ("fixed".equals(post.getPostType())
                && hasHiredFixed(userId, post.getAcademicTerm(), null)) {
            return Result.error("同一学期只能在一个固定岗位在岗");
        }

        WorkstudyApplication record = new WorkstudyApplication();
        record.setPostId(postId);
        record.setStudentId(userId);
        record.setAcademicTerm(post.getAcademicTerm());
        record.setReason(reason);
        record.setEvidence(emptyToNull(body.get("evidence")));
        record.setStatus("pending");
        applicationMapper.insert(record);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("id", record.getId());
        data.put("status", "pending");
        data.put("statusText", applicationStatusText("pending"));
        return Result.success("已提交申请，等待用工单位面试", data);
    }

    /** 我的勤工助学：申请 + 在岗 + 汇总 */
    public Result<?> mine(Long userId, String role) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (!"student".equals(role) && !STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限查看勤工助学信息");
        }
        List<WorkstudyApplication> applications = safe(applicationMapper.listByStudent(userId, 100, 0));
        List<Map<String, Object>> applicationItems = new ArrayList<Map<String, Object>>();
        for (WorkstudyApplication app : applications) {
            applicationItems.add(applicationBrief(app));
        }

        List<WorkstudyApplication> hired = safe(applicationMapper.listHiredByStudent(userId, null, null));
        List<Map<String, Object>> jobs = new ArrayList<Map<String, Object>>();
        BigDecimal totalHours = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (WorkstudyApplication app : hired) {
            Map<String, Object> job = new LinkedHashMap<String, Object>();
            WorkstudyPost post = postMapper.findById(app.getPostId());
            BigDecimal perPost = nz(timesheetMapper.sumHours(userId, null, null));
            job.put("postId", app.getPostId());
            job.put("postName", app.getPostName());
            job.put("unit", app.getUnit());
            job.put("postType", app.getPostType());
            job.put("postTypeText", postTypeText(app.getPostType()));
            job.put("hourlyWage", post == null ? null : post.getHourlyWage());
            job.put("academicTerm", app.getAcademicTerm());
            job.put("status", app.getStatus());
            job.put("statusText", applicationStatusText(app.getStatus()));
            job.put("hiredAt", str(app.getHiredAt()));
            job.put("totalHours", strip(perPost));
            jobs.add(job);
            totalHours = totalHours.add(perPost);
            if (post != null && post.getHourlyWage() != null) {
                totalAmount = totalAmount.add(perPost.multiply(post.getHourlyWage()));
            }
        }
        // 已生成的月酬金发放表为准，覆盖上面的估算（办法第二十四条）
        BigDecimal settled = nz(payrollMapper.sumAmount(null, null));
        if (settled.compareTo(BigDecimal.ZERO) > 0) {
            totalAmount = settled;
        }

        int hiredCount = 0;
        for (WorkstudyApplication app : applications) {
            if ("hired".equals(app.getStatus())) {
                hiredCount++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("applied", applications.size());
        summary.put("hired", hiredCount);
        summary.put("totalHours", strip(totalHours));
        summary.put("totalAmount", strip(totalAmount));
        summary.put("term", currentTerm());

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("applications", applicationItems);
        data.put("jobs", jobs);
        data.put("summary", summary);
        return Result.success(data);
    }

    /** 我的工时：按月（缺省全部），带月度/单周超限提示（不阻断） */
    public Result<?> myTimesheets(Long userId, String role, String month) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        String workMonth = emptyToNull(month);
        if (workMonth != null && !MONTH_PATTERN.matcher(workMonth).matches()) {
            return Result.error("月份格式应为 2026-10");
        }
        int total = timesheetMapper.countMine(userId, workMonth);
        List<WorkstudyTimesheet> rows = total == 0 ? new ArrayList<WorkstudyTimesheet>()
                : timesheetMapper.listMine(userId, workMonth, 200, 0);

        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        Map<LocalDate, BigDecimal> weekTotals = new LinkedHashMap<LocalDate, BigDecimal>();
        Set<Long> postIds = new HashSet<Long>();
        for (WorkstudyTimesheet sheet : rows) {
            items.add(timesheetBrief(sheet));
            if (sheet.getWorkDate() != null) {
                LocalDate weekStart = sheet.getWorkDate().with(DayOfWeek.MONDAY);
                BigDecimal sum = weekTotals.get(weekStart);
                weekTotals.put(weekStart, (sum == null ? BigDecimal.ZERO : sum).add(nz(sheet.getHours())));
            }
            if (sheet.getPostId() != null) {
                postIds.add(sheet.getPostId());
            }
        }
        BigDecimal totalHours = timesheetMapper.sumHours(userId, workMonth, null);
        if (totalHours == null) {
            totalHours = BigDecimal.ZERO;
        }
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Long postId : postIds) {
            WorkstudyPost post = postMapper.findById(postId);
            BigDecimal postHours = timesheetMapper.sumHours(userId, workMonth, null);
            if (post != null && post.getHourlyWage() != null && postHours != null) {
                totalAmount = totalAmount.add(postHours.multiply(post.getHourlyWage()));
            }
        }
        boolean overLimit = monthsOver(workMonth, rows) || totalHours.compareTo(MAX_HOURS_PER_MONTH) > 0;
        for (Map.Entry<LocalDate, BigDecimal> entry : weekTotals.entrySet()) {
            if (entry.getValue().compareTo(MAX_HOURS_PER_WEEK) > 0) {
                overLimit = true;
            }
        }

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("items", items);
        data.put("totalHours", strip(totalHours));
        data.put("totalAmount", strip(totalAmount));
        data.put("overLimit", overLimit);
        data.put("month", workMonth == null ? LocalDate.now().format(MONTH_FMT) : workMonth);
        return Result.success(data);
    }

    /** 学生辞职（第三十一条：书面提前 7 个工作日申请，单位签署意见后报中心） */
    @Transactional
    public Result<?> resign(Long userId, String role, Long applicationId, Map<String, String> body) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (applicationId == null) {
            return Result.error(404, "申请不存在");
        }
        WorkstudyApplication record = applicationMapper.findById(applicationId);
        if (record == null) {
            return Result.error(404, "申请不存在");
        }
        if (!userId.equals(record.getStudentId())) {
            return Result.error(403, "无权限操作该申请");
        }
        String reason = trim(body == null ? null : body.get("reason"));
        if (reason.isEmpty()) {
            return Result.error("请填写辞职理由");
        }
        if (reason.length() > 500) {
            return Result.error("辞职理由不能超过 500 字");
        }
        if (!RESIGNABLE_STATUS.contains(record.getStatus())) {
            return Result.error("该申请当前是「" + applicationStatusText(record.getStatus()) + "」，不能申请辞职");
        }
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("id", applicationId);
        params.put("fromStatus", record.getStatus());
        params.put("toStatus", "resigning");
        params.put("resignReason", reason);
        params.put("note", reason);
        int rows = applicationMapper.updateStatus(params);
        if (rows == 0) {
            return Result.error("该申请当前状态已变化，请刷新后重试");
        }
        return Result.success("辞职申请已提交，等待用工单位确认", applicationMapper.findById(applicationId));
    }

    // =====================================================================
    // 用工单位 / 资助中心：岗位管理
    // =====================================================================

    /** 兼容旧签名：不指定学期即不过滤学期 */
    public Result<?> managePosts(Long userId, String role, Integer page, Integer size,
                                 String status, Integer mine) {
        return managePosts(userId, role, page, size, status, mine, null);
    }

    /** 岗位管理列表：teacher 默认只看自己（mine=0 且 admin 才看全部）；term 省略时不过滤学期（跨学期设岗是常态） */
    public Result<?> managePosts(Long userId, String role, Integer page, Integer size,
                                 String status, Integer mine, String term) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限管理岗位");
        }
        String postStatus = emptyToNull(status);
        if (postStatus != null && !postStatusTexts().containsKey(postStatus)) {
            return Result.error("岗位状态不正确");
        }
        int p = clampPage(page);
        int s = clamp(size == null ? 20 : size, 1, 100);
        Long unitUserId = scopeUnitUserId(userId, role, mine);
        String academicTerm = emptyToNull(term);
        if (academicTerm != null && !TERM_PATTERN.matcher(academicTerm).matches()) {
            return Result.error("学期格式应为 2025-2026-1");
        }

        int total = postMapper.count(null, academicTerm, null, postStatus, unitUserId);
        List<WorkstudyPost> rows = total == 0 ? new ArrayList<WorkstudyPost>()
                : postMapper.list(null, academicTerm, null, postStatus, unitUserId, s, offsetOf(p, s));
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (WorkstudyPost post : rows) {
            items.add(postDetailMap(post));
        }
        return Result.success(page(items, total, p, s));
    }

    /** 设岗申报（第十八条）：内容 / 技能要求 / 用工时间 / 用工数量；超限只给 warnings */
    @Transactional
    public Result<?> createPost(Long userId, String role, Map<String, String> body) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限设岗");
        }
        if (body == null || body.isEmpty()) {
            return Result.error("没有要提交的内容");
        }
        String postType = trim(body.get("postType"));
        if (!POST_TYPES.contains(postType)) {
            return Result.error("岗位类型只支持 fixed / temporary");
        }
        String name = trim(body.get("name"));
        if (name.isEmpty()) {
            return Result.error("岗位名称不能为空");
        }
        if (name.length() > 100) {
            return Result.error("岗位名称不能超过 100 字");
        }
        String unit = trim(body.get("unit"));
        if (unit.isEmpty()) {
            return Result.error("用工单位不能为空");
        }
        String academicTerm = trim(body.get("academicTerm"));
        if (!TERM_PATTERN.matcher(academicTerm).matches()) {
            return Result.error("学期格式应为 2025-2026-1");
        }
        Integer headcount = intOrNull(body.get("headcount"));
        if (headcount == null) {
            return Result.error("招聘人数不正确");
        }
        if (headcount < 1) {
            return Result.error("招聘人数至少为 1");
        }
        BigDecimal hourlyWage = decimalOrNull(body.get("hourlyWage"));
        if (hourlyWage == null) {
            return Result.error("时薪不正确");
        }
        if (hourlyWage.compareTo(BigDecimal.ZERO) <= 0) {
            return Result.error("时薪必须大于 0");
        }
        BigDecimal hoursPerWeek = decimalOrNull(body.get("hoursPerWeek"));
        if (hoursPerWeek == null) {
            return Result.error("每周工时不正确");
        }
        if (hoursPerWeek.compareTo(BigDecimal.ZERO) <= 0) {
            return Result.error("每周工时必须大于 0");
        }
        BigDecimal hoursPerMonth = decimalOrNull(body.get("hoursPerMonth"));
        if (hoursPerMonth == null) {
            return Result.error("每月工时不正确");
        }
        if (hoursPerMonth.compareTo(BigDecimal.ZERO) <= 0) {
            return Result.error("每月工时必须大于 0");
        }
        LocalDateTime applyDeadline = dateTimeOrNull(body.get("applyDeadline"));
        LocalDateTime interviewAt = dateTimeOrNull(body.get("interviewAt"));

        WorkstudyPost post = new WorkstudyPost();
        post.setPostType(postType);
        post.setUnit(unit);
        post.setUnitUserId(userId);
        post.setName(name);
        post.setContent(emptyToNull(body.get("content")));
        post.setSkill(emptyToNull(body.get("skill")));
        post.setHeadcount(headcount);
        post.setHiredCount(0);
        post.setHourlyWage(hourlyWage);
        post.setHoursPerWeek(hoursPerWeek);
        post.setHoursPerMonth(hoursPerMonth);
        post.setAcademicTerm(academicTerm);
        post.setApplyDeadline(applyDeadline);
        post.setInterviewAt(interviewAt);
        post.setStatus("draft");
        postMapper.insert(post);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("id", post.getId());
        data.put("status", "draft");
        data.put("statusText", postStatusText("draft"));
        data.put("warnings", wageWarnings(postType, hourlyWage, hoursPerWeek, hoursPerMonth));
        return Result.success("已保存草稿，请提交资助中心审批", data);
    }

    /** 修改岗位：只能改自己的（admin 可改全部）；已招募且有录用时不许改时薪/名额 */
    @Transactional
    public Result<?> updatePost(Long userId, String role, Long postId, Map<String, String> body) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限管理岗位");
        }
        if (postId == null) {
            return Result.error(404, "岗位不存在");
        }
        WorkstudyPost record = postMapper.findById(postId);
        if (record == null) {
            return Result.error(404, "岗位不存在");
        }
        if (!canManagePost(userId, role, record)) {
            return Result.error(403, "无权限修改该岗位");
        }
        if ("closed".equals(record.getStatus())) {
            return Result.error("岗位已关闭，不能再修改");
        }
        if (body == null || body.isEmpty()) {
            return Result.error("没有要提交的内容");
        }

        int hired = applicationMapper.countByPostAndStatus(postId, "hired");
        WorkstudyPost update = new WorkstudyPost();
        update.setId(postId);
        List<String> warnings = new ArrayList<String>();

        if (body.containsKey("postType")) {
            String postType = trim(body.get("postType"));
            if (!POST_TYPES.contains(postType)) {
                return Result.error("岗位类型只支持 fixed / temporary");
            }
            update.setPostType(postType);
        }
        if (body.containsKey("name")) {
            String name = trim(body.get("name"));
            if (name.isEmpty()) {
                return Result.error("岗位名称不能为空");
            }
            update.setName(name);
        }
        if (body.containsKey("unit")) {
            String unit = trim(body.get("unit"));
            if (unit.isEmpty()) {
                return Result.error("用工单位不能为空");
            }
            update.setUnit(unit);
        }
        if (body.containsKey("content")) {
            update.setContent(emptyToNull(body.get("content")));
        }
        if (body.containsKey("skill")) {
            update.setSkill(emptyToNull(body.get("skill")));
        }
        if (body.containsKey("academicTerm")) {
            String academicTerm = trim(body.get("academicTerm"));
            if (!TERM_PATTERN.matcher(academicTerm).matches()) {
                return Result.error("学期格式应为 2025-2026-1");
            }
            update.setAcademicTerm(academicTerm);
        }
        if (body.containsKey("applyDeadline")) {
            update.setApplyDeadline(dateTimeOrNull(body.get("applyDeadline")));
        }
        if (body.containsKey("interviewAt")) {
            update.setInterviewAt(dateTimeOrNull(body.get("interviewAt")));
        }
        if (body.containsKey("hoursPerWeek")) {
            BigDecimal hoursPerWeek = decimalOrNull(body.get("hoursPerWeek"));
            if (hoursPerWeek == null || hoursPerWeek.compareTo(BigDecimal.ZERO) <= 0) {
                return Result.error("每周工时必须大于 0");
            }
            update.setHoursPerWeek(hoursPerWeek);
        }
        if (body.containsKey("hoursPerMonth")) {
            BigDecimal hoursPerMonth = decimalOrNull(body.get("hoursPerMonth"));
            if (hoursPerMonth == null || hoursPerMonth.compareTo(BigDecimal.ZERO) <= 0) {
                return Result.error("每月工时必须大于 0");
            }
            update.setHoursPerMonth(hoursPerMonth);
        }
        if (body.containsKey("headcount")) {
            Integer headcount = intOrNull(body.get("headcount"));
            if (headcount == null || headcount < 1) {
                return Result.error("招聘人数至少为 1");
            }
            if ("recruiting".equals(record.getStatus()) && hired > 0 && !headcount.equals(record.getHeadcount())) {
                return Result.error("岗位已招募且有录用学生，不能修改招聘人数");
            }
            if (headcount < hired) {
                return Result.error("招聘人数不能小于已录用人数");
            }
            update.setHeadcount(headcount);
        }
        if (body.containsKey("hourlyWage")) {
            BigDecimal hourlyWage = decimalOrNull(body.get("hourlyWage"));
            if (hourlyWage == null || hourlyWage.compareTo(BigDecimal.ZERO) <= 0) {
                return Result.error("时薪必须大于 0");
            }
            if ("recruiting".equals(record.getStatus()) && hired > 0 && hourlyWage.compareTo(record.getHourlyWage()) != 0) {
                return Result.error("岗位已招募且有录用学生，不能修改时薪");
            }
            update.setHourlyWage(hourlyWage);
        }

        if (update.getHourlyWage() != null || update.getHoursPerWeek() != null || update.getHoursPerMonth() != null) {
            String type = update.getPostType() == null ? record.getPostType() : update.getPostType();
            BigDecimal wage = update.getHourlyWage() == null ? record.getHourlyWage() : update.getHourlyWage();
            BigDecimal perWeek = update.getHoursPerWeek() == null ? record.getHoursPerWeek() : update.getHoursPerWeek();
            BigDecimal perMonth = update.getHoursPerMonth() == null ? record.getHoursPerMonth() : update.getHoursPerMonth();
            warnings = wageWarnings(type, wage, perWeek, perMonth);
        }

        postMapper.update(update);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("id", postId);
        data.put("warnings", warnings);
        return Result.success(warnings.isEmpty() ? "已保存" : "已保存，但存在需注意的用工条件", data);
    }

    /** 提交审批：draft → pending（第十八条：用工单位不得擅自设置或变更岗位） */
    @Transactional
    public Result<?> submitPost(Long userId, String role, Long postId) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限管理岗位");
        }
        WorkstudyPost record = requirePost(postId);
        if (record == null) {
            return Result.error(404, "岗位不存在");
        }
        if (!canManagePost(userId, role, record)) {
            return Result.error(403, "无权限操作该岗位");
        }
        if (!"draft".equals(record.getStatus())) {
            return Result.error("该岗位当前是「" + postStatusText(record.getStatus()) + "」，不能提交审批");
        }
        int rows = postMapper.updateStatus(postId, "draft", "pending", null, null, null);
        if (rows == 0) {
            return Result.error("该岗位当前状态已变化，请刷新后重试");
        }
        return Result.success("已提交资助中心审批", postMapper.findById(postId));
    }

    /** 岗位审批（仅资助中心 admin）：pending → recruiting / rejected */
    @Transactional
    public Result<?> reviewPost(Long userId, String role, Long postId, Map<String, String> body) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (!"admin".equals(role)) {
            return Result.error(403, "无权限审批岗位");
        }
        WorkstudyPost record = requirePost(postId);
        if (record == null) {
            return Result.error(404, "岗位不存在");
        }
        boolean approve = body != null && "true".equalsIgnoreCase(trim(body.get("approve")));
        String note = emptyToNull(body == null ? null : body.get("note"));
        if (!"pending".equals(record.getStatus())) {
            return Result.error("该岗位当前是「" + postStatusText(record.getStatus()) + "」，不能审批");
        }
        String to = approve ? "recruiting" : "rejected";
        int rows = postMapper.updateStatus(postId, "pending", to, userId, note, LocalDateTime.now());
        if (rows == 0) {
            return Result.error("该岗位当前状态已变化，请刷新后重试");
        }
        return Result.success(approve ? "岗位已通过，进入招募" : "岗位未通过", postMapper.findById(postId));
    }

    /** 关闭岗位（第二十五条终止情形：岗位撤销 / 岗位调整 / 考核不合格等） */
    @Transactional
    public Result<?> closePost(Long userId, String role, Long postId, Map<String, String> body) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限管理岗位");
        }
        WorkstudyPost record = requirePost(postId);
        if (record == null) {
            return Result.error(404, "岗位不存在");
        }
        if (!canManagePost(userId, role, record)) {
            return Result.error(403, "无权限操作该岗位");
        }
        if ("closed".equals(record.getStatus())) {
            return Result.error("该岗位已关闭");
        }
        String note = emptyToNull(body == null ? null : body.get("note"));
        if (note == null) {
            note = "岗位终止（岗位撤销 / 岗位调整 / 考核不合格等情形），停止招募并停发酬劳";
        }
        int rows = postMapper.updateStatus(postId, null, "closed", userId, note, LocalDateTime.now());
        if (rows == 0) {
            return Result.error("该岗位当前状态已变化，请刷新后重试");
        }
        return Result.success("岗位已关闭，停止招募并按办法停发酬劳", postMapper.findById(postId));
    }

    // =====================================================================
    // 用工单位 / 资助中心：申请（面试 / 录用 / 未录用）
    // =====================================================================

    public Result<?> manageApplications(Long userId, String role, Long postId, String status,
                                        Integer page, Integer size) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限查看申请");
        }
        String appStatus = emptyToNull(status);
        if (appStatus != null && !applicationStatusTexts().containsKey(appStatus)) {
            return Result.error("申请状态不正确");
        }
        int p = clampPage(page);
        int s = clamp(size == null ? 20 : size, 1, 100);
        Long unitUserId = "admin".equals(role) ? null : userId;

        int total = applicationMapper.countByPost(postId, appStatus, unitUserId, null);
        List<WorkstudyApplication> rows = total == 0 ? new ArrayList<WorkstudyApplication>()
                : applicationMapper.listByPost(postId, appStatus, unitUserId, null, s, offsetOf(p, s));
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (WorkstudyApplication app : rows) {
            items.add(applicationRow(app));
        }
        return Result.success(page(items, total, p, s));
    }

    /** 面试（第二十二条：用工单位组织面试、考核）：pending → interview_passed / interview_failed */
    @Transactional
    public Result<?> interview(Long userId, String role, Long applicationId, Map<String, String> body) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        WorkstudyApplication record = requireApplication(applicationId);
        if (record == null) {
            return Result.error(404, "申请不存在");
        }
        WorkstudyPost post = postMapper.findById(record.getPostId());
        if (!isUnitActor(userId, role, post)) {
            return Result.error(403, "无权限面试该岗位的申请");
        }
        String result = trim(body == null ? null : body.get("result"));
        if (!INTERVIEW_RESULTS.contains(result)) {
            return Result.error("面试结果只支持 pass / fail");
        }
        if (!INTERVIEWABLE_STATUS.contains(record.getStatus())) {
            return Result.error("该申请当前是「" + applicationStatusText(record.getStatus()) + "」，不能面试");
        }
        String note = emptyToNull(body.get("note"));
        String to = "pass".equals(result) ? "interview_passed" : "interview_failed";
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("id", applicationId);
        params.put("fromStatus", record.getStatus());
        params.put("toStatus", to);
        params.put("interviewResult", result);
        params.put("interviewNote", note);
        int rows = applicationMapper.updateStatus(params);
        if (rows == 0) {
            return Result.error("该申请当前状态已变化，请刷新后重试");
        }
        return Result.success("pass".equals(result) ? "面试通过，可办理录用" : "面试未通过",
                applicationMapper.findById(applicationId));
    }

    /** 录用（第二十二条：签署意见 → 报资助中心备案 → 建档）：再次校验同学期固定岗 ≤ 1 与名额 */
    @Transactional
    public Result<?> hire(Long userId, String role, Long applicationId, Map<String, String> body) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        WorkstudyApplication record = requireApplication(applicationId);
        if (record == null) {
            return Result.error(404, "申请不存在");
        }
        WorkstudyPost post = postMapper.findById(record.getPostId());
        if (!isUnitActor(userId, role, post)) {
            return Result.error(403, "无权限录用该岗位的申请");
        }
        if (!HIRABLE_STATUS.contains(record.getStatus())) {
            return Result.error("该申请当前是「" + applicationStatusText(record.getStatus()) + "」，不能录用");
        }
        int headcount = post.getHeadcount() == null ? 0 : post.getHeadcount();
        if (applicationMapper.countByPostAndStatus(post.getId(), "hired") >= headcount) {
            return Result.error("该岗位已招满");
        }
        if ("fixed".equals(post.getPostType())
                && hasHiredFixed(record.getStudentId(), post.getAcademicTerm(), applicationId)) {
            return Result.error("同一学期只能在一个固定岗位在岗");
        }
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("id", applicationId);
        params.put("fromStatus", record.getStatus());
        params.put("toStatus", "hired");
        params.put("hiredAt", LocalDateTime.now());
        params.put("note", emptyToNull(body == null ? null : body.get("note")));
        int rows = applicationMapper.updateStatus(params);
        if (rows == 0) {
            return Result.error("该申请当前状态已变化，请刷新后重试");
        }
        if (postMapper.updateHiredCount(post.getId(), 1) == 0) {
            throw new IllegalStateException("岗位名额同步失败：" + post.getId());
        }
        return Result.success("已录用，报资助中心备案并建立学生勤工助学档案",
                applicationMapper.findById(applicationId));
    }

    /** 未录用 */
    @Transactional
    public Result<?> reject(Long userId, String role, Long applicationId, Map<String, String> body) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        WorkstudyApplication record = requireApplication(applicationId);
        if (record == null) {
            return Result.error(404, "申请不存在");
        }
        WorkstudyPost post = postMapper.findById(record.getPostId());
        if (!isUnitActor(userId, role, post)) {
            return Result.error(403, "无权限处理该岗位的申请");
        }
        if (!REJECTABLE_STATUS.contains(record.getStatus())) {
            return Result.error("该申请当前是「" + applicationStatusText(record.getStatus()) + "」，不能标记未录用");
        }
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("id", applicationId);
        params.put("fromStatus", record.getStatus());
        params.put("toStatus", "rejected");
        params.put("note", emptyToNull(body == null ? null : body.get("note")));
        int rows = applicationMapper.updateStatus(params);
        if (rows == 0) {
            return Result.error("该申请当前状态已变化，请刷新后重试");
        }
        return Result.success("已标记未录用", applicationMapper.findById(applicationId));
    }

    /**
     * 单位确认辞职（第三十一条）：resigning → resigned，终止后停发酬劳并另行招募。
     * 契约未列出该路由，但不确认则 resigning 无法闭环，故按状态机补齐。
     */
    @Transactional
    public Result<?> confirmResign(Long userId, String role, Long applicationId, Map<String, String> body) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        WorkstudyApplication record = requireApplication(applicationId);
        if (record == null) {
            return Result.error(404, "申请不存在");
        }
        WorkstudyPost post = postMapper.findById(record.getPostId());
        if (!isUnitActor(userId, role, post)) {
            return Result.error(403, "无权限处理该岗位的申请");
        }
        if (!"resigning".equals(record.getStatus())) {
            return Result.error("该申请不在辞职流程中");
        }
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("id", applicationId);
        params.put("fromStatus", "resigning");
        params.put("toStatus", "resigned");
        params.put("note", emptyToNull(body == null ? null : body.get("note")));
        int rows = applicationMapper.updateStatus(params);
        if (rows == 0) {
            return Result.error("该申请当前状态已变化，请刷新后重试");
        }
        postMapper.updateHiredCount(post.getId(), -1);
        return Result.success("已确认离岗，按办法停发酬劳", applicationMapper.findById(applicationId));
    }

    // =====================================================================
    // 工时 + 酬金
    // =====================================================================

    public Result<?> manageTimesheets(Long userId, String role, String month, Long postId,
                                      Long studentId, Integer page, Integer size) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限查看工时");
        }
        String workMonth = emptyToNull(month);
        if (workMonth != null && !MONTH_PATTERN.matcher(workMonth).matches()) {
            return Result.error("月份格式应为 2026-10");
        }
        int p = clampPage(page);
        int s = clamp(size == null ? 20 : size, 1, 100);
        Long unitUserId = "admin".equals(role) ? null : userId;

        int total = timesheetMapper.countManage(workMonth, postId, studentId, unitUserId, null);
        List<WorkstudyTimesheet> rows = total == 0 ? new ArrayList<WorkstudyTimesheet>()
                : timesheetMapper.listManage(workMonth, postId, studentId, unitUserId, null, s, offsetOf(p, s));
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (WorkstudyTimesheet sheet : rows) {
            items.add(timesheetBrief(sheet));
        }
        return Result.success(page(items, total, p, s));
    }

    /** 记录工时（第二十四条）：用工单位填报，超限只写提示位 */
    @Transactional
    public Result<?> createTimesheet(Long userId, String role, Map<String, String> body) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限记录工时");
        }
        if (body == null || body.isEmpty()) {
            return Result.error("没有要提交的内容");
        }
        Long applicationId = longOrNull(body.get("applicationId"));
        if (applicationId == null) {
            return Result.error("申请不存在");
        }
        WorkstudyApplication application = applicationMapper.findById(applicationId);
        if (application == null) {
            return Result.error(404, "申请不存在");
        }
        WorkstudyPost post = postMapper.findById(application.getPostId());
        if (!isUnitActor(userId, role, post)) {
            return Result.error(403, "无权限为该岗位记录工时");
        }
        if (!"hired".equals(application.getStatus())) {
            return Result.error("该学生当前不是在岗状态，不能记录工时");
        }
        String workMonth = trim(body.get("workMonth"));
        if (!MONTH_PATTERN.matcher(workMonth).matches()) {
            return Result.error("用工月份格式应为 2026-10");
        }
        LocalDate workDate = dateOrNull(body.get("workDate"));
        if (workDate == null) {
            return Result.error("用工日期格式应为 2026-10-08");
        }
        if (!workMonth.equals(workDate.format(MONTH_FMT))) {
            return Result.error("用工日期与用工月份不一致");
        }
        BigDecimal hours = decimalOrNull(body.get("hours"));
        if (hours == null) {
            return Result.error("工时数不正确");
        }
        if (hours.compareTo(BigDecimal.ZERO) <= 0) {
            return Result.error("工时数必须大于 0");
        }
        if (hours.compareTo(MAX_SINGLE_HOURS) > 0) {
            return Result.error("单次工时不能超过 24 小时");
        }

        WorkstudyTimesheet sheet = new WorkstudyTimesheet();
        sheet.setApplicationId(applicationId);
        sheet.setPostId(post.getId());
        sheet.setStudentId(application.getStudentId());
        sheet.setWorkMonth(workMonth);
        sheet.setWorkDate(workDate);
        sheet.setHours(hours);
        sheet.setContent(emptyToNull(body.get("content")));
        sheet.setStatus("submitted");
        sheet.setRecorderId(userId);
        sheet.setOverLimit(overLimitFlag(application, workMonth, workDate, hours));
        timesheetMapper.insert(sheet);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("id", sheet.getId());
        data.put("status", "submitted");
        data.put("statusText", timesheetStatusText("submitted"));
        data.put("overLimit", Integer.valueOf(1).equals(sheet.getOverLimit()));
        data.put("overLimitWarning", Integer.valueOf(1).equals(sheet.getOverLimit())
                ? "该工时已超过每周 8 小时 / 每月 40 小时的常规上限（寒暑假可延长），请留意" : null);
        return Result.success("工时已记录，等待确认", data);
    }

    /** 确认工时：submitted → confirmed（只有已确认的工时进月酬金发放表） */
    @Transactional
    public Result<?> confirmTimesheet(Long userId, String role, Long timesheetId) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限确认工时");
        }
        if (timesheetId == null) {
            return Result.error(404, "工时记录不存在");
        }
        WorkstudyTimesheet record = timesheetMapper.findById(timesheetId);
        if (record == null) {
            return Result.error(404, "工时记录不存在");
        }
        WorkstudyPost post = postMapper.findById(record.getPostId());
        if (!isUnitActor(userId, role, post)) {
            return Result.error(403, "无权限确认该工时");
        }
        if (!CONFIRMABLE_STATUS.contains(record.getStatus())) {
            return Result.error("该工时当前是「" + timesheetStatusText(record.getStatus()) + "」，不能确认");
        }
        int rows = timesheetMapper.updateStatus(timesheetId, "submitted", "confirmed");
        if (rows == 0) {
            return Result.error("该工时当前状态已变化，请刷新后重试");
        }
        return Result.success("工时已确认，将进入月酬金发放表", timesheetMapper.findById(timesheetId));
    }

    /** 汇总月酬金（仅资助中心 admin）：只算 confirmed 工时，(学生,岗位,月) 幂等 */
    @Transactional
    public Result<?> generatePayroll(Long userId, String role, Map<String, String> body) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (!"admin".equals(role)) {
            return Result.error(403, "无权限汇总酬金");
        }
        String workMonth = trim(body == null ? null : body.get("workMonth"));
        if (!MONTH_PATTERN.matcher(workMonth).matches()) {
            return Result.error("发放月份格式应为 2026-10");
        }
        List<Map<String, Object>> groups = safe(timesheetMapper.listConfirmedGroups(workMonth));
        BigDecimal total = BigDecimal.ZERO;
        Set<Long> people = new HashSet<Long>();
        int items = 0;
        for (Map<String, Object> group : groups) {
            Long studentId = longOf(group.get("student_id"));
            Long postId = longOf(group.get("post_id"));
            Long applicationId = longOf(group.get("application_id"));
            BigDecimal hours = decimalOf(group.get("hours"));
            if (studentId == null || postId == null || hours == null || hours.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            WorkstudyPost post = postMapper.findById(postId);
            BigDecimal wage = post == null ? null : post.getHourlyWage();
            if (wage == null) {
                continue;
            }
            BigDecimal amount = hours.multiply(wage).setScale(2, RoundingMode.HALF_UP);
            WorkstudyPayroll payroll = new WorkstudyPayroll();
            payroll.setStudentId(studentId);
            payroll.setPostId(postId);
            payroll.setApplicationId(applicationId);
            payroll.setWorkMonth(workMonth);
            payroll.setTotalHours(hours);
            payroll.setHourlyWage(wage);
            payroll.setAmount(amount);
            payroll.setStatus("pending");
            payrollMapper.upsert(payroll);
            total = total.add(amount);
            people.add(studentId);
            items++;
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("month", workMonth);
        data.put("people", people.size());
        data.put("amount", strip(total));
        data.put("items", items);
        return Result.success("已汇总 " + workMonth + " 月酬金发放表", data);
    }

    /** 月酬金发放表查询（第三十四条口径：只汇总 confirmed 工时） */
    public Result<?> listPayroll(Long userId, String role, String workMonth, String status,
                                 Integer page, Integer size) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限查看酬金");
        }
        String month = emptyToNull(workMonth);
        if (month != null && !MONTH_PATTERN.matcher(month).matches()) {
            return Result.error("发放月份格式应为 2026-10");
        }
        String payrollStatus = emptyToNull(status);
        if (payrollStatus != null && !payrollStatusTexts().containsKey(payrollStatus)) {
            return Result.error("酬金状态不正确");
        }
        int p = clampPage(page);
        int s = clamp(size == null ? 20 : size, 1, 100);

        int total = payrollMapper.countList(month, payrollStatus);
        List<WorkstudyPayroll> rows = total == 0 ? new ArrayList<WorkstudyPayroll>()
                : payrollMapper.listByMonth(month, payrollStatus, s, offsetOf(p, s));
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        BigDecimal hours = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;
        Set<Long> people = new HashSet<Long>();
        for (WorkstudyPayroll payroll : rows) {
            items.add(payrollRow(payroll));
            hours = hours.add(nz(payroll.getTotalHours()));
            amount = amount.add(nz(payroll.getAmount()));
            if (payroll.getStudentId() != null) {
                people.add(payroll.getStudentId());
            }
        }
        BigDecimal unpaid = payrollMapper.sumAmount(month, "pending");
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("people", people.size());
        summary.put("hours", strip(hours));
        summary.put("amount", strip(amount));
        summary.put("unpaid", strip(unpaid == null ? BigDecimal.ZERO : unpaid));

        Map<String, Object> data = page(items, total, p, s);
        data.put("workMonth", month);
        data.put("summary", summary);
        return Result.success(data);
    }

    /** 确认发放（仅 admin）：pending → paid，记发放人与发放时间 */
    @Transactional
    public Result<?> payPayroll(Long userId, String role, Long payrollId) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (!"admin".equals(role)) {
            return Result.error(403, "无权限发放酬金");
        }
        if (payrollId == null) {
            return Result.error(404, "酬金记录不存在");
        }
        WorkstudyPayroll record = payrollMapper.findById(payrollId);
        if (record == null) {
            return Result.error(404, "酬金记录不存在");
        }
        if ("paid".equals(record.getStatus())) {
            return Result.error("该笔酬金已发放");
        }
        int rows = payrollMapper.updatePay(payrollId, "pending", userId, LocalDateTime.now());
        if (rows == 0) {
            return Result.error("该笔酬金当前状态已变化，请刷新后重试");
        }
        return Result.success("已标记发放，酬金打入学校统一办理的银行卡", payrollMapper.findById(payrollId));
    }

    // =====================================================================
    // 概览
    // =====================================================================

    public Result<?> overview(Long userId, String role) {
        if (userId == null) {
            return Result.error(401, "未登录");
        }
        if (!STAFF_ROLES.contains(role)) {
            return Result.error(403, "无权限查看勤工助学概览");
        }
        // 概览统计与列表同口径：**不按学期过滤**。列表 /api/workstudy/manage/posts 不传 term 即不过滤
        // （设岗本身可以跨学期），而统计曾按服务端当前学期过滤 —— 同一屏会出现「招募中 0」而列表里
        // 明明有招募中岗位的矛盾（P10 遗留 X4）。term 回显随之置 null，避免前端把统计读成「仅当前学期」。
        String term = null;
        Long unitUserId = "admin".equals(role) ? null : userId;

        Map<String, Object> postCounts = new LinkedHashMap<String, Object>();
        postCounts.put("total", 0);
        postCounts.put("pending", 0);
        postCounts.put("recruiting", 0);
        postCounts.put("closed", 0);
        int postTotal = 0;
        for (Map<String, Object> row : safe(postMapper.countGroupByStatus(term, unitUserId))) {
            String st = str(row.get("status"));
            int cnt = intOf(row.get("cnt"));
            postTotal += cnt;
            if (postCounts.containsKey(st)) {
                postCounts.put(st, cnt);
            }
        }
        postCounts.put("total", postTotal);

        Map<String, Object> appCounts = new LinkedHashMap<String, Object>();
        appCounts.put("pending", 0);
        appCounts.put("interviewed", 0);
        appCounts.put("hired", 0);
        appCounts.put("rejected", 0);
        int interviewed = 0;
        for (Map<String, Object> row : safe(applicationMapper.countGroupByStatus(unitUserId, term))) {
            String st = str(row.get("status"));
            int cnt = intOf(row.get("cnt"));
            if ("pending".equals(st)) {
                appCounts.put("pending", cnt);
            } else if ("interview_passed".equals(st) || "interview_failed".equals(st)) {
                interviewed += cnt;
            } else if ("hired".equals(st) || "resigning".equals(st) || "resigned".equals(st)) {
                appCounts.put("hired", intOf(appCounts.get("hired")) + cnt);
            } else if ("rejected".equals(st)) {
                appCounts.put("rejected", cnt);
            }
        }
        appCounts.put("interviewed", interviewed);

        String month = LocalDate.now().format(MONTH_FMT);
        BigDecimal confirmedHours = BigDecimal.ZERO;
        BigDecimal submittedHours = BigDecimal.ZERO;
        for (Map<String, Object> row : safe(timesheetMapper.sumGroupByStatus(month, unitUserId))) {
            String st = str(row.get("status"));
            BigDecimal hours = decimalOf(row.get("hours"));
            if ("confirmed".equals(st)) {
                confirmedHours = confirmedHours.add(hours);
            } else if ("submitted".equals(st)) {
                submittedHours = submittedHours.add(hours);
            }
        }
        Map<String, Object> timesheetCounts = new LinkedHashMap<String, Object>();
        timesheetCounts.put("month", month);
        timesheetCounts.put("confirmedHours", strip(confirmedHours));
        timesheetCounts.put("submittedHours", strip(submittedHours));
        timesheetCounts.put("overLimit", confirmedHours.compareTo(MAX_HOURS_PER_MONTH) > 0
                || submittedHours.compareTo(MAX_HOURS_PER_MONTH) > 0);

        BigDecimal amount = nz(payrollMapper.sumAmount(month, null));
        BigDecimal unpaid = nz(payrollMapper.sumAmount(month, "pending"));
        Map<String, Object> payrollCounts = new LinkedHashMap<String, Object>();
        payrollCounts.put("month", month);
        payrollCounts.put("amount", strip(amount));
        payrollCounts.put("unpaid", strip(unpaid));
        payrollCounts.put("people", payrollMapper.countByMonth(month, null));

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("term", term); // term 恒为 null：概览不按学期过滤，见方法开头说明
        data.put("posts", postCounts);
        data.put("applications", appCounts);
        data.put("timesheets", timesheetCounts);
        data.put("payroll", payrollCounts);
        return Result.success(data);
    }

    // =====================================================================
    // 视图装配
    // =====================================================================

    Map<String, Object> postBrief(WorkstudyPost post, WorkstudyApplication mine) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", post.getId());
        row.put("postType", post.getPostType());
        row.put("postTypeText", postTypeText(post.getPostType()));
        row.put("unit", post.getUnit());
        row.put("name", post.getName());
        row.put("content", post.getContent());
        row.put("skill", post.getSkill());
        row.put("headcount", post.getHeadcount());
        row.put("hiredCount", post.getHiredCount());
        row.put("hourlyWage", post.getHourlyWage());
        row.put("hoursPerWeek", post.getHoursPerWeek());
        row.put("hoursPerMonth", post.getHoursPerMonth());
        row.put("academicTerm", post.getAcademicTerm());
        row.put("applyDeadline", str(post.getApplyDeadline()));
        row.put("interviewAt", str(post.getInterviewAt()));
        row.put("status", post.getStatus());
        row.put("statusText", postStatusText(post.getStatus()));
        row.put("applied", mine != null);
        row.put("applicationStatus", mine == null ? null : mine.getStatus());
        row.put("applicationStatusText", mine == null ? null : applicationStatusText(mine.getStatus()));
        return row;
    }

    Map<String, Object> postDetailMap(WorkstudyPost post) {
        Map<String, Object> row = postBrief(post, null);
        row.put("unitUserId", post.getUnitUserId());
        row.put("unitUserName", post.getUnitUserName());
        row.put("reviewNote", post.getReviewNote());
        row.put("reviewerId", post.getReviewerId());
        row.put("reviewedAt", str(post.getReviewedAt()));
        row.put("createdAt", str(post.getCreatedAt()));
        row.put("hiredCountActual", post.getHiredCountActual());
        return row;
    }

    Map<String, Object> applicationBrief(WorkstudyApplication app) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", app.getId());
        row.put("postId", app.getPostId());
        row.put("postName", app.getPostName());
        row.put("unit", app.getUnit());
        row.put("postType", app.getPostType());
        row.put("postTypeText", postTypeText(app.getPostType()));
        row.put("hourlyWage", app.getHourlyWage());
        row.put("academicTerm", app.getAcademicTerm());
        row.put("status", app.getStatus());
        row.put("statusText", applicationStatusText(app.getStatus()));
        row.put("interviewResult", app.getInterviewResult());
        row.put("interviewNote", app.getInterviewNote());
        row.put("appliedAt", str(app.getAppliedAt()));
        return row;
    }

    Map<String, Object> applicationRow(WorkstudyApplication app) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", app.getId());
        row.put("postId", app.getPostId());
        row.put("postName", app.getPostName());
        row.put("unit", app.getUnit());
        row.put("postType", app.getPostType());
        row.put("postTypeText", postTypeText(app.getPostType()));
        row.put("studentId", app.getStudentId());
        row.put("studentName", app.getStudentName());
        row.put("studentNo", app.getStudentNo());
        row.put("className", app.getClassName());
        row.put("poverty", app.getPoverty());
        row.put("reason", app.getReason());
        row.put("evidence", app.getEvidence());
        row.put("status", app.getStatus());
        row.put("statusText", applicationStatusText(app.getStatus()));
        row.put("interviewResult", app.getInterviewResult());
        row.put("interviewNote", app.getInterviewNote());
        row.put("appliedAt", str(app.getAppliedAt()));
        row.put("hiredAt", str(app.getHiredAt()));
        return row;
    }

    Map<String, Object> timesheetBrief(WorkstudyTimesheet sheet) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", sheet.getId());
        row.put("postId", sheet.getPostId());
        row.put("postName", sheet.getPostName());
        row.put("workMonth", sheet.getWorkMonth());
        row.put("workDate", str(sheet.getWorkDate()));
        row.put("hours", sheet.getHours());
        row.put("content", sheet.getContent());
        row.put("status", sheet.getStatus());
        row.put("statusText", timesheetStatusText(sheet.getStatus()));
        row.put("overLimit", Integer.valueOf(1).equals(sheet.getOverLimit()));
        row.put("recordedBy", sheet.getRecorderId());
        return row;
    }

    Map<String, Object> payrollRow(WorkstudyPayroll payroll) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", payroll.getId());
        row.put("workMonth", payroll.getWorkMonth());
        row.put("studentId", payroll.getStudentId());
        row.put("studentName", payroll.getStudentName());
        row.put("studentNo", payroll.getStudentNo());
        row.put("className", payroll.getClassName());
        row.put("postName", payroll.getPostName());
        row.put("unit", payroll.getUnit());
        row.put("totalHours", payroll.getTotalHours());
        row.put("hourlyWage", payroll.getHourlyWage());
        row.put("amount", payroll.getAmount());
        row.put("assessment", payroll.getAssessment());
        row.put("status", payroll.getStatus());
        row.put("statusText", payrollStatusText(payroll.getStatus()));
        row.put("paidAt", str(payroll.getPaidAt()));
        return row;
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    /** 家庭经济困难学生优先（第二十一条）：读取学生画像的 family_income_level */
    boolean isPovertyStudent(Long studentId) {
        if (studentId == null) {
            return false;
        }
        StudentProfile profile = profileMapper.findByUserId(studentId);
        if (profile == null) {
            return false;
        }
        String level = trim(profile.getFamilyIncomeLevel());
        return !level.isEmpty();
    }

    /** 同一学生同一学期原则上不能同时在两个及以上固定岗位在岗（第二十一条） */
    private boolean hasHiredFixed(Long studentId, String academicTerm, Long exceptApplicationId) {
        for (WorkstudyApplication hired : safe(applicationMapper.listHiredByStudent(studentId, "fixed", academicTerm))) {
            if (exceptApplicationId == null || !exceptApplicationId.equals(hired.getId())) {
                return true;
            }
        }
        return false;
    }

    /** 超限提示位：单月 > 40 小时或含该日的自然周 > 8 小时（含本次） */
    private Integer overLimitFlag(WorkstudyApplication application, String workMonth,
                                  LocalDate workDate, BigDecimal hours) {
        BigDecimal monthHours = timesheetMapper.sumHours(application.getStudentId(), workMonth, null);
        BigDecimal projectMonth = nz(monthHours).add(nz(hours));
        if (projectMonth.compareTo(MAX_HOURS_PER_MONTH) > 0) {
            return 1;
        }
        LocalDate weekStart = workDate.with(DayOfWeek.MONDAY);
        LocalDate weekEnd = workDate.with(DayOfWeek.SUNDAY);
        BigDecimal weekHours = timesheetMapper.sumHoursByDateRange(application.getStudentId(),
                application.getPostId(), weekStart, weekEnd);
        if (nz(weekHours).add(nz(hours)).compareTo(MAX_HOURS_PER_WEEK) > 0) {
            return 1;
        }
        return 0;
    }

    /** 单月工时合计是否超 40（不含本次的兜底判断） */
    private boolean monthsOver(String workMonth, List<WorkstudyTimesheet> rows) {
        if (workMonth == null) {
            return false;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (WorkstudyTimesheet sheet : rows) {
            sum = sum.add(nz(sheet.getHours()));
        }
        return sum.compareTo(MAX_HOURS_PER_MONTH) > 0;
    }

    /** 时薪低于下限 / 工时超办法上限 → 只提示不阻断（寒暑假可延长） */
    private List<String> wageWarnings(String postType, BigDecimal hourlyWage,
                                      BigDecimal hoursPerWeek, BigDecimal hoursPerMonth) {
        List<String> warnings = new ArrayList<String>();
        if ("temporary".equals(postType) && hourlyWage != null
                && hourlyWage.compareTo(MIN_HOURLY_WAGE) < 0) {
            warnings.add("临时岗位时薪原则上不低于 " + MIN_HOURLY_WAGE.stripTrailingZeros().toPlainString()
                    + " 元/小时，当前为 " + strip(hourlyWage).toPlainString() + " 元/小时");
        }
        if (hoursPerWeek != null && hoursPerWeek.compareTo(MAX_HOURS_PER_WEEK) > 0) {
            warnings.add("每周工时超过办法上限 " + MAX_HOURS_PER_WEEK.stripTrailingZeros().toPlainString()
                    + " 小时（寒暑假可延长），当前为 " + strip(hoursPerWeek).toPlainString() + " 小时");
        }
        if (hoursPerMonth != null && hoursPerMonth.compareTo(MAX_HOURS_PER_MONTH) > 0) {
            warnings.add("每月工时超过办法上限 " + MAX_HOURS_PER_MONTH.stripTrailingZeros().toPlainString()
                    + " 小时（寒暑假可延长），当前为 " + strip(hoursPerMonth).toPlainString() + " 小时");
        }
        return warnings;
    }

    /** teacher 只能操作自己的岗位；admin 可操作全部 */
    private boolean canManagePost(Long userId, String role, WorkstudyPost post) {
        if ("admin".equals(role)) {
            return true;
        }
        return userId != null && userId.equals(post.getUnitUserId());
    }

    /** 面试/录用/工时：该岗位的用工单位或资助中心 admin */
    private boolean isUnitActor(Long userId, String role, WorkstudyPost post) {
        if ("admin".equals(role)) {
            return true;
        }
        if (post == null || userId == null) {
            return false;
        }
        return userId.equals(post.getUnitUserId());
    }

    /** teacher 默认只看自己（mine=0 且 admin 才看全部） */
    private Long scopeUnitUserId(Long userId, String role, Integer mine) {
        if ("admin".equals(role) && mine != null && mine == 0) {
            return null;
        }
        if ("admin".equals(role)) {
            return mine != null && mine == 1 ? userId : null;
        }
        return userId;
    }

    private WorkstudyPost requirePost(Long postId) {
        return postId == null ? null : postMapper.findById(postId);
    }

    private WorkstudyApplication requireApplication(Long applicationId) {
        return applicationId == null ? null : applicationMapper.findById(applicationId);
    }

    Map<String, Object> page(List<?> items, int total, int page, int size) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("items", safe(items));
        data.put("total", total);
        data.put("page", page);
        data.put("size", size);
        data.put("pages", size > 0 ? (total + size - 1) / size : 0);
        return data;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String emptyToNull(String s) {
        String t = trim(s);
        return t.isEmpty() ? null : t;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }

    /** 页码钳制：避免 (page-1)*size 溢出成负偏移 */
    private static int clampPage(Integer page) {
        return clamp(page == null ? 1 : page, 1, MAX_PAGE);
    }

    /** 分页偏移：以 long 计算后钳到 int 范围，杜绝 (page-1)*size 溢出成负偏移 */
    private static int offsetOf(int page, int size) {
        long offset = (long) (page - 1) * size;
        if (offset <= 0L) {
            return 0;
        }
        return offset > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) offset;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 金额/工时对外去掉多余的 0（2.50 → 2.5、3.00 → 3） */
    static BigDecimal strip(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.HALF_UP) : stripped;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) value).toLocalDateTime().toString();
        }
        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate().toString();
        }
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).toString();
        }
        if (value instanceof LocalDate) {
            return ((LocalDate) value).toString();
        }
        return String.valueOf(value);
    }

    private static Integer intOrNull(String raw) {
        String t = trim(raw);
        if (t.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(t);
        } catch (NumberFormatException e) {
            // 前端可能以 "3" / "3.0" / " 3 " 等字符串形式提交，统一宽容解析
            BigDecimal decimal = decimalOrNull(t);
            if (decimal == null) {
                return null;
            }
            try {
                return decimal.intValueExact();
            } catch (ArithmeticException exact) {
                return null;
            }
        }
    }

    private static Long longOrNull(String raw) {
        String t = trim(raw);
        if (t.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decimalOrNull(String raw) {
        String t = trim(raw);
        if (t.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate dateOrNull(String raw) {
        String t = trim(raw);
        if (t.isEmpty()) {
            return null;
        }
        if (t.length() > 10) {
            t = t.substring(0, 10);
        }
        try {
            return LocalDate.parse(t);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDateTime dateTimeOrNull(String raw) {
        String t = trim(raw);
        if (t.isEmpty()) {
            return null;
        }
        // 前端三种写法都要接受：yyyy-MM-ddTHH:mm:ss / yyyy-MM-ddTHH:mm / yyyy-MM-dd（空格分隔亦兼容）
        String normalized = t.replace('T', ' ').trim();
        if (normalized.length() == 10) {
            try {
                return LocalDate.parse(normalized).atStartOfDay();
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        if (normalized.length() > 19) {
            normalized = normalized.substring(0, 19);
        }
        if (normalized.length() == 16) {
            normalized = normalized + ":00";
        }
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Long longOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        String t = trim(String.valueOf(value));
        return t.isEmpty() ? null : longOrNull(t);
    }

    private static int intOf(Object value) {
        Long v = longOf(value);
        return v == null ? 0 : v.intValue();
    }

    private static BigDecimal decimalOf(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return new BigDecimal(String.valueOf(value));
        }
        BigDecimal parsed = decimalOrNull(String.valueOf(value));
        return parsed == null ? BigDecimal.ZERO : parsed;
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? new ArrayList<T>() : list;
    }

    static String currentTerm() {
        LocalDate now = LocalDate.now();
        int start = now.getMonthValue() >= 9 ? now.getYear() : now.getYear() - 1;
        return start + "-" + (start + 1) + (now.getMonthValue() >= 9 || now.getMonthValue() <= 1 ? "-1" : "-2");
    }

    static String postTypeText(String postType) {
        if (postType == null) {
            return "未知";
        }
        switch (postType) {
            case "fixed":
                return "固定岗";
            case "temporary":
                return "临时岗";
            default:
                return "未知";
        }
    }

    static String postStatusText(String status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case "draft":
                return "草稿";
            case "pending":
                return "待审批";
            case "recruiting":
                return "招募中";
            case "closed":
                return "已关闭";
            case "rejected":
                return "未通过";
            default:
                return "未知";
        }
    }

    static String applicationStatusText(String status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case "pending":
                return "待审核";
            case "interview_passed":
                return "面试通过";
            case "interview_failed":
                return "面试未通过";
            case "hired":
                return "已录用";
            case "rejected":
                return "未录用";
            case "resigning":
                return "辞职申请中";
            case "resigned":
                return "已离岗";
            default:
                return "未知";
        }
    }

    static String timesheetStatusText(String status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case "submitted":
                return "待确认";
            case "confirmed":
                return "已确认";
            default:
                return "未知";
        }
    }

    static String payrollStatusText(String status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case "pending":
                return "待发放";
            case "paid":
                return "已发放";
            default:
                return "未知";
        }
    }

    private static Map<String, String> postStatusTexts() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("draft", "草稿");
        map.put("pending", "待审批");
        map.put("recruiting", "招募中");
        map.put("closed", "已关闭");
        map.put("rejected", "未通过");
        return map;
    }

    private static Map<String, String> applicationStatusTexts() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("pending", "待审核");
        map.put("interview_passed", "面试通过");
        map.put("interview_failed", "面试未通过");
        map.put("hired", "已录用");
        map.put("rejected", "未录用");
        map.put("resigning", "辞职申请中");
        map.put("resigned", "已离岗");
        return map;
    }

    private static Map<String, String> payrollStatusTexts() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("pending", "待发放");
        map.put("paid", "已发放");
        return map;
    }
}
