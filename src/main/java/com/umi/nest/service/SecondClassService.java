package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.AuditLog;
import com.umi.nest.entity.SecondClassActivity;
import com.umi.nest.entity.SecondClassCategory;
import com.umi.nest.entity.SecondClassRecord;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.SecondClassActivityMapper;
import com.umi.nest.mapper.SecondClassCategoryMapper;
import com.umi.nest.mapper.SecondClassRecordMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import com.umi.nest.service.CommunityService.CsvExport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.umi.nest.service.CommunityService.CAS_FAIL;
import static com.umi.nest.service.CommunityService.DAY;
import static com.umi.nest.service.CommunityService.MAX_EXPORT;
import static com.umi.nest.service.CommunityService.STAMP;
import static com.umi.nest.service.CommunityService.csvCell;
import static com.umi.nest.service.CommunityService.guardLogin;
import static com.umi.nest.service.CommunityService.integer;
import static com.umi.nest.service.CommunityService.isAdmin;
import static com.umi.nest.service.CommunityService.isStaff;
import static com.umi.nest.service.CommunityService.longOf;
import static com.umi.nest.service.CommunityService.maskName;
import static com.umi.nest.service.CommunityService.pageOf;
import static com.umi.nest.service.CommunityService.tail4;
import static com.umi.nest.service.CommunityService.parseDate;
import static com.umi.nest.service.CommunityService.pick;
import static com.umi.nest.service.CommunityService.text;
import static com.umi.nest.service.CommunityService.trimToNull;

/**
 * 第二课堂成绩单（学工 P18，S26）服务层：板块配置 / 活动台账 / 学生申报 / 辅导员初审 / 管理员认定 / 汇总导出。
 *
 * <p>契约见 {@code target/p18-spec.md}（§2 DDL、§3 状态机、§4 判定规则、§5 路由、§6 断言）。三条硬口径：</p>
 *
 * <p><b>① 学校没定的数值一律 NULL，绝不代填</b>。{@code required_hours} / {@code yearly_cap_hours} /
 * {@code credit_per_hour} 三列允许 NULL，含义是「学校还没定」。服务层与页面都必须原样透出 null 并显示
 * 「未设置」。S26（中青联发〔2018〕5 号，转载非一手）无量值，S27 无「第二课堂」字样 ⇒ 任何具体学时都是我们的发明。
 * 学年上限只做<b>提示</b>（warnings），不硬截断——截断等于替学校定规则。</p>
 *
 * <p><b>② 流程如实为两级</b>：S26 的四级是「学生申报 → 团支部/院系审查 → 主办方审核 → 校团委认定」，
 * 本系统合成为「辅导员初审（counselor_approved）→ 管理员认定（confirmed）」，<b>不声称实现了四级</b>。</p>
 *
 * <p><b>③ 状态机 = 显式前置校验 + CAS</b>。CAS 的 fromStatus 一律取显式枚举字面量，绝不拿「刚读到的变量」
 * 当参数（否则 WHERE 恒真）。影响行数 0 → 400 {@link CommunityService#CAS_FAIL}。终态
 * （confirmed / rejected / withdrawn）不可再流转。</p>
 *
 * <p>数据级授权（P0-3）：学生只能读自己的申报；员工侧按<b>学生档案的辅导员</b>过滤
 * （{@link #scopeOf} 与 P13 {@code scopeCounselor} 同口径：管理员与「名下没有学生」的辅导员看全校区）；
 * 跨范围一律 403，文案含「无权」。认定只允许管理员。</p>
 */
@Service("secondClassService")
public class SecondClassService {

    private static final Logger log = LoggerFactory.getLogger(SecondClassService.class);

    /** 单条申报学时上限（防手误，不是政策值）。 */
    static final BigDecimal MAX_RECORD_HOURS = new BigDecimal("100");

    /** 待审核申报条数上限（防刷单）。 */
    static final int MAX_PENDING = 10;

    /** 板块编码格式。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}$");

    static final Set<String> LEVELS = Set.of("school", "college", "class", "other");

    /** 可复用的既有申报状态：撤回/被退回/被否定的行可以改后重交（表上有唯一键，不能靠 INSERT 重开）。 */
    private static final Set<String> REUSABLE = Set.of("withdrawn", "returned", "rejected");

    @Autowired
    private SecondClassCategoryMapper categoryMapper;

    @Autowired
    private SecondClassActivityMapper activityMapper;

    @Autowired
    private SecondClassRecordMapper recordMapper;

    @Autowired
    private StudentProfileMapper studentProfileMapper;

    @Autowired
    private AuditLogService auditLogService;

    // =========================================================================
    // 1. 板块（学生看启用项；管理端配置）
    // =========================================================================

    /** 学生/员工可见的启用板块。 */
    public Result<?> listCategories(Long actorId) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SecondClassCategory category : categoryMapper.listEnabled()) {
            rows.add(categoryMap(category));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("categories", rows);
        data.put("policyNote", policyNote());
        return Result.success(data);
    }

    /** 管理端板块台账（含停用）。 */
    public Result<?> adminListCategories(Long actorId, String role) {
        Result<?> guard = guardAdminOp(actorId, role, "查看板块台账");
        if (guard != null) {
            return guard;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SecondClassCategory category : categoryMapper.listAll()) {
            rows.add(categoryMap(category));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("categories", rows);
        data.put("policyNote", policyNote());
        return Result.success(data);
    }

    @Transactional
    public Result<?> createCategory(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardAdminOp(actorId, role, "新增板块");
        if (guard != null) {
            return guard;
        }
        Map<String, Object> safe = body == null ? new LinkedHashMap<>() : body;
        String name = trimToNull(text(pick(safe, "name")));
        String code = trimToNull(text(pick(safe, "code")));
        if (name == null) {
            return Result.error("板块名称不能为空");
        }
        if (code == null) {
            return Result.error("板块编码不能为空（大写字母，可含数字与下划线）");
        }
        if (!CODE_PATTERN.matcher(code).matches()) {
            return Result.error("板块编码格式应为大写字母开头、只含大写字母/数字/下划线（2-32 位）");
        }
        if (categoryMapper.selectByCode(code) != null) {
            return Result.error("板块编码 " + code + " 已存在");
        }
        SecondClassCategory category = new SecondClassCategory();
        category.setName(name);
        category.setCode(code);
        category.setSortNo(sortNoOf(pick(safe, "sortNo"), 100));
        category.setRequiredHours(decimalOrNull(pick(safe, "requiredHours")));
        category.setYearlyCapHours(decimalOrNull(pick(safe, "yearlyCapHours")));
        category.setCreditPerHour(decimalOrNull(pick(safe, "creditPerHour")));
        category.setNote(trimToNull(text(pick(safe, "note"))));
        Integer enabled = integer(pick(safe, "enabled"));
        category.setEnabled(enabled == null ? 1 : (enabled == 0 ? 0 : 1));
        categoryMapper.insert(category);
        audit("CATEGORY_CREATE", actorId, category.getId(), "新增板块 " + code);
        return Result.success(categoryMap(categoryMapper.selectById(category.getId())));
    }

    @Transactional
    public Result<?> updateCategory(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardAdminOp(actorId, role, "维护板块");
        if (guard != null) {
            return guard;
        }
        SecondClassCategory existing = categoryMapper.selectById(id);
        if (existing == null) {
            return Result.error(404, "板块不存在");
        }
        Map<String, Object> safe = body == null ? new LinkedHashMap<>() : body;
        SecondClassCategory patch = new SecondClassCategory();
        patch.setId(id);
        String name = trimToNull(text(pick(safe, "name")));
        if (name != null) {
            patch.setName(name);
        }
        Integer sortNo = integer(pick(safe, "sortNo"));
        if (sortNo != null) {
            patch.setSortNo(sortNo < 0 ? 0 : sortNo);
        }
        String note = trimToNull(text(pick(safe, "note")));
        if (note != null) {
            patch.setNote(note);
        }
        Integer enabled = integer(pick(safe, "enabled"));
        if (enabled != null) {
            if (enabled == 0 && categoryMapper.countRecordsByCategory(id) > 0) {
                // 停用允许：只禁止新申报，历史记录仍可读（不删板块，防止台账断裂）。
                log.info("停用第二课堂板块 id={}，已有 {} 条申报记录仍保留", id, categoryMapper.countRecordsByCategory(id));
            }
            patch.setEnabled(enabled == 0 ? 0 : 1);
        }
        // 三列口径整体覆盖（含写回 NULL = 学校未设置）；表单本来三列一起提交。
        patch.setRequiredHours(decimalOrNull(pick(safe, "requiredHours")));
        patch.setYearlyCapHours(decimalOrNull(pick(safe, "yearlyCapHours")));
        patch.setCreditPerHour(decimalOrNull(pick(safe, "creditPerHour")));
        categoryMapper.update(patch);
        audit("CATEGORY_UPDATE", actorId, id, "维护板块 " + existing.getCode());
        return Result.success(categoryMap(categoryMapper.selectById(id)));
    }

    // =========================================================================
    // 2. 活动
    // =========================================================================

    /** 学生可见活动（只回 published）。 */
    public Result<?> listActivities(Long actorId, Long categoryId, String keyword, Integer page, Integer size) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 200);
        String kw = trimToNull(keyword);
        List<SecondClassActivity> rows = activityMapper.listPublished(categoryId, kw, (p - 1) * s, s);
        long total = activityMapper.countPublished(categoryId, kw);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SecondClassActivity activity : rows) {
            out.add(activityMap(activity));
        }
        Map<String, Object> data = pageOf(out, (int) total, p, s);
        data.put("policyNote", policyNote());
        return Result.success(data);
    }

    /** 管理端活动台账（含草稿与已结束）。 */
    public Result<?> adminListActivities(Long actorId, String role, String status, Long categoryId,
                                         String keyword, Integer page, Integer size) {
        Result<?> guard = guardStaffOp(actorId, role, "查看活动台账");
        if (guard != null) {
            return guard;
        }
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 200);
        String st = trimToNull(status);
        String kw = trimToNull(keyword);
        List<SecondClassActivity> rows = activityMapper.listForAdmin(st, categoryId, kw, (p - 1) * s, s);
        long total = activityMapper.countForAdmin(st, categoryId, kw);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SecondClassActivity activity : rows) {
            out.add(activityMap(activity));
        }
        return Result.success(pageOf(out, (int) total, p, s));
    }

    @Transactional
    public Result<?> createActivity(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardAdminOp(actorId, role, "发布活动");
        if (guard != null) {
            return guard;
        }
        Map<String, Object> safe = body == null ? new LinkedHashMap<>() : body;
        Long categoryId = longOf(pick(safe, "categoryId"));
        if (categoryId == null) {
            return Result.error("请选择活动所属板块");
        }
        SecondClassCategory category = categoryMapper.selectById(categoryId);
        if (category == null) {
            return Result.error(404, "板块不存在");
        }
        if (category.getEnabled() != null && category.getEnabled() == 0) {
            return Result.error("板块「" + category.getName() + "」已停用，不能在其下新建活动");
        }
        String title = trimToNull(text(pick(safe, "title")));
        if (title == null) {
            return Result.error("活动名称不能为空");
        }
        BigDecimal hours = decimalOrNull(pick(safe, "hours"));
        if (hours == null || hours.compareTo(BigDecimal.ZERO) <= 0) {
            return Result.error("活动学时必须大于 0");
        }
        if (hours.compareTo(MAX_RECORD_HOURS) > 0) {
            return Result.error("单场活动学时不能超过 100（数值应由学校设定，超过请拆分为多场）");
        }
        String level = trimToNull(text(pick(safe, "level")));
        if (level == null) {
            level = "college";
        }
        if (!LEVELS.contains(level)) {
            return Result.error("组织层级只能是 school / college / class / other");
        }
        java.time.LocalDate startDate = parseDate(pick(safe, "startDate"));
        java.time.LocalDate endDate = parseDate(pick(safe, "endDate"));
        if (startDate == null) {
            return Result.error("活动开始日期不能为空");
        }
        if (endDate == null) {
            endDate = startDate;
        }
        if (endDate.isBefore(startDate)) {
            return Result.error("活动结束日期不能早于开始日期");
        }
        String organizer = trimToNull(text(pick(safe, "organizer")));
        if (organizer == null) {
            return Result.error("主办单位不能为空");
        }
        SecondClassActivity activity = new SecondClassActivity();
        activity.setCategoryId(categoryId);
        activity.setTitle(title);
        activity.setOrganizer(organizer);
        activity.setLevel(level);
        activity.setStartDate(startDate);
        activity.setEndDate(endDate);
        activity.setHours(hours);
        activity.setCredit(decimalOrNull(pick(safe, "credit")));
        activity.setLocation(trimToNull(text(pick(safe, "location"))));
        activity.setDescription(trimToNull(text(pick(safe, "description"))));
        activity.setStatus(isTruthy(pick(safe, "publish")) ? "published" : "draft");
        activity.setCreatedBy(actorId);
        activityMapper.insert(activity);
        audit("ACTIVITY_CREATE", actorId, activity.getId(), "新建活动 " + title + "（" + activity.getStatus() + "）");
        return Result.success(activityMap(activityMapper.selectById(activity.getId())));
    }

    @Transactional
    public Result<?> updateActivity(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardAdminOp(actorId, role, "维护活动");
        if (guard != null) {
            return guard;
        }
        SecondClassActivity existing = activityMapper.selectById(id);
        if (existing == null) {
            return Result.error(404, "活动不存在");
        }
        if (!"draft".equals(existing.getStatus())) {
            return Result.error("只有草稿活动可以修改；已发布/已结束的活动请另行发布更正说明");
        }
        Map<String, Object> safe = body == null ? new LinkedHashMap<>() : body;
        SecondClassActivity patch = new SecondClassActivity();
        patch.setId(id);
        Long categoryId = longOf(pick(safe, "categoryId"));
        if (categoryId != null) {
            if (categoryMapper.selectById(categoryId) == null) {
                return Result.error(404, "板块不存在");
            }
            patch.setCategoryId(categoryId);
        }
        String title = trimToNull(text(pick(safe, "title")));
        if (title != null) {
            patch.setTitle(title);
        }
        String organizer = trimToNull(text(pick(safe, "organizer")));
        if (organizer != null) {
            patch.setOrganizer(organizer);
        }
        String level = trimToNull(text(pick(safe, "level")));
        if (level != null) {
            if (!LEVELS.contains(level)) {
                return Result.error("组织层级只能是 school / college / class / other");
            }
            patch.setLevel(level);
        }
        java.time.LocalDate startDate = parseDate(pick(safe, "startDate"));
        if (startDate != null) {
            patch.setStartDate(startDate);
        }
        java.time.LocalDate endDate = parseDate(pick(safe, "endDate"));
        if (endDate != null) {
            patch.setEndDate(endDate);
        }
        BigDecimal hours = decimalOrNull(pick(safe, "hours"));
        if (hours != null) {
            if (hours.compareTo(BigDecimal.ZERO) <= 0) {
                return Result.error("活动学时必须大于 0");
            }
            if (hours.compareTo(MAX_RECORD_HOURS) > 0) {
                return Result.error("单场活动学时不能超过 100（数值应由学校设定）");
            }
            patch.setHours(hours);
        }
        patch.setCredit(decimalOrNull(pick(safe, "credit")));
        String location = trimToNull(text(pick(safe, "location")));
        if (location != null) {
            patch.setLocation(location);
        }
        String description = trimToNull(text(pick(safe, "description")));
        if (description != null) {
            patch.setDescription(description);
        }
        int rows = activityMapper.update(patch, "draft");
        if (rows == 0) {
            return Result.error(400, CAS_FAIL);
        }
        audit("ACTIVITY_UPDATE", actorId, id, "修改草稿活动 " + existing.getTitle());
        return Result.success(activityMap(activityMapper.selectById(id)));
    }

    /** 活动状态流转：发布（draft→published）/ 结束（published→closed）。 */
    @Transactional
    public Result<?> changeActivityStatus(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardAdminOp(actorId, role, "变更活动状态");
        if (guard != null) {
            return guard;
        }
        SecondClassActivity existing = activityMapper.selectById(id);
        if (existing == null) {
            return Result.error(404, "活动不存在");
        }
        Map<String, Object> safe = body == null ? new LinkedHashMap<>() : body;
        String action = trimToNull(text(pick(safe, "action", "status")));
        String from;
        String to;
        if ("publish".equals(action) || "published".equals(action)) {
            from = "draft";
            to = "published";
        } else if ("close".equals(action) || "closed".equals(action)) {
            from = "published";
            to = "closed";
        } else {
            return Result.error("action 只能是 publish（发布）或 close（结束）");
        }
        if (!from.equals(existing.getStatus())) {
            return Result.error("活动当前状态是「" + activityStatusText(existing.getStatus()) + "」，不能执行该操作");
        }
        int rows = activityMapper.updateStatus(id, from, to);
        if (rows == 0) {
            return Result.error(400, CAS_FAIL);
        }
        audit("ACTIVITY_STATUS", actorId, id, "活动状态 " + from + " → " + to + "：" + existing.getTitle());
        return Result.success(activityMap(activityMapper.selectById(id)));
    }

    // =========================================================================
    // 3. 学生申报
    // =========================================================================

    /** 学生申报（挂活动或自报）。 */
    @Transactional
    public Result<?> createRecord(Long actorId, Map<String, Object> body) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        Map<String, Object> safe = body == null ? new LinkedHashMap<>() : body;
        Long activityId = longOf(pick(safe, "activityId"));
        SecondClassActivity activity = null;
        if (activityId != null) {
            activity = activityMapper.selectById(activityId);
            if (activity == null) {
                return Result.error(404, "活动不存在");
            }
            if (!"published".equals(activity.getStatus())) {
                return Result.error("该活动当前不可申报（" + activityStatusText(activity.getStatus()) + "）");
            }
        }
        Long categoryId = activity != null ? activity.getCategoryId() : longOf(pick(safe, "categoryId"));
        if (categoryId == null) {
            return Result.error("请选择申报板块");
        }
        SecondClassCategory category = categoryMapper.selectById(categoryId);
        if (category == null) {
            return Result.error(404, "板块不存在");
        }
        if (category.getEnabled() != null && category.getEnabled() == 0) {
            return Result.error("板块「" + category.getName() + "」已停用，不能新申报");
        }
        BigDecimal hours = decimalOrNull(pick(safe, "hours"));
        if (hours == null || hours.compareTo(BigDecimal.ZERO) <= 0) {
            return Result.error("申报学时必须大于 0");
        }
        if (hours.compareTo(MAX_RECORD_HOURS) > 0) {
            return Result.error("单条申报学时不能超过 100");
        }
        if (activity != null && activity.getHours() != null && hours.compareTo(activity.getHours()) > 0) {
            return Result.error("申报学时不能超过活动学时（" + plain(activity.getHours()) + " 学时）");
        }
        String title = activity != null ? activity.getTitle() : trimToNull(text(pick(safe, "title")));
        if (title == null) {
            return Result.error("自报学时须填写申报标题");
        }
        String evidenceUrl = trimToNull(text(pick(safe, "evidenceUrl")));
        if (activity == null && evidenceUrl == null) {
            return Result.error("自报学时须填写证明材料链接（获奖证书 / 主办方证明等）");
        }
        BigDecimal credit = decimalOrNull(pick(safe, "credit"));
        if (credit != null && (credit.compareTo(BigDecimal.ZERO) < 0 || credit.compareTo(new BigDecimal("20")) > 0)) {
            return Result.error("申报学分应在 0-20 之间");
        }
        String note = trimToNull(text(pick(safe, "note")));

        // 同活动同学员：已有行就复用（表上有唯一键，不能靠 INSERT 重开）。
        SecondClassRecord existing = activityId == null ? null
                : recordMapper.selectByActivityAndUser(activityId, actorId);
        if (existing != null) {
            if (!REUSABLE.contains(existing.getStatus())) {
                return Result.error("你在该活动下已有申报（" + recordStatusText(existing.getStatus()) + "），不能重复申报");
            }
            SecondClassRecord patch = new SecondClassRecord();
            patch.setId(existing.getId());
            patch.setCategoryId(categoryId);
            patch.setTitleSnapshot(title);
            patch.setHours(hours);
            patch.setCredit(credit);
            patch.setEvidenceUrl(evidenceUrl);
            patch.setNote(note);
            int rows = recordMapper.resubmit(patch, existing.getStatus());
            if (rows == 0) {
                return Result.error(400, CAS_FAIL);
            }
            audit("RECORD_RESUBMIT", actorId, existing.getId(), "复用既有申报重新提交：" + title);
            SecondClassRecord saved = recordMapper.selectById(existing.getId());
            return Result.success(recordWithWarnings(saved, category, yearOf(saved)));
        }
        if (recordMapper.countPendingByUser(actorId) >= MAX_PENDING) {
            return Result.error("待审核申报已达 " + MAX_PENDING + " 条上限，请等待审核后再提交");
        }
        SecondClassRecord record = new SecondClassRecord();
        record.setActivityId(activityId);
        record.setCategoryId(categoryId);
        record.setUserId(actorId);
        record.setTitleSnapshot(title);
        record.setHours(hours);
        record.setCredit(credit);
        record.setEvidenceUrl(evidenceUrl);
        record.setNote(note);
        record.setStatus("submitted");
        record.setSubmittedAt(LocalDateTime.now());
        recordMapper.insert(record);
        audit("RECORD_CREATE", actorId, record.getId(), "申报 " + title + " " + plain(hours) + " 学时");
        SecondClassRecord saved = recordMapper.selectById(record.getId());
        return Result.success(recordWithWarnings(saved, category, yearOf(saved)));
    }

    /** 学生改后退回重交（只有 returned 可以）。 */
    @Transactional
    public Result<?> updateRecord(Long actorId, Long id, Map<String, Object> body) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        SecondClassRecord existing = recordMapper.selectById(id);
        if (existing == null) {
            return Result.error(404, "申报不存在");
        }
        if (!actorId.equals(existing.getUserId())) {
            return Result.error(403, "无权修改他人的申报");
        }
        if (!"returned".equals(existing.getStatus())) {
            return Result.error("只有被退回的申报可以修改后重新提交（当前：" + recordStatusText(existing.getStatus()) + "）");
        }
        Map<String, Object> safe = body == null ? new LinkedHashMap<>() : body;
        BigDecimal hours = decimalOrNull(pick(safe, "hours"));
        if (hours == null) {
            hours = existing.getHours();
        }
        if (hours == null || hours.compareTo(BigDecimal.ZERO) <= 0) {
            return Result.error("申报学时必须大于 0");
        }
        if (hours.compareTo(MAX_RECORD_HOURS) > 0) {
            return Result.error("单条申报学时不能超过 100");
        }
        SecondClassActivity activity = existing.getActivityId() == null ? null
                : activityMapper.selectById(existing.getActivityId());
        if (activity != null && activity.getHours() != null && hours.compareTo(activity.getHours()) > 0) {
            return Result.error("申报学时不能超过活动学时（" + plain(activity.getHours()) + " 学时）");
        }
        BigDecimal credit = decimalOrNull(pick(safe, "credit"));
        if (credit != null && (credit.compareTo(BigDecimal.ZERO) < 0 || credit.compareTo(new BigDecimal("20")) > 0)) {
            return Result.error("申报学分应在 0-20 之间");
        }
        String evidenceUrl = trimToNull(text(pick(safe, "evidenceUrl")));
        if (evidenceUrl == null) {
            evidenceUrl = existing.getEvidenceUrl();
        }
        if (existing.getActivityId() == null && evidenceUrl == null) {
            return Result.error("自报学时须填写证明材料链接");
        }
        SecondClassRecord patch = new SecondClassRecord();
        patch.setId(id);
        patch.setCategoryId(existing.getCategoryId());
        patch.setTitleSnapshot(trimToNull(text(pick(safe, "title"))) == null
                ? existing.getTitleSnapshot() : trimToNull(text(pick(safe, "title"))));
        patch.setHours(hours);
        patch.setCredit(decimalOrNull(pick(safe, "credit")));
        patch.setEvidenceUrl(evidenceUrl);
        patch.setNote(trimToNull(text(pick(safe, "note"))) == null
                ? existing.getNote() : trimToNull(text(pick(safe, "note"))));
        int rows = recordMapper.resubmit(patch, "returned");
        if (rows == 0) {
            return Result.error(400, CAS_FAIL);
        }
        audit("RECORD_RESUBMIT", actorId, id, "修改后重新提交 " + patch.getTitleSnapshot());
        SecondClassRecord saved = recordMapper.selectById(id);
        return Result.success(recordWithWarnings(saved, categoryMapper.selectById(saved.getCategoryId()), yearOf(saved)));
    }

    /** 学生撤回（只有 submitted 可以）。 */
    @Transactional
    public Result<?> withdraw(Long actorId, Long id) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        SecondClassRecord existing = recordMapper.selectById(id);
        if (existing == null) {
            return Result.error(404, "申报不存在");
        }
        if (!actorId.equals(existing.getUserId())) {
            return Result.error(403, "无权撤回他人的申报");
        }
        if (!"submitted".equals(existing.getStatus())) {
            return Result.error("只有待初审的申报可以撤回（当前：" + recordStatusText(existing.getStatus()) + "）");
        }
        SecondClassRecord patch = new SecondClassRecord();
        patch.setId(id);
        patch.setStatus("withdrawn");
        int rows = recordMapper.update(patch, "submitted");
        if (rows == 0) {
            return Result.error(400, CAS_FAIL);
        }
        audit("RECORD_WITHDRAW", actorId, id, "撤回申报 " + existing.getTitleSnapshot());
        return Result.success(recordMap(recordMapper.selectById(id)));
    }

    /** 我的申报列表。 */
    public Result<?> listMine(Long actorId, Integer year, String status, Integer page, Integer size) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 200);
        String st = trimToNull(status);
        Integer y = validYear(year);
        List<SecondClassRecord> rows = recordMapper.selectMine(actorId, y, st, (p - 1) * s, s);
        long total = recordMapper.countMine(actorId, y, st);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SecondClassRecord record : rows) {
            out.add(recordMap(record));
        }
        return Result.success(pageOf(out, (int) total, p, s));
    }

    /** 我的第二课堂成绩单（按板块汇总 + 合计 + 上限提示）。 */
    public Result<?> summary(Long actorId, Integer year) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        Integer y = validYear(year);
        List<Map<String, Object>> rows = recordMapper.summaryByCategory(actorId, y);
        List<Map<String, Object>> categories = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            BigDecimal required = decimalOrNull(row.get("required_hours"));
            BigDecimal cap = decimalOrNull(row.get("yearly_cap_hours"));
            BigDecimal confirmed = decimalOrNull(row.get("confirmed_hours"));
            BigDecimal pending = decimalOrNull(row.get("pending_hours"));
            if (confirmed == null) {
                confirmed = BigDecimal.ZERO;
            }
            if (pending == null) {
                pending = BigDecimal.ZERO;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("categoryId", longOf(row.get("category_id")));
            item.put("categoryName", text(row.get("category_name")));
            item.put("requiredHours", required);
            item.put("yearlyCapHours", cap);
            item.put("creditPerHour", decimalOrNull(row.get("credit_per_hour")));
            item.put("confirmedHours", confirmed);
            item.put("pendingHours", pending);
            item.put("confirmedCredit", decimalOrNull(row.get("confirmed_credit")) == null
                    ? BigDecimal.ZERO : decimalOrNull(row.get("confirmed_credit")));
            item.put("recordCount", integer(row.get("record_count")) == null ? 0 : integer(row.get("record_count")));
            item.put("gapHours", required == null ? null : required.subtract(confirmed).max(BigDecimal.ZERO));
            item.put("requiredConfigured", required != null);
            item.put("capConfigured", cap != null);
            categories.add(item);
            if (cap != null && confirmed.add(pending).compareTo(cap) > 0) {
                warnings.add("板块「" + text(row.get("category_name")) + "」本学年合计 " + plain(confirmed.add(pending))
                        + " 学时，已超过学校设定的单学年上限 " + plain(cap) + " 学时；超出部分是否计入由学校认定时决定（系统不自动截断）");
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("year", y);
        data.put("categories", categories);
        data.put("totals", recordMapper.totalsByUser(actorId, y));
        data.put("warnings", warnings);
        data.put("policyNote", policyNote());
        return Result.success(data);
    }

    /** 申报详情（本人 / 范围内员工 / 管理员）。 */
    public Result<?> detail(Long actorId, String role, Long id) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        SecondClassRecord record = recordMapper.selectById(id);
        if (record == null) {
            return Result.error(404, "申报不存在");
        }
        if (!actorId.equals(record.getUserId())) {
            if (!isStaff(role)) {
                return Result.error(403, "无权查看他人的第二课堂申报");
            }
            Long scope = scopeOf(actorId, role);
            if (scope != null) {
                StudentProfile profile = studentProfileMapper.findByUserId(record.getUserId());
                if (profile == null || !scope.equals(profile.getCounselorId())) {
                    return Result.error(403, "该学生不在你的名下，无权查看");
                }
            }
        }
        return Result.success(recordMap(record));
    }

    // =========================================================================
    // 4. 辅导员初审 / 管理员认定
    // =========================================================================

    /** 审核列表（按学生档案辅导员的 scope 过滤）。 */
    public Result<?> reviewList(Long actorId, String role, String status, Long categoryId,
                                String keyword, Integer page, Integer size) {
        Result<?> guard = guardStaffOp(actorId, role, "查看申报列表");
        if (guard != null) {
            return guard;
        }
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 200);
        Long scope = scopeOf(actorId, role);
        String st = trimToNull(status);
        String kw = trimToNull(keyword);
        List<SecondClassRecord> rows = recordMapper.selectForStaff(scope, st, categoryId, kw, (p - 1) * s, s);
        long total = recordMapper.countForStaff(scope, st, categoryId, kw);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SecondClassRecord record : rows) {
            out.add(recordMap(record));
        }
        Map<String, Object> data = pageOf(out, (int) total, p, s);
        data.put("scope", scope == null ? "all" : "counselor");
        return Result.success(data);
    }

    /** 辅导员初审：approve（通过）/ return（退回，原因必填）。 */
    @Transactional
    public Result<?> counselorReview(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardStaffOp(actorId, role, "初审申报");
        if (guard != null) {
            return guard;
        }
        SecondClassRecord existing = recordMapper.selectById(id);
        if (existing == null) {
            return Result.error(404, "申报不存在");
        }
        Long scope = scopeOf(actorId, role);
        if (scope != null) {
            StudentProfile profile = studentProfileMapper.findByUserId(existing.getUserId());
            if (profile == null || !scope.equals(profile.getCounselorId())) {
                return Result.error(403, "该学生不在你的名下，无权初审");
            }
        }
        if (!"submitted".equals(existing.getStatus())) {
            return Result.error("该申报当前状态是「" + recordStatusText(existing.getStatus()) + "」，只有待初审的申报可以初审");
        }
        Map<String, Object> safe = body == null ? new LinkedHashMap<>() : body;
        String action = trimToNull(text(pick(safe, "action", "status")));
        SecondClassRecord patch = new SecondClassRecord();
        patch.setId(id);
        patch.setCounselorId(actorId);
        patch.setCounselorAt(LocalDateTime.now());
        String to;
        if ("approve".equals(action)) {
            to = "counselor_approved";
        } else if ("return".equals(action) || "reject".equals(action)) {
            String reason = trimToNull(text(pick(safe, "reason", "returnReason", "rejectReason")));
            if (reason == null) {
                return Result.error("退回申报必须填写原因");
            }
            patch.setReturnReason(reason);
            to = "returned";
        } else {
            return Result.error("action 只能是 approve（通过）或 return（退回）");
        }
        patch.setStatus(to);
        int rows = recordMapper.update(patch, "submitted");
        if (rows == 0) {
            return Result.error(400, CAS_FAIL);
        }
        audit("RECORD_COUNSELOR", actorId, id, "初审 " + to + "：" + existing.getTitleSnapshot());
        return Result.success(recordMap(recordMapper.selectById(id)));
    }

    /** 管理员认定：confirm（认定）/ reject（否定，原因必填）。学分按板块系数折算，可显式覆盖。 */
    @Transactional
    public Result<?> confirm(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardAdminOp(actorId, role, "认定申报");
        if (guard != null) {
            return guard;
        }
        SecondClassRecord existing = recordMapper.selectById(id);
        if (existing == null) {
            return Result.error(404, "申报不存在");
        }
        if (!"counselor_approved".equals(existing.getStatus())) {
            return Result.error("只有已通过初审的申报可以认定（当前：" + recordStatusText(existing.getStatus()) + "）");
        }
        Map<String, Object> safe = body == null ? new LinkedHashMap<>() : body;
        String action = trimToNull(text(pick(safe, "action", "status")));
        SecondClassRecord patch = new SecondClassRecord();
        patch.setId(id);
        patch.setReviewerId(actorId);
        patch.setReviewedAt(LocalDateTime.now());
        String to;
        if ("confirm".equals(action) || "approve".equals(action)) {
            to = "confirmed";
            SecondClassCategory category = categoryMapper.selectById(existing.getCategoryId());
            BigDecimal credit = decimalOrNull(pick(safe, "credit"));
            if (credit == null && category != null && category.getCreditPerHour() != null && existing.getHours() != null) {
                credit = existing.getHours().multiply(category.getCreditPerHour()).setScale(2, RoundingMode.HALF_UP);
            }
            patch.setCredit(credit);
        } else if ("reject".equals(action)) {
            String reason = trimToNull(text(pick(safe, "reason", "rejectReason", "returnReason")));
            if (reason == null) {
                return Result.error("否定申报必须填写原因");
            }
            patch.setReturnReason(reason);
            to = "rejected";
        } else {
            return Result.error("action 只能是 confirm（认定）或 reject（否定）");
        }
        patch.setStatus(to);
        int rows = recordMapper.update(patch, "counselor_approved");
        if (rows == 0) {
            return Result.error(400, CAS_FAIL);
        }
        audit("RECORD_CONFIRM", actorId, id, "认定 " + to + "：" + existing.getTitleSnapshot());
        return Result.success(recordMap(recordMapper.selectById(id)));
    }

    /** 审核统计（按范围）。 */
    public Result<?> reviewStats(Long actorId, String role) {
        Result<?> guard = guardStaffOp(actorId, role, "查看审核统计");
        if (guard != null) {
            return guard;
        }
        Long scope = scopeOf(actorId, role);
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String status : List.of("submitted", "counselor_approved", "confirmed", "returned", "rejected", "withdrawn")) {
            counts.put(status, recordMapper.countForStaff(scope, status, null, null));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("counts", counts);
        data.put("scope", scope == null ? "all" : "counselor");
        return Result.success(data);
    }

    // =========================================================================
    // 5. 导出
    // =========================================================================

    /**
     * CSV 导出（员工；辅导员按名下学生范围）。
     * <b>脱敏</b>：学号只留后 4 位、姓名只留首字（对齐 VolunteerService.exportRecords）；
     * 未登录 / 非 staff → errorCode 403（控制器回 403 空 body）。
     */
    public CsvExport exportCsv(Long actorId, String role, Integer year, String status) {
        CsvExport out = new CsvExport();
        Result<?> guard = guardStaffOp(actorId, role, "导出申报");
        if (guard != null) {
            // 导出是文件流：拒绝一律 HTTP 403 + 空 body（对齐 ClubService.java:940 / VolunteerService.java:936）
            out.errorCode = 403;
            out.errorMessage = guard.getMessage();
            return out;
        }
        Long scope = scopeOf(actorId, role);
        Integer y = validYear(year);
        List<SecondClassRecord> rows = recordMapper.selectForExport(scope, trimToNull(status), y);
        if (rows.size() > MAX_EXPORT) {
            out.errorCode = 400;
            out.errorMessage = "导出条数超过 " + MAX_EXPORT + " 条上限，请缩小年份或状态范围";
            return out;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("学号,姓名,班级,板块,申报标题,学时,学分,状态,初审人,初审时间,认定人,认定时间,提交时间\n");
        for (SecondClassRecord record : rows) {
            sb.append(csvCell(maskedStudentNo(record.getStudentNo()))).append(',')
                    .append(csvCell(maskedName(record.getStudentName()))).append(',')
                    .append(csvCell(record.getClassName())).append(',')
                    .append(csvCell(record.getCategoryName())).append(',')
                    .append(csvCell(record.getTitleSnapshot())).append(',')
                    .append(csvCell(record.getHours() == null ? null : plain(record.getHours()))).append(',')
                    .append(csvCell(record.getCredit() == null ? null : plain(record.getCredit()))).append(',')
                    .append(csvCell(recordStatusText(record.getStatus()))).append(',')
                    .append(csvCell(record.getCounselorName())).append(',')
                    .append(csvCell(stamp(record.getCounselorAt()))).append(',')
                    .append(csvCell(record.getReviewerName())).append(',')
                    .append(csvCell(stamp(record.getReviewedAt()))).append(',')
                    .append(csvCell(stamp(record.getSubmittedAt()))).append('\n');
        }
        out.csv = sb.toString();
        String day = java.time.LocalDate.now().format(DAY).replace("-", "");
        out.filename = "second-class-" + day + ".csv";
        return out;
    }

    // =========================================================================
    // 校验与工具
    // =========================================================================

    /** 数据级授权范围：admin → null（全校区）；名下没有学生的辅导员 → null（与 P13 同口径）。 */
    Long scopeOf(Long actorId, String role) {
        if (isAdmin(role)) {
            return null;
        }
        if (actorId == null) {
            return null;
        }
        return studentProfileMapper.countByCounselor(actorId) > 0 ? actorId : null;
    }

    private Map<String, Object> categoryMap(SecondClassCategory category) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", category.getId());
        data.put("name", category.getName());
        data.put("code", category.getCode());
        data.put("sortNo", category.getSortNo());
        data.put("requiredHours", category.getRequiredHours());
        data.put("yearlyCapHours", category.getYearlyCapHours());
        data.put("creditPerHour", category.getCreditPerHour());
        data.put("note", category.getNote());
        data.put("enabled", category.getEnabled());
        data.put("configured", category.getRequiredHours() != null
                || category.getYearlyCapHours() != null || category.getCreditPerHour() != null);
        return data;
    }

    private Map<String, Object> activityMap(SecondClassActivity activity) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", activity.getId());
        data.put("categoryId", activity.getCategoryId());
        data.put("categoryName", activity.getCategoryName());
        data.put("title", activity.getTitle());
        data.put("organizer", activity.getOrganizer());
        data.put("level", activity.getLevel());
        data.put("levelText", levelText(activity.getLevel()));
        data.put("startDate", activity.getStartDate() == null ? null : activity.getStartDate().format(DAY));
        data.put("endDate", activity.getEndDate() == null ? null : activity.getEndDate().format(DAY));
        data.put("hours", activity.getHours());
        data.put("credit", activity.getCredit());
        data.put("location", activity.getLocation());
        data.put("description", activity.getDescription());
        data.put("status", activity.getStatus());
        data.put("statusText", activityStatusText(activity.getStatus()));
        data.put("recordCount", activity.getRecordCount());
        data.put("createdBy", activity.getCreatedBy());
        data.put("createdAt", stamp(activity.getCreatedAt()));
        return data;
    }

    private Map<String, Object> recordMap(SecondClassRecord record) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", record.getId());
        data.put("activityId", record.getActivityId());
        data.put("categoryId", record.getCategoryId());
        data.put("categoryName", record.getCategoryName());
        data.put("userId", record.getUserId());
        data.put("studentName", record.getStudentName());
        data.put("studentNo", record.getStudentNo());
        data.put("className", record.getClassName());
        data.put("titleSnapshot", record.getTitleSnapshot());
        data.put("hours", record.getHours());
        data.put("credit", record.getCredit());
        data.put("evidenceUrl", record.getEvidenceUrl());
        data.put("note", record.getNote());
        data.put("status", record.getStatus());
        data.put("statusText", recordStatusText(record.getStatus()));
        data.put("returnReason", record.getReturnReason());
        data.put("submittedAt", stamp(record.getSubmittedAt()));
        data.put("counselorId", record.getCounselorId());
        data.put("counselorName", record.getCounselorName());
        data.put("counselorAt", stamp(record.getCounselorAt()));
        data.put("reviewerId", record.getReviewerId());
        data.put("reviewerName", record.getReviewerName());
        data.put("reviewedAt", stamp(record.getReviewedAt()));
        return data;
    }

    /** 单条申报 + 该板块的学年上限提示（不阻断）。 */
    private Map<String, Object> recordWithWarnings(SecondClassRecord record, SecondClassCategory category, Integer year) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("record", recordMap(record));
        List<String> warnings = new ArrayList<>();
        if (category != null && category.getYearlyCapHours() != null) {
            List<Map<String, Object>> rows = recordMapper.summaryByCategory(record.getUserId(), year);
            for (Map<String, Object> row : rows) {
                if (!category.getId().equals(longOf(row.get("category_id")))) {
                    continue;
                }
                BigDecimal confirmed = decimalOrNull(row.get("confirmed_hours"));
                BigDecimal pending = decimalOrNull(row.get("pending_hours"));
                BigDecimal total = (confirmed == null ? BigDecimal.ZERO : confirmed)
                        .add(pending == null ? BigDecimal.ZERO : pending);
                if (total.compareTo(category.getYearlyCapHours()) > 0) {
                    warnings.add("板块「" + category.getName() + "」本学年合计 " + plain(total)
                            + " 学时，已超过学校设定的单学年上限 " + plain(category.getYearlyCapHours())
                            + " 学时；超出部分是否计入由学校认定时决定（系统不自动截断）");
                }
            }
        }
        data.put("warnings", warnings);
        return data;
    }

    private static Integer yearOf(SecondClassRecord record) {
        return record.getSubmittedAt() == null ? null : record.getSubmittedAt().getYear();
    }

    private static Integer validYear(Integer year) {
        if (year == null) {
            return null;
        }
        return year < 2000 || year > 2100 ? null : year;
    }

    private static int sortNoOf(Object value, int fallback) {
        Integer sortNo = integer(value);
        return sortNo == null ? fallback : (sortNo < 0 ? 0 : sortNo);
    }

    private static boolean isTruthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String str = trimToNull(text(value));
        return "true".equalsIgnoreCase(str) || "1".equals(str) || "yes".equalsIgnoreCase(str);
    }

    static BigDecimal decimalOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String str = trimToNull(text(value));
        if (str == null) {
            return null;
        }
        try {
            return new BigDecimal(str);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 数值展示：去掉无意义的 .00（12.00 → 12，12.50 → 12.5）。 */
    static String plain(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private static String stamp(LocalDateTime value) {
        return value == null ? null : value.format(STAMP);
    }

    public static String recordStatusText(String status) {
        if (status == null) {
            return null;
        }
        switch (status) {
            case "submitted":
                return "待初审";
            case "counselor_approved":
                return "待认定";
            case "confirmed":
                return "已认定";
            case "returned":
                return "已退回";
            case "rejected":
                return "已否定";
            case "withdrawn":
                return "已撤回";
            default:
                return status;
        }
    }

    public static String activityStatusText(String status) {
        if (status == null) {
            return null;
        }
        switch (status) {
            case "draft":
                return "草稿";
            case "published":
                return "已发布";
            case "closed":
                return "已结束";
            default:
                return status;
        }
    }

    public static String levelText(String level) {
        if (level == null) {
            return null;
        }
        switch (level) {
            case "school":
                return "校级";
            case "college":
                return "院级";
            case "class":
                return "班级";
            case "other":
                return "其他";
            default:
                return level;
        }
    }

    /** 诚实口径：数值由学校设定，系统不代填。 */
    public static String policyNote() {
        return "学时与学分口径由学校设定：未设置时显示「未设置」，系统不代填任何国家或校级标准";
    }

    private static String maskedName(String name) {
        return name == null || name.isEmpty() ? name : maskName(name);
    }

    private static String maskedStudentNo(String studentNo) {
        return studentNo == null || studentNo.isEmpty() ? studentNo : tail4(studentNo);
    }

    private void audit(String action, Long actorId, Long targetId, String detail) {
        try {
            AuditLog entry = new AuditLog();
            entry.setEventType(AuditLogService.TYPE_SECOND_CLASS);
            entry.setEventAction(action);
            entry.setUserId(actorId);
            entry.setTarget(targetId == null ? null : String.valueOf(targetId));
            entry.setSuccess(true);
            entry.setDetail(detail);
            auditLogService.record(entry);
        } catch (Exception e) {
            log.warn("第二课堂审计写入失败 action={} actor={} target={}：{}", action, actorId, targetId, e.toString());
        }
    }

    /** admin 守卫：文案必须含「无权」（E2E expectReject 口径）。 */
    private static Result<?> guardAdminOp(Long actorId, String role, String action) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        if (isAdmin(role)) {
            return null;
        }
        return Result.error(403, "无权" + action + "，仅团委管理员可操作");
    }

    /** staff（teacher / admin）守卫：文案必须含「无权」。 */
    private static Result<?> guardStaffOp(Long actorId, String role, String action) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        if (isStaff(role)) {
            return null;
        }
        return Result.error(403, "无权" + action + "，仅辅导员与管理员可操作");
    }
}
