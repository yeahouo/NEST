package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.controller.VolunteerController;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P17 志愿服务服务层单元测试（纯 Mockito，不连库、不 @SpringBootTest）。
 *
 * <p>契约：{@code target/p17-plan.md} §3 枚举与中文文案、§4 规则常量、§5 路由与响应字段名、
 * §6 权限 scope、§7 CAS 状态机、§11 入参契约 33 条（与 §5 冲突以 §11 为准）。
 * 被测：{@code service/VolunteerService.java} 与 {@code controller/VolunteerController.java 的 /stats/export}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VolunteerService — P17 志愿服务（立项 / 报名 / 时长 / 星级 / 黑名单 / 申诉 / 导出）")
class VolunteerServiceTest {

    // ==================== 账号口径（与既有测试保持一致） ====================
    private static final long ADMIN = 1L;
    private static final long COUNSELOR = 3L;
    private static final long WANGXM = 4L;
    private static final long ZHAOXH = 5L;
    private static final long OTHER_TEACHER = 6L;

    private static final String ROLE_ADMIN = "admin";
    private static final String ROLE_TEACHER = "teacher";
    private static final String ROLE_STUDENT = "student";

    private static final String CAS_FAIL = CommunityService.CAS_FAIL;
    private static final String STAR_SOURCE = "按 S21 第三十条推算，认证权属青年志愿服务指导中心";
    private static final String CATEGORY = "大型赛会活动";

    // ==================== 固定 id / 日期 ====================
    private static final long PROJECT_ID = 9101L;
    private static final long ENROLLMENT_ID = 9102L;
    private static final long RECORD_ID = 9103L;
    private static final long BLACKLIST_ID = 9104L;
    private static final long APPEAL_ID = 9105L;

    private static final LocalDate TODAY = LocalDate.now();
    private static final LocalDate SERVICE_DATE = TODAY.minusDays(10);

    @Mock
    private VolunteerProjectMapper projectMapper;
    @Mock
    private VolunteerEnrollmentMapper enrollmentMapper;
    @Mock
    private VolunteerRecordMapper recordMapper;
    @Mock
    private VolunteerBlacklistMapper blacklistMapper;
    @Mock
    private VolunteerAppealMapper appealMapper;
    @Mock
    private StudentProfileMapper studentProfileMapper;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private VolunteerService service;

    private final VolunteerController controller = new VolunteerController();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "volunteerService", service);
        // 队长裁定（2026-09-22）：createBlacklist / summaryForUser 会对学生做存在性校验（查无此人 → 404），
        // 默认把常用学生 WANGXM 视为在册（辅导员 COUNSELOR）；未知 userId（如 99999999）仍走 404。
        when(studentProfileMapper.findByUserId(eq(WANGXM))).thenReturn(profileOf(WANGXM, COUNSELOR));
    }

    // ==================================================================
    // 0. 造数 / 断言工具
    // ==================================================================

    /** 显式 LinkedHashMap，避免 {@code Map.of(Object,Object)} 的签名推断坑。 */
    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> projectBody() {
        return body("projectName", "迎新志愿服务", "category", CATEGORY,
                "organizer", "青年志愿服务指导中心", "organizerType", "center");
    }

    private static Map<String, Object> projectBodyWith(Object... extra) {
        Map<String, Object> m = projectBody();
        for (int i = 0; i + 1 < extra.length; i += 2) {
            m.put(String.valueOf(extra[i]), extra[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> recordBody(long userId, String serviceDate, String hours) {
        return body("userId", userId, "serviceDate", serviceDate, "hours", new BigDecimal(hours));
    }

    private static void assertOk(Result<?> r) {
        assertNotNull(r, "响应不应为 null");
        assertEquals(200, r.getCode().intValue(), "应成功，实际：" + r.getMessage());
    }

    private static void assertError(Result<?> r, int expectedCode, String keyword) {
        assertNotNull(r, "响应不应为 null");
        assertEquals(expectedCode, r.getCode().intValue(), "code 不符，message=" + r.getMessage());
        assertNotNull(r.getMessage(), "错误信息不应为 null");
        assertTrue(r.getMessage().contains(keyword),
                "错误信息应含「" + keyword + "」，实际：" + r.getMessage());
    }

    private static int num(Object v) {
        assertNotNull(v, "数值不应为 null");
        return ((Number) v).intValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(Result<?> r) {
        assertOk(r);
        Object data = r.getData();
        assertNotNull(data, "data 不应为 null");
        return (Map<String, Object>) data;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(Result<?> r) {
        Map<String, Object> data = dataOf(r);
        Object list = data.get("list");
        assertNotNull(list, "data.list 不应为 null");
        return (List<Map<String, Object>>) list;
    }

    private static VolunteerProject project(long id, String status, Long submitUserId) {
        VolunteerProject p = new VolunteerProject();
        p.setId(id);
        p.setProjectName("迎新志愿服务");
        p.setCategory(CATEGORY);
        p.setOrganizer("青年志愿服务指导中心");
        p.setOrganizerType("center");
        p.setStatus(status);
        p.setSubmitUserId(submitUserId);
        return p;
    }

    private static VolunteerEnrollment enrollment(long id, long projectId, long userId, String status) {
        VolunteerEnrollment e = new VolunteerEnrollment();
        e.setId(id);
        e.setProjectId(projectId);
        e.setUserId(userId);
        e.setStatus(status);
        e.setProjectName("迎新志愿服务");
        return e;
    }

    private static VolunteerRecord record(long id, long userId, String status) {
        VolunteerRecord r = new VolunteerRecord();
        r.setId(id);
        r.setUserId(userId);
        r.setServiceDate(SERVICE_DATE);
        r.setHours(new BigDecimal("4"));
        r.setHourType("credit");
        r.setSource("platform");
        r.setRecorderId(COUNSELOR);
        r.setStatus(status);
        return r;
    }

    private static VolunteerBlacklist blacklist(long id, long userId, String status, LocalDate listedAt) {
        VolunteerBlacklist b = new VolunteerBlacklist();
        b.setId(id);
        b.setUserId(userId);
        b.setClause("c34_1");
        b.setReason("无故缺席");
        b.setListedAt(listedAt);
        b.setExpireAt(listedAt.plusMonths(6));
        b.setStatus(status);
        b.setOperatorId(ADMIN);
        return b;
    }

    private static VolunteerAppeal appeal(long id, long userId, String status) {
        VolunteerAppeal a = new VolunteerAppeal();
        a.setId(id);
        a.setUserId(userId);
        a.setStatus(status);
        a.setReason("有异议");
        a.setSubmitTime(TODAY.minusDays(2).atTime(10, 0));
        a.setAcceptOrg("团委");
        return a;
    }

    private static StudentProfile profileOf(long userId, Long counselorId) {
        StudentProfile p = new StudentProfile();
        p.setUserId(userId);
        p.setCounselorId(counselorId);
        p.setName("王小明");
        p.setStudentNo("2024001");
        p.setClassName("计算机 2401");
        return p;
    }

    private VolunteerProject lastInsertedProject() {
        ArgumentCaptor<VolunteerProject> c = ArgumentCaptor.forClass(VolunteerProject.class);
        verify(projectMapper, atLeastOnce()).insert(c.capture());
        return c.getValue();
    }

    /** 覆盖被测用户（学生）的辅导员范围，供 teacher 录入用。 */
    private void stubUnderCounselor(long userId, long counselorId) {
        when(studentProfileMapper.findByUserId(eq(userId))).thenReturn(profileOf(userId, counselorId));
    }

    /** 汇总口径：total 走「无 hourType、无日期」那次，year 走「学年日期」那次。 */
    private void stubHours(long userId, BigDecimal total, BigDecimal yearHours) {
        when(recordMapper.sumHoursByUser(eq(userId), isNull(), isNull(), isNull())).thenReturn(total);
        when(recordMapper.sumHoursByUser(eq(userId), isNull(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(yearHours);
    }

    private static LocalDate lastFriday() {
        LocalDate d = LocalDate.now();
        while (d.getDayOfWeek() != DayOfWeek.FRIDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    private static MockHttpSession session(Object userId, Object role) {
        MockHttpSession s = new MockHttpSession();
        if (userId != null) {
            s.setAttribute("userId", userId);
        }
        if (role != null) {
            s.setAttribute("role", role);
        }
        return s;
    }

    // ==================================================================
    // 1. project：新建 / 修改 / 审批
    // ==================================================================

    @Test
    @DisplayName("1.1 createProject：缺 submit 落 draft 草稿；submit=true 落 pending（§11-5）")
    void createProjectDraftThenSubmit() {
        Result<?> draft = service.createProject(COUNSELOR, ROLE_TEACHER, projectBody());
        assertEquals("立项草稿已保存", draft.getMessage());
        assertEquals("draft", dataOf(draft).get("status"));
        assertEquals(COUNSELOR, lastInsertedProject().getSubmitUserId());
        assertEquals("draft", lastInsertedProject().getStatus());

        Result<?> pending = service.createProject(COUNSELOR, ROLE_TEACHER,
                projectBodyWith("submit", Boolean.TRUE));
        assertEquals("立项申请已提交，等待审核", pending.getMessage());
        assertEquals("pending", dataOf(pending).get("status"));
        VolunteerProject inserted = lastInsertedProject();
        assertEquals("pending", inserted.getStatus());
        assertEquals(CATEGORY, inserted.getCategory());
        assertNull(inserted.getRejectReason());
        verify(projectMapper, times(2)).insert(any(VolunteerProject.class));
    }

    @Test
    @DisplayName("1.2 createProject：缺必填 / 枚举非法 / 负数上限 / 结束早于开始 → 400，且不落库")
    void createProjectValidationNegatives() {
        assertError(service.createProject(COUNSELOR, ROLE_TEACHER,
                body("category", CATEGORY, "organizer", "中心", "organizerType", "center")),
                400, "项目名称不能为空");
        assertError(service.createProject(COUNSELOR, ROLE_TEACHER,
                projectBodyWith("category", "不存在的类别")), 400, "只能是");
        assertError(service.createProject(COUNSELOR, ROLE_TEACHER,
                projectBodyWith("organizerType", "company")), 400, "只能是 center");
        assertError(service.createProject(COUNSELOR, ROLE_TEACHER,
                projectBodyWith("recruitLimit", -1)), 400, "招募人数上限不能为负数");
        assertError(service.createProject(COUNSELOR, ROLE_TEACHER,
                projectBodyWith("startTime", "2026-10-10T10:00", "endTime", "2026-10-10T09:00")),
                400, "项目结束时间不能早于开始时间");
        verify(projectMapper, never()).insert(any(VolunteerProject.class));
    }

    @Test
    @DisplayName("1.3 createProject：未登录 / 学生 → 401 / 403（§6）")
    void createProjectPermission() {
        assertError(service.createProject(null, ROLE_TEACHER, projectBody()), 401, "请先登录");
        assertError(service.createProject(WANGXM, ROLE_STUDENT, projectBody()), 403, "无权访问");
    }

    @Test
    @DisplayName("1.4 updateProject：submit=true 与 {\"status\":\"pending\"} 都置 pending；不带则保留原状态（§11-6）")
    void updateProjectSubmitFlagOrStatusKey() {
        when(projectMapper.update(any(VolunteerProject.class), anyString())).thenReturn(1);

        // draft + submit=true → pending
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(
                project(PROJECT_ID, "draft", COUNSELOR), project(PROJECT_ID, "pending", COUNSELOR));
        Result<?> byFlag = service.updateProject(COUNSELOR, ROLE_TEACHER, PROJECT_ID,
                projectBodyWith("submit", Boolean.TRUE));
        assertEquals("立项申请已重新提交，等待审核", byFlag.getMessage());

        // rejected + {"status":"pending"} → pending
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(
                project(PROJECT_ID, "rejected", COUNSELOR), project(PROJECT_ID, "pending", COUNSELOR));
        Result<?> byStatus = service.updateProject(COUNSELOR, ROLE_TEACHER, PROJECT_ID,
                projectBodyWith("status", "pending"));
        assertEquals("立项申请已重新提交，等待审核", byStatus.getMessage());
        assertEquals("pending", dataOf(byStatus).get("status"));

        // draft + 都不带 → 保持 draft
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(
                project(PROJECT_ID, "draft", COUNSELOR), project(PROJECT_ID, "draft", COUNSELOR));
        Result<?> keep = service.updateProject(COUNSELOR, ROLE_TEACHER, PROJECT_ID, projectBody());
        assertEquals("立项信息已保存", keep.getMessage());
        assertEquals("draft", dataOf(keep).get("status"));

        ArgumentCaptor<VolunteerProject> cp = ArgumentCaptor.forClass(VolunteerProject.class);
        ArgumentCaptor<String> cf = ArgumentCaptor.forClass(String.class);
        verify(projectMapper, times(3)).update(cp.capture(), cf.capture());
        assertEquals("pending", cp.getAllValues().get(0).getStatus());
        assertEquals("pending", cp.getAllValues().get(1).getStatus());
        assertEquals("draft", cp.getAllValues().get(2).getStatus());
        assertEquals("draft", cf.getAllValues().get(0));
        assertEquals("rejected", cf.getAllValues().get(1));
    }

    @Test
    @DisplayName("1.5 updateProject：approved → 400；辅导员改他人提报 → 403（§11-6）")
    void updateProjectStatusAndOwnershipNegatives() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID, "approved", COUNSELOR));
        assertError(service.updateProject(COUNSELOR, ROLE_TEACHER, PROJECT_ID, projectBody()),
                400, "仅草稿或已驳回的项目可以修改");

        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID, "draft", COUNSELOR));
        assertError(service.updateProject(OTHER_TEACHER, ROLE_TEACHER, PROJECT_ID, projectBody()),
                403, "无权修改他人提报的项目");

        // admin 不受提报人限制（但仍受状态限制）
        when(projectMapper.update(any(VolunteerProject.class), anyString())).thenReturn(1);
        assertOk(service.updateProject(ADMIN, ROLE_ADMIN, PROJECT_ID, projectBody()));
        verify(projectMapper, never()).update(any(VolunteerProject.class), eq("approved"));
    }

    @Test
    @DisplayName("1.6 updateProject：CAS 影响 0 行 → 400 状态已变化（§11-3/§7）")
    void updateProjectCasFail() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID, "draft", COUNSELOR));
        when(projectMapper.update(any(VolunteerProject.class), anyString())).thenReturn(0);
        assertError(service.updateProject(COUNSELOR, ROLE_TEACHER, PROJECT_ID, projectBody()),
                400, CAS_FAIL);
    }

    @Test
    @DisplayName("1.7 reviewProject：approve → approved；reject + 原因 → rejected（§11-7）")
    void reviewProjectApproveAndReject() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID, "pending", COUNSELOR));
        when(projectMapper.reviewProject(any(), anyString(), anyString(), any(), any())).thenReturn(1);

        Result<?> approve = service.reviewProject(ADMIN, ROLE_ADMIN, PROJECT_ID, body("action", "approve"));
        assertEquals("立项申请已批准", approve.getMessage());
        Result<?> reject = service.reviewProject(ADMIN, ROLE_ADMIN, PROJECT_ID,
                body("action", "reject", "rejectReason", "材料不全"));
        assertEquals("立项申请已驳回", reject.getMessage());

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(projectMapper, times(2))
                .reviewProject(eq(PROJECT_ID), eq("pending"), to.capture(), eq(ADMIN), reason.capture());
        assertEquals("approved", to.getAllValues().get(0));
        assertEquals("rejected", to.getAllValues().get(1));
        assertNull(reason.getAllValues().get(0));
        assertEquals("材料不全", reason.getAllValues().get(1));
    }

    @Test
    @DisplayName("1.8 reviewProject：reject 无原因 / action 非法 / 非 pending 重复审批 → 400；非 admin → 403")
    void reviewProjectNegatives() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID, "pending", COUNSELOR));
        Map<String, Object> rejectNoReason = body("action", "reject");
        assertError(service.reviewProject(ADMIN, ROLE_ADMIN, PROJECT_ID, rejectNoReason),
                400, "驳回时必须填写驳回原因");
        assertError(service.reviewProject(ADMIN, ROLE_ADMIN, PROJECT_ID, body("action", "pass")),
                400, "action 只能是 approve 或 reject");
        assertError(service.reviewProject(COUNSELOR, ROLE_TEACHER, PROJECT_ID, body("action", "approve")),
                403, "仅管理员可操作");

        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID, "approved", COUNSELOR));
        assertError(service.reviewProject(ADMIN, ROLE_ADMIN, PROJECT_ID, body("action", "approve")),
                400, "仅待审批的项目可以审核");
        verify(projectMapper, never()).reviewProject(any(), anyString(), anyString(), any(), any());
    }

    // ==================================================================
    // 2. enrollment：报名 / 取消 / 出勤
    // ==================================================================

    @Test
    @DisplayName("2.1 enrollProject：插入即 confirmed；重复报名 → 400；approved 之外 → 400（§11-8）")
    void enrollProjectConfirmedDuplicateAndStatus() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID, "approved", COUNSELOR));
        assertOk(service.enrollProject(WANGXM, ROLE_STUDENT, PROJECT_ID));
        ArgumentCaptor<VolunteerEnrollment> c = ArgumentCaptor.forClass(VolunteerEnrollment.class);
        verify(enrollmentMapper).insert(c.capture());
        assertEquals("confirmed", c.getValue().getStatus());
        assertEquals(Long.valueOf(WANGXM), c.getValue().getUserId());
        assertEquals(Long.valueOf(PROJECT_ID), c.getValue().getProjectId());
        assertNotNull(c.getValue().getApplyTime());

        // 未 cancelled 的重复报名 → 400
        when(enrollmentMapper.selectByProjectAndUser(PROJECT_ID, WANGXM))
                .thenReturn(enrollment(ENROLLMENT_ID, PROJECT_ID, WANGXM, "confirmed"));
        assertError(service.enrollProject(WANGXM, ROLE_STUDENT, PROJECT_ID), 400, "您已报名");

        // 已 cancelled 允许重新报名
        when(enrollmentMapper.selectByProjectAndUser(PROJECT_ID, WANGXM))
                .thenReturn(enrollment(ENROLLMENT_ID, PROJECT_ID, WANGXM, "cancelled"));
        assertOk(service.enrollProject(WANGXM, ROLE_STUDENT, PROJECT_ID));

        // 非 approved 项目
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID, "pending", COUNSELOR));
        assertError(service.enrollProject(WANGXM, ROLE_STUDENT, PROJECT_ID), 400, "仅已批准的项目可以报名");
    }

    @Test
    @DisplayName("2.2 enrollProject：名额已满 → 400（§11-8）")
    void enrollProjectRecruitLimit() {
        VolunteerProject p = project(PROJECT_ID, "approved", COUNSELOR);
        p.setRecruitLimit(2);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        when(enrollmentMapper.countByProjectAndStatus(PROJECT_ID, "confirmed")).thenReturn(1L);
        when(enrollmentMapper.countByProjectAndStatus(PROJECT_ID, "attended")).thenReturn(1L);
        assertError(service.enrollProject(WANGXM, ROLE_STUDENT, PROJECT_ID), 400, "名额已满");
        verify(enrollmentMapper, never()).insert(any(VolunteerEnrollment.class));
    }

    @Test
    @DisplayName("2.3 cancelEnrollment：本人 → cancelled；他人（学生）→ 403；辅导员名下 → 放行")
    void cancelEnrollmentOwnership() {
        when(enrollmentMapper.cancelEnrollment(eq(ENROLLMENT_ID), eq("confirmed"), any())).thenReturn(1);
        when(enrollmentMapper.selectById(ENROLLMENT_ID))
                .thenReturn(enrollment(ENROLLMENT_ID, PROJECT_ID, WANGXM, "confirmed"));

        Result<?> own = service.cancelEnrollment(WANGXM, ROLE_STUDENT, ENROLLMENT_ID, body("remark", "临时有事"));
        assertEquals("报名已取消", own.getMessage());

        assertError(service.cancelEnrollment(ZHAOXH, ROLE_STUDENT, ENROLLMENT_ID, null),
                403, "无权取消他人的报名");

        // 辅导员 + 名下学生 → 放行
        stubUnderCounselor(WANGXM, COUNSELOR);
        assertOk(service.cancelEnrollment(COUNSELOR, ROLE_TEACHER, ENROLLMENT_ID, null));

        // admin → 放行
        assertOk(service.cancelEnrollment(ADMIN, ROLE_ADMIN, ENROLLMENT_ID, null));

        ArgumentCaptor<String> from = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> remark = ArgumentCaptor.forClass(String.class);
        verify(enrollmentMapper, times(3)).cancelEnrollment(eq(ENROLLMENT_ID), from.capture(), remark.capture());
        assertEquals("confirmed", from.getAllValues().get(0));
        assertEquals("临时有事", remark.getAllValues().get(0));
    }

    @Test
    @DisplayName("2.4 cancelEnrollment：非 pending/confirmed 状态与 CAS 0 行 → 400（§11-3/§11-10）")
    void cancelEnrollmentStatusNegatives() {
        when(enrollmentMapper.selectById(ENROLLMENT_ID))
                .thenReturn(enrollment(ENROLLMENT_ID, PROJECT_ID, WANGXM, "attended"));
        assertError(service.cancelEnrollment(WANGXM, ROLE_STUDENT, ENROLLMENT_ID, null),
                400, "仅待确认或已确认的报名可以取消");

        when(enrollmentMapper.selectById(ENROLLMENT_ID))
                .thenReturn(enrollment(ENROLLMENT_ID, PROJECT_ID, WANGXM, "pending"));
        when(enrollmentMapper.cancelEnrollment(eq(ENROLLMENT_ID), anyString(), any())).thenReturn(0);
        assertError(service.cancelEnrollment(WANGXM, ROLE_STUDENT, ENROLLMENT_ID, null), 400, CAS_FAIL);
    }

    @Test
    @DisplayName("2.5 markAttendance：confirmed 上 attended / absent 成功；cancelled → 400；学生 → 403；非法值 → 400")
    void markAttendanceMatrix() {
        when(enrollmentMapper.selectById(ENROLLMENT_ID))
                .thenReturn(enrollment(ENROLLMENT_ID, PROJECT_ID, WANGXM, "confirmed"));
        when(enrollmentMapper.markAttendance(eq(ENROLLMENT_ID), eq("confirmed"), anyString())).thenReturn(1);

        Result<?> attended = service.markAttendance(ADMIN, ROLE_ADMIN, ENROLLMENT_ID, body("status", "attended"));
        assertOk(attended);
        assertTrue(attended.getMessage().contains("登记成功"));
        assertOk(service.markAttendance(ADMIN, ROLE_ADMIN, ENROLLMENT_ID, body("status", "absent")));

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        verify(enrollmentMapper, times(2)).markAttendance(eq(ENROLLMENT_ID), eq("confirmed"), to.capture());
        assertEquals("attended", to.getAllValues().get(0));
        assertEquals("absent", to.getAllValues().get(1));

        // 学生调用 → 403
        assertError(service.markAttendance(WANGXM, ROLE_STUDENT, ENROLLMENT_ID, body("status", "attended")),
                403, "无权");
        // 非法 status
        assertError(service.markAttendance(ADMIN, ROLE_ADMIN, ENROLLMENT_ID, body("status", "present")),
                400, "只能是 attended 或 absent");
        // 空 status
        assertError(service.markAttendance(ADMIN, ROLE_ADMIN, ENROLLMENT_ID, body()),
                400, "出勤状态不能为空");

        // cancelled 上登记 → 400
        when(enrollmentMapper.selectById(ENROLLMENT_ID))
                .thenReturn(enrollment(ENROLLMENT_ID, PROJECT_ID, WANGXM, "cancelled"));
        assertError(service.markAttendance(ADMIN, ROLE_ADMIN, ENROLLMENT_ID, body("status", "attended")),
                400, "仅已确认的报名可以登记出勤");
    }

    // ==================================================================
    // 3. GET /enrollments 角色口径（§6 / §11-22）
    // ==================================================================

    @Test
    @DisplayName("3.1 listEnrollments 学生：scopeUserId=本人，不带他人行（mine 参数无法放宽）")
    void listEnrollmentsStudentScope() {
        when(enrollmentMapper.selectList(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(0) == null
                        ? Collections.singletonList(enrollment(ENROLLMENT_ID + 1, PROJECT_ID, ZHAOXH, "confirmed"))
                        : Collections.singletonList(enrollment(ENROLLMENT_ID, PROJECT_ID, WANGXM, "confirmed")));
        when(enrollmentMapper.selectCount(any(), any(), any(), any(), any())).thenReturn(1L);

        Result<?> r = service.listEnrollments(WANGXM, ROLE_STUDENT, null, null, null, 1, 20);
        List<Map<String, Object>> rows = rowsOf(r);
        assertEquals(1, rows.size());
        assertEquals(Long.valueOf(WANGXM), rows.get(0).get("userId"));
        assertEquals(1, num(dataOf(r).get("total")));

        ArgumentCaptor<Long> scopeUser = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> scopeCounselor = ArgumentCaptor.forClass(Long.class);
        verify(enrollmentMapper).selectList(scopeUser.capture(), scopeCounselor.capture(),
                any(), any(), any(), anyInt(), anyInt());
        assertEquals(Long.valueOf(WANGXM), scopeUser.getValue());
        assertNull(scopeCounselor.getValue(), "学生口径不得带辅导员 scope");
    }

    @Test
    @DisplayName("3.2 listEnrollments 辅导员：按 scopeCounselorId=本人过滤")
    void listEnrollmentsTeacherScope() {
        when(enrollmentMapper.selectList(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(1) == null
                        ? Collections.singletonList(enrollment(ENROLLMENT_ID + 9, PROJECT_ID, ZHAOXH, "confirmed"))
                        : Collections.singletonList(enrollment(ENROLLMENT_ID, PROJECT_ID, WANGXM, "confirmed")));
        when(enrollmentMapper.selectCount(any(), any(), any(), any(), any())).thenReturn(1L);

        Result<?> r = service.listEnrollments(COUNSELOR, ROLE_TEACHER, null, null, null, 1, 20);
        List<Map<String, Object>> rows = rowsOf(r);
        assertEquals(1, rows.size());
        assertEquals(Long.valueOf(WANGXM), rows.get(0).get("userId"));

        ArgumentCaptor<Long> scopeUser = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> scopeCounselor = ArgumentCaptor.forClass(Long.class);
        verify(enrollmentMapper).selectList(scopeUser.capture(), scopeCounselor.capture(),
                any(), any(), any(), anyInt(), anyInt());
        assertNull(scopeUser.getValue(), "辅导员口径不带学生 scope");
        assertEquals(Long.valueOf(COUNSELOR), scopeCounselor.getValue());
    }

    @Test
    @DisplayName("3.3 listEnrollments admin：全量，不带任何 scope 过滤")
    void listEnrollmentsAdminScope() {
        when(enrollmentMapper.selectList(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(java.util.Arrays.asList(
                        enrollment(ENROLLMENT_ID, PROJECT_ID, WANGXM, "confirmed"),
                        enrollment(ENROLLMENT_ID + 1, PROJECT_ID, ZHAOXH, "confirmed")));
        when(enrollmentMapper.selectCount(any(), any(), any(), any(), any())).thenReturn(2L);

        Result<?> r = service.listEnrollments(ADMIN, ROLE_ADMIN, null, null, null, 1, 20);
        assertEquals(2, rowsOf(r).size());

        ArgumentCaptor<Long> scopeUser = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> scopeCounselor = ArgumentCaptor.forClass(Long.class);
        verify(enrollmentMapper).selectList(scopeUser.capture(), scopeCounselor.capture(),
                any(), any(), any(), anyInt(), anyInt());
        assertNull(scopeUser.getValue());
        assertNull(scopeCounselor.getValue());
    }

    @Test
    @DisplayName("3.4 listEnrollments 未登录 → 401；列表行姓名字号打码（§11-2 脱敏）")
    void listEnrollmentsGuardAndMask() {
        assertError(service.listEnrollments(null, ROLE_STUDENT, null, null, null, 1, 20), 401, "请先登录");

        VolunteerEnrollment e = enrollment(ENROLLMENT_ID, PROJECT_ID, WANGXM, "confirmed");
        e.setStudentName("王小明");
        e.setStudentNo("2024001");
        when(enrollmentMapper.selectList(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(e));
        when(enrollmentMapper.selectCount(any(), any(), any(), any(), any())).thenReturn(1L);

        List<Map<String, Object>> rows = rowsOf(service.listEnrollments(WANGXM, ROLE_STUDENT, null, null, null, 1, 20));
        assertEquals("王*", rows.get(0).get("studentName"));
        assertEquals("4001", rows.get(0).get("studentNo"));
    }

    // ==================================================================
    // 4. record：录入 / 批量 / 审核 / 补录
    // ==================================================================

    @Test
    @DisplayName("4.1 createRecord 学生：服务端置 source=makeup、userId 强制本人（§11-11）")
    void createRecordStudentForcedSelf() {
        stubUnderCounselor(WANGXM, COUNSELOR);
        Result<?> r = service.createRecord(WANGXM, ROLE_STUDENT,
                body("serviceDate", SERVICE_DATE.toString(), "hours", new BigDecimal("4")));
        assertEquals("时长已录入，等待审核", r.getMessage());
        Map<String, Object> data = dataOf(r);
        assertEquals("makeup", data.get("source"));
        assertEquals("pending", data.get("status"));
        assertEquals(Long.valueOf(WANGXM), data.get("userId"));

        ArgumentCaptor<VolunteerRecord> c = ArgumentCaptor.forClass(VolunteerRecord.class);
        verify(recordMapper).insert(c.capture());
        assertEquals(Long.valueOf(WANGXM), c.getValue().getUserId());
        assertEquals("makeup", c.getValue().getSource());
        assertEquals("pending", c.getValue().getStatus());
        assertEquals("credit", c.getValue().getHourType());
        assertEquals(0, new BigDecimal("4.00").compareTo(c.getValue().getHours()));
    }

    @Test
    @DisplayName("4.2 createRecord 学生：显式传他人 userId → 403（§11-11 / §6）")
    void createRecordStudentOtherUser() {
        assertError(service.createRecord(WANGXM, ROLE_STUDENT, recordBody(ZHAOXH, SERVICE_DATE.toString(), "4")),
                403, "无权为他人的志愿服务时长记录录入");
        verify(recordMapper, never()).insert(any(VolunteerRecord.class));
    }

    @Test
    @DisplayName("4.3 createRecord 教师：source=platform，单日累计超 8 小时只进 warnings（§11-11 / §4）")
    void createRecordTeacherAndDailyWarnings() {
        stubUnderCounselor(WANGXM, COUNSELOR);
        when(recordMapper.sumHoursByUserAndDate(eq(WANGXM), any(LocalDate.class))).thenReturn(new BigDecimal("6"));
        Result<?> r = service.createRecord(COUNSELOR, ROLE_TEACHER,
                body("userId", WANGXM, "serviceDate", SERVICE_DATE.toString(), "hours", new BigDecimal("3")));
        assertOk(r);
        Map<String, Object> data = dataOf(r);
        assertEquals("platform", data.get("source"));
        assertTrue(r.getMessage().contains("有提示"));

        List<?> warnings = (List<?>) data.get("warnings");
        assertNotNull(warnings);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).toString().contains("已超过每日 8 小时"));
    }

    @Test
    @DisplayName("4.4 createRecord：字段负例（缺学生 / 缺日期 / 非正时长 / 非法时数类型 / 校外）")
    void createRecordFieldNegatives() {
        stubUnderCounselor(WANGXM, COUNSELOR);
        assertError(service.createRecord(COUNSELOR, ROLE_TEACHER,
                body("serviceDate", SERVICE_DATE.toString(), "hours", new BigDecimal("4"))),
                400, "学生不能为空");
        assertError(service.createRecord(COUNSELOR, ROLE_TEACHER,
                body("userId", WANGXM, "hours", new BigDecimal("4"))), 400, "服务日期不能为空");
        assertError(service.createRecord(COUNSELOR, ROLE_TEACHER,
                body("userId", WANGXM, "serviceDate", SERVICE_DATE.toString(), "hours", new BigDecimal("0"))),
                400, "时长必须大于 0");
        assertError(service.createRecord(COUNSELOR, ROLE_TEACHER,
                body("userId", WANGXM, "serviceDate", SERVICE_DATE.toString(),
                        "hours", new BigDecimal("4"), "hourType", "money")),
                400, "时数类型只能是 credit 或 honor");
        assertError(service.createRecord(COUNSELOR, ROLE_TEACHER,
                body("userId", WANGXM, "serviceDate", SERVICE_DATE.toString(),
                        "hours", new BigDecimal("4"), "offCampus", Boolean.TRUE)),
                400, "校外自行参加的志愿服务不予认定");
        // 教师为学生录入时必须在辅导员名下
        assertError(service.createRecord(OTHER_TEACHER, ROLE_TEACHER,
                recordBody(WANGXM, SERVICE_DATE.toString(), "4")), 403, "无权为该学生录入志愿服务时长");
    }

    @Test
    @DisplayName("4.5 batchCreateRecords：body 键 items 与 records 都接受，返回 created + warnings（§11-12）")
    void batchCreateAcceptsItemsAndRecordsKeys() {
        stubUnderCounselor(WANGXM, COUNSELOR);
        when(recordMapper.insertBatch(anyList())).thenReturn(1);

        List<Map<String, Object>> items = Collections.singletonList(
                body("userId", WANGXM, "serviceDate", SERVICE_DATE.toString(), "hours", new BigDecimal("4")));
        Result<?> byItems = service.batchCreateRecords(COUNSELOR, ROLE_TEACHER, body("items", items));
        assertEquals("批量录入完成，共 1 条", byItems.getMessage());
        Map<String, Object> data = dataOf(byItems);
        assertEquals(1, num(data.get("created")));
        assertTrue(((List<?>) data.get("warnings")).isEmpty());

        Result<?> byRecords = service.batchCreateRecords(COUNSELOR, ROLE_TEACHER, body("records", items));
        assertEquals("批量录入完成，共 1 条", byRecords.getMessage());
        assertEquals(1, num(dataOf(byRecords).get("created")));

        ArgumentCaptor<List<VolunteerRecord>> c = ArgumentCaptor.forClass(List.class);
        verify(recordMapper, times(2)).insertBatch(c.capture());
        assertEquals(1, c.getAllValues().get(0).size());
        assertEquals("platform", c.getAllValues().get(0).get(0).getSource());
    }

    @Test
    @DisplayName("4.6 batchCreateRecords：坏行只进 warnings 且带「第 N 条：」前缀；空明细 → 400（§11-12）")
    void batchCreateWarningsAndEmpty() {
        stubUnderCounselor(WANGXM, COUNSELOR);
        when(recordMapper.insertBatch(anyList())).thenReturn(1);

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(body("userId", WANGXM, "serviceDate", SERVICE_DATE.toString(), "hours", new BigDecimal("4")));
        rows.add(body("userId", WANGXM, "serviceDate", SERVICE_DATE.toString()));
        Result<?> r = service.batchCreateRecords(COUNSELOR, ROLE_TEACHER, body("items", rows));
        Map<String, Object> data = dataOf(r);
        assertEquals(1, num(data.get("created")));
        List<?> warnings = (List<?>) data.get("warnings");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).toString().startsWith("第 2 条："));
        assertTrue(warnings.get(0).toString().contains("时长不能为空"));

        assertError(service.batchCreateRecords(COUNSELOR, ROLE_TEACHER, body("items", Collections.emptyList())),
                400, "录入明细不能为空");
        assertError(service.batchCreateRecords(WANGXM, ROLE_STUDENT, body("items", rows)), 403, "无权访问");
    }

    @Test
    @DisplayName("4.7 reviewRecord：approve → approved；reject + 原因 → rejected（§11-13）")
    void reviewRecordApproveAndReject() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record(RECORD_ID, WANGXM, "pending"));
        when(recordMapper.reviewRecord(any(), anyString(), anyString(), any(), any())).thenReturn(1);

        assertEquals("时长记录已通过", service.reviewRecord(ADMIN, ROLE_ADMIN, RECORD_ID,
                body("action", "approve")).getMessage());
        assertEquals("时长记录已驳回", service.reviewRecord(ADMIN, ROLE_ADMIN, RECORD_ID,
                body("action", "reject", "rejectReason", "材料不全")).getMessage());

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(recordMapper, times(2))
                .reviewRecord(eq(RECORD_ID), eq("pending"), to.capture(), eq(ADMIN), reason.capture());
        assertEquals("approved", to.getAllValues().get(0));
        assertEquals("rejected", to.getAllValues().get(1));
        assertNull(reason.getAllValues().get(0));
        assertEquals("材料不全", reason.getAllValues().get(1));
    }

    @Test
    @DisplayName("4.8 reviewRecord：reject 无原因 / 终态再审 → 400；非 admin → 403（§7 / §11-13）")
    void reviewRecordNegatives() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record(RECORD_ID, WANGXM, "pending"));
        assertError(service.reviewRecord(ADMIN, ROLE_ADMIN, RECORD_ID, body("action", "reject")),
                400, "驳回时必须填写驳回原因");
        assertError(service.reviewRecord(COUNSELOR, ROLE_TEACHER, RECORD_ID, body("action", "approve")),
                403, "仅管理员可操作");

        when(recordMapper.selectById(RECORD_ID)).thenReturn(record(RECORD_ID, WANGXM, "approved"));
        assertError(service.reviewRecord(ADMIN, ROLE_ADMIN, RECORD_ID, body("action", "approve")),
                400, "仅待审核的记录可以审核");
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record(RECORD_ID, WANGXM, "rejected"));
        assertError(service.reviewRecord(ADMIN, ROLE_ADMIN, RECORD_ID, body("action", "approve")),
                400, "仅待审核的记录可以审核");
        verify(recordMapper, never()).reviewRecord(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("4.9 makeupRecord：仅 rejected 可补录，生成新记录（source=makeup / pending），原记录不动（§11-14）")
    void makeupCreatesNewRecordFromRejected() {
        VolunteerRecord origin = record(RECORD_ID, WANGXM, "rejected");
        when(recordMapper.selectById(RECORD_ID)).thenReturn(origin);

        Result<?> r = service.makeupRecord(WANGXM, ROLE_STUDENT, RECORD_ID,
                body("hours", new BigDecimal("2"), "makeupNote", "补录说明"));
        assertEquals("补录成功，等待审核", r.getMessage());
        Map<String, Object> data = dataOf(r);
        assertEquals("makeup", data.get("source"));
        assertEquals("pending", data.get("status"));
        assertEquals(Long.valueOf(RECORD_ID), data.get("originRecordId"));

        ArgumentCaptor<VolunteerRecord> c = ArgumentCaptor.forClass(VolunteerRecord.class);
        verify(recordMapper).insert(c.capture());
        VolunteerRecord created = c.getValue();
        assertNotEquals(origin, created);
        assertNull(created.getId(), "补录应生成新记录（无 id）");
        assertEquals(Long.valueOf(WANGXM), created.getUserId());
        assertEquals("makeup", created.getSource());
        assertEquals("pending", created.getStatus());
        assertEquals(0, new BigDecimal("2.00").compareTo(created.getHours()));
        assertEquals("补录说明", created.getMakeupNote());
        // 原记录未被修改（§11-14）
        assertEquals("rejected", origin.getStatus());
        verify(recordMapper, never()).update(any(VolunteerRecord.class), anyString());
    }

    @Test
    @DisplayName("4.10 makeupRecord：approved → 400；他人记录 → 403（§11-14 / §6）")
    void makeupStatusAndOwnershipNegatives() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record(RECORD_ID, WANGXM, "approved"));
        assertError(service.makeupRecord(WANGXM, ROLE_STUDENT, RECORD_ID, body()),
                400, "已通过的时长记录不能补录");

        when(recordMapper.selectById(RECORD_ID)).thenReturn(record(RECORD_ID, WANGXM, "pending"));
        assertError(service.makeupRecord(WANGXM, ROLE_STUDENT, RECORD_ID, body()),
                400, "仅已驳回的记录可以补录");

        when(recordMapper.selectById(RECORD_ID)).thenReturn(record(RECORD_ID, ZHAOXH, "rejected"));
        assertError(service.makeupRecord(WANGXM, ROLE_STUDENT, RECORD_ID, body()),
                403, "无权补录他人的时长记录");
        verify(recordMapper, never()).insert(any(VolunteerRecord.class));
    }

    @Test
    @DisplayName("4.11 makeupRecord：project.endTime 起超 6 个月 → 400（无 endTime 回退 serviceDate）（§4 / §11-14）")
    void makeupWindowNegatives() {
        // 项目 endTime 已过 7 个月
        VolunteerRecord withProject = record(RECORD_ID, WANGXM, "rejected");
        withProject.setProjectId(PROJECT_ID);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(withProject);
        VolunteerProject p = project(PROJECT_ID, "approved", COUNSELOR);
        p.setEndTime(TODAY.minusMonths(7).atTime(18, 0));
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        assertError(service.makeupRecord(WANGXM, ROLE_STUDENT, RECORD_ID, body()),
                400, "已超过补录窗口");

        // 无项目：serviceDate 已过 7 个月
        VolunteerRecord old = record(RECORD_ID, WANGXM, "rejected");
        old.setServiceDate(TODAY.minusMonths(7));
        old.setProjectId(null);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(old);
        Result<?> r = service.makeupRecord(WANGXM, ROLE_STUDENT, RECORD_ID, body());
        assertError(r, 400, "6 个月内可补录");
        verify(recordMapper, never()).insert(any(VolunteerRecord.class));

        // 边界内：serviceDate 距今 5 个月 → 允许
        VolunteerRecord recent = record(RECORD_ID, WANGXM, "rejected");
        recent.setServiceDate(TODAY.minusMonths(5));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(recent);
        assertOk(service.makeupRecord(WANGXM, ROLE_STUDENT, RECORD_ID, body()));
    }

    // ==================================================================
    // 5. summary：星级阈值 / 学年认定 / 权限
    // ==================================================================

    @Test
    @DisplayName("5.1 summary：阈值 50/100/150/300/500 边界 → starLevel 0..5 与中文文案（§4 / §11-16）")
    void summaryStarLevelBoundaries() {
        long[][] cases = {{49, 0}, {50, 1}, {99, 1}, {100, 2}, {149, 2}, {150, 3}, {299, 3}, {300, 4}, {499, 4}, {500, 5}};
        String[] texts = {"暂无星级", "一星级志愿者", "二星级志愿者", "三星级志愿者", "四星级志愿者", "五星级志愿者"};
        for (long[] c : cases) {
            stubHours(WANGXM, BigDecimal.valueOf(c[0]), BigDecimal.ZERO);
            Map<String, Object> data = dataOf(service.summaryMine(WANGXM, ROLE_STUDENT));
            long total = c[0];
            int level = (int) c[1];
            assertEquals(level, num(data.get("starLevel")), "total=" + total + " 星级不符");
            assertEquals(texts[level], data.get("starLevelText"), "total=" + total + " 星级文案不符");
            assertEquals(STAR_SOURCE, data.get("starSource"));
            assertEquals(0, BigDecimal.valueOf(total).compareTo((BigDecimal) data.get("totalHours")),
                    "totalHours 应等于累计时数");
        }
    }

    @Test
    @DisplayName("5.2 summary：五星 nextLevelHours=null、remainToNextLevel=0；未满级给出下一档与差值（§11-16）")
    void summaryNextLevelFields() {
        stubHours(WANGXM, new BigDecimal("500"), BigDecimal.ZERO);
        Map<String, Object> five = dataOf(service.summaryMine(WANGXM, ROLE_STUDENT));
        assertEquals(5, num(five.get("starLevel")));
        assertNull(five.get("nextLevelHours"), "五星时 nextLevelHours 必须为 null");
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) five.get("remainToNextLevel")),
                "五星时 remainToNextLevel 必须为 0");

        stubHours(WANGXM, new BigDecimal("50"), BigDecimal.ZERO);
        Map<String, Object> one = dataOf(service.summaryMine(WANGXM, ROLE_STUDENT));
        assertEquals(1, num(one.get("starLevel")));
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) one.get("nextLevelHours")));
        assertEquals(0, new BigDecimal("50.00").compareTo((BigDecimal) one.get("remainToNextLevel")));

        stubHours(WANGXM, new BigDecimal("49"), BigDecimal.ZERO);
        Map<String, Object> zero = dataOf(service.summaryMine(WANGXM, ROLE_STUDENT));
        assertEquals(0, num(zero.get("starLevel")));
        assertEquals(0, new BigDecimal("50").compareTo((BigDecimal) zero.get("nextLevelHours")));
    }

    @Test
    @DisplayName("5.3 summary：yearQualified 学年满 20 小时为 true、19.5 为 false（§4）")
    void summaryYearQualified() {
        stubHours(WANGXM, new BigDecimal("60"), new BigDecimal("20"));
        Map<String, Object> qualified = dataOf(service.summaryMine(WANGXM, ROLE_STUDENT));
        assertEquals(Boolean.TRUE, qualified.get("yearQualified"));
        assertEquals(0, new BigDecimal("20").compareTo((BigDecimal) qualified.get("yearHours")));

        stubHours(WANGXM, new BigDecimal("60"), new BigDecimal("19.5"));
        Map<String, Object> notQualified = dataOf(service.summaryMine(WANGXM, ROLE_STUDENT));
        assertEquals(Boolean.FALSE, notQualified.get("yearQualified"));
        assertEquals(0, new BigDecimal("19.5").compareTo((BigDecimal) notQualified.get("yearHours")));
    }

    @Test
    @DisplayName("5.4 summaryForUser：admin / 本人 / 名下辅导员 200；他人 403；查无此人 404；未登录 401（§6）")
    void summaryForUserPermissions() {
        stubHours(WANGXM, new BigDecimal("120"), new BigDecimal("30"));

        assertOk(service.summaryForUser(ADMIN, ROLE_ADMIN, WANGXM));
        Map<String, Object> mine = dataOf(service.summaryForUser(WANGXM, ROLE_STUDENT, WANGXM));
        assertEquals(2, num(mine.get("starLevel")));
        assertEquals(Long.valueOf(WANGXM), mine.get("userId"));
        // 队长裁定（2026-09-22）：辅导员可查名下学生汇总（原「仅 admin 或本人」口径过窄）
        assertOk(service.summaryForUser(COUNSELOR, ROLE_TEACHER, WANGXM));

        assertError(service.summaryForUser(ZHAOXH, ROLE_STUDENT, WANGXM), 403, "无权查看该学生的时长汇总");
        // 非名下辅导员（OTHER_TEACHER=6，WANGXM 名下辅导员为 COUNSELOR=3）不得查
        assertError(service.summaryForUser(OTHER_TEACHER, ROLE_TEACHER, WANGXM), 403, "无权查看该学生的时长汇总");
        // 查无此人 → 404（队长裁定：不存在与越权区分）
        assertError(service.summaryForUser(ADMIN, ROLE_ADMIN, 99999999L), 404, "学生不存在");
        assertError(service.summaryForUser(null, ROLE_STUDENT, WANGXM), 401, "请先登录");
    }

    // ==================================================================
    // 6. blacklist：列入 / 到期 / 撤销 / 条款文案 / 脱敏
    // ==================================================================

    @Test
    @DisplayName("6.1 createBlacklist：insert → active、expireAt = listedAt + 6 个月（§11-19 / §4）")
    void createBlacklistActive() {
        LocalDate listedAt = TODAY;
        assertOk(service.createBlacklist(ADMIN, ROLE_ADMIN,
                body("userId", WANGXM, "clause", "c34_1", "reason", "无故缺席")));

        ArgumentCaptor<VolunteerBlacklist> c = ArgumentCaptor.forClass(VolunteerBlacklist.class);
        verify(blacklistMapper).insert(c.capture());
        assertEquals("active", c.getValue().getStatus());
        assertEquals(listedAt.plusMonths(6), c.getValue().getExpireAt());
        assertEquals("c34_1", c.getValue().getClause());
        assertEquals(Long.valueOf(WANGXM), c.getValue().getUserId());
    }

    @Test
    @DisplayName("6.2 createBlacklist：显式 listedAt 使 expireAt 早于当天 → expired（§11-19）")
    void createBlacklistExpired() {
        LocalDate oldListedAt = TODAY.minusMonths(7);
        Result<?> r = service.createBlacklist(ADMIN, ROLE_ADMIN,
                body("userId", WANGXM, "clause", "c35", "reason", "屡次违规",
                        "listedAt", oldListedAt.toString()));
        assertOk(r);

        ArgumentCaptor<VolunteerBlacklist> c = ArgumentCaptor.forClass(VolunteerBlacklist.class);
        verify(blacklistMapper).insert(c.capture());
        assertEquals("expired", c.getValue().getStatus());
        assertEquals(oldListedAt.plusMonths(6), c.getValue().getExpireAt());
        assertTrue(c.getValue().getExpireAt().isBefore(TODAY));
    }

    @Test
    @DisplayName("6.3 createBlacklist：生效期内重复列入 → 400；非法条款 → 400；非 admin → 403（§11-19 / §6）")
    void createBlacklistNegatives() {
        VolunteerBlacklist active = blacklist(BLACKLIST_ID, WANGXM, "active", TODAY.minusMonths(1));
        when(blacklistMapper.selectActiveByUser(WANGXM)).thenReturn(active);
        assertError(service.createBlacklist(ADMIN, ROLE_ADMIN,
                body("userId", WANGXM, "clause", "c34_1", "reason", "重复列入")),
                400, "已在黑名单生效期内");

        when(blacklistMapper.selectActiveByUser(WANGXM)).thenReturn(null);
        assertError(service.createBlacklist(ADMIN, ROLE_ADMIN,
                body("userId", WANGXM, "clause", "c99", "reason", "非法条款")),
                400, "只能是 c34_1 至 c34_5 或 c35");
        assertError(service.createBlacklist(COUNSELOR, ROLE_TEACHER,
                body("userId", WANGXM, "clause", "c34_1", "reason", "越权")),
                403, "仅管理员可操作");
        verify(blacklistMapper, never()).insert(any(VolunteerBlacklist.class));
    }

    @Test
    @DisplayName("6.4 revokeBlacklist：active → revoked；非 active → 400（§11-19 / §7）")
    void revokeBlacklist() {
        VolunteerBlacklist active = blacklist(BLACKLIST_ID, WANGXM, "active", TODAY.minusMonths(1));
        when(blacklistMapper.selectById(BLACKLIST_ID)).thenReturn(active);
        when(blacklistMapper.update(any(VolunteerBlacklist.class), eq("active"))).thenReturn(1);

        assertEquals("黑名单已撤销", service.revokeBlacklist(ADMIN, ROLE_ADMIN, BLACKLIST_ID, body()).getMessage());
        ArgumentCaptor<VolunteerBlacklist> c = ArgumentCaptor.forClass(VolunteerBlacklist.class);
        verify(blacklistMapper).update(c.capture(), eq("active"));
        assertEquals("revoked", c.getValue().getStatus());

        VolunteerBlacklist expired = blacklist(BLACKLIST_ID, WANGXM, "expired", TODAY.minusMonths(7));
        when(blacklistMapper.selectById(BLACKLIST_ID)).thenReturn(expired);
        assertError(service.revokeBlacklist(ADMIN, ROLE_ADMIN, BLACKLIST_ID, body()),
                400, "仅生效中的黑名单可以撤销");

        when(blacklistMapper.selectById(BLACKLIST_ID)).thenReturn(active);
        when(blacklistMapper.update(any(VolunteerBlacklist.class), eq("active"))).thenReturn(0);
        assertError(service.revokeBlacklist(ADMIN, ROLE_ADMIN, BLACKLIST_ID, body()), 400, CAS_FAIL);
    }

    @Test
    @DisplayName("6.5 clause 码值 c34_1…c34_5 / c35 → 对应中文 clauseText（§3.2 / §11-19）")
    void blacklistClauseTexts() {
        String[][] clauses = {
                {"c34_1", "无故缺席志愿服务，造成不良影响的"},
                {"c34_2", "在志愿服务中言行不当，损害学校形象的"},
                {"c34_3", "故意煽动负面情绪，影响活动正常开展的"},
                {"c34_4", "在社交媒体发表不良言论，诋毁学校或志愿服务工作的"},
                {"c34_5", "青年志愿服务指导中心认定的其它违规行为"},
                {"c35", "未按要求开展志愿服务工作，或受到警告及以上处分"},
        };
        for (String[] pair : clauses) {
            when(blacklistMapper.selectList(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(blacklistWithClause(pair[0])));
            when(blacklistMapper.selectCount(any(), any(), any(), any(), any())).thenReturn(1L);

            Result<?> r = service.listBlacklist(ADMIN, ROLE_ADMIN, null, null, null, 1, 20);
            Map<String, Object> row = rowsOf(r).get(0);
            assertEquals(pair[0], row.get("clause"));
            assertTrue(row.get("clauseText").toString().contains(pair[1]),
                    pair[0] + " 的 clauseText 应含「" + pair[1] + "」，实际：" + row.get("clauseText"));
        }
    }

    private static VolunteerBlacklist blacklistWithClause(String clause) {
        VolunteerBlacklist b = blacklist(BLACKLIST_ID, WANGXM, "active", TODAY);
        b.setClause(clause);
        return b;
    }

    @Test
    @DisplayName("6.6 listBlacklist：行姓名打码 + 学号仅后四位 + daysLeft（§11-2 脱敏）")
    void blacklistListMasked() {
        VolunteerBlacklist b = blacklist(BLACKLIST_ID, WANGXM, "active", TODAY.minusMonths(1));
        b.setStudentName("王小明");
        b.setStudentNo("2024001");
        when(blacklistMapper.selectList(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(b));
        when(blacklistMapper.selectCount(any(), any(), any(), any(), any())).thenReturn(1L);

        Map<String, Object> row = rowsOf(service.listBlacklist(ADMIN, ROLE_ADMIN, null, null, null, 1, 20)).get(0);
        assertEquals("王*", row.get("studentName"));
        assertEquals("4001", row.get("studentNo"));
        assertEquals("生效中", row.get("statusText"));
        long expectDaysLeft = b.getExpireAt().toEpochDay() - TODAY.toEpochDay();
        assertEquals(expectDaysLeft, ((Number) row.get("daysLeft")).longValue());
    }

    @Test
    @DisplayName("6.7 listBlacklist：学生只看本人 scope、非法条款筛选 → 400（§6 / §11-22）")
    void blacklistListScopeAndClause() {
        when(blacklistMapper.selectList(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(blacklist(BLACKLIST_ID, WANGXM, "active", TODAY)));
        when(blacklistMapper.selectCount(any(), any(), any(), any(), any())).thenReturn(1L);

        assertOk(service.listBlacklist(WANGXM, ROLE_STUDENT, null, null, null, 1, 20));
        ArgumentCaptor<Long> scopeUser = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> scopeCounselor = ArgumentCaptor.forClass(Long.class);
        verify(blacklistMapper).selectList(scopeUser.capture(), scopeCounselor.capture(),
                any(), any(), any(), anyInt(), anyInt());
        assertEquals(Long.valueOf(WANGXM), scopeUser.getValue());
        assertNull(scopeCounselor.getValue());

        assertError(service.listBlacklist(ADMIN, ROLE_ADMIN, "c99", null, null, 1, 20),
                400, "只能是 c34_1 至 c34_5 或 c35");
    }

    // ==================================================================
    // 7. appeal：窗口（5 个工作日）/ 裁定
    // ==================================================================

    @Test
    @DisplayName("7.1 createAppeal：窗口起点 = 记录最近 review_time + 5 个工作日，跳周六日（周五起 → +7 天）")
    void createAppealWithinWindowCrossWeekend() {
        LocalDate friday = lastFriday();
        VolunteerRecord rec = record(RECORD_ID, WANGXM, "rejected");
        rec.setReviewTime(friday.atTime(14, 30));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(rec);
        when(appealMapper.countPendingByUserAndTarget(eq(WANGXM), eq(RECORD_ID), isNull())).thenReturn(0L);

        LocalDate expectedDeadline = friday.plusDays(7);
        Result<?> r = service.createAppeal(WANGXM, ROLE_STUDENT,
                body("recordId", RECORD_ID, "reason", "对驳回有异议"));
        assertOk(r);
        assertTrue(r.getMessage().contains("受理截止日 " + expectedDeadline),
                "周五 + 5 个工作日应跳到下周五（" + expectedDeadline + "），实际：" + r.getMessage());

        ArgumentCaptor<VolunteerAppeal> c = ArgumentCaptor.forClass(VolunteerAppeal.class);
        verify(appealMapper).insert(c.capture());
        assertEquals("pending", c.getValue().getStatus());
        assertEquals(Integer.valueOf(1), c.getValue().getWithinWindow());
        assertEquals(Long.valueOf(WANGXM), c.getValue().getUserId());
        assertNotNull(c.getValue().getSubmitTime());
    }

    @Test
    @DisplayName("7.2 createAppeal：黑名单分支窗口起点 = notify_time（空则 listed_at）；超窗 → 400 且给出截止日")
    void createAppealBlacklistBranchAndOutOfWindow() {
        LocalDate friday = lastFriday();
        VolunteerBlacklist bl = blacklist(BLACKLIST_ID, WANGXM, "active", friday);
        bl.setNotifyTime(null);
        when(blacklistMapper.selectById(BLACKLIST_ID)).thenReturn(bl);
        when(appealMapper.countPendingByUserAndTarget(eq(WANGXM), isNull(), eq(BLACKLIST_ID))).thenReturn(0L);
        assertOk(service.createAppeal(WANGXM, ROLE_STUDENT,
                body("blacklistId", BLACKLIST_ID, "reason", "对列入有异议")));

        // 超窗：reviewTime 在 14 天前的周五 → 截止日早于今天
        VolunteerRecord stale = record(RECORD_ID, WANGXM, "rejected");
        stale.setReviewTime(friday.minusDays(14).atTime(9, 0));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(stale);
        LocalDate staleDeadline = friday.minusDays(7);
        Result<?> r = service.createAppeal(WANGXM, ROLE_STUDENT,
                body("recordId", RECORD_ID, "reason", "逾期申诉"));
        assertError(r, 400, "已超过申诉受理窗口");
        assertTrue(r.getMessage().contains(staleDeadline.toString()),
                "超窗提示应给出截止日 " + staleDeadline + "，实际：" + r.getMessage());
    }

    @Test
    @DisplayName("7.3 createAppeal：recordId/blacklistId 二选一必填、理由必填、他人记录 → 403、重复申诉 → 400")
    void createAppealNegatives() {
        assertError(service.createAppeal(WANGXM, ROLE_STUDENT, body("reason", "x")),
                400, "recordId 或 blacklistId 必须填写其一");
        assertError(service.createAppeal(WANGXM, ROLE_STUDENT,
                body("recordId", RECORD_ID, "blacklistId", BLACKLIST_ID, "reason", "x")),
                400, "只能填写其一");
        assertError(service.createAppeal(WANGXM, ROLE_STUDENT, body("recordId", RECORD_ID)),
                400, "申诉理由不能为空");

        VolunteerRecord rec = record(RECORD_ID, WANGXM, "rejected");
        rec.setReviewTime(TODAY.atTime(9, 0));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(rec);
        assertError(service.createAppeal(ZHAOXH, ROLE_STUDENT,
                body("recordId", RECORD_ID, "reason", "越权申诉")), 403, "无权对他人记录提出申诉");

        when(appealMapper.countPendingByUserAndTarget(eq(WANGXM), eq(RECORD_ID), isNull())).thenReturn(1L);
        assertError(service.createAppeal(WANGXM, ROLE_STUDENT,
                body("recordId", RECORD_ID, "reason", "重复申诉")), 400, "已有待受理的申诉");
    }

    @Test
    @DisplayName("7.4 ruleAppeal：accepted / rejected / closed 都进终态（§11-21）")
    void ruleAppealTerminalStatuses() {
        when(appealMapper.selectById(APPEAL_ID)).thenReturn(appeal(APPEAL_ID, WANGXM, "pending"));
        when(appealMapper.ruleAppeal(any(), anyString(), anyString(), any(), any())).thenReturn(1);

        // 服务端 message = "申诉" + APPEAL_STATUS_TEXT[status]（accepted=已受理 → "申诉已受理"）
        // 注：原文案为 "申诉已" + text，会输出「申诉已已受理」这类叠字，已由队长修正为 "申诉" + text。
        assertEquals("申诉已受理", service.ruleAppeal(ADMIN, ROLE_ADMIN, APPEAL_ID,
                body("status", "accepted", "ruling", "同意受理")).getMessage());
        assertEquals("申诉已驳回", service.ruleAppeal(ADMIN, ROLE_ADMIN, APPEAL_ID,
                body("status", "rejected")).getMessage());
        assertEquals("申诉已结案", service.ruleAppeal(ADMIN, ROLE_ADMIN, APPEAL_ID,
                body("status", "closed")).getMessage());

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        verify(appealMapper, times(3))
                .ruleAppeal(eq(APPEAL_ID), eq("pending"), to.capture(), any(), eq(ADMIN));
        assertEquals("accepted", to.getAllValues().get(0));
        assertEquals("rejected", to.getAllValues().get(1));
        assertEquals("closed", to.getAllValues().get(2));
    }

    @Test
    @DisplayName("7.5 ruleAppeal：withinWindow=0 → 400；非 pending → 400；非法/空裁定 → 400；非 admin → 403")
    void ruleAppealNegatives() {
        VolunteerAppeal outOfWindow = appeal(APPEAL_ID, WANGXM, "pending");
        outOfWindow.setWithinWindow(0);
        when(appealMapper.selectById(APPEAL_ID)).thenReturn(outOfWindow);
        assertError(service.ruleAppeal(ADMIN, ROLE_ADMIN, APPEAL_ID, body("status", "accepted")),
                400, "已超过受理窗口");

        VolunteerAppeal ruled = appeal(APPEAL_ID, WANGXM, "accepted");
        ruled.setWithinWindow(1);
        when(appealMapper.selectById(APPEAL_ID)).thenReturn(ruled);
        assertError(service.ruleAppeal(ADMIN, ROLE_ADMIN, APPEAL_ID, body("status", "closed")),
                400, "仅待受理的申诉可以裁定");

        VolunteerAppeal pending = appeal(APPEAL_ID, WANGXM, "pending");
        pending.setWithinWindow(1);
        when(appealMapper.selectById(APPEAL_ID)).thenReturn(pending);
        assertError(service.ruleAppeal(ADMIN, ROLE_ADMIN, APPEAL_ID, body("status", "approved")),
                400, "裁定结果只能是 accepted、rejected 或 closed");
        assertError(service.ruleAppeal(ADMIN, ROLE_ADMIN, APPEAL_ID, body()),
                400, "裁定结果不能为空");
        assertError(service.ruleAppeal(COUNSELOR, ROLE_TEACHER, APPEAL_ID, body("status", "accepted")),
                403, "仅管理员可操作");
        verify(appealMapper, never()).ruleAppeal(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("7.6 listAppeals：deadline = submitTime + 5 个工作日（当天 23:59:59）")
    void listAppealsDeadline() {
        LocalDate friday = lastFriday();
        VolunteerAppeal a = appeal(APPEAL_ID, WANGXM, "pending");
        a.setSubmitTime(friday.atTime(10, 0));
        a.setStudentName("王小明");
        a.setStudentNo("2024001");
        when(appealMapper.selectList(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(a));
        when(appealMapper.selectCount(any(), any(), any(), any(), any(), any())).thenReturn(1L);

        Map<String, Object> row = rowsOf(service.listAppeals(ADMIN, ROLE_ADMIN, null, null, null, null, 1, 20)).get(0);
        assertEquals(friday.plusDays(7).atTime(23, 59, 59), row.get("deadline"));
        assertEquals("待受理", row.get("statusText"));
        assertEquals("王*", row.get("studentName"));
    }

    // ==================================================================
    // 8. export：CSV 脱敏 / BOM / 头部
    // ==================================================================

    @Test
    @DisplayName("8.1 exportStats：CSV 表头 + 学号仅后四位 + 姓名打码 + 不含完整学号与联系方式（§11-18）")
    void exportStatsCsvDesensitized() {
        VolunteerRecord row = record(RECORD_ID, WANGXM, "approved");
        row.setStudentName("王小明");
        row.setStudentNo("2024001");
        row.setProjectName("迎新志愿服务");
        row.setServiceDate(SERVICE_DATE);
        row.setHours(new BigDecimal("4"));
        row.setHourType("credit");
        when(recordMapper.selectList(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyInt(), anyInt())).thenReturn(Collections.singletonList(row));

        CommunityService.CsvExport out = service.exportStats(ADMIN, ROLE_ADMIN,
                null, null, null, null, null, null, null);
        assertNull(out.errorCode, "admin 导出不应报错：" + out.errorMessage);
        assertNotNull(out.csv);
        assertTrue(out.csv.startsWith("学号,姓名,项目,服务日期,时长(小时),时数类型,状态\r\n"),
                "CSV 表头不符：" + out.csv);
        assertTrue(out.csv.contains("4001,王*,迎新志愿服务," + SERVICE_DATE + ",4.00,信用时数,已通过"),
                "CSV 数据行不符：" + out.csv);
        assertFalse(out.csv.contains("2024001"), "不得出现完整学号");
        assertFalse(out.csv.contains("王小明"), "不得出现完整姓名");
        assertFalse(out.csv.contains("138"), "不得出现联系方式");

        String stamp = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        assertEquals("volunteer-stats-" + stamp + ".csv", out.filename);
    }

    @Test
    @DisplayName("8.2 exportStats：学生 → errorCode 403；超过 10 万行 → errorCode 400（§11-18）")
    void exportStatsLimits() {
        CommunityService.CsvExport denied = service.exportStats(WANGXM, ROLE_STUDENT,
                null, null, null, null, null, null, null);
        assertEquals(Integer.valueOf(403), denied.errorCode);
        assertTrue(denied.errorMessage.contains("无权"));

        VolunteerRecord row = record(RECORD_ID, WANGXM, "approved");
        List<VolunteerRecord> tooMany = new ArrayList<>(Collections.nCopies(CommunityService.MAX_EXPORT + 1, row));
        when(recordMapper.selectList(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyInt(), anyInt())).thenReturn(tooMany);
        CommunityService.CsvExport overflow = service.exportStats(ADMIN, ROLE_ADMIN,
                null, null, null, null, null, null, null);
        assertEquals(Integer.valueOf(400), overflow.errorCode);
        assertTrue(overflow.errorMessage.contains("超过 100000 行"));
    }

    @Test
    @DisplayName("8.3 Controller /stats/export：UTF-8 BOM + text/csv;charset=UTF-8 + 文件名含 yyyyMMdd")
    void controllerExportHeadersAndBom() {
        VolunteerRecord row = record(RECORD_ID, WANGXM, "approved");
        row.setStudentName("王小明");
        row.setStudentNo("2024001");
        row.setProjectName("迎新志愿服务");
        when(recordMapper.selectList(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyInt(), anyInt())).thenReturn(Collections.singletonList(row));

        ResponseEntity<byte[]> resp = controller.exportStats(session(ADMIN, ROLE_ADMIN),
                null, null, null, null, null, null, null);
        byte[] bytes = resp.getBody();
        assertNotNull(bytes);
        assertEquals(HttpStatus.OK.value(), resp.getStatusCode().value());
        assertEquals(0xEF, bytes[0] & 0xFF);
        assertEquals(0xBB, bytes[1] & 0xFF);
        assertEquals(0xBF, bytes[2] & 0xFF);
        String contentType = resp.getHeaders().getContentType().toString();
        assertTrue(contentType.contains("text/csv"), "Content-Type=" + contentType);
        assertTrue(contentType.contains("charset=UTF-8"), "Content-Type=" + contentType);
        String stamp = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String disposition = resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(disposition);
        assertTrue(disposition.contains("volunteer-stats-" + stamp + ".csv"), "Content-Disposition=" + disposition);
        assertEquals(bytes.length, resp.getHeaders().getContentLength());
        assertTrue(new String(bytes, StandardCharsets.UTF_8).startsWith("\uFEFF学号,姓名,"));

        // 未登录 / 学生 → 403 空 body（队长裁定：导出错误分支不回 JSON 体，对齐 ClubController）
        ResponseEntity<byte[]> anonymous = controller.exportStats(new MockHttpSession(),
                null, null, null, null, null, null, null);
        assertEquals(HttpStatus.FORBIDDEN.value(), anonymous.getStatusCode().value());
        assertNull(anonymous.getBody());

        ResponseEntity<byte[]> student = controller.exportStats(session(WANGXM, ROLE_STUDENT),
                null, null, null, null, null, null, null);
        assertEquals(HttpStatus.FORBIDDEN.value(), student.getStatusCode().value());
        assertNull(student.getBody());
    }

    // ==================================================================
    // 9. 审计：状态流转写审计、失败不影响返回
    // ==================================================================

    @Test
    @DisplayName("9.1 审计日志：立项/审批流转调用 AuditLogService，且告警不抛异常")
    void auditIsRecordedAndNeverBreaksFlow() {
        org.mockito.Mockito.doThrow(new RuntimeException("audit down"))
                .when(auditLogService).record(any(AuditLog.class));
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID, "pending", COUNSELOR));
        when(projectMapper.reviewProject(any(), anyString(), anyString(), any(), any())).thenReturn(1);
        assertOk(service.reviewProject(ADMIN, ROLE_ADMIN, PROJECT_ID, body("action", "approve")));
        verify(auditLogService, atLeastOnce()).record(any(AuditLog.class));
    }
}
