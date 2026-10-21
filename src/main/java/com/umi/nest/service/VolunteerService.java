package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.AuditLog;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.entity.VolunteerAppeal;
import com.umi.nest.entity.VolunteerBlacklist;
import com.umi.nest.entity.VolunteerEnrollment;
import com.umi.nest.entity.VolunteerProject;
import com.umi.nest.entity.VolunteerRecord;
import com.umi.nest.mapper.StudentProfileMapper;
import com.umi.nest.mapper.VolunteerAppealMapper;
import com.umi.nest.mapper.VolunteerBlacklistMapper;
import com.umi.nest.mapper.VolunteerEnrollmentMapper;
import com.umi.nest.mapper.VolunteerProjectMapper;
import com.umi.nest.mapper.VolunteerRecordMapper;
import com.umi.nest.service.CommunityService.CsvExport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.umi.nest.service.CommunityService.CAS_FAIL;
import static com.umi.nest.service.CommunityService.MAX_EXPORT;
import static com.umi.nest.service.CommunityService.csvCell;
import static com.umi.nest.service.CommunityService.dec;
import static com.umi.nest.service.CommunityService.flag;
import static com.umi.nest.service.CommunityService.guardAdmin;
import static com.umi.nest.service.CommunityService.guardLogin;
import static com.umi.nest.service.CommunityService.guardStaff;
import static com.umi.nest.service.CommunityService.isAdmin;
import static com.umi.nest.service.CommunityService.isBlank;
import static com.umi.nest.service.CommunityService.isStaff;
import static com.umi.nest.service.CommunityService.isStudent;
import static com.umi.nest.service.CommunityService.longOf;
import static com.umi.nest.service.CommunityService.maskName;
import static com.umi.nest.service.CommunityService.parseDate;
import static com.umi.nest.service.CommunityService.parseDateTime;
import static com.umi.nest.service.CommunityService.pick;
import static com.umi.nest.service.CommunityService.scale2;
import static com.umi.nest.service.CommunityService.tail4;
import static com.umi.nest.service.CommunityService.trimToNull;

/**
 * 学工平台 P17 志愿服务模块服务层（S21《志愿服务管理办法》）。
 *
 * <p>承接 26 条 {@code /api/volunteer} 路由：项目立项与审批、报名与出勤、时长录入与审核、
 * 补录、个人时长汇总与星级推算、统计与 CSV 导出、黑名单、申诉。契约见 {@code target/p17-plan.md}
 * §2 DDL / §3 枚举 / §4 规则常量 / §5 路由与字段名 / §6 权限 / §7 CAS 状态机 / §10 陷阱 / §11 入参契约。</p>
 *
 * <p>三条贯穿全类的硬约束：</p>
 * <ol>
 *   <li><b>CAS</b>：所有状态流转先做显式前置态校验（不合法 400 中文原因），再
 *       {@code UPDATE ... WHERE id=? AND deleted=0 AND status=#{fromStatus}}，影响行数 0 →
 *       400「状态已变化，请刷新后重试」。终态不可逆（approved/rejected 不得重复审核）。</li>
 *   <li><b>权限</b>：admin 全量读写；teacher（辅导员）限 student_profile.counselor_id 名下学生，
 *       且不可审批项目/审核时长/登记星级/发关公示/登黑名单/裁定申诉；student 只看自己、可报名（仅 approved
 *       项目）、可补录、可申诉。</li>
 *   <li><b>脱敏</b>：列表与导出不含完整学号与联系方式（学号仅留后四位、姓名打码）。</li>
 * </ol>
 *
 * <p>{@code hours} 一律 {@link BigDecimal}（DECIMAL(6,2)），禁止 double 累计。</p>
 */
@Service("volunteerService")
public class VolunteerService {

    private static final Logger log = LoggerFactory.getLogger(VolunteerService.class);

    @Autowired
    private VolunteerProjectMapper projectMapper;

    @Autowired
    private VolunteerEnrollmentMapper enrollmentMapper;

    @Autowired
    private VolunteerRecordMapper recordMapper;

    @Autowired
    private VolunteerBlacklistMapper blacklistMapper;

    @Autowired
    private VolunteerAppealMapper appealMapper;

    @Autowired
    private StudentProfileMapper studentProfileMapper;

    /** P16 缺陷教训：审计服务必须是实例字段，static 字段 Spring 只告警不注入 ⇒ 审计静默丢失。 */
    @Autowired
    private AuditLogService auditLogService;

    // ==================== §3 枚举码值与文案 ====================

    public static final String ST_DRAFT = "draft";
    public static final String ST_PENDING = "pending";
    public static final String ST_APPROVED = "approved";
    public static final String ST_REJECTED = "rejected";

    public static final String EN_PENDING = "pending";
    public static final String EN_CONFIRMED = "confirmed";
    public static final String EN_CANCELLED = "cancelled";
    public static final String EN_ATTENDED = "attended";
    public static final String EN_ABSENT = "absent";

    public static final String HT_CREDIT = "credit";
    public static final String HT_HONOR = "honor";

    public static final String SRC_PLATFORM = "platform";
    public static final String SRC_MAKEUP = "makeup";

    public static final String BL_ACTIVE = "active";
    public static final String BL_EXPIRED = "expired";
    public static final String BL_REVOKED = "revoked";

    public static final String AP_PENDING = "pending";
    public static final String AP_ACCEPTED = "accepted";
    public static final String AP_REJECTED = "rejected";
    public static final String AP_CLOSED = "closed";

    public static final String ACT_APPROVE = "approve";
    public static final String ACT_REJECT = "reject";

    /** §3.3 志愿类别 15 类（中文值直接落库） */
    private static final List<String> CATEGORY_VALUES = Arrays.asList(
            "乡村振兴", "环境保护", "文明实践", "关爱少年儿童", "为老服务", "阳光助残", "卫生健康",
            "应急救援与抗击疫情", "社区治理与邻里守望", "文化传播", "法律服务与普法", "校园公益",
            "大型赛会活动", "海外服务", "其它");

    /** §3.1 发起组织类型 / 录入主体 */
    private static final List<String> ORGANIZER_TYPE_VALUES = Arrays.asList(
            "center", "department", "club", "studentActivity");

    /** §3.1 黑名单条款码（S21 第三十四条(一)~(五) + 第三十五条） */
    private static final List<String> CLAUSE_VALUES = Arrays.asList(
            "c34_1", "c34_2", "c34_3", "c34_4", "c34_5", "c35");

    private static final Map<String, String> PROJECT_STATUS_TEXT = new LinkedHashMap<>();
    private static final Map<String, String> ORGANIZER_TYPE_TEXT = new LinkedHashMap<>();
    private static final Map<String, String> ENROLL_STATUS_TEXT = new LinkedHashMap<>();
    private static final Map<String, String> HOUR_TYPE_TEXT = new LinkedHashMap<>();
    private static final Map<String, String> RECORD_STATUS_TEXT = new LinkedHashMap<>();
    private static final Map<String, String> SOURCE_TEXT = new LinkedHashMap<>();
    private static final Map<String, String> CLAUSE_TEXT = new LinkedHashMap<>();
    private static final Map<String, String> BLACKLIST_STATUS_TEXT = new LinkedHashMap<>();
    private static final Map<String, String> APPEAL_STATUS_TEXT = new LinkedHashMap<>();

    static {
        PROJECT_STATUS_TEXT.put(ST_DRAFT, "草稿");
        PROJECT_STATUS_TEXT.put(ST_PENDING, "待审批");
        PROJECT_STATUS_TEXT.put(ST_APPROVED, "已批准");
        PROJECT_STATUS_TEXT.put(ST_REJECTED, "已驳回");

        ORGANIZER_TYPE_TEXT.put("center", "青年志愿服务指导中心");
        ORGANIZER_TYPE_TEXT.put("department", "院系志愿服务组织");
        ORGANIZER_TYPE_TEXT.put("club", "校内公益类组织、社团");
        ORGANIZER_TYPE_TEXT.put("studentActivity", "学生活动组织单位");

        ENROLL_STATUS_TEXT.put(EN_PENDING, "待确认");
        ENROLL_STATUS_TEXT.put(EN_CONFIRMED, "已确认");
        ENROLL_STATUS_TEXT.put(EN_CANCELLED, "已取消");
        ENROLL_STATUS_TEXT.put(EN_ATTENDED, "已参加");
        ENROLL_STATUS_TEXT.put(EN_ABSENT, "无故缺席");

        HOUR_TYPE_TEXT.put(HT_CREDIT, "信用时数");
        HOUR_TYPE_TEXT.put(HT_HONOR, "荣誉时数");

        RECORD_STATUS_TEXT.put(ST_PENDING, "待审核");
        RECORD_STATUS_TEXT.put(ST_APPROVED, "已通过");
        RECORD_STATUS_TEXT.put(ST_REJECTED, "已驳回");

        SOURCE_TEXT.put(SRC_PLATFORM, "平台打卡");
        SOURCE_TEXT.put(SRC_MAKEUP, "补录");

        CLAUSE_TEXT.put("c34_1", "（一）无故缺席志愿服务，造成不良影响的");
        CLAUSE_TEXT.put("c34_2", "（二）在志愿服务中言行不当，损害学校形象的");
        CLAUSE_TEXT.put("c34_3", "（三）故意煽动负面情绪，影响活动正常开展的");
        CLAUSE_TEXT.put("c34_4", "（四）在社交媒体发表不良言论，诋毁学校或志愿服务工作的");
        CLAUSE_TEXT.put("c34_5", "（五）青年志愿服务指导中心认定的其它违规行为");
        CLAUSE_TEXT.put("c35", "未按要求开展志愿服务工作，或受到警告及以上处分");

        BLACKLIST_STATUS_TEXT.put(BL_ACTIVE, "生效中");
        BLACKLIST_STATUS_TEXT.put(BL_EXPIRED, "已到期");
        BLACKLIST_STATUS_TEXT.put(BL_REVOKED, "已撤销");

        APPEAL_STATUS_TEXT.put(AP_PENDING, "待受理");
        APPEAL_STATUS_TEXT.put(AP_ACCEPTED, "已受理");
        APPEAL_STATUS_TEXT.put(AP_REJECTED, "已驳回");
        APPEAL_STATUS_TEXT.put(AP_CLOSED, "已结案");
    }

    /** §3.2 星级文案（S21 第三十条，仅按累计时长推算，认证权属青年志愿服务指导中心） */
    private static final int[] STAR_THRESHOLDS = {50, 100, 150, 300, 500};
    private static final String[] STAR_TEXT = {
            "暂无星级", "一星级志愿者", "二星级志愿者", "三星级志愿者", "四星级志愿者", "五星级志愿者"};
    private static final String STAR_SOURCE = "按 S21 第三十条推算，认证权属青年志愿服务指导中心";

    // ==================== §4 规则常量 ====================

    /** S21 第二十二条：每天认定上限 8 小时（超出只提示、不硬拒） */
    private static final BigDecimal DAILY_HOUR_LIMIT = new BigDecimal("8");
    /** S21 第三十三条：每学年认定下限 20 小时（只提示、不阻断） */
    private static final BigDecimal YEAR_HOUR_FLOOR = new BigDecimal("20");
    /** S21 第二十六条：补录/更正窗口 = 项目结束后 6 个月 */
    private static final int MAKEUP_WINDOW_MONTHS = 6;
    /** S21 第三十四/三十六条：黑名单期限 6 个月 */
    private static final int BLACKLIST_MONTHS = 6;
    /** S21 第三十七条：申诉受理窗口 5 个工作日 */
    private static final int APPEAL_WORKDAYS = 5;

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ==================== 项目立项 ====================

    /**
     * GET /projects：立项列表。admin 全量；teacher 限名下学生提报的项目；student 只看已批准项目（供报名）。
     */
    public Result<?> listProjects(Long actorId, String role, String status, String category,
                                  String organizerType, String keyword, int page, int size) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        int p = pageNo(page);
        int s = pageSize(size);
        Long scopeUserId = null;
        Long scopeCounselorId = null;
        String st = trimToNull(status);
        if (isAdmin(role)) {
            // 全量
        } else if (isStaff(role)) {
            scopeCounselorId = actorId;
        } else {
            // 学生：只能浏览已批准项目用于报名（§6：可报名「仅 approved 项目」）
            st = ST_APPROVED;
        }
        List<VolunteerProject> rows = projectMapper.selectList(scopeUserId, scopeCounselorId, st,
                trimToNull(category), trimToNull(organizerType), trimToNull(keyword), offsetOf(p, s), s);
        long total = projectMapper.selectCount(scopeUserId, scopeCounselorId, st,
                trimToNull(category), trimToNull(organizerType), trimToNull(keyword));
        return Result.success(pageOf(rows, total, p, s));
    }

    /** GET /projects/{id}：项目详情。 */
    public Result<?> projectDetail(Long actorId, String role, Long id) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error("项目 id 不能为空");
        }
        VolunteerProject project = projectMapper.selectById(id);
        if (project == null) {
            return Result.error(404, "志愿服务项目不存在");
        }
        if (!isAdmin(role) && !isStaff(role) && actorId != null
                && !ST_APPROVED.equals(project.getStatus())) {
            return Result.error(403, "无权查看未批准的项目");
        }
        return Result.success(projectView(project, role));
    }

    /**
     * POST /projects：提报立项申请（§11-5）。默认 status=draft，body {@code submit=true} → pending。
     */
    public Result<?> createProject(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardStaffOp(actorId, role);
        if (guard != null) {
            return guard;
        }
        String projectName = trimToNull(pick(body, "projectName", "project_name"));
        if (projectName == null) {
            return Result.error("项目名称不能为空");
        }
        String category = trimToNull(pick(body, "category"));
        if (category == null) {
            return Result.error("志愿类别不能为空");
        }
        if (!CATEGORY_VALUES.contains(category)) {
            return Result.error("志愿类别只能是 §3.3 规定的 15 类之一");
        }
        String organizer = trimToNull(pick(body, "organizer"));
        if (organizer == null) {
            return Result.error("发起组织不能为空");
        }
        String organizerType = trimToNull(pick(body, "organizerType", "organizer_type"));
        if (organizerType == null) {
            return Result.error("发起组织类型不能为空");
        }
        if (!ORGANIZER_TYPE_VALUES.contains(organizerType)) {
            return Result.error("发起组织类型只能是 center、department、club 或 studentActivity");
        }
        Integer recruitLimit = intOf(pick(body, "recruitLimit", "recruit_limit"));
        if (recruitLimit != null && recruitLimit < 0) {
            return Result.error("招募人数上限不能为负数");
        }
        boolean submit = submitFlag(body);
        VolunteerProject project = new VolunteerProject();
        project.setProjectName(projectName);
        project.setCategory(category);
        project.setOrganizer(organizer);
        project.setOrganizerType(organizerType);
        project.setStartTime(parseDateTime(pick(body, "startTime", "start_time")));
        project.setEndTime(parseDateTime(pick(body, "endTime", "end_time")));
        if (project.getStartTime() != null && project.getEndTime() != null
                && project.getEndTime().isBefore(project.getStartTime())) {
            return Result.error("项目结束时间不能早于开始时间");
        }
        project.setLocation(trimToNull(pick(body, "location")));
        project.setDurationNote(trimToNull(pick(body, "durationNote", "duration_note")));
        project.setRecruitLimit(recruitLimit);
        project.setSubmitUserId(actorId);
        project.setStatus(submit ? ST_PENDING : ST_DRAFT);
        project.setRejectReason(null);
        projectMapper.insert(project);
        audit(submit ? "PROJECT_SUBMIT" : "PROJECT_CREATE", actorId, project.getId(),
                "志愿服务项目立项：" + projectName);
        VolunteerProject saved = projectMapper.selectById(project.getId());
        return Result.success(submit ? "立项申请已提交，等待审核" : "立项草稿已保存",
                projectView(saved == null ? project : saved, role));
    }

    /**
     * PUT /projects/{id}：修改立项（§11-6 + 队长补充 1）。仅 draft/rejected 可改；
     * {@code submit=true} 或 {@code status=pending} 等价 → pending 并写审计；approved → 400。
     */
    public Result<?> updateProject(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardStaffOp(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error("项目 id 不能为空");
        }
        VolunteerProject exists = projectMapper.selectById(id);
        if (exists == null) {
            return Result.error(404, "志愿服务项目不存在");
        }
        if (!isAdmin(role) && (exists.getSubmitUserId() == null
                || !exists.getSubmitUserId().equals(actorId))) {
            return Result.error(403, "无权修改他人提报的项目");
        }
        String fromStatus = exists.getStatus();
        if (!ST_DRAFT.equals(fromStatus) && !ST_REJECTED.equals(fromStatus)) {
            return Result.error("仅草稿或已驳回的项目可以修改，当前状态为 "
                    + PROJECT_STATUS_TEXT.getOrDefault(fromStatus, fromStatus));
        }
        boolean submit = submitFlag(body) || ST_PENDING.equals(trimToNull(pick(body, "status")));
        String projectName = trimToNull(pick(body, "projectName", "project_name"));
        String category = trimToNull(pick(body, "category"));
        String organizer = trimToNull(pick(body, "organizer"));
        String organizerType = trimToNull(pick(body, "organizerType", "organizer_type"));
        // 只提交审批：管理端「提交审批」按钮（volunteer-review.html:1530-1534）只发 {status:'pending'}，
        // 此时仅做 CAS 状态流转，不校验也不改动台账字段（update 为动态 set，null 字段不写）。
        if (projectName == null && category == null && organizer == null && organizerType == null) {
            if (!submit) {
                return Result.error("项目名称不能为空");
            }
            VolunteerProject only = new VolunteerProject();
            only.setId(id);
            only.setStatus(ST_PENDING);
            int onlyRows = projectMapper.update(only, fromStatus);
            if (onlyRows == 0) {
                return Result.error(CAS_FAIL);
            }
            audit("PROJECT_RESUBMIT", actorId, id,
                    "提交志愿服务项目立项审批（" + fromStatus + " → " + ST_PENDING + "）");
            VolunteerProject submitSaved = projectMapper.selectById(id);
            return Result.success("立项申请已提交，等待审核",
                    projectView(submitSaved == null ? exists : submitSaved, role));
        }
        if (projectName == null) {
            return Result.error("项目名称不能为空");
        }
        if (category == null) {
            return Result.error("志愿类别不能为空");
        }
        if (!CATEGORY_VALUES.contains(category)) {
            return Result.error("志愿类别只能是 §3.3 规定的 15 类之一");
        }
        if (organizer == null) {
            return Result.error("发起组织不能为空");
        }
        if (organizerType == null) {
            return Result.error("发起组织类型不能为空");
        }
        if (!ORGANIZER_TYPE_VALUES.contains(organizerType)) {
            return Result.error("发起组织类型只能是 center、department、club 或 studentActivity");
        }
        VolunteerProject update = new VolunteerProject();
        update.setId(id);
        update.setProjectName(projectName);
        update.setCategory(category);
        update.setOrganizer(organizer);
        update.setOrganizerType(organizerType);
        update.setStartTime(parseDateTime(pick(body, "startTime", "start_time")));
        update.setEndTime(parseDateTime(pick(body, "endTime", "end_time")));
        if (update.getStartTime() != null && update.getEndTime() != null
                && update.getEndTime().isBefore(update.getStartTime())) {
            return Result.error("项目结束时间不能早于开始时间");
        }
        update.setLocation(trimToNull(pick(body, "location")));
        update.setDurationNote(trimToNull(pick(body, "durationNote", "duration_note")));
        update.setRecruitLimit(intOf(pick(body, "recruitLimit", "recruit_limit")));
        update.setStatus(submit ? ST_PENDING : fromStatus);
        update.setRejectReason(null);
        int rows = projectMapper.update(update, fromStatus);
        if (rows == 0) {
            return Result.error(CAS_FAIL);
        }
        audit(submit ? "PROJECT_RESUBMIT" : "PROJECT_UPDATE", actorId, id,
                "修改志愿服务项目立项：" + projectName + "（" + fromStatus + " → " + update.getStatus() + "）");
        VolunteerProject saved = projectMapper.selectById(id);
        return Result.success(submit ? "立项申请已重新提交，等待审核" : "立项信息已保存",
                projectView(saved == null ? update : saved, role));
    }

    /**
     * POST /projects/{id}/review：立项审批（§11-7，仅 admin）。CAS 前置 pending，重复审批 400。
     */
    public Result<?> reviewProject(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardAdminOp(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error("项目 id 不能为空");
        }
        VolunteerProject exists = projectMapper.selectById(id);
        if (exists == null) {
            return Result.error(404, "志愿服务项目不存在");
        }
        if (!ST_PENDING.equals(exists.getStatus())) {
            return Result.error("仅待审批的项目可以审核，当前状态为 "
                    + PROJECT_STATUS_TEXT.getOrDefault(exists.getStatus(), exists.getStatus()));
        }
        String action = actionOf(body);
        if (action == null) {
            return Result.error("action 只能是 approve 或 reject");
        }
        String rejectReason = trimToNull(pick(body, "rejectReason", "reject_reason"));
        if (ACT_REJECT.equals(action) && rejectReason == null) {
            return Result.error("驳回时必须填写驳回原因");
        }
        String toStatus = ACT_APPROVE.equals(action) ? ST_APPROVED : ST_REJECTED;
        int rows = projectMapper.reviewProject(id, ST_PENDING, toStatus, actorId,
                ACT_REJECT.equals(action) ? rejectReason : null);
        if (rows == 0) {
            return Result.error(CAS_FAIL);
        }
        audit(ACT_APPROVE.equals(action) ? "PROJECT_APPROVE" : "PROJECT_REJECT", actorId, id,
                "审核志愿服务项目立项：" + exists.getProjectName());
        VolunteerProject saved = projectMapper.selectById(id);
        return Result.success(ACT_APPROVE.equals(action) ? "立项申请已批准" : "立项申请已驳回",
                projectView(saved == null ? exists : saved, role));
    }

    // ==================== 报名与出勤 ====================

    /**
     * POST /projects/{id}/enroll：报名（§11-8）。仅 approved 项目；重复报名 400；成功即 confirmed。
     */
    public Result<?> enrollProject(Long actorId, String role, Long id) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error("项目 id 不能为空");
        }
        VolunteerProject project = projectMapper.selectById(id);
        if (project == null) {
            return Result.error(404, "志愿服务项目不存在");
        }
        if (!ST_APPROVED.equals(project.getStatus())) {
            return Result.error("仅已批准的项目可以报名");
        }
        VolunteerEnrollment last = enrollmentMapper.selectByProjectAndUser(id, actorId);
        if (last != null && !EN_CANCELLED.equals(last.getStatus())) {
            return Result.error("您已报名该项目，请勿重复提交");
        }
        if (project.getRecruitLimit() != null && project.getRecruitLimit() > 0) {
            long taken = enrollmentMapper.countByProjectAndStatus(id, EN_CONFIRMED)
                    + enrollmentMapper.countByProjectAndStatus(id, EN_ATTENDED);
            if (taken >= project.getRecruitLimit()) {
                return Result.error("该项目招募名额已满");
            }
        }
        VolunteerEnrollment enrollment = new VolunteerEnrollment();
        enrollment.setProjectId(id);
        enrollment.setUserId(actorId);
        enrollment.setApplyTime(LocalDateTime.now());
        enrollment.setStatus(EN_CONFIRMED);
        enrollment.setRemark(null);
        enrollmentMapper.insert(enrollment);
        audit("ENROLLMENT_CREATE", actorId, enrollment.getId(),
                "报名志愿服务项目：" + project.getProjectName());
        VolunteerEnrollment saved = enrollmentMapper.selectById(enrollment.getId());
        return Result.success("报名成功", enrollmentView(saved == null ? enrollment : saved, role));
    }

    /**
     * GET /enrollments：报名列表，角色自适应口径 —— admin 全量；teacher 按 scope（counselor_id 名下学生）；
     * student 仅本人（前端「我的报名」页签；无论是否带 {@code ?mine=1} 都看不到他人行）。
     */
    public Result<?> listEnrollments(Long actorId, String role, Long projectId, String status,
                                     String keyword, int page, int size) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        int p = pageNo(page);
        int s = pageSize(size);
        Long scopeUserId = null;
        Long scopeCounselorId = null;
        if (isAdmin(role)) {
            // 全量
        } else if (isStaff(role)) {
            scopeCounselorId = actorId;
        } else {
            scopeUserId = actorId;
        }
        String st = trimToNull(status);
        List<VolunteerEnrollment> rows = enrollmentMapper.selectList(scopeUserId, scopeCounselorId, projectId,
                st, trimToNull(keyword), offsetOf(p, s), s);
        long total = enrollmentMapper.selectCount(scopeUserId, scopeCounselorId, projectId, st,
                trimToNull(keyword));
        List<Map<String, Object>> list = new ArrayList<>();
        for (VolunteerEnrollment row : rows) {
            list.add(enrollmentView(row, role));
        }
        return Result.success(pageOf(list, total, p, s));
    }

    /** POST /enrollments/{id}/cancel：取消报名（§11-10）。前台态 pending|confirmed，student 仅本人。 */
    public Result<?> cancelEnrollment(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error("报名 id 不能为空");
        }
        VolunteerEnrollment exists = enrollmentMapper.selectById(id);
        if (exists == null) {
            return Result.error(404, "报名记录不存在");
        }
        if (!isAdmin(role)) {
            if (actorId != null && actorId.equals(exists.getUserId())) {
                // 学生本人
            } else if (isStaff(role) && underCounselor(actorId, exists.getUserId())) {
                // 辅导员名下学生
            } else {
                return Result.error(403, "无权取消他人的报名");
            }
        }
        String fromStatus = exists.getStatus();
        if (!EN_PENDING.equals(fromStatus) && !EN_CONFIRMED.equals(fromStatus)) {
            return Result.error("仅待确认或已确认的报名可以取消，当前状态为 "
                    + ENROLL_STATUS_TEXT.getOrDefault(fromStatus, fromStatus));
        }
        String remark = trimToNull(pick(body, "remark", "reason"));
        int rows = enrollmentMapper.cancelEnrollment(id, fromStatus, remark);
        if (rows == 0) {
            return Result.error(CAS_FAIL);
        }
        audit("ENROLLMENT_CANCEL", actorId, id, "取消志愿服务报名（" + fromStatus + " → " + EN_CANCELLED + "）");
        VolunteerEnrollment saved = enrollmentMapper.selectById(id);
        return Result.success("报名已取消", enrollmentView(saved == null ? exists : saved, role));
    }

    /**
     * POST /enrollments/{id}/attendance：出勤登记（§11-9）。队长裁定（2026-09-22）：放行 admin + 辅导员
     * （管理端 volunteer-review.html 由辅导员登记出勤，且不按 counselor 限）。前置 confirmed。
     */
    public Result<?> markAttendance(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardStaffOp(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error("报名 id 不能为空");
        }
        VolunteerEnrollment exists = enrollmentMapper.selectById(id);
        if (exists == null) {
            return Result.error(404, "报名记录不存在");
        }
        if (!EN_CONFIRMED.equals(exists.getStatus())) {
            return Result.error("仅已确认的报名可以登记出勤，当前状态为 "
                    + ENROLL_STATUS_TEXT.getOrDefault(exists.getStatus(), exists.getStatus()));
        }
        String status = trimToNull(pick(body, "status", "attendance"));
        if (status == null) {
            return Result.error("出勤状态不能为空");
        }
        if (!EN_ATTENDED.equals(status) && !EN_ABSENT.equals(status)) {
            return Result.error("出勤状态只能是 attended 或 absent");
        }
        int rows = enrollmentMapper.markAttendance(id, EN_CONFIRMED, status);
        if (rows == 0) {
            return Result.error(CAS_FAIL);
        }
        audit("ENROLLMENT_ATTENDANCE", actorId, id,
                "出勤登记：" + ENROLL_STATUS_TEXT.getOrDefault(status, status));
        VolunteerEnrollment saved = enrollmentMapper.selectById(id);
        return Result.success(ENROLL_STATUS_TEXT.getOrDefault(status, status) + "登记成功",
                enrollmentView(saved == null ? exists : saved, role));
    }

    // ==================== 时长记录 ====================

    /** GET /records：时长记录管理列表（admin / teacher；student 无权）。 */
    public Result<?> listRecords(Long actorId, String role, Long projectId, String status, String hourType,
                                 String source, String startDate, String endDate, String keyword,
                                 int page, int size) {
        Result<?> guard = guardStaffOp(actorId, role);
        if (guard != null) {
            return guard;
        }
        return recordPage(null, isAdmin(role) ? null : actorId, projectId, status, hourType, source,
                startDate, endDate, keyword, page, size);
    }

    /** GET /records/mine：我的时长记录（任何已登录角色，scopeUserId = 本人）。 */
    public Result<?> listMyRecords(Long actorId, String role, Long projectId, String status, String hourType,
                                   String source, String startDate, String endDate, String keyword,
                                   int page, int size) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        return recordPage(actorId, null, projectId, status, hourType, source, startDate, endDate, keyword,
                page, size);
    }

    /** GET /records/{id}：记录详情。admin 全量；teacher 限名下学生或本人录入；student 仅本人。 */
    public Result<?> recordDetail(Long actorId, String role, Long id) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error("记录 id 不能为空");
        }
        VolunteerRecord record = recordMapper.selectById(id);
        if (record == null) {
            return Result.error(404, "志愿服务时长记录不存在");
        }
        if (!isAdmin(role)) {
            boolean mine = actorId != null && actorId.equals(record.getUserId());
            boolean recorder = actorId != null && actorId.equals(record.getRecorderId());
            if (!mine && !recorder && !(isStaff(role) && underCounselor(actorId, record.getUserId()))) {
                return Result.error(403, "无权查看他人的志愿服务时长记录");
            }
        }
        return Result.success(recordView(record, false));
    }

    /**
     * POST /records：录入时长（§11-11 teacher/admin 为学生录入；队长补充 4：学生本人也可提交，
     * 此时 source 由服务端置 makeup、userId 强制本人）。默认 status=pending；
     * 单日累计超过 8 小时只提示不拒，提示放在 data.warnings[]。
     */
    public Result<?> createRecord(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        boolean student = isStudent(role);
        if (!student) {
            Result<?> staffGuard = guardStaffOp(actorId, role);
            if (staffGuard != null) {
                return staffGuard;
            }
        }
        Map<String, Object> payload = body;
        if (student) {
            Long bodyUserId = longOf(pick(body, "userId", "user_id"));
            if (bodyUserId != null && !bodyUserId.equals(actorId)) {
                return Result.error(403, "无权为他人的志愿服务时长记录录入");
            }
            payload = new LinkedHashMap<>();
            if (body != null) {
                payload.putAll(body);
            }
            payload.put("userId", actorId);
        }
        String bodySource = trimToNull(pick(body, "source", "source_type"));
        if (bodySource != null && !SRC_PLATFORM.equals(bodySource) && !SRC_MAKEUP.equals(bodySource)) {
            return Result.error("时长来源只能是 platform 或 makeup");
        }
        String source = student ? SRC_MAKEUP
                : (SRC_MAKEUP.equals(bodySource) ? SRC_MAKEUP : SRC_PLATFORM);
        Result<?> built = buildRecord(actorId, role, payload, source, null);
        if (built.getCode() != null && built.getCode() != 200) {
            return built;
        }
        VolunteerRecord record = (VolunteerRecord) built.getData();
        if (SRC_MAKEUP.equals(record.getSource())) {
            LocalDate windowBase = makeupWindowBase(record.getProjectId(), record.getServiceDate());
            if (windowBase != null && windowBase.plusMonths(MAKEUP_WINDOW_MONTHS).isBefore(LocalDate.now())) {
                return Result.error(400, "已超过补录窗口：项目结束（" + windowBase + "）起 "
                        + MAKEUP_WINDOW_MONTHS + " 个月内可申请补录（S21 第二十六条）");
            }
        }
        List<String> warnings = dailyWarnings(record.getUserId(), record.getServiceDate(),
                record.getHours(), null);
        recordMapper.insert(record);
        audit("RECORD_CREATE", actorId, record.getId(),
                "录入志愿服务时长：" + scale2(record.getHours()) + " 小时（" + record.getServiceDate() + "）");
        Map<String, Object> data = recordView(record, false);
        data.put("warnings", warnings);
        return Result.success(warnings.isEmpty() ? "时长已录入，等待审核" : "时长已录入，等待审核（有提示）", data);
    }

    /**
     * POST /records/batch：批量录入（§11-12，队长补充 2：items / records 两个键都读）。
     * 返回 data={created:n, warnings:[...]}。
     */
    public Result<?> batchCreateRecords(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardStaffOp(actorId, role);
        if (guard != null) {
            return guard;
        }
        Object raw = pick(body, "items", "records");
        List<?> items = raw instanceof List ? (List<?>) raw : null;
        if (items == null || items.isEmpty()) {
            return Result.error("录入明细不能为空");
        }
        List<String> warnings = new ArrayList<>();
        Map<String, BigDecimal> daySum = new LinkedHashMap<>();
        List<VolunteerRecord> batch = new ArrayList<>();
        int index = 0;
        for (Object item : items) {
            index++;
            if (!(item instanceof Map)) {
                warnings.add("第 " + index + " 条：格式不正确，已跳过");
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) item;
            Result<?> built = buildRecord(actorId, role, row, SRC_PLATFORM, "第 " + index + " 条：");
            if (built.getCode() != null && built.getCode() != 200) {
                warnings.add("第 " + index + " 条：" + built.getMessage());
                continue;
            }
            VolunteerRecord record = (VolunteerRecord) built.getData();
            warnings.addAll(dailyWarnings(record.getUserId(), record.getServiceDate(),
                    record.getHours(), daySum));
            batch.add(record);
        }
        int created = 0;
        if (!batch.isEmpty()) {
            recordMapper.insertBatch(batch);
            created = batch.size();
        }
        audit("RECORD_BATCH", actorId, null, "批量录入志愿服务时长 " + created + " 条");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("created", created);
        data.put("warnings", warnings);
        return Result.success("批量录入完成，共 " + created + " 条", data);
    }

    /** POST /records/{id}/review：时长审核（§11-13，admin）。CAS 前置 pending，终态不可再改。 */
    public Result<?> reviewRecord(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardAdminOp(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error("记录 id 不能为空");
        }
        VolunteerRecord exists = recordMapper.selectById(id);
        if (exists == null) {
            return Result.error(404, "志愿服务时长记录不存在");
        }
        if (!ST_PENDING.equals(exists.getStatus())) {
            return Result.error("仅待审核的记录可以审核，当前状态为 "
                    + RECORD_STATUS_TEXT.getOrDefault(exists.getStatus(), exists.getStatus()));
        }
        String action = actionOf(body);
        if (action == null) {
            return Result.error("action 只能是 approve 或 reject");
        }
        String rejectReason = trimToNull(pick(body, "rejectReason", "reject_reason", "reason"));
        if (ACT_REJECT.equals(action) && rejectReason == null) {
            return Result.error("驳回时必须填写驳回原因");
        }
        String toStatus = ACT_APPROVE.equals(action) ? ST_APPROVED : ST_REJECTED;
        int rows = recordMapper.reviewRecord(id, ST_PENDING, toStatus, actorId,
                ACT_REJECT.equals(action) ? rejectReason : null);
        if (rows == 0) {
            return Result.error(CAS_FAIL);
        }
        audit(ACT_APPROVE.equals(action) ? "RECORD_APPROVE" : "RECORD_REJECT", actorId, id,
                "审核志愿服务时长记录（" + scale2(exists.getHours()) + " 小时）");
        VolunteerRecord saved = recordMapper.selectById(id);
        return Result.success(ACT_APPROVE.equals(action) ? "时长记录已通过" : "时长记录已驳回",
                recordView(saved == null ? exists : saved, false));
    }

    /**
     * POST /records/{id}/makeup：补录（§11-14）。仅 rejected 记录可补录，<b>生成新记录</b>
     * （source=makeup、status=pending），原记录不动；项目结束起超 6 个月 → 400（S21 第二十六条）。
     */
    public Result<?> makeupRecord(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error("记录 id 不能为空");
        }
        VolunteerRecord origin = recordMapper.selectById(id);
        if (origin == null) {
            return Result.error(404, "志愿服务时长记录不存在");
        }
        if (!isAdmin(role)) {
            boolean mine = actorId != null && actorId.equals(origin.getUserId());
            if (!mine && !(isStaff(role) && underCounselor(actorId, origin.getUserId()))) {
                return Result.error(403, "无权补录他人的时长记录");
            }
        }
        if (ST_APPROVED.equals(origin.getStatus())) {
            return Result.error("已通过的时长记录不能补录");
        }
        if (!ST_REJECTED.equals(origin.getStatus())) {
            return Result.error("仅已驳回的记录可以补录，当前状态为 "
                    + RECORD_STATUS_TEXT.getOrDefault(origin.getStatus(), origin.getStatus()));
        }
        LocalDate today = LocalDate.now();
        LocalDate windowBase = makeupWindowBase(origin.getProjectId(), origin.getServiceDate());
        if (windowBase != null && windowBase.plusMonths(MAKEUP_WINDOW_MONTHS).isBefore(today)) {
            return Result.error("已超过补录窗口：项目结束（" + windowBase + "）起 "
                    + MAKEUP_WINDOW_MONTHS + " 个月内可补录（S21 第二十六条）");
        }
        BigDecimal hours = dec(pick(body, "hours"));
        if (hours != null && hours.compareTo(BigDecimal.ZERO) <= 0) {
            return Result.error("时长必须大于 0");
        }
        LocalDate serviceDate = parseDate(pick(body, "serviceDate", "service_date"));
        VolunteerRecord record = new VolunteerRecord();
        record.setUserId(origin.getUserId());
        record.setProjectId(origin.getProjectId());
        record.setServiceDate(serviceDate == null ? origin.getServiceDate() : serviceDate);
        record.setSignIn(origin.getSignIn());
        record.setSignOut(origin.getSignOut());
        record.setHours(hours == null ? scale2(origin.getHours()) : scale2(hours));
        record.setHourType(origin.getHourType() == null ? HT_CREDIT : origin.getHourType());
        record.setSource(SRC_MAKEUP);
        record.setRecorderId(actorId);
        record.setRecorderSubject(origin.getRecorderSubject());
        record.setStatus(ST_PENDING);
        record.setMakeupNote(trimToNull(pick(body, "makeupNote", "makeup_note", "reason")));
        recordMapper.insert(record);
        audit("RECORD_MAKEUP", actorId, record.getId(),
                "补录志愿服务时长（原记录 " + origin.getId() + "）：" + scale2(record.getHours()) + " 小时");
        Map<String, Object> data = recordView(record, false);
        data.put("originRecordId", origin.getId());
        data.put("warnings", dailyWarnings(record.getUserId(), record.getServiceDate(),
                record.getHours(), null));
        return Result.success("补录成功，等待审核", data);
    }

    // ==================== 时长汇总与星级 ====================

    /** GET /summary/mine：本人时长汇总与星级推算（§11-16）。 */
    public Result<?> summaryMine(Long actorId, String role) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        return Result.success(summaryView(actorId));
    }

    /** GET /summary/{userId}：单人时长汇总（队长补充 2：仅 admin 可查他人；非 admin 仅能查自己）。 */
    public Result<?> summaryForUser(Long actorId, String role, Long userId) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        if (userId == null) {
            return Result.error("学生 id 不能为空");
        }
        if (!isAdmin(role) && (actorId == null || !actorId.equals(userId))
                && !(isStaff(role) && underCounselor(actorId, userId))) {
            return Result.error(403, "无权查看该学生的时长汇总");
        }
        if (studentProfileMapper.findByUserId(userId) == null) {
            return Result.error(404, "学生不存在");
        }
        return Result.success(summaryView(userId));
    }

    // ==================== 统计与导出 ====================

    /** GET /stats：志愿服务统计（§11-17，admin / teacher）。 */
    public Result<?> stats(Long actorId, String role) {
        Result<?> guard = guardStaffOp(actorId, role);
        if (guard != null) {
            return guard;
        }
        LocalDate today = LocalDate.now();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("projectCount", projectMapper.selectCount(null, null, null, null, null, null));
        data.put("enrollmentCount", enrollmentMapper.selectCount(null, null, null, null, null));
        data.put("recordCount", recordMapper.countByStatus(null));
        data.put("pendingRecordCount", recordMapper.countByStatus(ST_PENDING));
        data.put("approvedHours", approvedHours(null));
        long active = blacklistMapper.countByStatus(BL_ACTIVE);
        long expired = blacklistMapper.countExpiredActive(today);
        data.put("blacklistActiveCount", Math.max(0L, active - expired));
        data.put("appealPendingCount", appealMapper.countByStatus(AP_PENDING));
        data.put("byCategory", countByCategory());
        return Result.success(data);
    }

    /** GET /stats/export：CSV 导出（§11-18，脱敏 + UTF-8 BOM，文件名 volunteer-stats-<yyyyMMdd>.csv）。 */
    public CsvExport exportStats(Long actorId, String role, Long projectId, String status, String hourType,
                                 String source, String startDate, String endDate, String keyword) {
        CsvExport out = new CsvExport();
        Result<?> guard = guardStaffOp(actorId, role);
        if (guard != null) {
            // 队长裁定（2026-09-22）：导出被拒一律 HTTP 403 + 空 body（对齐 ClubService），
            // 未登录（401）也按 403 回，便于统一判定「无权导出」。
            out.errorCode = 403;
            out.errorMessage = guard.getMessage();
            return out;
        }
        Long scopeCounselorId = isAdmin(role) ? null : actorId;
        List<VolunteerRecord> rows = recordMapper.selectList(null, scopeCounselorId, projectId,
                trimToNull(status), trimToNull(hourType), trimToNull(source), parseDate(startDate),
                parseDate(endDate), trimToNull(keyword), 0, MAX_EXPORT + 1);
        if (rows.size() > MAX_EXPORT) {
            out.errorCode = 400;
            out.errorMessage = "导出数据量超过 " + MAX_EXPORT + " 行，请缩小范围（日期 / 项目）后重试";
            return out;
        }
        StringBuilder csv = new StringBuilder();
        csv.append("学号,姓名,项目,服务日期,时长(小时),时数类型,状态\r\n");
        for (VolunteerRecord row : rows) {
            csv.append(csvCell(maskedStudentNo(row.getStudentNo()))).append(',')
                    .append(csvCell(maskedName(row.getStudentName()))).append(',')
                    .append(csvCell(row.getProjectName())).append(',')
                    .append(csvCell(row.getServiceDate() == null ? "" : row.getServiceDate().toString())).append(',')
                    .append(csvCell(scale2(row.getHours()) == null ? "" : scale2(row.getHours()).toPlainString()))
                    .append(',')
                    .append(csvCell(HOUR_TYPE_TEXT.getOrDefault(row.getHourType(), row.getHourType()))).append(',')
                    .append(csvCell(RECORD_STATUS_TEXT.getOrDefault(row.getStatus(), row.getStatus())))
                    .append("\r\n");
        }
        out.csv = csv.toString();
        out.filename = "volunteer-stats-" + LocalDate.now().format(FILE_STAMP) + ".csv";
        return out;
    }

    // ==================== 黑名单 ====================

    /** GET /blacklist：黑名单列表（admin 全量 / teacher 名下学生 / student 仅本人，队长补充 3）。 */
    public Result<?> listBlacklist(Long actorId, String role, String clause, String status,
                                   String keyword, int page, int size) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        int p = pageNo(page);
        int s = pageSize(size);
        Long scopeUserId = null;
        Long scopeCounselorId = null;
        if (isAdmin(role)) {
            // 全量
        } else if (isStaff(role)) {
            scopeCounselorId = actorId;
        } else {
            scopeUserId = actorId;
        }
        String clauseValue = trimToNull(clause);
        if (clauseValue != null && !CLAUSE_VALUES.contains(clauseValue)) {
            return Result.error("黑名单条款只能是 c34_1 至 c34_5 或 c35");
        }
        String st = trimToNull(status);
        List<VolunteerBlacklist> rows = blacklistMapper.selectList(scopeUserId, scopeCounselorId, clauseValue,
                st, trimToNull(keyword), offsetOf(p, s), s);
        long total = blacklistMapper.selectCount(scopeUserId, scopeCounselorId, clauseValue, st,
                trimToNull(keyword));
        List<Map<String, Object>> list = new ArrayList<>();
        for (VolunteerBlacklist row : rows) {
            list.add(blacklistView(row));
        }
        return Result.success(pageOf(list, total, p, s));
    }

    /**
     * POST /blacklist：列入黑名单（§11-19，仅 admin）。expireAt = listedAt + 6 个月；
     * status 按当天推算 active/expired。
     */
    public Result<?> createBlacklist(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardAdminOp(actorId, role);
        if (guard != null) {
            return guard;
        }
        Long userId = longOf(pick(body, "userId", "user_id"));
        if (userId == null) {
            return Result.error("学生不能为空");
        }
        if (studentProfileMapper.findByUserId(userId) == null) {
            return Result.error(404, "学生不存在");
        }
        String clause = trimToNull(pick(body, "clause"));
        if (clause == null) {
            return Result.error("黑名单条款不能为空");
        }
        if (!CLAUSE_VALUES.contains(clause)) {
            return Result.error("黑名单条款只能是 c34_1 至 c34_5 或 c35");
        }
        String reason = trimToNull(pick(body, "reason"));
        if (reason == null) {
            return Result.error("列入原因不能为空");
        }
        LocalDate today = LocalDate.now();
        LocalDate listedAt = parseDate(pick(body, "listedAt", "listed_at"));
        if (listedAt == null) {
            listedAt = today;
        }
        VolunteerBlacklist exist = blacklistMapper.selectActiveByUser(userId);
        if (exist != null && (exist.getExpireAt() == null || !exist.getExpireAt().isBefore(today))) {
            return Result.error("该学生已在黑名单生效期内（截止 " + exist.getExpireAt() + "），无需重复列入");
        }
        LocalDate expireAt = listedAt.plusMonths(BLACKLIST_MONTHS);
        VolunteerBlacklist blacklist = new VolunteerBlacklist();
        blacklist.setUserId(userId);
        blacklist.setClause(clause);
        blacklist.setReason(reason);
        blacklist.setListedAt(listedAt);
        blacklist.setExpireAt(expireAt);
        blacklist.setNotifyTime(parseDateTime(pick(body, "notifyTime", "notify_time")));
        blacklist.setStatus(expireAt.isBefore(today) ? BL_EXPIRED : BL_ACTIVE);
        blacklist.setOperatorId(actorId);
        blacklistMapper.insert(blacklist);
        audit("BLACKLIST_ADD", actorId, blacklist.getId(),
                "列入志愿服务黑名单：" + CLAUSE_TEXT.getOrDefault(clause, clause)
                        + "，生效至 " + expireAt);
        VolunteerBlacklist saved = blacklistMapper.selectById(blacklist.getId());
        return Result.success("已列入黑名单，生效期至 " + expireAt,
                blacklistView(saved == null ? blacklist : saved));
    }

    /** POST /blacklist/{id}/revoke：撤销黑名单（§11-19，仅 admin）。CAS 前置 active。 */
    public Result<?> revokeBlacklist(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardAdminOp(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error("黑名单 id 不能为空");
        }
        VolunteerBlacklist exists = blacklistMapper.selectById(id);
        if (exists == null) {
            return Result.error(404, "黑名单记录不存在");
        }
        if (!BL_ACTIVE.equals(exists.getStatus())) {
            return Result.error("仅生效中的黑名单可以撤销，当前状态为 "
                    + BLACKLIST_STATUS_TEXT.getOrDefault(exists.getStatus(), exists.getStatus()));
        }
        VolunteerBlacklist update = new VolunteerBlacklist();
        update.setId(id);
        update.setStatus(BL_REVOKED);
        int rows = blacklistMapper.update(update, BL_ACTIVE);
        if (rows == 0) {
            return Result.error(CAS_FAIL);
        }
        audit("BLACKLIST_REVOKE", actorId, id, "撤销志愿服务黑名单："
                + trimToNull(pick(body, "reason")));
        VolunteerBlacklist saved = blacklistMapper.selectById(id);
        return Result.success("黑名单已撤销", blacklistView(saved == null ? exists : saved));
    }

    // ==================== 申诉 ====================

    /** GET /appeals：申诉列表（admin 全量 / teacher 名下学生 / student 仅本人，队长补充 3）。 */
    public Result<?> listAppeals(Long actorId, String role, Long recordId, Long blacklistId, String status,
                                 String keyword, int page, int size) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        int p = pageNo(page);
        int s = pageSize(size);
        Long scopeUserId = null;
        Long scopeCounselorId = null;
        if (isAdmin(role)) {
            // 全量
        } else if (isStaff(role)) {
            scopeCounselorId = actorId;
        } else {
            scopeUserId = actorId;
        }
        String st = trimToNull(status);
        List<VolunteerAppeal> rows = appealMapper.selectList(scopeUserId, scopeCounselorId, recordId, blacklistId,
                st, trimToNull(keyword), offsetOf(p, s), s);
        long total = appealMapper.selectCount(scopeUserId, scopeCounselorId, recordId, blacklistId, st,
                trimToNull(keyword));
        List<Map<String, Object>> list = new ArrayList<>();
        for (VolunteerAppeal row : rows) {
            list.add(appealView(row));
        }
        return Result.success(pageOf(list, total, p, s));
    }

    /**
     * POST /appeals：提交申诉（§11-20，学生本人）。recordId / blacklistId 二选一必填；
     * 窗口 = 记录最近一次审核时间 / 黑名单通知时间（空则列入时间）+ 5 个工作日，超窗 400 并给出截止日。
     */
    public Result<?> createAppeal(Long actorId, String role, Map<String, Object> body) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        Long recordId = longOf(pick(body, "recordId", "record_id"));
        Long blacklistId = longOf(pick(body, "blacklistId", "blacklist_id"));
        if (recordId == null && blacklistId == null) {
            return Result.error("recordId 或 blacklistId 必须填写其一（不能为空）");
        }
        if (recordId != null && blacklistId != null) {
            return Result.error("recordId 与 blacklistId 只能填写其一");
        }
        String reason = trimToNull(pick(body, "reason"));
        if (reason == null) {
            return Result.error("申诉理由不能为空");
        }
        LocalDateTime base;
        if (recordId != null) {
            VolunteerRecord record = recordMapper.selectById(recordId);
            if (record == null) {
                return Result.error(404, "志愿服务时长记录不存在");
            }
            if (!isAdmin(role) && (actorId == null || !actorId.equals(record.getUserId()))) {
                return Result.error(403, "无权对他人记录提出申诉");
            }
            base = record.getReviewTime() != null ? record.getReviewTime()
                    : (record.getServiceDate() == null ? null : record.getServiceDate().atStartOfDay());
        } else {
            VolunteerBlacklist blacklist = blacklistMapper.selectById(blacklistId);
            if (blacklist == null) {
                return Result.error(404, "黑名单记录不存在");
            }
            if (!isAdmin(role) && (actorId == null || !actorId.equals(blacklist.getUserId()))) {
                return Result.error(403, "无权对他人黑名单提出申诉");
            }
            base = blacklist.getNotifyTime() != null ? blacklist.getNotifyTime()
                    : (blacklist.getListedAt() == null ? null : blacklist.getListedAt().atStartOfDay());
        }
        if (base == null) {
            base = LocalDateTime.now();
        }
        LocalDate deadline = workdayDeadline(base, APPEAL_WORKDAYS);
        if (LocalDate.now().isAfter(deadline)) {
            return Result.error("已超过申诉受理窗口（S21 第三十七条 " + APPEAL_WORKDAYS
                    + " 个工作日），受理截止日为 " + deadline);
        }
        if (appealMapper.countPendingByUserAndTarget(actorId, recordId, blacklistId) > 0) {
            return Result.error("该事项已有待受理的申诉，请勿重复提交");
        }
        VolunteerAppeal appeal = new VolunteerAppeal();
        appeal.setUserId(actorId);
        appeal.setRecordId(recordId);
        appeal.setBlacklistId(blacklistId);
        appeal.setReason(reason);
        appeal.setSubmitTime(LocalDateTime.now());
        appeal.setAcceptOrg("团委");
        appeal.setWithinWindow(1);
        appeal.setStatus(AP_PENDING);
        appealMapper.insert(appeal);
        audit("APPEAL_CREATE", actorId, appeal.getId(), "提交志愿服务申诉："
                + (recordId != null ? "记录 " + recordId : "黑名单 " + blacklistId));
        VolunteerAppeal saved = appealMapper.selectById(appeal.getId());
        return Result.success("申诉已提交，受理截止日 " + deadline,
                appealView(saved == null ? appeal : saved));
    }

    /**
     * POST /appeals/{id}/rule：申诉裁定（§11-21 仅 admin；队长补充 6：withinWindow=0 → 400）。
     * CAS 前置 pending。
     */
    public Result<?> ruleAppeal(Long actorId, String role, Long id, Map<String, Object> body) {
        Result<?> guard = guardAdminOp(actorId, role);
        if (guard != null) {
            return guard;
        }
        if (id == null) {
            return Result.error("申诉 id 不能为空");
        }
        VolunteerAppeal exists = appealMapper.selectById(id);
        if (exists == null) {
            return Result.error(404, "申诉记录不存在");
        }
        if (exists.getWithinWindow() != null && exists.getWithinWindow() == 0) {
            return Result.error("该申诉已超过受理窗口，不能裁定");
        }
        if (!AP_PENDING.equals(exists.getStatus())) {
            return Result.error("仅待受理的申诉可以裁定，当前状态为 "
                    + APPEAL_STATUS_TEXT.getOrDefault(exists.getStatus(), exists.getStatus()));
        }
        String status = trimToNull(pick(body, "status", "action"));
        if (status == null) {
            return Result.error("裁定结果不能为空");
        }
        if (!AP_ACCEPTED.equals(status) && !AP_REJECTED.equals(status) && !AP_CLOSED.equals(status)) {
            return Result.error("裁定结果只能是 accepted、rejected 或 closed");
        }
        String ruling = trimToNull(pick(body, "ruling", "reason"));
        int rows = appealMapper.ruleAppeal(id, AP_PENDING, status, ruling, actorId);
        if (rows == 0) {
            return Result.error(CAS_FAIL);
        }
        audit("APPEAL_RULE", actorId, id, "裁定志愿服务申诉："
                + APPEAL_STATUS_TEXT.getOrDefault(status, status));
        VolunteerAppeal saved = appealMapper.selectById(id);
        return Result.success("申诉" + APPEAL_STATUS_TEXT.getOrDefault(status, status),
                appealView(saved == null ? exists : saved));
    }

    // ==================== 内部：列表分页与视图 ====================

    private Result<?> recordPage(Long scopeUserId, Long scopeCounselorId, Long projectId, String status,
                                 String hourType, String source, String startDate, String endDate,
                                 String keyword, int page, int size) {
        int p = pageNo(page);
        int s = pageSize(size);
        String st = trimToNull(status);
        String ht = trimToNull(hourType);
        if (ht != null && !HT_CREDIT.equals(ht) && !HT_HONOR.equals(ht)) {
            return Result.error("时数类型只能是 credit 或 honor");
        }
        LocalDate start = parseDate(startDate);
        LocalDate end = parseDate(endDate);
        List<VolunteerRecord> rows = recordMapper.selectList(scopeUserId, scopeCounselorId, projectId, st, ht,
                trimToNull(source), start, end, trimToNull(keyword), offsetOf(p, s), s);
        long total = recordMapper.selectCount(scopeUserId, scopeCounselorId, projectId, st, ht,
                trimToNull(source), start, end, trimToNull(keyword));
        List<Map<String, Object>> list = new ArrayList<>();
        for (VolunteerRecord row : rows) {
            list.add(recordView(row, true));
        }
        return Result.success(pageOf(list, total, p, s));
    }

    /** §5 记录响应字段（键名固定，不得改名）。 */
    private static Map<String, Object> recordView(VolunteerRecord r, boolean mask) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("userId", r.getUserId());
        m.put("studentName", mask ? maskedName(r.getStudentName()) : r.getStudentName());
        m.put("studentNo", mask ? maskedStudentNo(r.getStudentNo()) : r.getStudentNo());
        m.put("projectId", r.getProjectId());
        m.put("projectName", r.getProjectName());
        m.put("serviceDate", r.getServiceDate());
        m.put("signIn", r.getSignIn());
        m.put("signOut", r.getSignOut());
        m.put("hours", scale2(r.getHours()));
        m.put("hourType", r.getHourType());
        m.put("hourTypeText", HOUR_TYPE_TEXT.getOrDefault(r.getHourType(), null));
        m.put("source", r.getSource());
        m.put("sourceText", SOURCE_TEXT.getOrDefault(r.getSource(), null));
        m.put("status", r.getStatus());
        m.put("statusText", RECORD_STATUS_TEXT.getOrDefault(r.getStatus(), null));
        m.put("rejectReason", r.getRejectReason());
        m.put("makeupNote", r.getMakeupNote());
        m.put("recorderSubject", r.getRecorderSubject());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    private static Map<String, Object> projectView(VolunteerProject p, String role) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("projectName", p.getProjectName());
        m.put("category", p.getCategory());
        m.put("organizer", p.getOrganizer());
        m.put("organizerType", p.getOrganizerType());
        m.put("organizerTypeText", ORGANIZER_TYPE_TEXT.getOrDefault(p.getOrganizerType(), null));
        m.put("startTime", p.getStartTime());
        m.put("endTime", p.getEndTime());
        m.put("location", p.getLocation());
        m.put("durationNote", p.getDurationNote());
        m.put("recruitLimit", p.getRecruitLimit());
        m.put("enrolledCount", p.getEnrolledCount());
        m.put("submitUserId", p.getSubmitUserId());
        m.put("submitUserName", p.getSubmitUserName());
        m.put("reviewUserId", p.getReviewUserId());
        m.put("reviewUserName", p.getReviewUserName());
        m.put("reviewTime", p.getReviewTime());
        m.put("rejectReason", p.getRejectReason());
        m.put("status", p.getStatus());
        m.put("statusText", PROJECT_STATUS_TEXT.getOrDefault(p.getStatus(), null));
        m.put("createdAt", p.getCreatedAt());
        return m;
    }

    private static Map<String, Object> enrollmentView(VolunteerEnrollment e, String role) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("projectId", e.getProjectId());
        m.put("projectName", e.getProjectName());
        m.put("userId", e.getUserId());
        m.put("studentName", maskedName(e.getStudentName()));
        m.put("studentNo", maskedStudentNo(e.getStudentNo()));
        m.put("className", e.getClassName());
        m.put("applyTime", e.getApplyTime());
        m.put("status", e.getStatus());
        m.put("statusText", ENROLL_STATUS_TEXT.getOrDefault(e.getStatus(), null));
        m.put("remark", e.getRemark());
        return m;
    }

    /** §5 黑名单响应字段（dayLeft 由 SQL DATEDIFF 现算，缺失时服务层补算）。 */
    private static Map<String, Object> blacklistView(VolunteerBlacklist b) {
        LocalDate today = LocalDate.now();
        String status = b.getStatus();
        if (BL_ACTIVE.equals(status) && b.getExpireAt() != null && b.getExpireAt().isBefore(today)) {
            status = BL_EXPIRED;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("userId", b.getUserId());
        m.put("studentName", maskedName(b.getStudentName()));
        m.put("studentNo", maskedStudentNo(b.getStudentNo()));
        m.put("clause", b.getClause());
        m.put("clauseText", CLAUSE_TEXT.getOrDefault(b.getClause(), null));
        m.put("reason", b.getReason());
        m.put("listedAt", b.getListedAt());
        m.put("expireAt", b.getExpireAt());
        m.put("notifyTime", b.getNotifyTime());
        Long daysLeft = b.getDaysLeft();
        if (daysLeft == null && b.getExpireAt() != null) {
            daysLeft = b.getExpireAt().toEpochDay() - today.toEpochDay();
        }
        m.put("daysLeft", daysLeft);
        m.put("status", status);
        m.put("statusText", BLACKLIST_STATUS_TEXT.getOrDefault(status, null));
        return m;
    }

    /** §5 申诉响应字段（deadline = 提交时间起 5 个工作日，与 §11-20 的 5 个工作日口径一致）。 */
    private static Map<String, Object> appealView(VolunteerAppeal a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("userId", a.getUserId());
        m.put("studentName", maskedName(a.getStudentName()));
        m.put("studentNo", maskedStudentNo(a.getStudentNo()));
        m.put("recordId", a.getRecordId());
        m.put("blacklistId", a.getBlacklistId());
        m.put("reason", a.getReason());
        m.put("submitTime", a.getSubmitTime());
        m.put("acceptOrg", a.getAcceptOrg());
        m.put("withinWindow", a.getWithinWindow());
        LocalDateTime deadline = a.getSubmitTime() == null
                ? a.getDeadline()
                : workdayDeadline(a.getSubmitTime(), APPEAL_WORKDAYS).atTime(23, 59, 59);
        m.put("deadline", deadline);
        m.put("status", a.getStatus());
        m.put("statusText", APPEAL_STATUS_TEXT.getOrDefault(a.getStatus(), null));
        m.put("ruling", a.getRuling());
        m.put("rulerId", a.getRulerId());
        m.put("rulingTime", a.getRulingTime());
        return m;
    }

    // ==================== 内部：时长录入与统计 ====================

    /**
     * 组装一条待录入记录（校验全部前置）。返回的 Result 若 code != 200 则为校验失败，
     * data 为校验通过后的实体（此处用 Result.success(data) 承载）。
     */
    private Result<?> buildRecord(Long actorId, String role, Map<String, Object> body,
                                  String source, String prefix) {
        String label = prefix == null ? "" : prefix;
        Long userId = longOf(pick(body, "userId", "user_id"));
        if (userId == null) {
            return Result.error(label + "学生不能为空");
        }
        if (!checkTarget(actorId, role, userId)) {
            return Result.error(403, "无权为该学生录入志愿服务时长");
        }
        LocalDate serviceDate = parseDate(pick(body, "serviceDate", "service_date"));
        if (serviceDate == null) {
            return Result.error(label + "服务日期不能为空");
        }
        BigDecimal hours = dec(pick(body, "hours", "hour"));
        if (hours == null) {
            return Result.error(label + "时长不能为空");
        }
        if (hours.compareTo(BigDecimal.ZERO) <= 0) {
            return Result.error(label + "时长必须大于 0");
        }
        String hourType = trimToNull(pick(body, "hourType", "hour_type"));
        if (hourType != null && !HT_CREDIT.equals(hourType) && !HT_HONOR.equals(hourType)) {
            return Result.error(label + "时数类型只能是 credit 或 honor");
        }
        String subject = trimToNull(pick(body, "recorderSubject", "recorder_subject"));
        if (subject != null && !ORGANIZER_TYPE_VALUES.contains(subject)) {
            return Result.error(label + "录入主体只能是 center、department、club 或 studentActivity");
        }
        if (flag(pick(body, "offCampus", "off_campus")) == 1) {
            return Result.error(label + "校外自行参加的志愿服务不予认定（S21 第二十五条）");
        }
        Long projectId = longOf(pick(body, "projectId", "project_id"));
        if (projectId != null && projectMapper.selectById(projectId) == null) {
            return Result.error(label + "志愿服务项目不存在");
        }
        VolunteerRecord record = new VolunteerRecord();
        record.setUserId(userId);
        record.setProjectId(projectId);
        record.setServiceDate(serviceDate);
        record.setSignIn(parseDateTime(pick(body, "signIn", "sign_in")));
        record.setSignOut(parseDateTime(pick(body, "signOut", "sign_out")));
        record.setHours(scale2(hours));
        record.setHourType(hourType == null ? HT_CREDIT : hourType);
        record.setSource(source);
        record.setRecorderId(actorId);
        record.setRecorderSubject(subject);
        record.setStatus(ST_PENDING);
        record.setMakeupNote(trimToNull(pick(body, "makeupNote", "makeup_note", "remark")));
        return Result.success(record);
    }

    /**
     * S21 第二十二条：单日累计超过 8 小时只提示不拒。已认定时长取 approved 口径，
     * 同批次内同一天多条累加。
     */
    private List<String> dailyWarnings(Long userId, LocalDate serviceDate, BigDecimal hours,
                                       Map<String, BigDecimal> daySum) {
        List<String> warnings = new ArrayList<>();
        if (userId == null || serviceDate == null || hours == null) {
            return warnings;
        }
        String key = userId + "#" + serviceDate;
        BigDecimal base;
        if (daySum != null && daySum.containsKey(key)) {
            base = daySum.get(key);
        } else {
            BigDecimal sum = recordMapper.sumHoursByUserAndDate(userId, serviceDate);
            base = sum == null ? BigDecimal.ZERO : sum;
            if (daySum != null) {
                daySum.put(key, base);
            }
        }
        BigDecimal total = base.add(hours);
        if (daySum != null) {
            daySum.put(key, total);
        }
        if (total.compareTo(DAILY_HOUR_LIMIT) > 0) {
            warnings.add("学生 " + userId + " 在 " + serviceDate + " 累计 " + scale2(total)
                    + " 小时，已超过每日 8 小时认定上限（S21 第二十二条），请核实");
        }
        if (recordMapper.countByUserAndDate(userId, serviceDate) > 1) {
            warnings.add("学生 " + userId + " 在 " + serviceDate + " 已有其它时长记录，请确认是否重复录入");
        }
        return warnings;
    }

    /** 单人时长汇总 + 星级推算（§11-16）。 */
    private Map<String, Object> summaryView(Long userId) {
        BigDecimal total = nz(recordMapper.sumHoursByUser(userId, null, null, null));
        BigDecimal credit = nz(recordMapper.sumHoursByUser(userId, HT_CREDIT, null, null));
        BigDecimal honor = nz(recordMapper.sumHoursByUser(userId, HT_HONOR, null, null));
        LocalDate[] year = schoolYear(LocalDate.now());
        BigDecimal yearHours = nz(recordMapper.sumHoursByUser(userId, null, year[0], year[1]));
        BigDecimal starBase = total;
        int level = 0;
        for (int i = 0; i < STAR_THRESHOLDS.length; i++) {
            if (starBase.compareTo(BigDecimal.valueOf(STAR_THRESHOLDS[i])) >= 0) {
                level = i + 1;
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId);
        data.put("totalHours", scale2(total));
        data.put("creditHours", scale2(credit));
        data.put("honorHours", scale2(honor));
        data.put("yearHours", scale2(yearHours));
        data.put("yearQualified", yearHours.compareTo(YEAR_HOUR_FLOOR) >= 0);
        data.put("yearFloorHours", YEAR_HOUR_FLOOR);
        data.put("yearStart", year[0]);
        data.put("yearEnd", year[1]);
        data.put("starLevel", level);
        data.put("starLevelText", STAR_TEXT[level]);
        data.put("starSource", STAR_SOURCE);
        if (level >= STAR_THRESHOLDS.length) {
            data.put("nextLevelHours", null);
            data.put("remainToNextLevel", scale2(BigDecimal.ZERO));
        } else {
            BigDecimal next = BigDecimal.valueOf(STAR_THRESHOLDS[level]);
            data.put("nextLevelHours", next);
            data.put("remainToNextLevel", scale2(next.subtract(starBase).max(BigDecimal.ZERO)));
        }
        return data;
    }

    /** 校级统计：全部已认定时长（approved 口径，SQL 的 sumHoursByUser 必须带 userId，故此处按行汇总）。 */
    private BigDecimal approvedHours(Long userId) {
        List<VolunteerRecord> rows = recordMapper.selectList(null, null, null, ST_APPROVED, null, null,
                null, null, null, 0, MAX_EXPORT);
        BigDecimal sum = BigDecimal.ZERO;
        for (VolunteerRecord row : rows) {
            if (row.getHours() != null) {
                sum = sum.add(row.getHours());
            }
        }
        return scale2(sum);
    }

    /** 按志愿类别统计项目数（§11-17 byCategory[]）。 */
    private List<Map<String, Object>> countByCategory() {
        List<VolunteerProject> rows = projectMapper.selectList(null, null, null, null, null, null, 0, MAX_EXPORT);
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (VolunteerProject row : rows) {
            String key = row.getCategory() == null ? "未分类" : row.getCategory();
            counter.put(key, counter.getOrDefault(key, 0) + 1);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counter.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", entry.getKey());
            item.put("count", entry.getValue());
            list.add(item);
        }
        return list;
    }

    // ==================== 内部：权限 / 分页 / 工具 ====================

    /**
     * 管理员守卫（队长裁定 2026-09-22）：403 文案必须含「无权」。
     * 社区共享的 CommunityService.guardAdmin 文案为「仅管理员可操作」，不含「无权」，
     * 而管理端页面与 tools/e2e-p17.mjs 统一按「无权」判定越权，故本服务自带守卫。
     */
    private static Result<?> guardAdminOp(Long actorId, String role) {
        Result<?> guard = guardLogin(actorId);
        if (guard != null) {
            return guard;
        }
        return isAdmin(role) ? null : Result.error(403, "无权操作：仅管理员可操作");
    }

    /** 员工（管理员 / 辅导员）守卫沿用社区共享的 {@code guardStaff}（文案「无权访问」已含「无权」）。 */
    private static Result<?> guardStaffOp(Long actorId, String role) {
        return guardStaff(actorId, role);
    }

    /** 教师只能在名下学生范围内操作（admin 放行，student 仅本人）。 */
    private boolean checkTarget(Long actorId, String role, Long targetUserId) {
        if (isAdmin(role)) {
            return true;
        }
        if (actorId != null && actorId.equals(targetUserId)) {
            return true;
        }
        return isStaff(role) && underCounselor(actorId, targetUserId);
    }

    /** 补录窗口基准日（S21 第二十六条）：项目结束日优先，无项目则用服务日期。 */
    private LocalDate makeupWindowBase(Long projectId, LocalDate serviceDate) {
        if (projectId != null) {
            VolunteerProject project = projectMapper.selectById(projectId);
            if (project != null && project.getEndTime() != null) {
                return project.getEndTime().toLocalDate();
            }
        }
        return serviceDate;
    }

    /** student_profile.counselor_id 是否为该辅导员（§6 辅导员边界）。 */
    private boolean underCounselor(Long counselorId, Long userId) {
        if (counselorId == null || userId == null) {
            return false;
        }
        StudentProfile profile = studentProfileMapper.findByUserId(userId);
        return profile != null && counselorId.equals(profile.getCounselorId());
    }

    /** 学年区间：9 月 1 日起算，返回 [start, end]。 */
    private static LocalDate[] schoolYear(LocalDate today) {
        int year = today.getMonthValue() >= 9 ? today.getYear() : today.getYear() - 1;
        return new LocalDate[]{LocalDate.of(year, 9, 1), LocalDate.of(year + 1, 8, 31)};
    }

    /** 5 个工作日后的日期（跳过周六周日，S21 第三十七条）。 */
    private static LocalDate workdayDeadline(LocalDateTime base, int workdays) {
        LocalDate date = base.toLocalDate();
        int count = 0;
        while (count < workdays) {
            date = date.plusDays(1);
            DayOfWeek day = date.getDayOfWeek();
            if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
                count++;
            }
        }
        return date;
    }

    private static Map<String, Object> pageOf(List<?> list, long total, int page, int size) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("total", total);
        data.put("page", page);
        data.put("size", size);
        return data;
    }

    private static int pageNo(int page) {
        return page < 1 ? 1 : page;
    }

    private static int pageSize(int size) {
        if (size < 1) {
            return 20;
        }
        return Math.min(size, 200);
    }

    private static int offsetOf(int page, int size) {
        return (page - 1) * size;
    }

    private static String actionOf(Map<String, Object> body) {
        String action = trimToNull(pick(body, "action", "result"));
        if (ACT_APPROVE.equals(action)) {
            return ACT_APPROVE;
        }
        if (ACT_REJECT.equals(action)) {
            return ACT_REJECT;
        }
        return null;
    }

    /** createProject / updateProject 的「提报审批」开关：submit=true 或 submit=1。 */
    private static boolean submitFlag(Map<String, Object> body) {
        if (flag(pick(body, "submit")) == 1) {
            return true;
        }
        String submit = trimToNull(pick(body, "submit"));
        return "true".equalsIgnoreCase(submit) || "yes".equalsIgnoreCase(submit);
    }

    private static Integer intOf(Object value) {
        if (value == null || isBlank(String.valueOf(value))) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String maskedName(String name) {
        return name == null || name.isEmpty() ? name : maskName(name);
    }

    private static String maskedStudentNo(String studentNo) {
        return studentNo == null || studentNo.isEmpty() ? studentNo : tail4(studentNo);
    }

    /** 每个写操作都落审计（§11-30：类型用现成的 TYPE_VOLUNTEER，路径分支由审计服务按 URL 判定）。 */
    private void audit(String action, Long actorId, Long targetId, String detail) {
        try {
            AuditLog entry = new AuditLog();
            entry.setEventType(AuditLogService.TYPE_VOLUNTEER);
            entry.setEventAction(action);
            entry.setUserId(actorId);
            entry.setTarget(targetId == null ? null : String.valueOf(targetId));
            entry.setSuccess(true);
            entry.setDetail(detail);
            auditLogService.record(entry);
        } catch (Exception e) {
            log.warn("志愿服务模块审计写入失败：action={}, target={}, err={}", action, targetId, e.getMessage());
        }
    }

    /** S21 第二十三、二十四条：交通/培训单次不足 30 分钟不计入时长（口径常量，供审核参考）。 */
    public static final int MIN_COUNTABLE_MINUTES = 30;
}
