package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.controller.SecondClassController;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P18 第二课堂成长手册 —— SecondClassService 单元测试（纯 Mockito，不起 Spring 上下文、不连库）。
 *
 * <p>口径来源：{@code target/p18-spec.md}、{@code SecondClassService} 实现、
 * {@code SecondClassController} 实际行为。本类只断言 service 层返回的 {@link Result}
 * 与 {@link CsvExport}，以及 controller 的 {@link ResponseEntity} 分支。</p>
 *
 * <p>契约缺口 P18-C1/C2/C3（导出脱敏、导出拒绝码 403、updateRecord 学分范围）已按契约修复，
 * 对应断言现按契约口径钉住（2026-10-19）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SecondClassService — P18 第二课堂成绩单（板块 / 活动 / 申报 / 初审 / 认定 / 汇总 / 导出）")
class SecondClassServiceTest {

    // ===== 角色与主键 =====
    private static final long ADMIN = 1L;
    private static final long COUNSELOR = 3L;
    private static final long WANGXM = 4L;
    private static final long OUT_OF_SCOPE_COUNSELOR = 7L;
    private static final long OTHER_STUDENT = 9L;

    private static final long CATEGORY_ID = 700L;
    private static final long OTHER_CATEGORY_ID = 701L;
    private static final long ACTIVITY_ID = 800L;
    private static final long OTHER_ACTIVITY_ID = 801L;
    private static final long RECORD_ID = 900L;
    private static final long EXISTING_RECORD_ID = 901L;

    private static final String ROLE_ADMIN = "admin";
    private static final String ROLE_TEACHER = "teacher";
    private static final String ROLE_STUDENT = "student";

    private static final String CAS_FAIL = CommunityService.CAS_FAIL;
    private static final String CSV_HEADER =
            "学号,姓名,班级,板块,申报标题,学时,学分,状态,初审人,初审时间,认定人,认定时间,提交时间\n";

    @Mock
    private SecondClassCategoryMapper categoryMapper;
    @Mock
    private SecondClassActivityMapper activityMapper;
    @Mock
    private SecondClassRecordMapper recordMapper;
    @Mock
    private StudentProfileMapper studentProfileMapper;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private SecondClassService service;

    private final SecondClassController controller = new SecondClassController();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "secondClassService", service);
        // 汇总查询是「附带提示」路径，默认给空列表，避免任何 recordWithWarnings/summary 走到 null 上。
        when(recordMapper.summaryByCategory(any(), any())).thenReturn(new ArrayList<>());
        // 默认：这名老师名下没有学生 ⇒ scopeOf 返回 null（与 P13 同口径：未分派学生按全校）。
        when(studentProfileMapper.countByCounselor(any())).thenReturn(0);
        // 默认：待审条数 0，允许新建申报。
        when(recordMapper.countPendingByUser(any())).thenReturn(0L);
    }

    // =========================================================================
    // 断言与造数工具
    // =========================================================================

    private static void assertOk(Result<?> result) {
        assertNotNull(result, "service 不应返回 null");
        assertEquals(200, result.getCode().intValue(),
                "期望业务码 200，实际：" + result.getCode() + " / " + result.getMessage());
        assertEquals("success", result.getMessage());
    }

    /** 业务码承载一切（HTTP 恒 200）：中文 message 用 contains 片段断言。 */
    private static void assertError(Result<?> result, int expectedCode, String keyword) {
        assertNotNull(result, "service 不应返回 null");
        assertEquals(expectedCode, result.getCode().intValue(),
                "业务码不符，实际 message=" + result.getMessage());
        assertNotNull(result.getMessage(), "错误结果必须有 message");
        assertTrue(result.getMessage().contains(keyword),
                "期望 message 含「" + keyword + "」，实际：" + result.getMessage());
        assertNull(result.getData(), "错误结果的 data 应为 null");
    }

    private static void assertDenied(Result<?> result, String keyword) {
        assertError(result, 403, "无权");
        assertTrue(result.getMessage().contains(keyword),
                "期望 message 含「" + keyword + "」，实际：" + result.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(Result<?> result) {
        assertOk(result);
        return (Map<String, Object>) result.getData();
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> rowsOf(Map<String, Object> data, String key) {
        return (List<T>) data.get(key);
    }

    /** 显式 LinkedHashMap：避免 Map.of 的签名推断坑（与 VolunteerServiceTest 同风格）。 */
    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private static SecondClassCategory category(long id, String code, String name, Integer enabled,
                                                String requiredHours, String capHours, String creditPerHour) {
        SecondClassCategory category = new SecondClassCategory();
        category.setId(id);
        category.setCode(code);
        category.setName(name);
        category.setSortNo(100);
        category.setEnabled(enabled);
        category.setRequiredHours(requiredHours == null ? null : new BigDecimal(requiredHours));
        category.setYearlyCapHours(capHours == null ? null : new BigDecimal(capHours));
        category.setCreditPerHour(creditPerHour == null ? null : new BigDecimal(creditPerHour));
        return category;
    }

    private static SecondClassActivity activity(long id, Long categoryId, String status, String hours) {
        SecondClassActivity activity = new SecondClassActivity();
        activity.setId(id);
        activity.setCategoryId(categoryId);
        activity.setTitle("迎新志愿");
        activity.setOrganizer("校团委");
        activity.setLevel("college");
        activity.setStartDate(LocalDate.of(2026, 3, 1));
        activity.setEndDate(LocalDate.of(2026, 3, 1));
        activity.setHours(hours == null ? null : new BigDecimal(hours));
        activity.setStatus(status);
        activity.setCategoryName("志愿公益");
        activity.setRecordCount(3);
        activity.setCreatedBy(ADMIN);
        activity.setCreatedAt(LocalDateTime.of(2026, 2, 20, 9, 0));
        return activity;
    }

    private static SecondClassRecord record(long id, Long activityId, long userId, long categoryId, String status) {
        SecondClassRecord record = new SecondClassRecord();
        record.setId(id);
        record.setActivityId(activityId);
        record.setUserId(userId);
        record.setCategoryId(categoryId);
        record.setTitleSnapshot("迎新志愿");
        record.setHours(new BigDecimal("8"));
        record.setStatus(status);
        record.setSubmittedAt(LocalDateTime.of(2026, 3, 1, 8, 0));
        record.setCategoryName("志愿公益");
        record.setStudentName("王小明");
        record.setStudentNo("2024001");
        record.setClassName("计算机2401");
        return record;
    }

    private static StudentProfile profileOf(long userId, Long counselorId) {
        StudentProfile profile = new StudentProfile();
        profile.setUserId(userId);
        profile.setCounselorId(counselorId);
        profile.setName("王小明");
        return profile;
    }

    private static MockHttpSession session(Object userId, Object role) {
        MockHttpSession session = new MockHttpSession();
        if (userId != null) {
            session.setAttribute("userId", userId);
        }
        if (role != null) {
            session.setAttribute("role", role);
        }
        return session;
    }

    // =========================================================================
    // 1. 权限矩阵（学生 / 辅导员 teacher / 管理员 admin）
    // =========================================================================

    @Test
    @DisplayName("1.1 未登录：所有学生读写接口一律 401「请先登录」")
    void studentEndpointsRejectAnonymous() {
        assertError(service.listCategories(null), 401, "请先登录");
        assertError(service.listActivities(null, null, null, null, null), 401, "请先登录");
        assertError(service.listMine(null, null, null, null, null), 401, "请先登录");
        assertError(service.summary(null, null), 401, "请先登录");
        assertError(service.createRecord(null, body()), 401, "请先登录");
        assertError(service.updateRecord(null, RECORD_ID, body()), 401, "请先登录");
        assertError(service.withdraw(null, RECORD_ID), 401, "请先登录");
        assertError(service.detail(null, null, RECORD_ID), 401, "请先登录");
    }

    @Test
    @DisplayName("1.2 权限矩阵：板块写接口 admin 专属，teacher/student 均被 403 拒绝")
    void categoryWriteIsAdminOnly() {
        assertDenied(service.createCategory(COUNSELOR, ROLE_TEACHER,
                body("name", "实践实习", "code", "SJ")), "无权新增板块");
        Result<?> byStudent = service.createCategory(WANGXM, ROLE_STUDENT,
                body("name", "实践实习", "code", "SJ"));
        assertDenied(byStudent, "无权新增板块");
        assertTrue(byStudent.getMessage().contains("仅团委管理员可操作"));

        assertDenied(service.updateCategory(COUNSELOR, ROLE_TEACHER, CATEGORY_ID,
                body("name", "实践实习")), "无权维护板块");
        assertDenied(service.updateCategory(WANGXM, ROLE_STUDENT, CATEGORY_ID,
                body("name", "实践实习")), "无权维护板块");

        // 被守卫拦下时不得触达数据层
        verify(categoryMapper, never()).insert(any(SecondClassCategory.class));
        verify(categoryMapper, never()).update(any(SecondClassCategory.class));
    }

    @Test
    @DisplayName("1.3 权限矩阵：活动台账写接口 admin 专属（发布 / 维护 / 状态流转）")
    void activityWriteIsAdminOnly() {
        assertDenied(service.createActivity(COUNSELOR, ROLE_TEACHER,
                body("categoryId", CATEGORY_ID, "title", "迎新志愿", "hours", new BigDecimal("8"),
                        "startDate", "2026-03-01")), "无权发布活动");
        assertDenied(service.updateActivity(COUNSELOR, ROLE_TEACHER, ACTIVITY_ID,
                body("title", "迎新志愿")), "无权维护活动");
        assertDenied(service.changeActivityStatus(COUNSELOR, ROLE_TEACHER, ACTIVITY_ID,
                body("action", "publish")), "无权变更活动状态");
        assertTrue(service.changeActivityStatus(COUNSELOR, ROLE_TEACHER, ACTIVITY_ID,
                body("action", "publish")).getMessage().contains("仅团委管理员可操作"));

        verify(activityMapper, never()).insert(any(SecondClassActivity.class));
        verify(activityMapper, never()).update(any(SecondClassActivity.class), anyString());
        verify(activityMapper, never()).updateStatus(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("1.4 权限矩阵：最终认定只能 admin，teacher 被 403 拒绝")
    void confirmIsAdminOnly() {
        Result<?> denied = service.confirm(COUNSELOR, ROLE_TEACHER, RECORD_ID, body("action", "confirm"));
        assertDenied(denied, "无权认定申报");
        assertTrue(denied.getMessage().contains("仅团委管理员可操作"));
        assertDenied(service.confirm(WANGXM, ROLE_STUDENT, RECORD_ID, body("action", "confirm")), "无权认定申报");

        verify(recordMapper, never()).update(any(SecondClassRecord.class), anyString());
    }

    @Test
    @DisplayName("1.5 权限矩阵：员工读接口（板块台账/活动台账/申报列表/统计）拒绝学生")
    void staffReadEndpointsRejectStudent() {
        assertDenied(service.adminListCategories(WANGXM, ROLE_STUDENT), "无权查看板块台账");
        assertDenied(service.adminListActivities(WANGXM, ROLE_STUDENT, null, null, null, null, null),
                "无权查看活动台账");
        assertDenied(service.reviewList(WANGXM, ROLE_STUDENT, null, null, null, null, null), "无权查看申报列表");
        assertDenied(service.reviewStats(WANGXM, ROLE_STUDENT), "无权查看审核统计");
        CommunityService.CsvExport denied = service.exportCsv(WANGXM, ROLE_STUDENT, null, null);
        assertEquals(Integer.valueOf(403), denied.errorCode);
        assertTrue(denied.errorMessage.contains("无权导出申报"), denied.errorMessage);
        assertNull(denied.csv);
        assertTrue(service.reviewStats(WANGXM, ROLE_STUDENT).getMessage().contains("仅辅导员与管理员可操作"));
    }

    @Test
    @DisplayName("1.6 权限矩阵：导师可初审（teacher 放行），admin 可代审")
    void counselorReviewAllowsStaff() {
        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, null, WANGXM, CATEGORY_ID, "submitted"));
        when(recordMapper.update(any(SecondClassRecord.class), eq("submitted"))).thenReturn(1);

        assertOk(service.counselorReview(COUNSELOR, ROLE_TEACHER, RECORD_ID, body("action", "approve")));
        assertOk(service.counselorReview(ADMIN, ROLE_ADMIN, RECORD_ID, body("action", "approve")));
    }

    @Test
    @DisplayName("1.7 权限矩阵：详情——学生读他人 403，admin 读他人放行")
    void detailAuthorization() {
        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "submitted"));

        assertOk(service.detail(WANGXM, ROLE_STUDENT, RECORD_ID));
        assertDenied(service.detail(OTHER_STUDENT, ROLE_STUDENT, RECORD_ID), "无权查看他人的第二课堂申报");

        Map<String, Object> data = dataOf(service.detail(ADMIN, ROLE_ADMIN, RECORD_ID));
        assertEquals(RECORD_ID, ((Number) data.get("id")).longValue());
        assertEquals("submitted", data.get("status"));
        assertEquals("待初审", data.get("statusText"));
    }

    @Test
    @DisplayName("1.8 权限矩阵：跨辅导员范围读详情 403「该学生不在你的名下」")
    void detailRejectsOutOfScopeCounselor() {
        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "submitted"));
        // 该老师名下有学生 ⇒ scope 生效
        when(studentProfileMapper.countByCounselor(COUNSELOR)).thenReturn(2);
        // 但这条申报的学生不属于他
        when(studentProfileMapper.findByUserId(WANGXM))
                .thenReturn(profileOf(WANGXM, OUT_OF_SCOPE_COUNSELOR));

        assertDenied(service.detail(COUNSELOR, ROLE_TEACHER, RECORD_ID), "该学生不在你的名下");

        // 学生档案缺失同样按越界处理
        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(null);
        assertDenied(service.detail(COUNSELOR, ROLE_TEACHER, RECORD_ID), "该学生不在你的名下");
    }

    @Test
    @DisplayName("1.9 权限矩阵：scopeOf 三态（admin→null / 有名下学生→本人 / 无名下学生→null）")
    void scopeOfMatrix() {
        assertNull(service.scopeOf(ADMIN, ROLE_ADMIN));
        assertNull(service.scopeOf(null, ROLE_TEACHER));

        when(studentProfileMapper.countByCounselor(COUNSELOR)).thenReturn(2);
        assertEquals(Long.valueOf(COUNSELOR), service.scopeOf(COUNSELOR, ROLE_TEACHER));

        when(studentProfileMapper.countByCounselor(OUT_OF_SCOPE_COUNSELOR)).thenReturn(0);
        assertNull(service.scopeOf(OUT_OF_SCOPE_COUNSELOR, ROLE_TEACHER));
    }

    @Test
    @DisplayName("1.10 权限矩阵：撤回/修改他人申报 403")
    void withdrawAndUpdateRejectOtherStudentsRecord() {
        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "submitted"));

        assertDenied(service.withdraw(OTHER_STUDENT, RECORD_ID), "无权撤回他人的申报");

        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "returned"));
        assertDenied(service.updateRecord(OTHER_STUDENT, RECORD_ID, body("hours", new BigDecimal("2"))),
                "无权修改他人的申报");
    }

    // =========================================================================
    // 2. 申报创建（挂活动 / 自主申报）
    // =========================================================================

    @Test
    @DisplayName("2.1 createRecord：挂活动申报成功，标题与板块取活动快照，初始状态 submitted")
    void createRecordByActivitySucceeds() {
        when(activityMapper.selectById(ACTIVITY_ID))
                .thenReturn(activity(ACTIVITY_ID, CATEGORY_ID, "published", "8"));
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "ZYGY", "志愿公益", 1, null, null, null));
        when(recordMapper.selectByActivityAndUser(ACTIVITY_ID, WANGXM)).thenReturn(null);
        when(recordMapper.selectById(any()))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "submitted"));

        Result<?> result = service.createRecord(WANGXM,
                body("activityId", ACTIVITY_ID, "hours", new BigDecimal("6"), "note", "现场服务 6 小时"));
        Map<String, Object> data = dataOf(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> saved = (Map<String, Object>) data.get("record");
        assertEquals(RECORD_ID, ((Number) saved.get("id")).longValue());
        assertEquals("submitted", saved.get("status"));
        assertEquals("待初审", saved.get("statusText"));
        assertEquals("迎新志愿", saved.get("titleSnapshot"));
        assertNotNull(data.get("warnings"), "recordWithWarnings 必须给出 warnings 列表（可为空）");

        ArgumentCaptor<SecondClassRecord> captor = ArgumentCaptor.forClass(SecondClassRecord.class);
        verify(recordMapper).insert(captor.capture());
        SecondClassRecord inserted = captor.getValue();
        assertEquals(ACTIVITY_ID, inserted.getActivityId().longValue());
        assertEquals(CATEGORY_ID, inserted.getCategoryId().longValue(), "板块必须取活动所属板块");
        assertEquals(WANGXM, inserted.getUserId().longValue());
        assertEquals("迎新志愿", inserted.getTitleSnapshot(), "标题取活动标题（快照）");
        assertEquals(0, new BigDecimal("6").compareTo(inserted.getHours()));
        assertEquals("submitted", inserted.getStatus());
        assertNotNull(inserted.getSubmittedAt(), "申报必须写入 submitted_at（学年汇总口径）");
        assertEquals("现场服务 6 小时", inserted.getNote());
    }

    @Test
    @DisplayName("2.2 createRecord：活动不存在 → 404「活动不存在」")
    void createRecordRejectsMissingActivity() {
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(null);
        assertError(service.createRecord(WANGXM, body("activityId", ACTIVITY_ID, "hours", new BigDecimal("2"))),
                404, "活动不存在");
        verify(recordMapper, never()).insert(any(SecondClassRecord.class));
    }

    @Test
    @DisplayName("2.3 createRecord：活动非 published（草稿/已结束）不可申报")
    void createRecordRejectsClosedOrDraftActivity() {
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity(ACTIVITY_ID, CATEGORY_ID, "draft", "8"));
        assertError(service.createRecord(WANGXM, body("activityId", ACTIVITY_ID, "hours", new BigDecimal("2"))),
                400, "该活动当前不可申报（草稿）");

        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity(ACTIVITY_ID, CATEGORY_ID, "closed", "8"));
        assertError(service.createRecord(WANGXM, body("activityId", ACTIVITY_ID, "hours", new BigDecimal("2"))),
                400, "该活动当前不可申报（已结束）");
        verify(recordMapper, never()).insert(any(SecondClassRecord.class));
    }

    @Test
    @DisplayName("2.4 createRecord：自主申报成功（标题/学时/证明材料均由学生填写）")
    void createRecordSelfReportSucceeds() {
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "JNTC", "技能特长", 1, null, null, null));
        when(recordMapper.selectById(any()))
                .thenReturn(record(RECORD_ID, null, WANGXM, CATEGORY_ID, "submitted"));

        Result<?> result = service.createRecord(WANGXM, body(
                "categoryId", CATEGORY_ID,
                "title", "自学 Python 并取得证书",
                "hours", new BigDecimal("4"),
                "credit", new BigDecimal("1.5"),
                "evidenceUrl", "https://pan.example.com/cert.pdf"));
        Map<String, Object> data = dataOf(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> saved = (Map<String, Object>) data.get("record");
        assertEquals("待初审", saved.get("statusText"));

        ArgumentCaptor<SecondClassRecord> captor = ArgumentCaptor.forClass(SecondClassRecord.class);
        verify(recordMapper).insert(captor.capture());
        SecondClassRecord inserted = captor.getValue();
        assertNull(inserted.getActivityId(), "自主申报 activity_id 为 null");
        assertEquals("自学 Python 并取得证书", inserted.getTitleSnapshot());
        assertEquals(0, new BigDecimal("4").compareTo(inserted.getHours()));
        assertEquals(0, new BigDecimal("1.5").compareTo(inserted.getCredit()));
        assertEquals("https://pan.example.com/cert.pdf", inserted.getEvidenceUrl());
        assertEquals("submitted", inserted.getStatus());
    }

    @Test
    @DisplayName("2.5 createRecord：自主申报缺标题 → 400")
    void createRecordSelfReportRequiresTitle() {
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "JNTC", "技能特长", 1, null, null, null));
        assertError(service.createRecord(WANGXM, body(
                "categoryId", CATEGORY_ID, "hours", new BigDecimal("4"),
                "evidenceUrl", "https://pan.example.com/cert.pdf")), 400, "自报学时须填写申报标题");
        verify(recordMapper, never()).insert(any(SecondClassRecord.class));
    }

    @Test
    @DisplayName("2.6 createRecord：自主申报缺证明材料 → 400（实现强制必填）")
    void createRecordSelfReportRequiresEvidence() {
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "JNTC", "技能特长", 1, null, null, null));
        assertError(service.createRecord(WANGXM, body(
                "categoryId", CATEGORY_ID, "title", "自学 Python", "hours", new BigDecimal("4"))),
                400, "自报学时须填写证明材料链接");
        verify(recordMapper, never()).insert(any(SecondClassRecord.class));
    }

    @Test
    @DisplayName("2.7 createRecord：板块未指定 → 400；板块不存在 → 404")
    void createRecordRequiresExistingCategory() {
        assertError(service.createRecord(WANGXM, body(
                "title", "自学 Python", "hours", new BigDecimal("4"),
                "evidenceUrl", "https://pan.example.com/cert.pdf")), 400, "请选择申报板块");

        when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(null);
        assertError(service.createRecord(WANGXM, body(
                "categoryId", CATEGORY_ID, "title", "自学 Python", "hours", new BigDecimal("4"),
                "evidenceUrl", "https://pan.example.com/cert.pdf")), 404, "板块不存在");
    }

    @Test
    @DisplayName("2.8 createRecord：板块已停用 → 400，历史记录不受影响")
    void createRecordRejectsDisabledCategory() {
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "JNTC", "技能特长", 0, null, null, null));
        assertError(service.createRecord(WANGXM, body(
                "categoryId", CATEGORY_ID, "title", "自学 Python", "hours", new BigDecimal("4"),
                "evidenceUrl", "https://pan.example.com/cert.pdf")),
                400, "板块「技能特长」已停用，不能新申报");
        verify(recordMapper, never()).insert(any(SecondClassRecord.class));
    }

    @Test
    @DisplayName("2.9 createRecord：学时缺失/0/负数 → 400「申报学时必须大于 0」")
    void createRecordRejectsNonPositiveHours() {
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "JNTC", "技能特长", 1, null, null, null));

        assertError(service.createRecord(WANGXM, body(
                "categoryId", CATEGORY_ID, "title", "自学 Python",
                "evidenceUrl", "https://pan.example.com/cert.pdf")), 400, "申报学时必须大于 0");
        assertError(service.createRecord(WANGXM, body(
                "categoryId", CATEGORY_ID, "title", "自学 Python", "hours", BigDecimal.ZERO,
                "evidenceUrl", "https://pan.example.com/cert.pdf")), 400, "申报学时必须大于 0");
        assertError(service.createRecord(WANGXM, body(
                "categoryId", CATEGORY_ID, "title", "自学 Python", "hours", new BigDecimal("-1"),
                "evidenceUrl", "https://pan.example.com/cert.pdf")), 400, "申报学时必须大于 0");
        verify(recordMapper, never()).insert(any(SecondClassRecord.class));
    }

    @Test
    @DisplayName("2.10 createRecord：单条申报学时上限 100")
    void createRecordRejectsHoursAboveHundred() {
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "JNTC", "技能特长", 1, null, null, null));
        assertError(service.createRecord(WANGXM, body(
                "categoryId", CATEGORY_ID, "title", "自学 Python", "hours", new BigDecimal("101"),
                "evidenceUrl", "https://pan.example.com/cert.pdf")), 400, "单条申报学时不能超过 100");
        // 边界 100 允许
        when(recordMapper.selectById(any()))
                .thenReturn(record(RECORD_ID, null, WANGXM, CATEGORY_ID, "submitted"));
        assertOk(service.createRecord(WANGXM, body(
                "categoryId", CATEGORY_ID, "title", "自学 Python", "hours", new BigDecimal("100"),
                "evidenceUrl", "https://pan.example.com/cert.pdf")));
    }

    @Test
    @DisplayName("2.11 createRecord：申报学时不得超过活动学时")
    void createRecordRejectsHoursAboveActivityHours() {
        when(activityMapper.selectById(ACTIVITY_ID))
                .thenReturn(activity(ACTIVITY_ID, CATEGORY_ID, "published", "8"));
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "ZYGY", "志愿公益", 1, null, null, null));
        assertError(service.createRecord(WANGXM,
                        body("activityId", ACTIVITY_ID, "hours", new BigDecimal("9"))),
                400, "申报学时不能超过活动学时（8 学时）");
        verify(recordMapper, never()).insert(any(SecondClassRecord.class));
    }

    @Test
    @DisplayName("2.12 createRecord：申报学分必须在 0-20 之间")
    void createRecordValidatesCreditRange() {
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "JNTC", "技能特长", 1, null, null, null));
        assertError(service.createRecord(WANGXM, body(
                "categoryId", CATEGORY_ID, "title", "自学 Python", "hours", new BigDecimal("4"),
                "credit", new BigDecimal("-1"),
                "evidenceUrl", "https://pan.example.com/cert.pdf")), 400, "申报学分应在 0-20 之间");
        assertError(service.createRecord(WANGXM, body(
                "categoryId", CATEGORY_ID, "title", "自学 Python", "hours", new BigDecimal("4"),
                "credit", new BigDecimal("21"),
                "evidenceUrl", "https://pan.example.com/cert.pdf")), 400, "申报学分应在 0-20 之间");
    }

    @Test
    @DisplayName("2.13 createRecord：同活动重复申报被拒（待初审/已认定均不可重开）")
    void createRecordRejectsDuplicate() {
        when(activityMapper.selectById(ACTIVITY_ID))
                .thenReturn(activity(ACTIVITY_ID, CATEGORY_ID, "published", "8"));
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "ZYGY", "志愿公益", 1, null, null, null));

        when(recordMapper.selectByActivityAndUser(ACTIVITY_ID, WANGXM))
                .thenReturn(record(EXISTING_RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "submitted"));
        assertError(service.createRecord(WANGXM, body("activityId", ACTIVITY_ID, "hours", new BigDecimal("6"))),
                400, "你在该活动下已有申报（待初审），不能重复申报");

        when(recordMapper.selectByActivityAndUser(ACTIVITY_ID, WANGXM))
                .thenReturn(record(EXISTING_RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "confirmed"));
        assertError(service.createRecord(WANGXM, body("activityId", ACTIVITY_ID, "hours", new BigDecimal("6"))),
                400, "你在该活动下已有申报（已认定），不能重复申报");

        verify(recordMapper, never()).insert(any(SecondClassRecord.class));
        verify(recordMapper, never()).resubmit(any(SecondClassRecord.class), anyString());
    }

    @Test
    @DisplayName("2.14 createRecord：已撤回的申报可复用重交（resubmit 带 CAS fromStatus）")
    void createRecordReusesWithdrawnRow() {
        when(activityMapper.selectById(ACTIVITY_ID))
                .thenReturn(activity(ACTIVITY_ID, CATEGORY_ID, "published", "8"));
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "ZYGY", "志愿公益", 1, null, null, null));
        when(recordMapper.selectByActivityAndUser(ACTIVITY_ID, WANGXM))
                .thenReturn(record(EXISTING_RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "withdrawn"));
        when(recordMapper.resubmit(any(SecondClassRecord.class), eq("withdrawn"))).thenReturn(1);
        when(recordMapper.selectById(EXISTING_RECORD_ID))
                .thenReturn(record(EXISTING_RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "submitted"));

        Map<String, Object> data = dataOf(service.createRecord(WANGXM,
                body("activityId", ACTIVITY_ID, "hours", new BigDecimal("6"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> saved = (Map<String, Object>) data.get("record");
        assertEquals(EXISTING_RECORD_ID, ((Number) saved.get("id")).longValue());

        ArgumentCaptor<SecondClassRecord> captor = ArgumentCaptor.forClass(SecondClassRecord.class);
        verify(recordMapper).resubmit(captor.capture(), eq("withdrawn"));
        assertEquals(EXISTING_RECORD_ID, captor.getValue().getId().longValue());
        assertEquals("迎新志愿", captor.getValue().getTitleSnapshot());
        verify(recordMapper, never()).insert(any(SecondClassRecord.class));
    }

    @Test
    @DisplayName("2.15 createRecord：复用重交 CAS 影响 0 行 → 400「状态已变化」")
    void createRecordResubmitCasConflict() {
        when(activityMapper.selectById(ACTIVITY_ID))
                .thenReturn(activity(ACTIVITY_ID, CATEGORY_ID, "published", "8"));
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "ZYGY", "志愿公益", 1, null, null, null));
        when(recordMapper.selectByActivityAndUser(ACTIVITY_ID, WANGXM))
                .thenReturn(record(EXISTING_RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "returned"));
        when(recordMapper.resubmit(any(SecondClassRecord.class), anyString())).thenReturn(0);

        assertError(service.createRecord(WANGXM, body("activityId", ACTIVITY_ID, "hours", new BigDecimal("6"))),
                400, CAS_FAIL);
        verify(recordMapper, never()).insert(any(SecondClassRecord.class));
    }

    @Test
    @DisplayName("2.16 createRecord：待审核申报达 10 条上限 → 400")
    void createRecordEnforcesPendingLimit() {
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "JNTC", "技能特长", 1, null, null, null));
        when(recordMapper.countPendingByUser(WANGXM)).thenReturn(10L);

        assertError(service.createRecord(WANGXM, body(
                "categoryId", CATEGORY_ID, "title", "自学 Python", "hours", new BigDecimal("4"),
                "evidenceUrl", "https://pan.example.com/cert.pdf")),
                400, "待审核申报已达 10 条上限");
        verify(recordMapper, never()).insert(any(SecondClassRecord.class));

        // 9 条时仍可提交
        when(recordMapper.countPendingByUser(WANGXM)).thenReturn(9L);
        when(recordMapper.selectById(any()))
                .thenReturn(record(RECORD_ID, null, WANGXM, CATEGORY_ID, "submitted"));
        assertOk(service.createRecord(WANGXM, body(
                "categoryId", CATEGORY_ID, "title", "自学 Python", "hours", new BigDecimal("4"),
                "evidenceUrl", "https://pan.example.com/cert.pdf")));
    }

    @Test
    @DisplayName("2.17 createRecord：超过板块学年上限只提示不阻断（warnings 附带）")
    void createRecordReturnsCapWarningWithoutBlocking() {
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "ZYGY", "志愿公益", 1, null, "10", "0.25"));
        when(recordMapper.selectById(any()))
                .thenReturn(record(RECORD_ID, null, WANGXM, CATEGORY_ID, "submitted"));
        Map<String, Object> row = body("category_id", CATEGORY_ID, "category_name", "志愿公益",
                "required_hours", new BigDecimal("20"), "yearly_cap_hours", new BigDecimal("10"),
                "credit_per_hour", new BigDecimal("0.25"),
                "confirmed_hours", new BigDecimal("6"), "pending_hours", new BigDecimal("6"),
                "confirmed_credit", new BigDecimal("1.5"), "record_count", 3);
        when(recordMapper.summaryByCategory(WANGXM, 2026)).thenReturn(List.of(row));

        Map<String, Object> data = dataOf(service.createRecord(WANGXM, body(
                "categoryId", CATEGORY_ID, "title", "自学 Python", "hours", new BigDecimal("6"),
                "evidenceUrl", "https://pan.example.com/cert.pdf")));

        List<String> warnings = rowsOf(data, "warnings");
        assertEquals(1, warnings.size(), "超上限必须给出 1 条提示");
        assertTrue(warnings.get(0).contains("本学年合计 12 学时"), warnings.get(0));
        assertTrue(warnings.get(0).contains("上限 10 学时"), warnings.get(0));
        assertTrue(warnings.get(0).contains("系统不自动截断"), warnings.get(0));
        // 不阻断：申报照常写入
        verify(recordMapper).insert(any(SecondClassRecord.class));
    }

    // =========================================================================
    // 3. 状态机（withdraw / updateRecord / counselor-review / confirm）
    // =========================================================================

    @Test
    @DisplayName("3.1 withdraw：submitted → withdrawn 成功（CAS fromStatus=submitted）")
    void withdrawSubmittedSucceeds() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(
                record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "submitted"),
                record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "withdrawn"));
        when(recordMapper.update(any(SecondClassRecord.class), eq("submitted"))).thenReturn(1);

        Map<String, Object> data = dataOf(service.withdraw(WANGXM, RECORD_ID));
        assertEquals("withdrawn", data.get("status"));
        assertEquals("已撤回", data.get("statusText"));

        ArgumentCaptor<SecondClassRecord> captor = ArgumentCaptor.forClass(SecondClassRecord.class);
        verify(recordMapper).update(captor.capture(), eq("submitted"));
        assertEquals("withdrawn", captor.getValue().getStatus());
        assertEquals(RECORD_ID, captor.getValue().getId().longValue());
    }

    @Test
    @DisplayName("3.2 withdraw：只有 submitted 可撤回，其余状态全部拒绝")
    void withdrawRejectsNonSubmittedStates() {
        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "counselor_approved"));
        assertError(service.withdraw(WANGXM, RECORD_ID), 400, "只有待初审的申报可以撤回（当前：待认定）");

        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "confirmed"));
        assertError(service.withdraw(WANGXM, RECORD_ID), 400, "只有待初审的申报可以撤回（当前：已认定）");

        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "rejected"));
        assertError(service.withdraw(WANGXM, RECORD_ID), 400, "只有待初审的申报可以撤回（当前：已否定）");

        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "withdrawn"));
        assertError(service.withdraw(WANGXM, RECORD_ID), 400, "只有待初审的申报可以撤回（当前：已撤回）");

        verify(recordMapper, never()).update(any(SecondClassRecord.class), anyString());
    }

    @Test
    @DisplayName("3.3 withdraw：CAS 影响 0 行 → 400「状态已变化，请刷新后重试」")
    void withdrawCasConflict() {
        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "submitted"));
        when(recordMapper.update(any(SecondClassRecord.class), anyString())).thenReturn(0);

        assertError(service.withdraw(WANGXM, RECORD_ID), 400, CAS_FAIL);
    }

    @Test
    @DisplayName("3.4 withdraw：申报不存在 → 404")
    void withdrawMissingRecord() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(null);
        assertError(service.withdraw(WANGXM, RECORD_ID), 404, "申报不存在");
        verify(recordMapper, never()).update(any(SecondClassRecord.class), anyString());
    }

    @Test
    @DisplayName("3.5 updateRecord：returned → 重交成功（resubmit fromStatus=returned）")
    void updateReturnedRecordSucceeds() {
        SecondClassRecord existing = record(RECORD_ID, null, WANGXM, CATEGORY_ID, "returned");
        existing.setEvidenceUrl("https://pan.example.com/old.pdf");
        existing.setReturnReason("材料不全");
        when(recordMapper.selectById(RECORD_ID)).thenReturn(existing,
                record(RECORD_ID, null, WANGXM, CATEGORY_ID, "submitted"));
        when(recordMapper.resubmit(any(SecondClassRecord.class), eq("returned"))).thenReturn(1);
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "ZYGY", "志愿公益", 1, null, null, null));

        Map<String, Object> data = dataOf(service.updateRecord(WANGXM, RECORD_ID,
                body("hours", new BigDecimal("10"), "note", "已补交证书")));
        @SuppressWarnings("unchecked")
        Map<String, Object> saved = (Map<String, Object>) data.get("record");
        assertEquals("submitted", saved.get("status"));

        ArgumentCaptor<SecondClassRecord> captor = ArgumentCaptor.forClass(SecondClassRecord.class);
        verify(recordMapper).resubmit(captor.capture(), eq("returned"));
        SecondClassRecord patch = captor.getValue();
        assertEquals(RECORD_ID, patch.getId().longValue());
        assertEquals(0, new BigDecimal("10").compareTo(patch.getHours()));
        assertEquals("已补交证书", patch.getNote());
        assertEquals("迎新志愿", patch.getTitleSnapshot(), "未传 title 时保留原快照");
        assertEquals("https://pan.example.com/old.pdf", patch.getEvidenceUrl(), "未传证明材料时沿用原值");
        assertEquals(CATEGORY_ID, patch.getCategoryId().longValue());
    }

    @Test
    @DisplayName("3.6 updateRecord：只有 returned 可改，其它状态拒绝并回显当前状态")
    void updateRejectsNonReturnedStates() {
        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "submitted"));
        assertError(service.updateRecord(WANGXM, RECORD_ID, body("hours", new BigDecimal("2"))),
                400, "只有被退回的申报可以修改后重新提交（当前：待初审）");

        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "confirmed"));
        assertError(service.updateRecord(WANGXM, RECORD_ID, body("hours", new BigDecimal("2"))),
                400, "只有被退回的申报可以修改后重新提交（当前：已认定）");

        verify(recordMapper, never()).resubmit(any(SecondClassRecord.class), anyString());
    }

    @Test
    @DisplayName("3.7 updateRecord：申报不存在 → 404")
    void updateMissingRecord() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(null);
        assertError(service.updateRecord(WANGXM, RECORD_ID, body()), 404, "申报不存在");
    }

    @Test
    @DisplayName("3.8 updateRecord：未传学时沿用原值")
    void updateKeepsOriginalHoursWhenAbsent() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(
                record(RECORD_ID, null, WANGXM, CATEGORY_ID, "returned"),
                record(RECORD_ID, null, WANGXM, CATEGORY_ID, "submitted"));
        when(recordMapper.resubmit(any(SecondClassRecord.class), anyString())).thenReturn(1);
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "ZYGY", "志愿公益", 1, null, null, null));

        assertOk(service.updateRecord(WANGXM, RECORD_ID, body("evidenceUrl", "https://pan.example.com/new.pdf")));

        ArgumentCaptor<SecondClassRecord> captor = ArgumentCaptor.forClass(SecondClassRecord.class);
        verify(recordMapper).resubmit(captor.capture(), eq("returned"));
        assertEquals(0, new BigDecimal("8").compareTo(captor.getValue().getHours()));
        assertEquals("https://pan.example.com/new.pdf", captor.getValue().getEvidenceUrl());
    }

    @Test
    @DisplayName("3.9 updateRecord：学时 0/负/超 100 → 400")
    void updateValidatesHours() {
        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, null, WANGXM, CATEGORY_ID, "returned"));
        assertError(service.updateRecord(WANGXM, RECORD_ID, body("hours", BigDecimal.ZERO)),
                400, "申报学时必须大于 0");
        assertError(service.updateRecord(WANGXM, RECORD_ID, body("hours", new BigDecimal("101"))),
                400, "单条申报学时不能超过 100");
        verify(recordMapper, never()).resubmit(any(SecondClassRecord.class), anyString());
    }

    @Test
    @DisplayName("3.10 updateRecord：自主申报重交必须仍有证明材料")
    void updateSelfReportRequiresEvidence() {
        SecondClassRecord existing = record(RECORD_ID, null, WANGXM, CATEGORY_ID, "returned");
        existing.setEvidenceUrl(null);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(existing);
        assertError(service.updateRecord(WANGXM, RECORD_ID, body("hours", new BigDecimal("4"))),
                400, "自报学时须填写证明材料链接");
    }

    @Test
    @DisplayName("3.11 updateRecord：重交学时不得超过活动学时")
    void updateRejectsHoursAboveActivityHours() {
        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "returned"));
        when(activityMapper.selectById(ACTIVITY_ID))
                .thenReturn(activity(ACTIVITY_ID, CATEGORY_ID, "published", "8"));
        assertError(service.updateRecord(WANGXM, RECORD_ID, body("hours", new BigDecimal("12"))),
                400, "申报学时不能超过活动学时（8 学时）");
    }

    @Test
    @DisplayName("3.12 updateRecord：CAS 影响 0 行 → 400")
    void updateCasConflict() {
        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, null, WANGXM, CATEGORY_ID, "returned"));
        when(recordMapper.resubmit(any(SecondClassRecord.class), anyString())).thenReturn(0);
        assertError(service.updateRecord(WANGXM, RECORD_ID,
                body("hours", new BigDecimal("4"), "evidenceUrl", "https://pan.example.com/cert.pdf")),
                400, CAS_FAIL);
    }

    @Test
    @DisplayName("3.13 updateRecord 与 createRecord 一致：学分必须在 0-20 之间（P18-C3 已修）")
    void updateValidatesCreditRange() {
        // createRecord 同值被拒（对照组）
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "ZYGY", "志愿公益", 1, null, null, null));
        assertError(service.createRecord(WANGXM, body(
                "categoryId", CATEGORY_ID, "title", "自学 Python", "hours", new BigDecimal("4"),
                "credit", new BigDecimal("25"),
                "evidenceUrl", "https://pan.example.com/cert.pdf")), 400, "申报学分应在 0-20 之间");

        // updateRecord 同样传 25 学分：现在同样 400，且不得落库
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record(RECORD_ID, null, WANGXM, CATEGORY_ID, "returned"));
        assertError(service.updateRecord(WANGXM, RECORD_ID,
                body("hours", new BigDecimal("4"), "credit", new BigDecimal("25"),
                        "evidenceUrl", "https://pan.example.com/cert.pdf")), 400, "申报学分应在 0-20 之间");
        assertError(service.updateRecord(WANGXM, RECORD_ID,
                body("hours", new BigDecimal("4"), "credit", new BigDecimal("-0.5"),
                        "evidenceUrl", "https://pan.example.com/cert.pdf")), 400, "申报学分应在 0-20 之间");
        verify(recordMapper, never()).resubmit(any(SecondClassRecord.class), anyString());
    }

    @Test
    @DisplayName("3.14 counselorReview：approve → counselor_approved，并记录初审人与时间")
    void counselorReviewApprove() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(
                record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "submitted"),
                record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "counselor_approved"));
        when(recordMapper.update(any(SecondClassRecord.class), eq("submitted"))).thenReturn(1);

        Map<String, Object> data = dataOf(service.counselorReview(COUNSELOR, ROLE_TEACHER, RECORD_ID,
                body("action", "approve")));
        assertEquals("counselor_approved", data.get("status"));
        assertEquals("待认定", data.get("statusText"));

        ArgumentCaptor<SecondClassRecord> captor = ArgumentCaptor.forClass(SecondClassRecord.class);
        verify(recordMapper).update(captor.capture(), eq("submitted"));
        SecondClassRecord patch = captor.getValue();
        assertEquals("counselor_approved", patch.getStatus());
        assertEquals(COUNSELOR, patch.getCounselorId().longValue());
        assertNotNull(patch.getCounselorAt());
        assertNull(patch.getReturnReason(), "通过时不应写退回原因");
    }

    @Test
    @DisplayName("3.15 counselorReview：return/reject 均落 returned 并写回原因")
    void counselorReviewReturnWithReason() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(
                record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "submitted"),
                record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "returned"));
        when(recordMapper.update(any(SecondClassRecord.class), eq("submitted"))).thenReturn(1);

        Map<String, Object> data = dataOf(service.counselorReview(COUNSELOR, ROLE_TEACHER, RECORD_ID,
                body("action", "return", "reason", "证明材料不清晰，请重新上传")));
        assertEquals("returned", data.get("status"));
        assertEquals("已退回", data.get("statusText"));

        ArgumentCaptor<SecondClassRecord> captor = ArgumentCaptor.forClass(SecondClassRecord.class);
        verify(recordMapper, atLeastOnce()).update(captor.capture(), eq("submitted"));
        assertEquals("returned", captor.getValue().getStatus());
        assertEquals("证明材料不清晰，请重新上传", captor.getValue().getReturnReason());

        // 初审 reject 等价于 return（同在 submitted 阶段）
        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "submitted"));
        when(recordMapper.update(any(SecondClassRecord.class), eq("submitted"))).thenReturn(1);
        assertOk(service.counselorReview(COUNSELOR, ROLE_TEACHER, RECORD_ID,
                body("action", "reject", "rejectReason", "不属于第二课堂范围")));
    }

    @Test
    @DisplayName("3.16 counselorReview：退回/否定缺原因 → 400")
    void counselorReviewRequiresReason() {
        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "submitted"));

        assertError(service.counselorReview(COUNSELOR, ROLE_TEACHER, RECORD_ID, body("action", "return")),
                400, "退回申报必须填写原因");
        assertError(service.counselorReview(COUNSELOR, ROLE_TEACHER, RECORD_ID,
                body("action", "return", "reason", "   ")), 400, "退回申报必须填写原因");
        verify(recordMapper, never()).update(any(SecondClassRecord.class), anyString());
    }

    @Test
    @DisplayName("3.17 counselorReview：只有 submitted 可初审（状态检查先于 action 解析）")
    void counselorReviewRejectsNonSubmitted() {
        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "confirmed"));
        assertError(service.counselorReview(COUNSELOR, ROLE_TEACHER, RECORD_ID, body("action", "approve")),
                400, "该申报当前状态是「已认定」，只有待初审的申报可以初审");

        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "returned"));
        assertError(service.counselorReview(COUNSELOR, ROLE_TEACHER, RECORD_ID, body("action", "approve")),
                400, "该申报当前状态是「已退回」，只有待初审的申报可以初审");
    }

    @Test
    @DisplayName("3.18 counselorReview：非法 action → 400")
    void counselorReviewRejectsUnknownAction() {
        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "submitted"));
        assertError(service.counselorReview(COUNSELOR, ROLE_TEACHER, RECORD_ID, body("action", "confirm")),
                400, "action 只能是 approve（通过）或 return（退回）");
        assertError(service.counselorReview(COUNSELOR, ROLE_TEACHER, RECORD_ID, body()),
                400, "action 只能是 approve（通过）或 return（退回）");
        verify(recordMapper, never()).update(any(SecondClassRecord.class), anyString());
    }

    @Test
    @DisplayName("3.19 counselorReview：不存在 → 404；CAS 0 行 → 400")
    void counselorReviewMissingAndCasConflict() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(null);
        assertError(service.counselorReview(COUNSELOR, ROLE_TEACHER, RECORD_ID, body("action", "approve")),
                404, "申报不存在");

        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "submitted"));
        when(recordMapper.update(any(SecondClassRecord.class), anyString())).thenReturn(0);
        assertError(service.counselorReview(COUNSELOR, ROLE_TEACHER, RECORD_ID, body("action", "approve")),
                400, CAS_FAIL);
    }

    @Test
    @DisplayName("3.20 counselorReview：跨辅导员范围 → 403「该学生不在你的名下，无权初审」")
    void counselorReviewRejectsOutOfScope() {
        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "submitted"));
        when(studentProfileMapper.countByCounselor(COUNSELOR)).thenReturn(2);
        when(studentProfileMapper.findByUserId(WANGXM))
                .thenReturn(profileOf(WANGXM, OUT_OF_SCOPE_COUNSELOR));

        assertDenied(service.counselorReview(COUNSELOR, ROLE_TEACHER, RECORD_ID, body("action", "approve")),
                "该学生不在你的名下，无权初审");
        verify(recordMapper, never()).update(any(SecondClassRecord.class), anyString());

        // 名下学生则放行
        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(profileOf(WANGXM, COUNSELOR));
        when(recordMapper.update(any(SecondClassRecord.class), eq("submitted"))).thenReturn(1);
        assertOk(service.counselorReview(COUNSELOR, ROLE_TEACHER, RECORD_ID, body("action", "approve")));
    }

    @Test
    @DisplayName("3.21 confirm：认定时按板块 credit_per_hour 折算学分（8 × 0.25 = 2.00）")
    void confirmConvertsCreditByCoefficient() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(
                record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "counselor_approved"),
                record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "confirmed"));
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "ZYGY", "志愿公益", 1, null, null, "0.25"));
        when(recordMapper.update(any(SecondClassRecord.class), eq("counselor_approved"))).thenReturn(1);

        Map<String, Object> data = dataOf(service.confirm(ADMIN, ROLE_ADMIN, RECORD_ID,
                body("action", "confirm")));
        assertEquals("confirmed", data.get("status"));
        assertEquals("已认定", data.get("statusText"));

        ArgumentCaptor<SecondClassRecord> captor = ArgumentCaptor.forClass(SecondClassRecord.class);
        verify(recordMapper).update(captor.capture(), eq("counselor_approved"));
        SecondClassRecord patch = captor.getValue();
        assertEquals("confirmed", patch.getStatus());
        assertNotNull(patch.getCredit());
        assertEquals(0, new BigDecimal("2.00").compareTo(patch.getCredit()));
        assertEquals(ADMIN, patch.getReviewerId().longValue());
        assertNotNull(patch.getReviewedAt());
    }

    @Test
    @DisplayName("3.22 confirm：显式传入学分优先于系数折算")
    void confirmExplicitCreditWins() {
        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "counselor_approved"));
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "ZYGY", "志愿公益", 1, null, null, "0.25"));
        when(recordMapper.update(any(SecondClassRecord.class), anyString())).thenReturn(1);

        assertOk(service.confirm(ADMIN, ROLE_ADMIN, RECORD_ID,
                body("action", "confirm", "credit", new BigDecimal("3.5"))));

        ArgumentCaptor<SecondClassRecord> captor = ArgumentCaptor.forClass(SecondClassRecord.class);
        verify(recordMapper).update(captor.capture(), eq("counselor_approved"));
        assertEquals(0, new BigDecimal("3.5").compareTo(captor.getValue().getCredit()));
    }

    @Test
    @DisplayName("3.23 confirm：板块未设 credit_per_hour 时学分保持 null（不代填 0）")
    void confirmLeavesCreditNullWhenNotConfigured() {
        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "counselor_approved"));
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "ZYGY", "志愿公益", 1, null, null, null));
        when(recordMapper.update(any(SecondClassRecord.class), anyString())).thenReturn(1);

        assertOk(service.confirm(ADMIN, ROLE_ADMIN, RECORD_ID, body("action", "confirm")));

        ArgumentCaptor<SecondClassRecord> captor = ArgumentCaptor.forClass(SecondClassRecord.class);
        verify(recordMapper).update(captor.capture(), anyString());
        assertNull(captor.getValue().getCredit(), "学校未设置折算系数时不得写 0");
    }

    @Test
    @DisplayName("3.24 confirm：reject → rejected 并写回否定原因")
    void confirmRejectWithReason() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(
                record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "counselor_approved"),
                record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "rejected"));
        when(recordMapper.update(any(SecondClassRecord.class), eq("counselor_approved"))).thenReturn(1);

        Map<String, Object> data = dataOf(service.confirm(ADMIN, ROLE_ADMIN, RECORD_ID,
                body("action", "reject", "reason", "材料不成立")));
        assertEquals("rejected", data.get("status"));
        assertEquals("已否定", data.get("statusText"));

        ArgumentCaptor<SecondClassRecord> captor = ArgumentCaptor.forClass(SecondClassRecord.class);
        verify(recordMapper).update(captor.capture(), eq("counselor_approved"));
        assertEquals("rejected", captor.getValue().getStatus());
        assertEquals("材料不成立", captor.getValue().getReturnReason());
    }

    @Test
    @DisplayName("3.25 confirm：否定缺原因 → 400")
    void confirmRejectRequiresReason() {
        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "counselor_approved"));
        assertError(service.confirm(ADMIN, ROLE_ADMIN, RECORD_ID, body("action", "reject")),
                400, "否定申报必须填写原因");
        verify(recordMapper, never()).update(any(SecondClassRecord.class), anyString());
    }

    @Test
    @DisplayName("3.26 confirm：只能从 counselor_approved 认定")
    void confirmRequiresCounselorApproved() {
        for (String status : List.of("submitted", "returned", "confirmed", "rejected", "withdrawn")) {
            when(recordMapper.selectById(RECORD_ID))
                    .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, status));
            Result<?> result = service.confirm(ADMIN, ROLE_ADMIN, RECORD_ID, body("action", "confirm"));
            assertError(result, 400, "只有已通过初审的申报可以认定（当前：");
        }
        verify(recordMapper, never()).update(any(SecondClassRecord.class), anyString());
    }

    @Test
    @DisplayName("3.27 confirm：非法 action → 400；不存在 → 404；CAS 0 行 → 400")
    void confirmUnknownActionMissingAndCasConflict() {
        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "counselor_approved"));
        assertError(service.confirm(ADMIN, ROLE_ADMIN, RECORD_ID, body("action", "approveAll")),
                400, "action 只能是 confirm（认定）或 reject（否定）");

        when(recordMapper.selectById(RECORD_ID)).thenReturn(null);
        assertError(service.confirm(ADMIN, ROLE_ADMIN, RECORD_ID, body("action", "confirm")),
                404, "申报不存在");

        when(recordMapper.selectById(RECORD_ID))
                .thenReturn(record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "counselor_approved"));
        when(recordMapper.update(any(SecondClassRecord.class), anyString())).thenReturn(0);
        assertError(service.confirm(ADMIN, ROLE_ADMIN, RECORD_ID, body("action", "confirm")), 400, CAS_FAIL);
    }

    // =========================================================================
    // 4. 成绩单汇总（学年筛选 / 学分换算 / 上限 / warnings / 空数据）
    // =========================================================================

    @Test
    @DisplayName("4.1 summary：无任何记录时不炸，返回空板块与空提示")
    void summaryHandlesEmptyData() {
        Map<String, Object> data = dataOf(service.summary(WANGXM, null));
        assertTrue(rowsOf(data, "categories").isEmpty());
        assertTrue(rowsOf(data, "warnings").isEmpty());
        assertNull(data.get("year"));
        assertNotNull(data.get("policyNote"));
        assertTrue(String.valueOf(data.get("policyNote")).contains("学校设定"));
    }

    @Test
    @DisplayName("4.2 summary：年度筛选——合法年份透传，越界年份归零为 null（不限年）")
    void summaryFiltersByYear() {
        dataOf(service.summary(WANGXM, 2026));
        verify(recordMapper).summaryByCategory(WANGXM, 2026);
        verify(recordMapper).totalsByUser(WANGXM, 2026);

        Map<String, Object> data = dataOf(service.summary(WANGXM, 1999));
        assertNull(data.get("year"));
        verify(recordMapper).summaryByCategory(WANGXM, null);

        dataOf(service.summary(WANGXM, 2101));
        verify(recordMapper, times(2)).summaryByCategory(WANGXM, null);
    }

    @Test
    @DisplayName("4.3 summary：合计超学年上限只出 warning 与 gap，不截断数字")
    void summaryReportsCapWarning() {
        Map<String, Object> row = body(
                "category_id", CATEGORY_ID, "category_name", "志愿公益",
                "required_hours", new BigDecimal("20"), "yearly_cap_hours", new BigDecimal("10"),
                "credit_per_hour", new BigDecimal("0.25"),
                "confirmed_hours", new BigDecimal("8"), "pending_hours", new BigDecimal("4"),
                "confirmed_credit", new BigDecimal("2.00"), "record_count", 3);
        when(recordMapper.summaryByCategory(WANGXM, 2026)).thenReturn(List.of(row));

        Map<String, Object> data = dataOf(service.summary(WANGXM, 2026));
        List<Map<String, Object>> categories = rowsOf(data, "categories");
        assertEquals(1, categories.size());

        Map<String, Object> item = categories.get(0);
        assertEquals(CATEGORY_ID, ((Number) item.get("categoryId")).longValue());
        assertEquals("志愿公益", item.get("categoryName"));
        assertEquals(0, new BigDecimal("20").compareTo((BigDecimal) item.get("requiredHours")));
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) item.get("yearlyCapHours")));
        assertEquals(0, new BigDecimal("0.25").compareTo((BigDecimal) item.get("creditPerHour")));
        assertEquals(0, new BigDecimal("8").compareTo((BigDecimal) item.get("confirmedHours")));
        assertEquals(0, new BigDecimal("4").compareTo((BigDecimal) item.get("pendingHours")));
        assertEquals(0, new BigDecimal("2.00").compareTo((BigDecimal) item.get("confirmedCredit")));
        assertEquals(3, ((Number) item.get("recordCount")).intValue());
        // gap = required - confirmed = 20 - 8 = 12（只减已认定）
        assertEquals(0, new BigDecimal("12").compareTo((BigDecimal) item.get("gapHours")));
        assertEquals(Boolean.TRUE, item.get("requiredConfigured"));
        assertEquals(Boolean.TRUE, item.get("capConfigured"));

        // 12 > cap 10 ⇒ 提示但原始合计 12 原样保留（不自动截断为 10）
        List<String> warnings = rowsOf(data, "warnings");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("板块「志愿公益」本学年合计 12 学时"), warnings.get(0));
        assertTrue(warnings.get(0).contains("已超过学校设定的单学年上限 10 学时"), warnings.get(0));
        assertTrue(warnings.get(0).contains("系统不自动截断"), warnings.get(0));
    }

    @Test
    @DisplayName("4.4 summary：NULL 阈值原样下发 null（不写 0），configured 标记为 false")
    void summaryKeepsNullThresholds() {
        Map<String, Object> row = body("category_id", OTHER_CATEGORY_ID, "category_name", "创新创业");
        when(recordMapper.summaryByCategory(WANGXM, null)).thenReturn(List.of(row));

        Map<String, Object> data = dataOf(service.summary(WANGXM, null));
        Map<String, Object> item = (Map<String, Object>) rowsOf(data, "categories").get(0);
        assertNull(item.get("requiredHours"), "未设置必须回 null，不能补 0");
        assertNull(item.get("yearlyCapHours"));
        assertNull(item.get("creditPerHour"));
        assertNull(item.get("gapHours"), "未设要求学时就没有缺口概念");
        assertEquals(Boolean.FALSE, item.get("requiredConfigured"));
        assertEquals(Boolean.FALSE, item.get("capConfigured"));
        // 已认定/待审缺失按 0 参与计算
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) item.get("confirmedHours")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) item.get("pendingHours")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) item.get("confirmedCredit")));
        assertEquals(0, ((Number) item.get("recordCount")).intValue());
        assertTrue(rowsOf(data, "warnings").isEmpty(), "未设上限不得产生超限提示");
    }

    @Test
    @DisplayName("4.5 summary：totals 原样透出（合计口径由 SQL 负责）")
    void summaryPassesTotalsThrough() {
        Map<String, Object> totals = body(
                "confirmed_hours", new BigDecimal("18"), "pending_hours", new BigDecimal("6"),
                "confirmed_credit", new BigDecimal("4.5"),
                "confirmed_count", 4, "pending_count", 2);
        when(recordMapper.totalsByUser(WANGXM, 2026)).thenReturn(totals);

        Map<String, Object> data = dataOf(service.summary(WANGXM, 2026));
        assertSame(totals, data.get("totals"));
    }

    // =========================================================================
    // 5. 台账 CRUD（板块 / 活动）
    // =========================================================================

    @Test
    @DisplayName("5.1 createCategory：成功默认 sortNo=100、enabled=1，三列阈值保持未设置")
    void createCategorySucceeds() {
        when(categoryMapper.selectByCode("SJ")).thenReturn(null);
        when(categoryMapper.selectById(any()))
                .thenReturn(category(9L, "SJ", "实践实习", 1, null, null, null));

        Map<String, Object> data = dataOf(service.createCategory(ADMIN, ROLE_ADMIN,
                body("name", "实践实习", "code", "SJ")));
        assertEquals(9L, ((Number) data.get("id")).longValue());
        assertEquals("实践实习", data.get("name"));
        assertEquals("SJ", data.get("code"));
        assertEquals(100, ((Number) data.get("sortNo")).intValue());
        assertEquals(1, ((Number) data.get("enabled")).intValue());
        assertEquals(Boolean.FALSE, data.get("configured"));
        assertNull(data.get("requiredHours"));
        assertNull(data.get("yearlyCapHours"));
        assertNull(data.get("creditPerHour"));

        ArgumentCaptor<SecondClassCategory> captor = ArgumentCaptor.forClass(SecondClassCategory.class);
        verify(categoryMapper).insert(captor.capture());
        SecondClassCategory inserted = captor.getValue();
        assertEquals("实践实习", inserted.getName());
        assertEquals("SJ", inserted.getCode());
        assertEquals(100, inserted.getSortNo().intValue());
        assertEquals(1, inserted.getEnabled().intValue());
        assertNull(inserted.getRequiredHours(), "建表不预置任何数值");
        assertNull(inserted.getYearlyCapHours());
        assertNull(inserted.getCreditPerHour());
    }

    @Test
    @DisplayName("5.2 createCategory：名称/编码必填、编码格式、编码唯一性校验")
    void createCategoryValidates() {
        assertError(service.createCategory(ADMIN, ROLE_ADMIN, body("code", "SJ")),
                400, "板块名称不能为空");
        assertError(service.createCategory(ADMIN, ROLE_ADMIN, body("name", "实践实习")),
                400, "板块编码不能为空");
        assertError(service.createCategory(ADMIN, ROLE_ADMIN, body("name", "实践实习", "code", "sj")),
                400, "板块编码格式应为大写字母开头");
        assertError(service.createCategory(ADMIN, ROLE_ADMIN, body("name", "实践实习", "code", "A")),
                400, "板块编码格式应为大写字母开头");

        when(categoryMapper.selectByCode("SJ"))
                .thenReturn(category(OTHER_CATEGORY_ID, "SJ", "实践实习", 1, null, null, null));
        assertError(service.createCategory(ADMIN, ROLE_ADMIN, body("name", "实践实习", "code", "SJ")),
                400, "板块编码 SJ 已存在");
        verify(categoryMapper, never()).insert(any(SecondClassCategory.class));
    }

    @Test
    @DisplayName("5.3 updateCategory：板块不存在 → 404")
    void updateCategoryMissing() {
        when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(null);
        assertError(service.updateCategory(ADMIN, ROLE_ADMIN, CATEGORY_ID, body("name", "x")),
                404, "板块不存在");
        verify(categoryMapper, never()).update(any(SecondClassCategory.class));
    }

    @Test
    @DisplayName("5.4 updateCategory：三列阈值整体覆盖（可写回 NULL），停用只记日志不阻断")
    void updateCategoryOverwritesThresholds() {
        SecondClassCategory existing = category(CATEGORY_ID, "ZYGY", "志愿公益", 1, "20", "10", "0.25");
        when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(existing,
                category(CATEGORY_ID, "ZYGY", "志愿公益（改）", 0, null, null, null));
        when(categoryMapper.countRecordsByCategory(CATEGORY_ID)).thenReturn(3L);

        Map<String, Object> data = dataOf(service.updateCategory(ADMIN, ROLE_ADMIN, CATEGORY_ID,
                body("name", "志愿公益（改）", "sortNo", 5, "enabled", 0, "note", "由校团委设置")));
        assertEquals(0, ((Number) data.get("enabled")).intValue());
        assertEquals("志愿公益（改）", data.get("name"));

        ArgumentCaptor<SecondClassCategory> captor = ArgumentCaptor.forClass(SecondClassCategory.class);
        verify(categoryMapper).update(captor.capture());
        SecondClassCategory patch = captor.getValue();
        assertEquals(CATEGORY_ID, patch.getId().longValue());
        assertEquals("志愿公益（改）", patch.getName());
        assertEquals(5, patch.getSortNo().intValue());
        assertEquals(0, patch.getEnabled().intValue());
        assertEquals("由校团委设置", patch.getNote());
        assertNull(patch.getRequiredHours(), "表单未带三列 ⇒ 整体覆盖为 NULL（学校未设置）");
        assertNull(patch.getYearlyCapHours());
        assertNull(patch.getCreditPerHour());
        // 停用前查过记录数（仅提示，不阻断）
        verify(categoryMapper, atLeastOnce()).countRecordsByCategory(CATEGORY_ID);
    }

    @Test
    @DisplayName("5.5 板块列表：学生只见启用项；admin 台账含停用项")
    void listCategoriesRespectsEnabled() {
        when(categoryMapper.listEnabled())
                .thenReturn(List.of(category(CATEGORY_ID, "ZYGY", "志愿公益", 1, null, null, null)));
        Map<String, Object> studentData = dataOf(service.listCategories(WANGXM));
        assertEquals(1, rowsOf(studentData, "categories").size());
        assertNotNull(studentData.get("policyNote"));

        when(categoryMapper.listAll()).thenReturn(List.of(
                category(CATEGORY_ID, "ZYGY", "志愿公益", 1, null, null, null),
                category(OTHER_CATEGORY_ID, "SJ", "实践实习", 0, null, null, null)));
        Map<String, Object> adminData = dataOf(service.adminListCategories(ADMIN, ROLE_ADMIN));
        assertEquals(2, rowsOf(adminData, "categories").size());
        assertNotNull(adminData.get("policyNote"));
    }

    @Test
    @DisplayName("5.6 createActivity：默认落 draft，结束日期缺省等于开始日期")
    void createActivityDefaultsToDraft() {
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "ZYGY", "志愿公益", 1, null, null, null));
        when(activityMapper.selectById(any()))
                .thenReturn(activity(ACTIVITY_ID, CATEGORY_ID, "draft", "8"));

        Map<String, Object> data = dataOf(service.createActivity(ADMIN, ROLE_ADMIN, body(
                "categoryId", CATEGORY_ID, "title", "迎新志愿", "hours", new BigDecimal("8"),
                "startDate", "2026-03-01", "organizer", "校团委")));
        assertEquals("draft", data.get("status"));
        assertEquals("草稿", data.get("statusText"));
        assertEquals("2026-03-01", data.get("startDate"));

        ArgumentCaptor<SecondClassActivity> captor = ArgumentCaptor.forClass(SecondClassActivity.class);
        verify(activityMapper).insert(captor.capture());
        SecondClassActivity inserted = captor.getValue();
        assertEquals("draft", inserted.getStatus());
        assertEquals(LocalDate.of(2026, 3, 1), inserted.getStartDate());
        assertEquals(LocalDate.of(2026, 3, 1), inserted.getEndDate());
        assertEquals("college", inserted.getLevel(), "层级缺省院级");
        assertEquals(ADMIN, inserted.getCreatedBy().longValue());
    }

    @Test
    @DisplayName("5.7 createActivity：publish 为真值直接发布")
    void createActivityCanPublishImmediately() {
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "ZYGY", "志愿公益", 1, null, null, null));
        when(activityMapper.selectById(any()))
                .thenReturn(activity(ACTIVITY_ID, CATEGORY_ID, "published", "8"));

        Map<String, Object> data = dataOf(service.createActivity(ADMIN, ROLE_ADMIN, body(
                "categoryId", CATEGORY_ID, "title", "迎新志愿", "hours", new BigDecimal("8"),
                "startDate", "2026-03-01", "organizer", "校团委", "level", "school", "publish", "yes")));
        assertEquals("published", data.get("status"));
        assertEquals("已发布", data.get("statusText"));

        ArgumentCaptor<SecondClassActivity> captor = ArgumentCaptor.forClass(SecondClassActivity.class);
        verify(activityMapper).insert(captor.capture());
        assertEquals("published", captor.getValue().getStatus());
        assertEquals("school", captor.getValue().getLevel());
    }

    @Test
    @DisplayName("5.8 createActivity：板块/名称/学时/层级/日期校验矩阵")
    void createActivityValidates() {
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "ZYGY", "志愿公益", 1, null, null, null));
        when(categoryMapper.selectById(OTHER_CATEGORY_ID)).thenReturn(null);

        assertError(service.createActivity(ADMIN, ROLE_ADMIN, body("title", "迎新志愿")),
                400, "请选择活动所属板块");
        assertError(service.createActivity(ADMIN, ROLE_ADMIN, body("categoryId", OTHER_CATEGORY_ID)),
                404, "板块不存在");
        assertError(service.createActivity(ADMIN, ROLE_ADMIN,
                        body("categoryId", CATEGORY_ID, "hours", new BigDecimal("8"),
                                "startDate", "2026-03-01")), 400, "活动名称不能为空");
        assertError(service.createActivity(ADMIN, ROLE_ADMIN,
                        body("categoryId", CATEGORY_ID, "title", "迎新志愿",
                                "startDate", "2026-03-01")), 400, "活动学时必须大于 0");
        assertError(service.createActivity(ADMIN, ROLE_ADMIN,
                        body("categoryId", CATEGORY_ID, "title", "迎新志愿", "hours", BigDecimal.ZERO,
                                "startDate", "2026-03-01")), 400, "活动学时必须大于 0");
        assertError(service.createActivity(ADMIN, ROLE_ADMIN,
                        body("categoryId", CATEGORY_ID, "title", "迎新志愿", "hours", new BigDecimal("101"),
                                "startDate", "2026-03-01")),
                400, "单场活动学时不能超过 100（数值应由学校设定，超过请拆分为多场）");
        assertError(service.createActivity(ADMIN, ROLE_ADMIN,
                        body("categoryId", CATEGORY_ID, "title", "迎新志愿", "hours", new BigDecimal("8"),
                                "level", "world", "startDate", "2026-03-01")),
                400, "组织层级只能是 school / college / class / other");
        assertError(service.createActivity(ADMIN, ROLE_ADMIN,
                        body("categoryId", CATEGORY_ID, "title", "迎新志愿", "hours", new BigDecimal("8"))),
                400, "活动开始日期不能为空");
        assertError(service.createActivity(ADMIN, ROLE_ADMIN,
                        body("categoryId", CATEGORY_ID, "title", "迎新志愿", "hours", new BigDecimal("8"),
                                "startDate", "2026-03-10", "endDate", "2026-03-01")),
                400, "活动结束日期不能早于开始日期");
        // 主办单位必填：DDL 是 organizer NOT NULL，若不在服务层拦会抛 SQL 约束异常被兜成 500
        assertError(service.createActivity(ADMIN, ROLE_ADMIN,
                        body("categoryId", CATEGORY_ID, "title", "迎新志愿", "hours", new BigDecimal("8"),
                                "startDate", "2026-03-01")),
                400, "主办单位不能为空");
        assertError(service.createActivity(ADMIN, ROLE_ADMIN,
                        body("categoryId", CATEGORY_ID, "title", "迎新志愿", "hours", new BigDecimal("8"),
                                "startDate", "2026-03-01", "organizer", "   ")),
                400, "主办单位不能为空");
        verify(activityMapper, never()).insert(any(SecondClassActivity.class));
    }

    @Test
    @DisplayName("5.9 createActivity：板块停用不可在其下建活动")
    void createActivityRejectsDisabledCategory() {
        when(categoryMapper.selectById(CATEGORY_ID))
                .thenReturn(category(CATEGORY_ID, "ZYGY", "志愿公益", 0, null, null, null));
        assertError(service.createActivity(ADMIN, ROLE_ADMIN,
                        body("categoryId", CATEGORY_ID, "title", "迎新志愿", "hours", new BigDecimal("8"),
                                "startDate", "2026-03-01")),
                400, "板块「志愿公益」已停用，不能在其下新建活动");
    }

    @Test
    @DisplayName("5.10 updateActivity：只有草稿可改")
    void updateActivityRequiresDraft() {
        when(activityMapper.selectById(ACTIVITY_ID))
                .thenReturn(activity(ACTIVITY_ID, CATEGORY_ID, "published", "8"));
        assertError(service.updateActivity(ADMIN, ROLE_ADMIN, ACTIVITY_ID, body("title", "改")),
                400, "只有草稿活动可以修改");

        when(activityMapper.selectById(ACTIVITY_ID))
                .thenReturn(activity(ACTIVITY_ID, CATEGORY_ID, "closed", "8"));
        assertError(service.updateActivity(ADMIN, ROLE_ADMIN, ACTIVITY_ID, body("title", "改")),
                400, "只有草稿活动可以修改");
        verify(activityMapper, never()).update(any(SecondClassActivity.class), anyString());
    }

    @Test
    @DisplayName("5.11 updateActivity：草稿修改成功，学分整体覆盖，CAS 带 fromStatus=draft")
    void updateActivitySucceeds() {
        SecondClassActivity existing = activity(ACTIVITY_ID, CATEGORY_ID, "draft", "8");
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(existing,
                activity(ACTIVITY_ID, CATEGORY_ID, "draft", "6"));
        when(activityMapper.update(any(SecondClassActivity.class), eq("draft"))).thenReturn(1);

        assertOk(service.updateActivity(ADMIN, ROLE_ADMIN, ACTIVITY_ID, body(
                "title", "迎新志愿（改）", "hours", new BigDecimal("6"),
                "credit", new BigDecimal("1.5"), "level", "school", "startDate", "2026-04-01")));

        ArgumentCaptor<SecondClassActivity> captor = ArgumentCaptor.forClass(SecondClassActivity.class);
        verify(activityMapper).update(captor.capture(), eq("draft"));
        SecondClassActivity patch = captor.getValue();
        assertEquals("迎新志愿（改）", patch.getTitle());
        assertEquals(0, new BigDecimal("6").compareTo(patch.getHours()));
        assertEquals(0, new BigDecimal("1.5").compareTo(patch.getCredit()));
        assertEquals("school", patch.getLevel());
        assertEquals(LocalDate.of(2026, 4, 1), patch.getStartDate());
    }

    @Test
    @DisplayName("5.12 updateActivity：板块不存在 404 / 层级非法 400 / 学时越界 400 / CAS 0 行 400")
    void updateActivityValidationAndCas() {
        when(activityMapper.selectById(ACTIVITY_ID))
                .thenReturn(activity(ACTIVITY_ID, CATEGORY_ID, "draft", "8"));
        when(categoryMapper.selectById(OTHER_CATEGORY_ID)).thenReturn(null);

        assertError(service.updateActivity(ADMIN, ROLE_ADMIN, ACTIVITY_ID,
                body("categoryId", OTHER_CATEGORY_ID)), 404, "板块不存在");
        assertError(service.updateActivity(ADMIN, ROLE_ADMIN, ACTIVITY_ID,
                body("level", "world")), 400, "组织层级只能是 school / college / class / other");
        assertError(service.updateActivity(ADMIN, ROLE_ADMIN, ACTIVITY_ID,
                body("hours", BigDecimal.ZERO)), 400, "活动学时必须大于 0");
        assertError(service.updateActivity(ADMIN, ROLE_ADMIN, ACTIVITY_ID,
                body("hours", new BigDecimal("101"))),
                400, "单场活动学时不能超过 100（数值应由学校设定）");

        when(activityMapper.update(any(SecondClassActivity.class), anyString())).thenReturn(0);
        assertError(service.updateActivity(ADMIN, ROLE_ADMIN, ACTIVITY_ID,
                body("title", "迎新志愿（改）")), 400, CAS_FAIL);
    }

    @Test
    @DisplayName("5.13 updateActivity：活动不存在 → 404")
    void updateActivityMissing() {
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(null);
        assertError(service.updateActivity(ADMIN, ROLE_ADMIN, ACTIVITY_ID, body("title", "改")),
                404, "活动不存在");
    }

    @Test
    @DisplayName("5.14 changeActivityStatus：draft→published→closed 合法流转")
    void changeActivityStatusHappyPath() {
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(
                activity(ACTIVITY_ID, CATEGORY_ID, "draft", "8"),
                activity(ACTIVITY_ID, CATEGORY_ID, "published", "8"));
        when(activityMapper.updateStatus(ACTIVITY_ID, "draft", "published")).thenReturn(1);

        Map<String, Object> published = dataOf(service.changeActivityStatus(ADMIN, ROLE_ADMIN, ACTIVITY_ID,
                body("action", "publish")));
        assertEquals("published", published.get("status"));
        assertEquals("已发布", published.get("statusText"));

        // 结束：published → closed（action 也可用 status 键传封闭值）
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(
                activity(ACTIVITY_ID, CATEGORY_ID, "published", "8"),
                activity(ACTIVITY_ID, CATEGORY_ID, "closed", "8"));
        when(activityMapper.updateStatus(ACTIVITY_ID, "published", "closed")).thenReturn(1);
        Map<String, Object> closed = dataOf(service.changeActivityStatus(ADMIN, ROLE_ADMIN, ACTIVITY_ID,
                body("status", "closed")));
        assertEquals("closed", closed.get("status"));
        assertEquals("已结束", closed.get("statusText"));
    }

    @Test
    @DisplayName("5.15 changeActivityStatus：非法 action / 状态不符 / CAS 0 行 / 不存在")
    void changeActivityStatusRejectsIllegalMoves() {
        when(activityMapper.selectById(ACTIVITY_ID))
                .thenReturn(activity(ACTIVITY_ID, CATEGORY_ID, "draft", "8"));
        assertError(service.changeActivityStatus(ADMIN, ROLE_ADMIN, ACTIVITY_ID, body("action", "reopen")),
                400, "action 只能是 publish（发布）或 close（结束）");

        // draft 不能 close，published 不能再 publish
        assertError(service.changeActivityStatus(ADMIN, ROLE_ADMIN, ACTIVITY_ID, body("action", "close")),
                400, "活动当前状态是「草稿」，不能执行该操作");

        when(activityMapper.selectById(ACTIVITY_ID))
                .thenReturn(activity(ACTIVITY_ID, CATEGORY_ID, "published", "8"));
        assertError(service.changeActivityStatus(ADMIN, ROLE_ADMIN, ACTIVITY_ID, body("action", "publish")),
                400, "活动当前状态是「已发布」，不能执行该操作");

        when(activityMapper.updateStatus(ACTIVITY_ID, "published", "closed")).thenReturn(0);
        assertError(service.changeActivityStatus(ADMIN, ROLE_ADMIN, ACTIVITY_ID, body("action", "close")),
                400, CAS_FAIL);

        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(null);
        assertError(service.changeActivityStatus(ADMIN, ROLE_ADMIN, ACTIVITY_ID, body("action", "publish")),
                404, "活动不存在");
        verify(activityMapper, never()).updateStatus(eq(ACTIVITY_ID), eq("draft"), eq("closed"));
    }

    @Test
    @DisplayName("5.16 listActivities：只回 published，分页参数兜底并带上诚实口径说明")
    void listActivitiesPaging() {
        when(activityMapper.listPublished(CATEGORY_ID, "志愿", 0, 200))
                .thenReturn(List.of(activity(ACTIVITY_ID, CATEGORY_ID, "published", "8")));
        when(activityMapper.countPublished(CATEGORY_ID, "志愿")).thenReturn(1L);

        Map<String, Object> data = dataOf(service.listActivities(WANGXM, CATEGORY_ID, "  志愿  ", 0, 500));
        List<Map<String, Object>> list = rowsOf(data, "list");
        assertEquals(1, list.size());
        assertEquals("published", list.get(0).get("status"));
        assertEquals(1, ((Number) data.get("total")).intValue());
        assertEquals(1, ((Number) data.get("page")).intValue());
        assertEquals(200, ((Number) data.get("size")).intValue(), "size 上限 200");
        assertNotNull(data.get("policyNote"));

        verify(activityMapper).listPublished(CATEGORY_ID, "志愿", 0, 200);
    }

    @Test
    @DisplayName("5.17 adminListActivities：含草稿/已结束的状态过滤，不带 policyNote")
    void adminListActivitiesPaging() {
        when(activityMapper.listForAdmin("draft", CATEGORY_ID, null, 0, 20))
                .thenReturn(List.of(activity(ACTIVITY_ID, CATEGORY_ID, "draft", "8")));
        when(activityMapper.countForAdmin("draft", CATEGORY_ID, null)).thenReturn(2L);

        Map<String, Object> data = dataOf(service.adminListActivities(COUNSELOR, ROLE_TEACHER,
                "draft", CATEGORY_ID, null, null, null));
        assertEquals(1, rowsOf(data, "list").size());
        assertEquals(2, ((Number) data.get("total")).intValue());
        assertFalse(data.containsKey("policyNote"), "台账不重复口径说明");
    }

    @Test
    @DisplayName("5.18 reviewList / reviewStats：scope 为 all 或 counselor，统计 6 个状态")
    void reviewListAndStatsExposeScope() {
        when(recordMapper.countForStaff(any(), anyString(), any(), any())).thenReturn(2L);

        Map<String, Object> adminList = dataOf(service.reviewList(ADMIN, ROLE_ADMIN, null, null, null, null, null));
        assertEquals("all", adminList.get("scope"));

        when(studentProfileMapper.countByCounselor(COUNSELOR)).thenReturn(2);
        Map<String, Object> counselorList = dataOf(service.reviewList(COUNSELOR, ROLE_TEACHER,
                null, null, null, null, null));
        assertEquals("counselor", counselorList.get("scope"));
        verify(recordMapper).selectForStaff(eq(COUNSELOR), isNull(), isNull(), isNull(), eq(0), eq(20));

        Map<String, Object> stats = dataOf(service.reviewStats(COUNSELOR, ROLE_TEACHER));
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) stats.get("counts");
        assertEquals(6, counts.size());
        for (String status : List.of("submitted", "counselor_approved", "confirmed",
                "returned", "rejected", "withdrawn")) {
            assertTrue(counts.containsKey(status), "统计缺少状态 " + status);
            assertEquals(2L, ((Number) counts.get(status)).longValue());
        }
        assertEquals("counselor", stats.get("scope"));
    }

    // =========================================================================
    // 6. 导出（service 层 CsvExport + controller ResponseEntity）
    // =========================================================================

    private static SecondClassRecord exportRow() {
        SecondClassRecord record = record(RECORD_ID, ACTIVITY_ID, WANGXM, CATEGORY_ID, "confirmed");
        record.setHours(new BigDecimal("8.00"));
        record.setCredit(new BigDecimal("2.00"));
        record.setCounselorName("李老师");
        record.setCounselorAt(LocalDateTime.of(2026, 3, 2, 10, 0));
        record.setReviewerName("校团委");
        record.setReviewedAt(LocalDateTime.of(2026, 3, 3, 9, 0));
        record.setSubmittedAt(LocalDateTime.of(2026, 3, 1, 8, 0));
        return record;
    }

    @Test
    @DisplayName("6.1 exportCsv：admin 全量导出，学号/姓名脱敏，表头口径与文件名正确")
    void exportCsvForAdmin() {
        when(recordMapper.selectForExport(isNull(), isNull(), eq(2026))).thenReturn(List.of(exportRow()));

        CsvExport out = service.exportCsv(ADMIN, ROLE_ADMIN, 2026, null);
        assertNull(out.errorCode);
        assertNull(out.errorMessage);
        assertEquals(CSV_HEADER
                        + "4001,王*,计算机2401,志愿公益,迎新志愿,8,2,已认定,李老师,2026-03-02 10:00:00,"
                        + "校团委,2026-03-03 09:00:00,2026-03-01 08:00:00\n",
                out.csv, "学号脱敏成后 4 位、姓名只留首字 + *；学时/学分去尾零，时间 yyyy-MM-dd HH:mm:ss，状态中文");
        assertTrue(out.filename.matches("second-class-\\d{8}\\.csv"), out.filename);
        assertTrue(out.filename.startsWith("second-class-"), out.filename);
    }

    @Test
    @DisplayName("6.2 exportCsv：辅导员按名下学生范围导出，admin 不受限")
    void exportCsvRespectsScope() {
        when(studentProfileMapper.countByCounselor(COUNSELOR)).thenReturn(2);
        when(recordMapper.selectForExport(eq(COUNSELOR), isNull(), eq(2026))).thenReturn(List.of(exportRow()));
        service.exportCsv(COUNSELOR, ROLE_TEACHER, 2026, null);
        verify(recordMapper).selectForExport(COUNSELOR, null, 2026);

        when(recordMapper.selectForExport(isNull(), isNull(), eq(2026))).thenReturn(List.of(exportRow()));
        service.exportCsv(ADMIN, ROLE_ADMIN, 2026, null);
        verify(recordMapper).selectForExport(null, null, 2026);
    }

    @Test
    @DisplayName("6.3 exportCsv：学生被拒 403（与 Volunteer/Club 一致的拒学生口径）")
    void exportCsvRejectsStudent() {
        CsvExport out = service.exportCsv(WANGXM, ROLE_STUDENT, null, null);
        assertEquals(403, out.errorCode.intValue());
        assertTrue(out.errorMessage.contains("无权导出申报"), out.errorMessage);
        assertNull(out.csv);
        assertNull(out.filename);
        verify(recordMapper, never()).selectForExport(any(), any(), any());
    }

    @Test
    @DisplayName("6.4 exportCsv：未登录导出 → 403（与 Club/Volunteer 及 controller javadoc 一致）")
    void exportCsvAnonymousRejectedWith403() {
        CsvExport out = service.exportCsv(null, null, null, null);
        // 文件流的拒绝一律 403 + 空 body（P18-C2 已修，原文案保留「请先登录」便于前端提示）
        assertEquals(403, out.errorCode.intValue());
        assertTrue(out.errorMessage.contains("请先登录"), out.errorMessage);
        assertNull(out.csv);
        verify(recordMapper, never()).selectForExport(any(), any(), any());
    }

    @Test
    @DisplayName("6.5 exportCsv：超过 100000 条上限报 400，不产出 CSV")
    void exportCsvRejectsTooManyRows() {
        SecondClassRecord row = exportRow();
        when(recordMapper.selectForExport(any(), any(), any()))
                .thenReturn(Collections.nCopies(CommunityService.MAX_EXPORT + 1, row));

        CsvExport out = service.exportCsv(ADMIN, ROLE_ADMIN, null, "confirmed");
        assertEquals(400, out.errorCode.intValue());
        assertTrue(out.errorMessage.contains("导出条数超过 100000 条上限"), out.errorMessage);
        assertNull(out.csv);
    }

    @Test
    @DisplayName("6.6 controller /export：200 + UTF-8 BOM + text/csv;charset=UTF-8 + attachment 文件名")
    void controllerExportProducesCsvDownload() {
        when(recordMapper.selectForExport(isNull(), isNull(), isNull())).thenReturn(List.of(exportRow()));

        ResponseEntity<byte[]> response = controller.export(session(ADMIN, ROLE_ADMIN), null, null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        byte[] bytes = response.getBody();
        assertNotNull(bytes);
        assertEquals((byte) 0xEF, bytes[0], "缺少 UTF-8 BOM 首位");
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);
        assertEquals(CSV_HEADER, new String(bytes, 3, CSV_HEADER.getBytes(StandardCharsets.UTF_8).length,
                StandardCharsets.UTF_8));

        assertEquals(MediaType.parseMediaType("text/csv;charset=UTF-8"), response.getHeaders().getContentType());
        String disposition = response.getHeaders().getFirst("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(disposition.startsWith("attachment; filename=\"second-class-"), disposition);
        assertEquals(bytes.length, response.getHeaders().getContentLength());
    }

    @Test
    @DisplayName("6.7 controller /export：学生 → 403 且空 body（service 的 403 透传成 HTTP 状态码）")
    void controllerExportRejectsStudentWith403() {
        ResponseEntity<byte[]> response = controller.export(session(WANGXM, ROLE_STUDENT), 2026, "confirmed");
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNull(response.getBody(), "拒绝时不返回任何正文");
    }

    @Test
    @DisplayName("6.8 controller /export：未登录 → 403 且空 body")
    void controllerExportAnonymousIs403() {
        ResponseEntity<byte[]> response = controller.export(session(null, null), null, null);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNull(response.getBody());
    }

    // =========================================================================
    // 7. 审计与工具口径
    // =========================================================================

    @Test
    @DisplayName("7.1 审计：写接口成功落一条 SECOND_CLASS 审计（含 actor / target / action）")
    void writesAuditLog() {
        when(categoryMapper.selectByCode("SJ")).thenReturn(null);
        when(categoryMapper.selectById(any()))
                .thenReturn(category(9L, "SJ", "实践实习", 1, null, null, null));
        assertOk(service.createCategory(ADMIN, ROLE_ADMIN, body("name", "实践实习", "code", "SJ")));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, atLeastOnce()).record(captor.capture());
        AuditLog entry = captor.getValue();
        assertEquals(AuditLogService.TYPE_SECOND_CLASS, entry.getEventType());
        assertEquals("CATEGORY_CREATE", entry.getEventAction());
        assertEquals(ADMIN, entry.getUserId().longValue());
        assertEquals(Boolean.TRUE, entry.getSuccess());
        assertNotNull(entry.getDetail());
    }

    @Test
    @DisplayName("7.2 审计：审计写库异常不影响业务返回（只记日志）")
    void auditFailureDoesNotBreakBusiness() {
        doThrow(new RuntimeException("audit down")).when(auditLogService).record(any(AuditLog.class));
        when(categoryMapper.selectByCode("SJ")).thenReturn(null);
        when(categoryMapper.selectById(any()))
                .thenReturn(category(9L, "SJ", "实践实习", 1, null, null, null));

        assertOk(service.createCategory(ADMIN, ROLE_ADMIN, body("name", "实践实习", "code", "SJ")));
    }

    @Test
    @DisplayName("7.3 工具口径：状态/层级中文映射与学时去尾零")
    void statusAndLevelText() {
        assertEquals("待初审", SecondClassService.recordStatusText("submitted"));
        assertEquals("待认定", SecondClassService.recordStatusText("counselor_approved"));
        assertEquals("已认定", SecondClassService.recordStatusText("confirmed"));
        assertEquals("已退回", SecondClassService.recordStatusText("returned"));
        assertEquals("已否定", SecondClassService.recordStatusText("rejected"));
        assertEquals("已撤回", SecondClassService.recordStatusText("withdrawn"));
        assertNull(SecondClassService.recordStatusText(null));

        assertEquals("草稿", SecondClassService.activityStatusText("draft"));
        assertEquals("已发布", SecondClassService.activityStatusText("published"));
        assertEquals("已结束", SecondClassService.activityStatusText("closed"));

        assertEquals("校级", SecondClassService.levelText("school"));
        assertEquals("院级", SecondClassService.levelText("college"));
        assertEquals("班级", SecondClassService.levelText("class"));
        assertEquals("其他", SecondClassService.levelText("other"));

        assertEquals("2", SecondClassService.plain(new BigDecimal("2.00")));
        assertEquals("12.5", SecondClassService.plain(new BigDecimal("12.50")));
        assertEquals("", SecondClassService.plain(null));
    }
}
