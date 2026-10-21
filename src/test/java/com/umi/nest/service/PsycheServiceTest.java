package com.umi.nest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.umi.nest.common.Result;
import com.umi.nest.entity.PsycheAnswer;
import com.umi.nest.entity.PsycheAppointment;
import com.umi.nest.entity.PsycheBatch;
import com.umi.nest.entity.PsycheFocus;
import com.umi.nest.entity.PsycheFollowup;
import com.umi.nest.entity.PsycheInstrument;
import com.umi.nest.entity.PsycheReferral;
import com.umi.nest.entity.PsycheTask;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.entity.User;
import com.umi.nest.mapper.PsycheAnswerMapper;
import com.umi.nest.mapper.PsycheAppointmentMapper;
import com.umi.nest.mapper.PsycheBatchMapper;
import com.umi.nest.mapper.PsycheFocusMapper;
import com.umi.nest.mapper.PsycheFollowupMapper;
import com.umi.nest.mapper.PsycheInstrumentMapper;
import com.umi.nest.mapper.PsycheReferralMapper;
import com.umi.nest.mapper.PsycheTaskMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import com.umi.nest.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P14 心理健康（§9）—— {@link PsycheService} 与 {@link PsycheAppointmentService} 的服务层单测。
 *
 * <p>口径出处：《学工平台功能对齐说明》§9（P14 spec，S7 / S8 / S16 / S17 / S18），其中
 * §9.2「实现口径」为验收依据；本类逐条把口径写成断言。</p>
 *
 * <p>风格与 AidReviewServiceTest / EvalServiceTest / CounselorServiceTest 一致：纯 Mockito
 * （@ExtendWith(MockitoExtension.class) + @MockitoSettings(LENIENT) + @InjectMocks），不起 Spring 容器、
 * 不连库、不写任何数据行，因此不需要唯一前缀与清理，可反复跑且绝不动 nest_psyche_work_demo.sql 的演示数据。
 * 唯一例外是 ObjectMapper 必须用 @Spy（真实实现），否则服务里的 JSON 题本/分档解析全为 null。</p>
 *
 * <p>账号口径取自 sql\nest_seed.sql:39-43 与 sql\nest_psyche_work_demo.sql:19-20（真实库事实）：
 * admin=1、zhangsan=2、lisi=3；学生 wangxm=4 / zhaoxh=5 / chendw=6 的 student_profile.counselor_id=3=lisi，
 * liusq=7 / zhoujie=8 的 counselor_id=2=zhangsan。真实姓名与学号：王小明/2024001、赵小红/2024002、
 * 陈大伟/2024003、刘思琪/2024004、周杰/2024005。</p>
 *
 * <p>**按用户口径「学生侧查询返回的对象里不能有 result_level 原文与中心内部备注」，
 * 有两个用例被刻意写成预期失败**（见 77 / 78 号）：PsycheService.taskResult 的 resultView 原样回传
 * resultLevel / resultNote，taskDetail 回传整个 PsycheTask 实体，只有 my() 做了置空。
 * PsycheController.java:30 与 :152 的注释都声称「学生端不下发」，代码与该自述契约矛盾——
 * 这是报告给用户的后端缺陷，而不是把断言放宽。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PsycheService / PsycheAppointmentService - P14 心理健康（普查 · 关注台账 · 预约 · 转介）口径")
class PsycheServiceTest {

    // ---------------------------------------------------------------- 真实账号（sql\nest_seed.sql:39-43）

    /** 管理员。 */
    private static final long ADMIN = 1L;

    /** 辅导员 zhangsan：名下 liusq(7) / zhoujie(8)。 */
    private static final long ZHANGSAN = 2L;

    /** 辅导员 lisi：名下 wangxm(4) / zhaoxh(5) / chendw(6)。 */
    private static final long LISI = 3L;

    private static final long WANGXM = 4L;

    private static final long ZHAOXH = 5L;

    private static final long CHENDW = 6L;

    private static final long LIUSQ = 7L;

    // ---------------------------------------------------------------- 固定 id 与文案

    private static final long BATCH = 601L;

    private static final long INSTR = 501L;

    private static final long TASK_A = 701L;

    private static final String ADVANCE_MSG = "请至少提前一个工作日预约（如有紧急情况请直接电话联系 0596-6288525）";

    private static final String WINDOW_MSG = "心理咨询预约时间为周一至周五 8:00-17:00";

    private static final String CAS_FAIL = "状态已变化，请刷新后重试";

    private static final String OUT_OF_WINDOW = "当前不在普查时间内";

    private static final String NOT_ALL_ANSWERED = "请完成全部题目后再提交";

    @Mock
    private PsycheInstrumentMapper instrumentMapper;

    @Mock
    private PsycheBatchMapper batchMapper;

    @Mock
    private PsycheTaskMapper taskMapper;

    @Mock
    private PsycheAnswerMapper answerMapper;

    @Mock
    private PsycheFocusMapper focusMapper;

    @Mock
    private PsycheFollowupMapper followupMapper;

    @Mock
    private PsycheReferralMapper referralMapper;

    @Mock
    private PsycheAppointmentMapper appointmentMapper;

    @Mock
    private StudentProfileMapper profileMapper;

    @Mock
    private UserMapper userMapper;

    /** 必须是真的 ObjectMapper（spy），否则题本/分档 JSON 解析全空。 */
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PsycheService service;

    @InjectMocks
    private PsycheAppointmentService appointmentService;

    // ================================================================
    // 工具
    // ================================================================

    private static boolean ok(Result<?> r) {
        return r != null && Integer.valueOf(200).equals(r.getCode());
    }

    private static void assertOk(Result<?> r) {
        assertNotNull(r, "结果不应为 null");
        assertEquals(200, r.getCode(), "期望 200，实际 code=" + r.getCode() + " message=" + r.getMessage());
    }

    private static void assertCode(Result<?> r, int code) {
        assertNotNull(r, "结果不应为 null");
        assertEquals(code, r.getCode(), "期望 code=" + code + "，实际 code=" + r.getCode() + " message=" + r.getMessage());
    }

    private static void assertMsg(Result<?> r, String expected) {
        assertNotNull(r);
        assertEquals(expected, r.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataMap(Result<?> r) {
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private static List<PsycheTask> taskList(Map<String, Object> data) {
        return (List<PsycheTask>) data.get("list");
    }

    @SuppressWarnings("unchecked")
    private static List<PsycheFocus> focusList(Map<String, Object> data) {
        return (List<PsycheFocus>) data.get("list");
    }

    @SuppressWarnings("unchecked")
    private static List<PsycheReferral> referralList(Map<String, Object> data) {
        return (List<PsycheReferral>) data.get("list");
    }

    @SuppressWarnings("unchecked")
    private static List<PsycheAppointment> appointmentList(Map<String, Object> data) {
        return (List<PsycheAppointment>) data.get("list");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return (Map<String, Object>) value;
    }

    private static int intOf(Object value) {
        assertNotNull(value, "期望有值");
        return ((Number) value).intValue();
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** 递归断言：聚合统计里不能出现可反查个体的字段名或实体行（§9 口径 8）。 */
    private static void assertNoIndividualRows(Object node, String path) {
        if (node instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) node).entrySet()) {
                String key = String.valueOf(e.getKey());
                assertFalse("studentId".equals(key) || "studentName".equals(key) || "studentNo".equals(key)
                                || "list".equals(key) || "tasks".equals(key) || "students".equals(key),
                        "聚合统计 " + path + " 出现个体级字段：" + key);
                assertNoIndividualRows(e.getValue(), path + "." + key);
            }
            return;
        }
        if (node instanceof List<?>) {
            int i = 0;
            for (Object one : (List<?>) node) {
                assertNoIndividualRows(one, path + "[" + i + "]");
                i++;
            }
            return;
        }
        assertFalse(node instanceof PsycheTask || node instanceof PsycheFocus || node instanceof PsycheReferral
                        || node instanceof PsycheAppointment,
                "聚合统计 " + path + " 出现个体级实体行：" + node);
    }

    /** 下个指定的星期几（不含今天）。 */
    private static LocalDate nextDayOfWeek(LocalDate from, DayOfWeek target) {
        LocalDate d = from.plusDays(1);
        while (d.getDayOfWeek() != target) {
            d = d.plusDays(1);
        }
        return d;
    }

    /** 第 workdaysAhead 个工作日（严格在工作日意义上加，跳过周六周日）的 hour:minute。 */
    private static LocalDateTime workdayAt(int workdaysAhead, int hour, int minute) {
        LocalDateTime base = PsycheAppointmentService.addWorkingDays(LocalDateTime.now().withNano(0), workdaysAhead);
        return base.toLocalDate().atTime(hour, minute);
    }

    private static boolean inServiceWindow(LocalDateTime t) {
        if (t == null) {
            return false;
        }
        DayOfWeek d = t.getDayOfWeek();
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime at = t.toLocalTime();
        return !at.isBefore(LocalTime.of(8, 0)) && !at.isAfter(LocalTime.of(17, 0));
    }

    // ================================================================
    // 实体构造
    // ================================================================

    private static PsycheInstrument instrument(long id, String code, String name, String itemJson,
                                               String bandJson, Integer itemCount) {
        PsycheInstrument it = new PsycheInstrument();
        it.setId(id);
        it.setCode(code);
        it.setName(name);
        it.setItemJson(itemJson);
        it.setBandJson(bandJson);
        it.setItemCount(itemCount);
        it.setGuideText("请按最近两周的实际情况作答");
        it.setActive(1);
        return it;
    }

    private static PsycheBatch batch(long id, String status) {
        PsycheBatch b = new PsycheBatch();
        b.setId(id);
        b.setBatchName("2024级新生心理普查");
        b.setGrade("2024级");
        b.setAcademicYear("2024-2025");
        b.setStatus(status);
        b.setInterviewRequired(0);
        b.setCreatedBy(ADMIN);
        return b;
    }

    /** 开放中的批次：窗口 = 现在前后各 1 小时（保证 inWindow 为真）。 */
    private static PsycheBatch openBatch(long id, Long instrumentId) {
        PsycheBatch b = batch(id, "open");
        b.setInstrumentId(instrumentId);
        b.setOpenAt(LocalDateTime.now().withNano(0).minusHours(1));
        b.setCloseAt(LocalDateTime.now().withNano(0).plusHours(1));
        return b;
    }

    private static PsycheTask task(long id, long batchId, long studentId, String status) {
        PsycheTask t = new PsycheTask();
        t.setId(id);
        t.setBatchId(batchId);
        t.setStudentId(studentId);
        t.setStatus(status);
        t.setAnswerCount(0);
        t.setInterviewed(0);
        return t;
    }

    private static PsycheFocus focus(long id, long studentId, String status) {
        PsycheFocus f = new PsycheFocus();
        f.setId(id);
        f.setStudentId(studentId);
        f.setLevel("general");
        f.setSource("task");
        f.setStatus(status);
        f.setCounselorId(LISI);
        return f;
    }

    private static PsycheReferral referral(long id, long studentId, String status) {
        PsycheReferral r = new PsycheReferral();
        r.setId(id);
        r.setStudentId(studentId);
        r.setSourceType("focus");
        r.setTarget("campus");
        r.setStatus(status);
        r.setReferredBy(LISI);
        return r;
    }

    private static PsycheAppointment appointment(long id, long studentId, Long counselorId, String mode,
                                                 String status, LocalDateTime expectStart) {
        PsycheAppointment a = new PsycheAppointment();
        a.setId(id);
        a.setStudentId(studentId);
        a.setCounselorId(counselorId);
        a.setMode(mode);
        a.setStatus(status);
        a.setExpectStart(expectStart);
        a.setDurationMinutes(50);
        a.setMainIssue("最近睡不好，想聊聊");
        a.setContactPhone("13800000004");
        return a;
    }

    private static StudentProfile profile(long userId, Long counselorId) {
        StudentProfile p = new StudentProfile();
        p.setUserId(userId);
        p.setCounselorId(counselorId);
        p.setStudentNo("2024001");
        p.setClassName("软工2401");
        p.setMajor("软件工程");
        p.setGrade("2024级");
        return p;
    }

    private static User student(long id, String studentId, String username) {
        User u = new User();
        u.setId(id);
        u.setStudentId(studentId);
        u.setUsername(username);
        u.setRealName("王小明");
        u.setRole("student");
        u.setDeleted(0);
        return u;
    }

    /** 带答案的提交 body：answers = [{no:1,value:3}, …]。 */
    private static Map<String, Object> answersBody(Object... noValuePairs) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i + 1 < noValuePairs.length; i += 2) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("no", noValuePairs[i]);
            one.put("value", noValuePairs[i + 1]);
            list.add(one);
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("answers", list);
        return b;
    }

    private static final String TWO_ITEM_JSON = "[{\"no\":1,\"text\":\"睡得好吗\"},{\"no\":2,\"text\":\"心情如何\"}]";

    private static final String THREE_BAND_JSON =
            "[{\"band\":\"正常\",\"max\":10},{\"band\":\"关注\",\"max\":20},{\"band\":\"预警\",\"max\":999999}]";

    // ---------------------------------------------------------------- PART2 常量与工具

    /** 预约侧文案（PsycheAppointmentService 的 PENDING_MSG / HALF_DAY_MSG）。 */
    private static final String PENDING_MSG = "你已有待处理的预约";

    private static final String HALF_DAY_MSG = "线上咨询需提前半天确认（S17）";

    /** 与 PsycheAppointmentService.STAMP 同格式（服务内为 private，测试侧自备）。 */
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 聚合统计行：状态 / 等级计数。 */
    private static Map<String, Object> countRow(String key, String value, int cnt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        m.put("cnt", cnt);
        return m;
    }

    /** 聚合统计行：按班级 / 专业的完成数。 */
    private static Map<String, Object> groupCountRow(String key, String value, int cnt, int done) {
        Map<String, Object> m = countRow(key, value, cnt);
        m.put("done", done);
        return m;
    }

    /** 10 天前（往前找到的最近工作日）10:00：在服务时段内，但远早于「提前一个工作日」门槛。 */
    private static LocalDateTime pastWindowTime() {
        LocalDate d = LocalDate.now().minusDays(10);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d.atTime(10, 0);
    }

    /** 未来 7~13 天里的那个周六 10:00（必然晚于「提前一个工作日」门槛，用于测服务时段）。 */
    private static LocalDateTime nextSaturdayWindow() {
        LocalDate d = LocalDate.now().plusDays(7);
        while (d.getDayOfWeek() != DayOfWeek.SATURDAY) {
            d = d.plusDays(1);
        }
        return d.atTime(10, 0);
    }

    // ================================================================
    // 一、脱敏与权限（S16 保密性原则 / §9 口径 6）
    // ================================================================

    @Test
    @DisplayName("01 listTasks：列表脱敏，姓名保留姓、学号只留后四位")
    void listTasksMasksNameAndNo() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "open"));
        PsycheTask row = task(TASK_A, BATCH, WANGXM, "pending");
        row.setStudentName("王小明");
        row.setStudentNo("2024001");
        when(taskMapper.list(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(row)));
        when(taskMapper.count(any(), any(), any(), any())).thenReturn(1);

        Result<?> r = service.listTasks(LISI, "teacher", BATCH, null, null, null, 1, 20);

        assertOk(r);
        Map<String, Object> data = dataMap(r);
        List<PsycheTask> list = taskList(data);
        assertEquals(1, list.size());
        assertAll(
                () -> assertEquals("王*", list.get(0).getStudentName(), "姓名只留姓"),
                () -> assertEquals("4001", list.get(0).getStudentNo(), "学号只留后四位"),
                () -> assertEquals(1, intOf(data.get("total"))),
                () -> assertEquals(1, intOf(data.get("page"))),
                () -> assertEquals(20, intOf(data.get("size"))));
    }

    @Test
    @DisplayName("02 listTasks：单字姓名原样、空姓名变空串、短学号不截断")
    void listTasksMaskEdgeCases() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "open"));
        PsycheTask one = task(1L, BATCH, ZHAOXH, "pending");
        one.setStudentName("李");
        one.setStudentNo("123");
        PsycheTask two = task(2L, BATCH, CHENDW, "pending");
        two.setStudentName(null);
        two.setStudentNo(null);
        PsycheTask three = task(3L, BATCH, LIUSQ, "pending");
        three.setStudentName("   ");
        three.setStudentNo("2024004");
        when(taskMapper.list(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(one, two, three)));

        Result<?> r = service.listTasks(ADMIN, "admin", BATCH, null, null, null, 1, 20);

        List<PsycheTask> list = taskList(dataMap(r));
        assertAll(
                () -> assertEquals("李", list.get(0).getStudentName()),
                () -> assertEquals("123", list.get(0).getStudentNo()),
                () -> assertEquals("", list.get(1).getStudentName()),
                () -> assertEquals("", list.get(1).getStudentNo()),
                () -> assertEquals("", list.get(2).getStudentName()),
                () -> assertEquals("4004", list.get(2).getStudentNo()));
    }

    @Test
    @DisplayName("03 listTasks：未登录 401「请先登录」，且一次库都不查")
    void listTasksNotLoggedIn() {
        Result<?> r = service.listTasks(null, "teacher", BATCH, null, null, null, 1, 20);
        assertCode(r, 401);
        assertMsg(r, "请先登录");
        verifyNoInteractions(taskMapper, batchMapper);
    }

    @Test
    @DisplayName("04 listTasks：学生角色 403「无权访问」，且一次库都不查")
    void listTasksStudentForbidden() {
        Result<?> r = service.listTasks(WANGXM, "student", BATCH, null, null, null, 1, 20);
        assertCode(r, 403);
        assertMsg(r, "无权访问");
        verifyNoInteractions(taskMapper, batchMapper);
    }

    @Test
    @DisplayName("05 listTasks：batchId 为空 400「请指定批次」")
    void listTasksBatchIdRequired() {
        Result<?> r = service.listTasks(LISI, "teacher", null, null, null, null, 1, 20);
        assertCode(r, 400);
        assertMsg(r, "请指定批次");
    }

    @Test
    @DisplayName("06 listTasks：批次不存在 404「批次不存在」")
    void listTasksBatchNotFound() {
        when(batchMapper.findById(999L)).thenReturn(null);
        Result<?> r = service.listTasks(LISI, "teacher", 999L, null, null, null, 1, 20);
        assertCode(r, 404);
        assertMsg(r, "批次不存在");
    }

    @Test
    @DisplayName("07 listTasks：分页钳制——page<1 取 1，size<1 取 20，size>200 取 200")
    void listTasksPageClamp() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "open"));
        when(taskMapper.list(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
        when(taskMapper.count(any(), any(), any(), any())).thenReturn(0);

        service.listTasks(ADMIN, "admin", BATCH, null, null, null, 0, 0);
        verify(taskMapper).list(any(), any(), any(), any(), eq(0), eq(20));

        service.listTasks(ADMIN, "admin", BATCH, null, null, null, -5, 9999);
        verify(taskMapper).list(any(), any(), any(), any(), eq(0), eq(200));

        Map<String, Object> data = dataMap(service.listTasks(ADMIN, "admin", BATCH, null, null, null, 3, 50));
        assertAll(
                () -> assertEquals(3, intOf(data.get("page"))),
                () -> assertEquals(50, intOf(data.get("size"))));
    }

    @Test
    @DisplayName("08 listTasks：data 里带 stats 聚合块（只聚合、无个体）")
    void listTasksCarriesAggregateStats() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "open"));
        when(taskMapper.list(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
        when(taskMapper.countGroupByStatus(BATCH)).thenReturn(List.of(
                Map.of("status", "completed", "cnt", 1),
                Map.of("status", "pending", "cnt", 3)));

        Result<?> r = service.listTasks(LISI, "teacher", BATCH, null, null, null, 1, 20);

        Map<String, Object> stats = mapOf(dataMap(r).get("stats"));
        assertAll(
                () -> assertEquals(4, intOf(stats.get("total"))),
                () -> assertEquals(1, intOf(stats.get("completed"))),
                () -> assertEquals(3, intOf(stats.get("pending"))),
                () -> assertEquals(0, intOf(stats.get("in_progress"))),
                () -> assertEquals(0, intOf(stats.get("absent"))),
                () -> assertEquals(0, new BigDecimal("0.2500").compareTo((BigDecimal) stats.get("completionRate"))),
                () -> assertEquals(0, new BigDecimal("25.00").compareTo((BigDecimal) stats.get("completionPercent"))));
        assertNoIndividualRows(dataMap(r).get("stats"), "stats");
    }

    @Test
    @DisplayName("09 export：未登录 → errorCode 401（与 P13 导出口径一致）")
    void exportNotLoggedIn() {
        PsycheService.CsvExport out = service.export(null, "teacher", "tasks", null);
        assertEquals(401, out.errorCode.intValue());
        verifyNoInteractions(taskMapper);
    }

    @Test
    @DisplayName("10 export：学生角色 → errorCode 403")
    void exportStudentForbidden() {
        PsycheService.CsvExport out = service.export(WANGXM, "student", "tasks", null);
        assertEquals(403, out.errorCode.intValue());
    }

    @Test
    @DisplayName("11 export：type 为空 → 400「请指定导出类型」")
    void exportTypeRequired() {
        PsycheService.CsvExport out = service.export(ADMIN, "admin", null, null);
        assertEquals(400, out.errorCode.intValue());
        assertEquals("请指定导出类型", out.errorMessage);
    }

    @Test
    @DisplayName("12 export：type 非法 → 400「导出类型只支持 tasks / focus / appointments」")
    void exportTypeInvalid() {
        PsycheService.CsvExport out = service.export(ADMIN, "admin", "students", null);
        assertEquals(400, out.errorCode.intValue());
        assertEquals("导出类型只支持 tasks / focus / appointments", out.errorMessage);
    }

    @Test
    @DisplayName("13 export=traks：表头齐全、姓名与学号脱敏、不含全名与全学号")
    void exportTasksMasked() {
        PsycheTask row = task(TASK_A, BATCH, WANGXM, "completed");
        row.setStudentName("王小明");
        row.setStudentNo("2024001");
        row.setClassName("软工2401");
        row.setMajor("软件工程");
        row.setBatchName("2024级新生心理普查");
        row.setResultLevel("attention");
        row.setInterviewed(1);
        when(taskMapper.list(isNull(), isNull(), isNull(), isNull(), eq(0), eq(100000)))
                .thenReturn(new ArrayList<>(List.of(row)));

        PsycheService.CsvExport out = service.export(LISI, "teacher", "tasks", null);

        assertNull(out.errorCode);
        assertEquals("psyche-tasks.csv", out.filename);
        assertTrue(out.csv.startsWith("姓名,学号后四位,班级,专业,批次,状态,结果等级,是否已访谈\r\n"),
                "实际表头=" + out.csv.split("\r\n")[0]);
        assertAll(
                () -> assertTrue(out.csv.contains("王*"), "姓名应打码"),
                () -> assertFalse(out.csv.contains("王小明"), "不得出现全名"),
                () -> assertTrue(out.csv.contains("4001")),
                () -> assertFalse(out.csv.contains("2024001"), "不得出现完整学号"),
                () -> assertTrue(out.csv.contains("attention"), "老师端导出带结果等级列"),
                () -> assertTrue(out.csv.contains("是"), "已访谈 → 是"));
    }

    @Test
    @DisplayName("14 export=focus：表头含等级/来源，含关注缘由，不含已采取措施全文")
    void exportFocusMasked() {
        PsycheFocus row = focus(1L, WANGXM, "open");
        row.setStudentName("王小明");
        row.setStudentNo("2024001");
        row.setClassName("软工2401");
        row.setLevel("risk");
        row.setSource("task");
        row.setSummary("关注缘由全文");
        row.setMeasures("已采取措施全文");
        row.setCounselorName("李四");
        row.setOpenedAt(LocalDateTime.of(2026, 3, 1, 9, 0, 0));
        when(focusMapper.list(isNull(), isNull(), isNull(), isNull(), eq(LISI), eq(0), eq(100000)))
                .thenReturn(new ArrayList<>(List.of(row)));

        PsycheService.CsvExport out = service.export(LISI, "teacher", "focus", null);

        assertEquals("psyche-focus.csv", out.filename);
        assertTrue(out.csv.startsWith("姓名,学号后四位,班级,等级,来源,关注缘由,状态,跟进辅导员,登记时间\r\n"),
                "实际表头=" + out.csv.split("\r\n")[0]);
        assertAll(
                () -> assertTrue(out.csv.contains("王*")),
                () -> assertTrue(out.csv.contains("risk")),
                () -> assertTrue(out.csv.contains("关注缘由全文")),
                () -> assertFalse(out.csv.contains("已采取措施全文"), "focus 导出只含 summary"),
                () -> assertTrue(out.csv.contains("2026-03-01 09:00:00")));
    }

    @Test
    @DisplayName("15 export=focus：含逗号的关注缘由按 CSV 规则加引号")
    void exportFocusQuotesComma() {
        PsycheFocus row = focus(1L, WANGXM, "open");
        row.setStudentName("王小明");
        row.setSummary("缘由,含逗号");
        when(focusMapper.list(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(row)));

        PsycheService.CsvExport out = service.export(ADMIN, "admin", "focus", null);

        assertTrue(out.csv.contains("\"缘由,含逗号\""), "实际=" + out.csv);
    }

    @Test
    @DisplayName("16 export=appointments：表头不含咨询问题与备注列")
    void exportAppointmentsHeaderHasNoIssueOrNote() {
        when(appointmentMapper.list(isNull(), isNull(), isNull(), eq(ADMIN), eq(0), eq(100000)))
                .thenReturn(new ArrayList<>());

        PsycheService.CsvExport out = service.export(ADMIN, "admin", "appointments", null);

        assertEquals("psyche-appointments.csv", out.filename);
        String header = out.csv.split("\r\n")[0];
        assertEquals("姓名,学号后四位,班级,方式,期望开始,期望结束,时长(分钟),状态,咨询师,确认时间,完成时间,续约来源", header);
        assertAll(
                () -> assertFalse(header.contains("咨询问题")),
                () -> assertFalse(header.contains("备注")));
    }

    @Test
    @DisplayName("17 export=appointments：不导出 main_issue 全文与 note（S16 保密性）")
    void exportAppointmentsDropsIssueAndNote() {
        PsycheAppointment row = appointment(1L, WANGXM, LISI, "office", "confirmed",
                LocalDateTime.of(2026, 3, 2, 10, 0, 0));
        row.setStudentName("王小明");
        row.setStudentNo("2024001");
        row.setMainIssue("主要问题全文");
        row.setNote("中心内部备注");
        row.setRenewedFrom(TASK_A);
        when(appointmentMapper.list(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(row)));

        PsycheService.CsvExport out = service.export(LISI, "teacher", "appointments", null);

        assertAll(
                () -> assertFalse(out.csv.contains("主要问题全文"), "不得导出咨询问题全文"),
                () -> assertFalse(out.csv.contains("中心内部备注"), "不得导出中心内部备注"),
                () -> assertTrue(out.csv.contains("王*")),
                () -> assertTrue(out.csv.contains("50"), "时长列"),
                () -> assertTrue(out.csv.contains("701"), "续约来源列"));
    }

    // ================================================================
    // 二、量表
    // ================================================================

    @Test
    @DisplayName("18 listInstruments：未登录 401")
    void listInstrumentsNotLoggedIn() {
        assertMsg(service.listInstruments(null, "student"), "请先登录");
        assertEquals(401, service.listInstruments(null, "student").getCode());
    }

    @Test
    @DisplayName("19 listInstruments：学生只拿公开字段，不下发题本与分档阈值")
    void listInstrumentsStudentView() {
        PsycheInstrument it = instrument(INSTR, "demo-20", "演示自评问卷", TWO_ITEM_JSON, THREE_BAND_JSON, 2);
        when(instrumentMapper.list(1)).thenReturn(new ArrayList<>(List.of(it)));

        Result<?> r = service.listInstruments(WANGXM, "student");

        assertOk(r);
        List<?> list = (List<?>) r.getData();
        assertEquals(1, list.size());
        Map<String, Object> one = mapOf(list.get(0));
        assertAll(
                () -> assertEquals(INSTR, ((Number) one.get("id")).longValue()),
                () -> assertEquals("demo-20", one.get("code")),
                () -> assertEquals("演示自评问卷", one.get("name")),
                () -> assertEquals(2, intOf(one.get("itemCount"))),
                () -> assertFalse(one.containsKey("item_json"), "学生侧不得下发题本"),
                () -> assertFalse(one.containsKey("itemJson"), "学生侧不得下发题本"),
                () -> assertFalse(one.containsKey("band_json"), "学生侧不得下发分档阈值"),
                () -> assertFalse(one.containsKey("bandJson"), "学生侧不得下发分档阈值"));
    }

    @Test
    @DisplayName("20 listInstruments：教师拿完整实体（含题本）")
    void listInstrumentsStaffView() {
        PsycheInstrument it = instrument(INSTR, "demo-20", "演示自评问卷", TWO_ITEM_JSON, THREE_BAND_JSON, 2);
        when(instrumentMapper.list(1)).thenReturn(new ArrayList<>(List.of(it)));

        Result<?> r = service.listInstruments(LISI, "teacher");

        List<?> list = (List<?>) r.getData();
        assertTrue(list.get(0) instanceof PsycheInstrument);
        assertEquals(TWO_ITEM_JSON, ((PsycheInstrument) list.get(0)).getItemJson());
    }

    @Test
    @DisplayName("21 createInstrument：教师 403「仅管理员可操作」")
    void createInstrumentTeacherForbidden() {
        Result<?> r = service.createInstrument(LISI, "teacher", body("code", "x", "name", "y"));
        assertCode(r, 403);
        assertMsg(r, "仅管理员可操作");
    }

    @Test
    @DisplayName("22 createInstrument：缺编码 400「请填写量表编码」")
    void createInstrumentCodeRequired() {
        Result<?> r = service.createInstrument(ADMIN, "admin", body("name", "演示"));
        assertCode(r, 400);
        assertMsg(r, "请填写量表编码");
    }

    @Test
    @DisplayName("23 createInstrument：缺名称 400「请填写量表名称」")
    void createInstrumentNameRequired() {
        Result<?> r = service.createInstrument(ADMIN, "admin", body("code", "demo-20"));
        assertCode(r, 400);
        assertMsg(r, "请填写量表名称");
    }

    @Test
    @DisplayName("24 createInstrument：题本不是数组 400")
    void createInstrumentItemJsonMustBeArray() {
        Result<?> r = service.createInstrument(ADMIN, "admin",
                body("code", "demo-20", "name", "演示", "itemJson", "{\"no\":1}"));
        assertCode(r, 400);
        assertMsg(r, "题本 JSON 必须是题目数组");
    }

    @Test
    @DisplayName("25 createInstrument：分档阈值不是数组 400")
    void createInstrumentBandJsonMustBeArray() {
        Result<?> r = service.createInstrument(ADMIN, "admin",
                body("code", "demo-20", "name", "演示", "itemJson", TWO_ITEM_JSON, "bandJson", "{}"));
        assertCode(r, 400);
        assertMsg(r, "分档阈值 JSON 必须是数组");
    }

    @Test
    @DisplayName("26 createInstrument：编码已存在 400「量表编码已存在」")
    void createInstrumentCodeDuplicated() {
        when(instrumentMapper.findByCode("demo-20")).thenReturn(instrument(INSTR, "demo-20", "旧", null, null, 0));
        Result<?> r = service.createInstrument(ADMIN, "admin",
                body("code", "demo-20", "name", "演示", "itemJson", TWO_ITEM_JSON));
        assertCode(r, 400);
        assertMsg(r, "量表编码已存在");
        verify(instrumentMapper, never()).insert(any());
    }

    @Test
    @DisplayName("27 createInstrument：成功——itemCount 取自题本长度，active 缺省 1")
    void createInstrumentSuccess() {
        when(instrumentMapper.findByCode("demo-20")).thenReturn(null);
        when(instrumentMapper.findById(any())).thenReturn(instrument(INSTR, "demo-20", "演示", TWO_ITEM_JSON,
                THREE_BAND_JSON, 2));

        Result<?> r = service.createInstrument(ADMIN, "admin",
                body("code", "demo-20", "name", "演示自评问卷", "itemJson", TWO_ITEM_JSON,
                        "bandJson", THREE_BAND_JSON, "guideText", "请按最近两周作答"));

        assertOk(r);
        ArgumentCaptor<PsycheInstrument> captor = ArgumentCaptor.forClass(PsycheInstrument.class);
        verify(instrumentMapper).insert(captor.capture());
        PsycheInstrument saved = captor.getValue();
        assertAll(
                () -> assertEquals("demo-20", saved.getCode()),
                () -> assertEquals(2, saved.getItemCount(), "itemCount 由题本数组长度得出"),
                () -> assertEquals(1, saved.getActive(), "active 缺省 1"),
                () -> assertEquals(ADMIN, saved.getCreatedBy()),
                () -> assertEquals("请按最近两周作答", saved.getGuideText()));
    }

    // ================================================================
    // 三、批次状态机（draft → open → closed）
    // ================================================================

    @Test
    @DisplayName("28 listBatches：非 admin 自动隐藏 draft，admin 不隐藏")
    void listBatchesHidesDraft() {
        when(batchMapper.list(anyBoolean(), any(), any())).thenReturn(new ArrayList<>());

        service.listBatches(LISI, "teacher", null, null);
        verify(batchMapper).list(eq(true), isNull(), isNull());

        service.listBatches(ADMIN, "admin", null, null);
        verify(batchMapper).list(eq(false), isNull(), isNull());

        service.listBatches(WANGXM, "student", "open", " 2024 ");
        verify(batchMapper).list(eq(true), eq("open"), eq("2024"));
    }

    @Test
    @DisplayName("29 createBatch：缺批次名称 400")
    void createBatchNameRequired() {
        Result<?> r = service.createBatch(ADMIN, "admin", body("grade", "2024级"));
        assertCode(r, 400);
        assertMsg(r, "请填写批次名称");
    }

    @Test
    @DisplayName("30 createBatch：缺普查对象年级 400")
    void createBatchGradeRequired() {
        Result<?> r = service.createBatch(ADMIN, "admin", body("batchName", "2024级新生普查"));
        assertCode(r, 400);
        assertMsg(r, "请填写普查对象年级");
    }

    @Test
    @DisplayName("31 createBatch：绑定的量表不存在 400「量表不存在」")
    void createBatchInstrumentMissing() {
        when(instrumentMapper.findById(INSTR)).thenReturn(null);
        Result<?> r = service.createBatch(ADMIN, "admin",
                body("batchName", "2024级新生普查", "grade", "2024级", "instrumentId", INSTR));
        assertCode(r, 400);
        assertMsg(r, "量表不存在");
    }

    @Test
    @DisplayName("32 createBatch：开放时间格式不正确 400")
    void createBatchOpenAtFormat() {
        Result<?> r = service.createBatch(ADMIN, "admin",
                body("batchName", "2024级新生普查", "grade", "2024级", "openAt", "2026/03/01"));
        assertCode(r, 400);
        assertMsg(r, "开放时间格式不正确（yyyy-MM-dd HH:mm:ss）");
    }

    @Test
    @DisplayName("33 createBatch：关闭时间早于开放时间 400")
    void createBatchCloseBeforeOpen() {
        Result<?> r = service.createBatch(ADMIN, "admin",
                body("batchName", "2024级新生普查", "grade", "2024级",
                        "openAt", "2026-03-10 08:00:00", "closeAt", "2026-03-01 08:00:00"));
        assertCode(r, 400);
        assertMsg(r, "关闭时间不能早于开放时间");
    }

    @Test
    @DisplayName("34 createBatch：成功落到 draft，interviewRequired 缺省 0")
    void createBatchSuccess() {
        when(batchMapper.findById(any())).thenReturn(batch(BATCH, "draft"));

        Result<?> r = service.createBatch(ADMIN, "admin",
                body("batchName", "2024级新生心理普查", "grade", "2024级", "academicYear", "2024-2025",
                        "openAt", "2026-03-10 08:00:00", "closeAt", "2026-03-20 17:00:00"));

        assertOk(r);
        ArgumentCaptor<PsycheBatch> captor = ArgumentCaptor.forClass(PsycheBatch.class);
        verify(batchMapper).insert(captor.capture());
        PsycheBatch saved = captor.getValue();
        assertAll(
                () -> assertEquals("draft", saved.getStatus(), "新批次一律 draft"),
                () -> assertEquals(0, saved.getInterviewRequired(), "interviewRequired 缺省 0"),
                () -> assertEquals(ADMIN, saved.getCreatedBy()),
                () -> assertEquals(LocalDateTime.of(2026, 3, 10, 8, 0, 0), saved.getOpenAt()),
                () -> assertEquals(LocalDateTime.of(2026, 3, 20, 17, 0, 0), saved.getCloseAt()));
    }

    @Test
    @DisplayName("35 updateBatch：批次不存在 404")
    void updateBatchNotFound() {
        when(batchMapper.findById(999L)).thenReturn(null);
        Result<?> r = service.updateBatch(ADMIN, "admin", 999L, body("batchName", "x"));
        assertCode(r, 404);
        assertMsg(r, "批次不存在");
    }

    @Test
    @DisplayName("36 updateBatch：未传字段保持现值，CAS 带上原状态")
    void updateBatchKeepsUntouchedFields() {
        PsycheBatch exist = batch(BATCH, "open");
        exist.setInterviewRequired(1);
        when(batchMapper.findById(BATCH)).thenReturn(exist);
        when(batchMapper.update(any(), any())).thenReturn(1);

        Result<?> r = service.updateBatch(ADMIN, "admin", BATCH, body("noticeTitle", "普查通知"));

        assertOk(r);
        ArgumentCaptor<PsycheBatch> captor = ArgumentCaptor.forClass(PsycheBatch.class);
        verify(batchMapper).update(captor.capture(), eq("open"));
        PsycheBatch merged = captor.getValue();
        assertAll(
                () -> assertEquals(BATCH, merged.getId()),
                () -> assertEquals("2024级新生心理普查", merged.getBatchName(), "未传 → 保持现值"),
                () -> assertEquals("2024级", merged.getGrade(), "未传 → 保持现值"),
                () -> assertEquals("2024-2025", merged.getAcademicYear()),
                () -> assertEquals(1, merged.getInterviewRequired()),
                () -> assertEquals("普查通知", merged.getNoticeTitle()));
    }

    @Test
    @DisplayName("37 updateBatch：已关闭批次 400「状态已变化，请刷新后重试」（状态前置校验，不再走 SQL）")
    void updateBatchCasFail() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "closed"));
        when(batchMapper.update(any(), any())).thenReturn(0);

        Result<?> r = service.updateBatch(ADMIN, "admin", BATCH, body("batchName", "改名"));

        assertCode(r, 400);
        assertMsg(r, CAS_FAIL);
        // 修复 P14 非法迁移缺陷后：fromStatus 取自刚读到的状态会使 CAS 恒成立，
        // 因此已关闭批次在服务层就挡掉，绝不能落到 SQL（否则会真的改库）。
        verify(batchMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("38 publishBatch：批次不存在 404")
    void publishBatchNotFound() {
        when(batchMapper.findById(999L)).thenReturn(null);
        Result<?> r = service.publishBatch(ADMIN, "admin", 999L);
        assertCode(r, 404);
        assertMsg(r, "批次不存在");
    }

    @Test
    @DisplayName("39 publishBatch：draft → open 成功")
    void publishBatchSuccess() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "draft"));
        when(batchMapper.publish(eq(BATCH), eq("draft"), any())).thenReturn(1);

        Result<?> r = service.publishBatch(ADMIN, "admin", BATCH);

        assertOk(r);
        verify(batchMapper).publish(eq(BATCH), eq("draft"), any());
    }

    @Test
    @DisplayName("40 publishBatch：已关闭批次 CAS 0 行 400")
    void publishBatchCasFail() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "closed"));
        when(batchMapper.publish(eq(BATCH), eq("closed"), any())).thenReturn(0);

        Result<?> r = service.publishBatch(ADMIN, "admin", BATCH);

        assertCode(r, 400);
        assertMsg(r, CAS_FAIL);
        // 非 draft 时服务层直接挡掉，绝不能落到 SQL（否则 WHERE status=? 恒成立会真的改库）。
        verify(batchMapper, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("41 publishBatch：教师 403「仅管理员可操作」")
    void publishBatchTeacherForbidden() {
        Result<?> r = service.publishBatch(LISI, "teacher", BATCH);
        assertCode(r, 403);
        assertMsg(r, "仅管理员可操作");
        verifyNoInteractions(batchMapper);
    }

    @Test
    @DisplayName("42 closeBatch：open → closed 成功，未作答名单按未参加处理")
    void closeBatchSuccess() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "open"));
        when(batchMapper.close(eq(BATCH), eq("open"), any())).thenReturn(1);
        when(taskMapper.markAbsentByBatch(BATCH)).thenReturn(3);

        Result<?> r = service.closeBatch(ADMIN, "admin", BATCH);

        assertOk(r);
        assertMsg(r, "已关闭批次，未作答名单已按未参加处理");
        Map<String, Object> data = dataMap(r);
        assertAll(
                () -> assertEquals(3, intOf(data.get("absent"))),
                () -> assertNotNull(data.get("batch")));
        verify(taskMapper).markAbsentByBatch(BATCH);
    }

    @Test
    @DisplayName("43 closeBatch：CAS 0 行 400，且不置未参加")
    void closeBatchCasFail() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "draft"));
        when(batchMapper.close(eq(BATCH), eq("draft"), any())).thenReturn(0);

        Result<?> r = service.closeBatch(ADMIN, "admin", BATCH);

        assertCode(r, 400);
        assertMsg(r, CAS_FAIL);
        verify(batchMapper, never()).close(any(), any(), any());
        verify(taskMapper, never()).markAbsentByBatch(any());
    }

    @Test
    @DisplayName("44 closeBatch：未登录 401 先于角色判断")
    void closeBatchNotLoggedInFirst() {
        Result<?> r = service.closeBatch(null, "admin", BATCH);
        assertCode(r, 401);
        assertMsg(r, "请先登录");
        verifyNoInteractions(batchMapper);
    }

    // ================================================================
    // 四、名单导入
    // ================================================================

    @Test
    @DisplayName("45 importTasks：batchId 为空 400「请指定批次」")
    void importTasksBatchIdRequired() {
        Result<?> r = service.importTasks(LISI, "teacher", null, body("studentIds", "2024001"));
        assertCode(r, 400);
        assertMsg(r, "请指定批次");
    }

    @Test
    @DisplayName("46 importTasks：批次不存在 404")
    void importTasksBatchNotFound() {
        when(batchMapper.findById(999L)).thenReturn(null);
        Result<?> r = service.importTasks(LISI, "teacher", 999L, body("studentIds", "2024001"));
        assertCode(r, 404);
        assertMsg(r, "批次不存在");
    }

    @Test
    @DisplayName("47 importTasks：既没 studentIds 也没 csv → 400「请提供 studentIds 或 csv 文本」")
    void importTasksEmptyTokens() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "open"));
        Result<?> r = service.importTasks(LISI, "teacher", BATCH, body());
        assertCode(r, 400);
        assertMsg(r, "请提供 studentIds 或 csv 文本");
        verify(taskMapper, never()).insert(any());
    }

    @Test
    @DisplayName("48 importTasks：csv 跳过表头、按行取第一列")
    void importTasksCsvSkipsHeader() {
        when(batchMapper.findById(BATCH)).thenReturn(openBatch(BATCH, INSTR));
        when(instrumentMapper.findById(INSTR)).thenReturn(instrument(INSTR, "demo-20", "演示", TWO_ITEM_JSON,
                THREE_BAND_JSON, 2));
        when(userMapper.findByStudentId("2024001")).thenReturn(student(WANGXM, "2024001", "wangxm"));

        String csv = "\uFEFF学号,姓名\r\n2024001,王小明\r\n\r\n";
        Result<?> r = service.importTasks(LISI, "teacher", BATCH, body("csv", csv));

        assertOk(r);
        Map<String, Object> data = dataMap(r);
        assertAll(
                () -> assertEquals(1, intOf(data.get("created"))),
                () -> assertEquals(0, intOf(data.get("skipped"))),
                () -> assertEquals(0, ((List<?>) data.get("notFound")).size()));
        ArgumentCaptor<PsycheTask> captor = ArgumentCaptor.forClass(PsycheTask.class);
        verify(taskMapper).insert(captor.capture());
        PsycheTask saved = captor.getValue();
        assertAll(
                () -> assertEquals(BATCH, saved.getBatchId()),
                () -> assertEquals(WANGXM, saved.getStudentId()),
                () -> assertEquals("pending", saved.getStatus()),
                () -> assertEquals(0, saved.getAnswerCount()),
                () -> assertEquals(0, saved.getInterviewed()),
                () -> assertEquals(2, saved.getTotalItems(), "totalItems 取量表的 itemCount"),
                () -> assertNull(saved.getResultLevel(), "导入名单不得预置结果等级"));
    }

    @Test
    @DisplayName("49 importTasks：studentIds 支持数组与逗号/分号分隔的字符串")
    void importTasksStudentIdTokens() {
        when(batchMapper.findById(BATCH)).thenReturn(openBatch(BATCH, INSTR));
        when(instrumentMapper.findById(INSTR)).thenReturn(instrument(INSTR, "demo-20", "演示", TWO_ITEM_JSON,
                THREE_BAND_JSON, 2));
        when(userMapper.findByStudentId("2024001")).thenReturn(student(WANGXM, "2024001", "wangxm"));
        when(userMapper.findByStudentId("2024002")).thenReturn(student(ZHAOXH, "2024002", "zhaoxh"));
        when(userMapper.findByStudentId("2024003")).thenReturn(student(CHENDW, "2024003", "chendw"));

        Result<?> list = service.importTasks(LISI, "teacher", BATCH,
                body("studentIds", List.of("2024001", " 2024002 ")));
        assertEquals(2, intOf(dataMap(list).get("created")));

        Result<?> text = service.importTasks(LISI, "teacher", BATCH, body("student_ids", "2024003；2024001, "));
        assertEquals(2, intOf(dataMap(text).get("created")));
    }

    @Test
    @DisplayName("50 importTasks：已在名单里的学生计入 skipped")
    void importTasksSkipsExisting() {
        when(batchMapper.findById(BATCH)).thenReturn(openBatch(BATCH, INSTR));
        when(instrumentMapper.findById(INSTR)).thenReturn(instrument(INSTR, "demo-20", "演示", TWO_ITEM_JSON,
                THREE_BAND_JSON, 2));
        when(userMapper.findByStudentId("2024001")).thenReturn(student(WANGXM, "2024001", "wangxm"));
        when(taskMapper.findByBatchAndStudent(BATCH, WANGXM)).thenReturn(task(TASK_A, BATCH, WANGXM, "pending"));

        Result<?> r = service.importTasks(LISI, "teacher", BATCH, body("studentIds", "2024001"));

        Map<String, Object> data = dataMap(r);
        assertAll(
                () -> assertEquals(0, intOf(data.get("created"))),
                () -> assertEquals(1, intOf(data.get("skipped"))),
                () -> assertEquals(0, ((List<?>) data.get("notFound")).size()));
        verify(taskMapper, never()).insert(any());
    }

    @Test
    @DisplayName("51 importTasks：非学生/不存在/已删除账号进 notFound 且去重")
    void importTasksNotFound() {
        when(batchMapper.findById(BATCH)).thenReturn(openBatch(BATCH, INSTR));
        when(instrumentMapper.findById(INSTR)).thenReturn(instrument(INSTR, "demo-20", "演示", TWO_ITEM_JSON,
                THREE_BAND_JSON, 2));
        when(userMapper.findByStudentId("teacher1")).thenReturn(null);
        User teacher = new User();
        teacher.setId(LISI);
        teacher.setUsername("lisi");
        teacher.setRole("teacher");
        when(userMapper.findByUsername("teacher1")).thenReturn(teacher);
        when(userMapper.findByStudentId("9999")).thenReturn(null);
        when(userMapper.findByUsername("9999")).thenReturn(null);
        when(userMapper.findById(9999L)).thenReturn(null);

        Result<?> r = service.importTasks(LISI, "teacher", BATCH, body("studentIds", "teacher1,9999,teacher1"));

        Map<String, Object> data = dataMap(r);
        List<?> notFound = (List<?>) data.get("notFound");
        assertAll(
                () -> assertEquals(0, intOf(data.get("created"))),
                () -> assertEquals(0, intOf(data.get("skipped"))),
                () -> assertEquals(2, notFound.size(), "非学生 + 不存在，且按 token 去重"),
                () -> assertTrue(notFound.contains("teacher1")),
                () -> assertTrue(notFound.contains("9999")));
    }

    @Test
    @DisplayName("52 importTasks：学生角色 403「无权访问」")
    void importTasksStudentForbidden() {
        Result<?> r = service.importTasks(WANGXM, "student", BATCH, body("studentIds", "2024001"));
        assertCode(r, 403);
        assertMsg(r, "无权访问");
    }

    @Test
    @DisplayName("53 importTasks：未登录 401")
    void importTasksNotLoggedIn() {
        Result<?> r = service.importTasks(null, "teacher", BATCH, body("studentIds", "2024001"));
        assertCode(r, 401);
        assertMsg(r, "请先登录");
    }

    // ================================================================
    // 五、学生端：我的 / 取题 / 提交 / 结果
    // ================================================================

    @Test
    @DisplayName("54 my：未登录 401、教师 403「仅学生可用」")
    void myGuards() {
        assertAll(
                () -> assertEquals(401, service.my(null, "student").getCode()),
                () -> assertEquals("请先登录", service.my(null, "student").getMessage()),
                () -> assertEquals(403, service.my(LISI, "teacher").getCode()),
                () -> assertEquals("仅学生可用", service.my(LISI, "teacher").getMessage()));
    }

    @Test
    @DisplayName("55 my：跳过 draft 批次，不下发 resultLevel/resultNote，预约清掉 note，只给建议卡")
    void myStudentView() {
        PsycheTask mine = task(TASK_A, BATCH, WANGXM, "completed");
        mine.setResultLevel("warning");
        mine.setResultNote("中心内部备注：建议复测");
        PsycheTask draftTask = task(702L, 602L, WANGXM, "pending");
        when(taskMapper.listByStudent(WANGXM)).thenReturn(new ArrayList<>(List.of(mine, draftTask)));
        when(batchMapper.findById(BATCH)).thenReturn(openBatch(BATCH, INSTR));
        when(batchMapper.findById(602L)).thenReturn(batch(602L, "draft"));
        when(instrumentMapper.findById(INSTR)).thenReturn(instrument(INSTR, "demo-20", "演示自评问卷",
                TWO_ITEM_JSON, THREE_BAND_JSON, 2));
        PsycheAppointment appt = appointment(1L, WANGXM, LISI, "office", "requested",
                LocalDateTime.now().plusDays(3));
        appt.setNote("中心内部备注");
        when(appointmentMapper.listByStudent(WANGXM)).thenReturn(new ArrayList<>(List.of(appt)));
        when(focusMapper.listByStudent(WANGXM)).thenReturn(new ArrayList<>(List.of(focus(1L, WANGXM, "open"))));

        Result<?> r = service.my(WANGXM, "student");

        assertOk(r);
        Map<String, Object> data = dataMap(r);
        List<Map<String, Object>> batches = mapList(data.get("batches"));
        Map<String, Object> advice = mapOf(data.get("focusAdvice"));
        @SuppressWarnings("unchecked")
        List<PsycheAppointment> myAppointments = (List<PsycheAppointment>) data.get("appointments");
        assertAll(
                () -> assertEquals(1, batches.size(), "draft 批次对学生不可见"),
                () -> assertNull(((PsycheTask) batches.get(0).get("myTask")).getResultLevel(),
                        "§9 口径 7：学生端不给等级原文"),
                () -> assertNull(((PsycheTask) batches.get(0).get("myTask")).getResultNote(),
                        "内部备注不下发学生"),
                () -> assertEquals("演示自评问卷", batches.get(0).get("instrumentName")),
                () -> assertNull(myAppointments.get(0).getNote(), "预约不发中心内部备注"),
                () -> assertEquals(Boolean.TRUE, advice.get("show")),
                () -> assertEquals("心理服务中心建议你与辅导员或中心联系", advice.get("text")),
                () -> assertFalse(data.containsKey("resultLevel")));
    }

    @Test
    @DisplayName("56 my：所有关注台账都 closed 时建议卡不展示")
    void myFocusAdviceHiddenWhenClosed() {
        when(taskMapper.listByStudent(WANGXM)).thenReturn(new ArrayList<>());
        when(appointmentMapper.listByStudent(WANGXM)).thenReturn(new ArrayList<>());
        when(focusMapper.listByStudent(WANGXM)).thenReturn(new ArrayList<>(List.of(
                focus(1L, WANGXM, "closed"), focus(2L, WANGXM, "closed"))));

        Result<?> r = service.my(WANGXM, "student");

        Map<String, Object> advice = mapOf(dataMap(r).get("focusAdvice"));
        assertEquals(Boolean.FALSE, advice.get("show"));
    }

    @Test
    @DisplayName("57 taskDetail：名单不存在 404")
    void taskDetailNotFound() {
        when(taskMapper.findById(TASK_A)).thenReturn(null);
        Result<?> r = service.taskDetail(WANGXM, "student", TASK_A);
        assertCode(r, 404);
        assertMsg(r, "名单不存在");
    }

    @Test
    @DisplayName("58 taskDetail：窗口外且未完成 → 400「当前不在普查时间内」")
    void taskDetailOutOfWindow() {
        PsycheTask t = task(TASK_A, BATCH, WANGXM, "pending");
        when(taskMapper.findById(TASK_A)).thenReturn(t);
        PsycheBatch b = batch(BATCH, "open");
        b.setOpenAt(LocalDateTime.now().plusDays(1));
        b.setCloseAt(LocalDateTime.now().plusDays(2));
        when(batchMapper.findById(BATCH)).thenReturn(b);

        Result<?> r = service.taskDetail(WANGXM, "student", TASK_A);

        assertCode(r, 400);
        assertMsg(r, OUT_OF_WINDOW);
        verify(taskMapper, never()).startTask(any(), any(), any());
    }

    @Test
    @DisplayName("59 taskDetail：窗口外但已完成的任务仍可查看")
    void taskDetailCompletedOutsideWindow() {
        PsycheTask t = task(TASK_A, BATCH, WANGXM, "completed");
        when(taskMapper.findById(TASK_A)).thenReturn(t);
        PsycheBatch b = batch(BATCH, "closed");
        b.setOpenAt(LocalDateTime.now().minusDays(10));
        b.setCloseAt(LocalDateTime.now().minusDays(5));
        when(batchMapper.findById(BATCH)).thenReturn(b);

        Result<?> r = service.taskDetail(WANGXM, "student", TASK_A);

        assertOk(r);
        verify(taskMapper, never()).startTask(any(), any(), any());
    }

    @Test
    @DisplayName("60 taskDetail：窗口内首次取题把 pending 推进 in_progress 并落 startedAt")
    void taskDetailStartsTask() {
        PsycheTask t = task(TASK_A, BATCH, WANGXM, "pending");
        when(taskMapper.findById(TASK_A)).thenReturn(t);
        when(batchMapper.findById(BATCH)).thenReturn(openBatch(BATCH, INSTR));
        when(instrumentMapper.findById(INSTR)).thenReturn(instrument(INSTR, "demo-20", "演示", TWO_ITEM_JSON,
                THREE_BAND_JSON, 2));
        when(taskMapper.startTask(eq(TASK_A), eq(2), any())).thenReturn(1);

        Result<?> r = service.taskDetail(WANGXM, "student", TASK_A);

        assertOk(r);
        PsycheTask got = (PsycheTask) dataMap(r).get("task");
        assertAll(
                () -> assertEquals("in_progress", got.getStatus()),
                () -> assertEquals(2, got.getTotalItems()),
                () -> assertNotNull(got.getStartedAt()));
        verify(taskMapper).startTask(eq(TASK_A), eq(2), any());
    }

    @Test
    @DisplayName("61 taskDetail：学生看别人的名单 403「只能查看自己的普查名单」")
    void taskDetailStudentPeers() {
        when(taskMapper.findById(TASK_A)).thenReturn(task(TASK_A, BATCH, ZHAOXH, "pending"));
        Result<?> r = service.taskDetail(WANGXM, "student", TASK_A);
        assertCode(r, 403);
        assertMsg(r, "只能查看自己的普查名单");
    }

    @Test
    @DisplayName("62 taskDetail：跨辅导员 403「只能查看名下学生的普查记录」")
    void taskDetailCrossCounselor() {
        when(taskMapper.findById(TASK_A)).thenReturn(task(TASK_A, BATCH, WANGXM, "completed"));
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));

        Result<?> r = service.taskDetail(ZHANGSAN, "teacher", TASK_A);

        assertCode(r, 403);
        assertMsg(r, "只能查看名下学生的普查记录");
    }

    @Test
    @DisplayName("63 taskDetail：明细给全名与原始学号（与列表脱敏形成两层对比），并下发题本")
    void taskDetailGivesFullName() {
        PsycheTask t = task(TASK_A, BATCH, WANGXM, "completed");
        t.setStudentName("王小明");
        t.setStudentNo("2024001");
        when(taskMapper.findById(TASK_A)).thenReturn(t);
        when(batchMapper.findById(BATCH)).thenReturn(openBatch(BATCH, INSTR));
        when(instrumentMapper.findById(INSTR)).thenReturn(instrument(INSTR, "demo-20", "演示自评问卷",
                TWO_ITEM_JSON, THREE_BAND_JSON, 2));

        Result<?> r = service.taskDetail(WANGXM, "student", TASK_A);

        assertOk(r);
        Map<String, Object> data = dataMap(r);
        PsycheTask got = (PsycheTask) data.get("task");
        Map<String, Object> view = mapOf(data.get("instrument"));
        assertAll(
                () -> assertEquals("王小明", got.getStudentName(), "明细给全名（本人才可见）"),
                () -> assertEquals("2024001", got.getStudentNo()),
                () -> assertEquals(TWO_ITEM_JSON, view.get("itemJson"), "取题时下发题本"),
                () -> assertEquals(2, intOf(view.get("itemCount"))));
    }

    @Test
    @DisplayName("64 taskDetail：未登录 401")
    void taskDetailNotLoggedIn() {
        assertMsg(service.taskDetail(null, "student", TASK_A), "请先登录");
    }

    @Test
    @DisplayName("65 submit：名单不存在 404")
    void submitNotFound() {
        when(taskMapper.findById(TASK_A)).thenReturn(null);
        Result<?> r = service.submit(WANGXM, "student", TASK_A, answersBody(1, 3, 2, 4));
        assertCode(r, 404);
        assertMsg(r, "名单不存在");
    }

    @Test
    @DisplayName("66 submit：教师角色 403「仅学生本人可提交普查问卷」")
    void submitStaffForbidden() {
        when(taskMapper.findById(TASK_A)).thenReturn(task(TASK_A, BATCH, WANGXM, "in_progress"));
        Result<?> r = service.submit(LISI, "teacher", TASK_A, answersBody(1, 3, 2, 4));
        assertCode(r, 403);
        assertMsg(r, "仅学生本人可提交普查问卷");
    }

    @Test
    @DisplayName("67 submit：替别人提交 403「只能提交自己的普查问卷」")
    void submitNotOwner() {
        when(taskMapper.findById(TASK_A)).thenReturn(task(TASK_A, BATCH, ZHAOXH, "in_progress"));
        Result<?> r = service.submit(WANGXM, "student", TASK_A, answersBody(1, 3, 2, 4));
        assertCode(r, 403);
        assertMsg(r, "只能提交自己的普查问卷");
    }

    @Test
    @DisplayName("68 submit：重复提交 400「该量表已提交，不能重复提交」")
    void submitTwice() {
        when(taskMapper.findById(TASK_A)).thenReturn(task(TASK_A, BATCH, WANGXM, "completed"));
        Result<?> r = service.submit(WANGXM, "student", TASK_A, answersBody(1, 3, 2, 4));
        assertCode(r, 400);
        assertMsg(r, "该量表已提交，不能重复提交");
    }

    @Test
    @DisplayName("69 submit：批次未绑定量表 400")
    void submitNoInstrument() {
        when(taskMapper.findById(TASK_A)).thenReturn(task(TASK_A, BATCH, WANGXM, "in_progress"));
        when(batchMapper.findById(BATCH)).thenReturn(openBatch(BATCH, null));
        Result<?> r = service.submit(WANGXM, "student", TASK_A, answersBody(1, 3, 2, 4));
        assertCode(r, 400);
        assertMsg(r, "该批次未绑定量表，无法作答");
    }

    @Test
    @DisplayName("70 submit：题本未导入（itemCount 0）400")
    void submitNoItems() {
        when(taskMapper.findById(TASK_A)).thenReturn(task(TASK_A, BATCH, WANGXM, "in_progress"));
        when(batchMapper.findById(BATCH)).thenReturn(openBatch(BATCH, INSTR));
        when(instrumentMapper.findById(INSTR)).thenReturn(instrument(INSTR, "demo-20", "演示", "[]", null, 0));
        Result<?> r = service.submit(WANGXM, "student", TASK_A, body("answers", List.of()));
        assertCode(r, 400);
        assertMsg(r, "该量表题本未导入，无法作答");
    }

    @Test
    @DisplayName("71 submit：窗口外 400「当前不在普查时间内」")
    void submitOutOfWindow() {
        when(taskMapper.findById(TASK_A)).thenReturn(task(TASK_A, BATCH, WANGXM, "in_progress"));
        when(batchMapper.findById(BATCH)).thenReturn(openBatch(BATCH, INSTR));
        when(instrumentMapper.findById(INSTR)).thenReturn(instrument(INSTR, "demo-20", "演示", TWO_ITEM_JSON,
                THREE_BAND_JSON, 2));
        PsycheBatch closed = batch(BATCH, "closed");
        closed.setInstrumentId(INSTR);
        closed.setOpenAt(LocalDateTime.now().minusDays(10));
        closed.setCloseAt(LocalDateTime.now().minusDays(1));
        when(batchMapper.findById(BATCH)).thenReturn(closed);

        Result<?> r = service.submit(WANGXM, "student", TASK_A, answersBody(1, 3, 2, 4));

        assertCode(r, 400);
        assertMsg(r, OUT_OF_WINDOW);
    }

    @Test
    @DisplayName("72 submit：没答完全部题目 400「请完成全部题目后再提交」（S8：否则测试无效）")
    void submitNotAllAnswered() {
        when(taskMapper.findById(TASK_A)).thenReturn(task(TASK_A, BATCH, WANGXM, "in_progress"));
        when(batchMapper.findById(BATCH)).thenReturn(openBatch(BATCH, INSTR));
        when(instrumentMapper.findById(INSTR)).thenReturn(instrument(INSTR, "demo-20", "演示", TWO_ITEM_JSON,
                THREE_BAND_JSON, 2));

        Result<?> r = service.submit(WANGXM, "student", TASK_A, answersBody(1, 3));

        assertCode(r, 400);
        assertMsg(r, NOT_ALL_ANSWERED);
        verify(taskMapper, never()).submit(any(), any(), anyInt(), anyInt(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("73 submit：重复题号不算答完（answered 集合去重）")
    void submitDuplicateItemNo() {
        when(taskMapper.findById(TASK_A)).thenReturn(task(TASK_A, BATCH, WANGXM, "in_progress"));
        when(batchMapper.findById(BATCH)).thenReturn(openBatch(BATCH, INSTR));
        when(instrumentMapper.findById(INSTR)).thenReturn(instrument(INSTR, "demo-20", "演示", TWO_ITEM_JSON,
                THREE_BAND_JSON, 2));

        Result<?> r = service.submit(WANGXM, "student", TASK_A, answersBody(1, 3, 1, 4));

        assertCode(r, 400);
        assertMsg(r, NOT_ALL_ANSWERED);
    }

    @Test
    @DisplayName("74 submit：成功——写总分与 band_json 折算的档位，答案落 psyche_answer")
    void submitSuccess() {
        when(taskMapper.findById(TASK_A)).thenReturn(task(TASK_A, BATCH, WANGXM, "in_progress"));
        when(batchMapper.findById(BATCH)).thenReturn(openBatch(BATCH, INSTR));
        when(instrumentMapper.findById(INSTR)).thenReturn(instrument(INSTR, "demo-20", "演示", TWO_ITEM_JSON,
                THREE_BAND_JSON, 2));
        when(taskMapper.submit(any(), any(), anyInt(), anyInt(), any(), any(), any(), any())).thenReturn(1);

        Result<?> r = service.submit(WANGXM, "student", TASK_A,
                body("answers", List.of(Map.of("no", 1, "value", 3), Map.of("no", 2, "value", 4))));

        assertOk(r);
        ArgumentCaptor<BigDecimal> score = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> band = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).submit(eq(TASK_A), eq("in_progress"), eq(2), eq(2), score.capture(), band.capture(),
                isNull(), any());
        assertAll(
                () -> assertEquals(0, new BigDecimal("7").compareTo(score.getValue()), "总分=3+4"),
                () -> assertEquals("正常", band.getValue(), "7 ≤ 10 → 第一个 band"));

        ArgumentCaptor<PsycheAnswer> answer = ArgumentCaptor.forClass(PsycheAnswer.class);
        verify(answerMapper).insert(answer.capture());
        assertAll(
                () -> assertEquals(TASK_A, answer.getValue().getTaskId()),
                () -> assertEquals(WANGXM, answer.getValue().getStudentId()),
                () -> assertEquals(2, answer.getValue().getAnswerCount()),
                () -> assertEquals(2, answer.getValue().getTotalItems()),
                () -> assertEquals(0, new BigDecimal("7").compareTo(answer.getValue().getScoreTotal())),
                () -> assertEquals("正常", answer.getValue().getScoreBand()),
                () -> assertTrue(answer.getValue().getAnswerJson().contains("\"no\":1")));
    }

    @Test
    @DisplayName("75 submit：CAS 0 行 400「状态已变化，请刷新后重试」")
    void submitCasFail() {
        when(taskMapper.findById(TASK_A)).thenReturn(task(TASK_A, BATCH, WANGXM, "in_progress"));
        when(batchMapper.findById(BATCH)).thenReturn(openBatch(BATCH, INSTR));
        when(instrumentMapper.findById(INSTR)).thenReturn(instrument(INSTR, "demo-20", "演示", TWO_ITEM_JSON,
                THREE_BAND_JSON, 2));
        when(taskMapper.submit(any(), any(), anyInt(), anyInt(), any(), any(), any(), any())).thenReturn(0);

        Result<?> r = service.submit(WANGXM, "student", TASK_A,
                body("answers", List.of(Map.of("no", 1, "value", 3), Map.of("no", 2, "value", 4))));

        assertCode(r, 400);
        assertMsg(r, CAS_FAIL);
        verify(answerMapper, never()).insert(any());
    }

    @Test
    @DisplayName("76 submit：只按 band_json 折算档位，平台不推导 result_level（§9 口径 3）")
    void submitDoesNotDeriveResultLevel() {
        PsycheTask t = task(TASK_A, BATCH, WANGXM, "in_progress");
        when(taskMapper.findById(TASK_A)).thenReturn(t);
        when(batchMapper.findById(BATCH)).thenReturn(openBatch(BATCH, INSTR));
        // 高分（18 分 → 落"关注"档）也不得自动写成 attention/warning
        when(instrumentMapper.findById(INSTR)).thenReturn(instrument(INSTR, "demo-20", "演示", TWO_ITEM_JSON,
                THREE_BAND_JSON, 2));
        when(taskMapper.submit(any(), any(), anyInt(), anyInt(), any(), any(), any(), any())).thenReturn(1);

        Result<?> r = service.submit(WANGXM, "student", TASK_A,
                body("answers", List.of(Map.of("no", 1, "value", 9), Map.of("no", 2, "value", 9))));

        assertOk(r);
        Map<String, Object> data = dataMap(r);
        assertAll(
                () -> assertFalse(data.containsKey("resultLevel"), "提交响应里不许出现结果等级"),
                () -> assertNull(t.getResultLevel(), "平台不得按分数自动判定等级"),
                () -> assertEquals("关注", data.get("scoreBand"), "只回 band_json 里的档位原文"),
                () -> assertEquals(0, new BigDecimal("18").compareTo((BigDecimal) data.get("scoreTotal"))));
        verify(taskMapper, never()).updateResult(any(), any(), any(), any());
    }

    @Test
    @DisplayName("77 taskResult 学生侧不下发结果等级原文与中心内部备注（修复后）")
    void taskResultStudentMustNotReceiveResultLevel() {
        PsycheTask t = task(TASK_A, BATCH, WANGXM, "completed");
        t.setResultLevel("warning");
        t.setResultNote("中心内部备注：建议复测");
        when(taskMapper.findById(TASK_A)).thenReturn(t);

        Result<?> r = service.taskResult(WANGXM, "student", TASK_A);

        assertOk(r);
        Map<String, Object> data = dataMap(r);
        assertAll(
                () -> assertFalse(data.containsKey("resultLevel"),
                        "学生侧结果查询不应出现 resultLevel（§9 口径 7 / PsycheController.java:30、:152 自述契约）"),
                () -> assertFalse(data.containsKey("resultNote"),
                        "学生侧结果查询不应出现中心内部备注 resultNote"));
    }

    @Test
    @DisplayName("78 taskDetail 学生侧的 task 实体不带等级与内部备注（修复后）")
    void taskDetailStudentMustNotReceiveResultLevel() {
        PsycheTask t = task(TASK_A, BATCH, WANGXM, "completed");
        t.setResultLevel("warning");
        t.setResultNote("中心内部备注：建议复测");
        when(taskMapper.findById(TASK_A)).thenReturn(t);
        when(batchMapper.findById(BATCH)).thenReturn(openBatch(BATCH, INSTR));
        when(instrumentMapper.findById(INSTR)).thenReturn(instrument(INSTR, "demo-20", "演示", TWO_ITEM_JSON,
                THREE_BAND_JSON, 2));

        Result<?> r = service.taskDetail(WANGXM, "student", TASK_A);

        assertOk(r);
        PsycheTask got = (PsycheTask) dataMap(r).get("task");
        assertAll(
                () -> assertNull(got.getResultLevel(), "学生取题不应拿到等级原文"),
                () -> assertNull(got.getResultNote(), "学生取题不应拿到中心内部备注"));
    }

    // ================================================================
    // 六、结果等级回填与访谈（§9 口径 3：等级只能登记 / 导入，平台不推导）
    // ================================================================

    @Test
    @DisplayName("79 updateResult：未登录 401、学生 403「无权访问」，且一次库都不查")
    void updateResultGuards() {
        assertAll(
                () -> assertCode(service.updateResult(null, "student", TASK_A, body("resultLevel", "attention")), 401),
                () -> assertCode(service.updateResult(WANGXM, "student", TASK_A, body("resultLevel", "attention")), 403),
                () -> assertMsg(service.updateResult(WANGXM, "student", TASK_A, body("resultLevel", "attention")),
                        "无权访问"));
        verifyNoInteractions(taskMapper);
    }

    @Test
    @DisplayName("80 updateResult：名单不存在 404「名单不存在」；跨辅导员 403「只能回填名下学生的结果等级」")
    void updateResultNotFoundAndCrossCounselor() {
        Result<?> missing = service.updateResult(LISI, "teacher", 999L, body("resultLevel", "attention"));
        assertCode(missing, 404);
        assertMsg(missing, "名单不存在");

        when(taskMapper.findById(TASK_A)).thenReturn(task(TASK_A, BATCH, LIUSQ, "completed"));
        when(profileMapper.findByUserId(LIUSQ)).thenReturn(profile(LIUSQ, ZHANGSAN));
        Result<?> denied = service.updateResult(LISI, "teacher", TASK_A, body("resultLevel", "attention"));
        assertCode(denied, 403);
        assertMsg(denied, "只能回填名下学生的结果等级");
    }

    @Test
    @DisplayName("81 updateResult：等级非枚举 400，只接受 normal / attention / warning（平台不推导也不接受自造等级）")
    void updateResultRejectsUnknownLevel() {
        when(taskMapper.findById(TASK_A)).thenReturn(task(TASK_A, BATCH, WANGXM, "completed"));
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));

        Result<?> r = service.updateResult(LISI, "teacher", TASK_A, body("resultLevel", "high"));

        assertCode(r, 400);
        assertMsg(r, "结果等级只接受 normal / attention / warning");
        verify(taskMapper, never()).updateResult(anyLong(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("82 updateResult：结果备注超 500 字 400")
    void updateResultRejectsLongNote() {
        when(taskMapper.findById(TASK_A)).thenReturn(task(TASK_A, BATCH, WANGXM, "completed"));
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));

        Result<?> r = service.updateResult(LISI, "teacher", TASK_A,
                body("resultLevel", "warning", "resultNote", "备".repeat(501)));

        assertMsg(r, "结果备注不能超过 500 个字符");
    }

    @Test
    @DisplayName("83 updateResult：成功——等级与内部备注按原文落库，老师侧看得见等级原文")
    void updateResultWritesLevelAndNote() {
        PsycheTask exist = task(TASK_A, BATCH, WANGXM, "completed");
        exist.setScoreTotal(new BigDecimal("12"));
        exist.setScoreBand("关注");
        PsycheTask updated = task(TASK_A, BATCH, WANGXM, "completed");
        updated.setScoreTotal(new BigDecimal("12"));
        updated.setScoreBand("关注");
        updated.setResultLevel("attention");
        updated.setResultNote("已转介中心面谈");
        when(taskMapper.findById(TASK_A)).thenReturn(exist, updated);
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        when(taskMapper.updateResult(TASK_A, "attention", "已转介中心面谈", LISI)).thenReturn(1);

        Result<?> r = service.updateResult(LISI, "teacher", TASK_A,
                body("resultLevel", "attention", "resultNote", "已转介中心面谈"));

        assertOk(r);
        Map<String, Object> data = dataMap(r);
        assertAll(
                () -> assertEquals("attention", data.get("resultLevel")),
                () -> assertEquals("已转介中心面谈", data.get("resultNote")),
                () -> assertEquals("关注", data.get("scoreBand")),
                () -> assertEquals(0, new BigDecimal("12").compareTo((BigDecimal) data.get("scoreTotal"))));
        verify(taskMapper).updateResult(TASK_A, "attention", "已转介中心面谈", LISI);
    }

    @Test
    @DisplayName("84 updateResult：CAS 0 行 400「结果回填失败，请刷新后重试」")
    void updateResultCasFail() {
        when(taskMapper.findById(TASK_A)).thenReturn(task(TASK_A, BATCH, WANGXM, "completed"));
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        when(taskMapper.updateResult(anyLong(), any(), any(), anyLong())).thenReturn(0);

        Result<?> r = service.updateResult(LISI, "teacher", TASK_A, body("resultLevel", "normal"));

        assertCode(r, 400);
        assertMsg(r, "结果回填失败，请刷新后重试");
    }

    @Test
    @DisplayName("85 taskResult(GET)：教师能看到等级原文——与学生侧形成对比（student 侧见 77 号缺陷用例）")
    void taskResultGivesLevelToTeacher() {
        PsycheTask done = task(TASK_A, BATCH, WANGXM, "completed");
        done.setResultLevel("warning");
        done.setResultNote("中心内部备注：建议持续跟进");
        when(taskMapper.findById(TASK_A)).thenReturn(done);
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));

        Result<?> r = service.taskResult(LISI, "teacher", TASK_A);

        assertOk(r);
        Map<String, Object> data = dataMap(r);
        assertAll(
                () -> assertEquals("warning", data.get("resultLevel")),
                () -> assertEquals("中心内部备注：建议持续跟进", data.get("resultNote")));
    }

    @Test
    @DisplayName("86 interview：未登录 401、学生 403「无权访问」")
    void interviewGuards() {
        assertAll(
                () -> assertCode(service.interview(null, "teacher", TASK_A, body("note", "已面谈")), 401),
                () -> assertCode(service.interview(WANGXM, "student", TASK_A, body("note", "已面谈")), 403));
        verifyNoInteractions(taskMapper);
    }

    @Test
    @DisplayName("87 interview：名单不存在 404；跨辅导员 403「只能填写名下学生的访谈记录」")
    void interviewNotFoundAndCrossCounselor() {
        assertMsg(service.interview(LISI, "teacher", 999L, body("note", "已面谈")), "名单不存在");

        when(taskMapper.findById(TASK_A)).thenReturn(task(TASK_A, BATCH, LIUSQ, "completed"));
        when(profileMapper.findByUserId(LIUSQ)).thenReturn(profile(LIUSQ, ZHANGSAN));
        Result<?> denied = service.interview(LISI, "teacher", TASK_A, body("note", "已面谈"));
        assertCode(denied, 403);
        assertMsg(denied, "只能填写名下学生的访谈记录");
    }

    @Test
    @DisplayName("88 interview：访谈记录超 500 字 400")
    void interviewRejectsLongNote() {
        when(taskMapper.findById(TASK_A)).thenReturn(task(TASK_A, BATCH, WANGXM, "completed"));
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));

        Result<?> r = service.interview(LISI, "teacher", TASK_A, body("note", "访".repeat(501)));

        assertMsg(r, "访谈记录不能超过 500 个字符");
    }

    @Test
    @DisplayName("89 interview：成功——interviewed 缺省 1，记录与访谈人落库")
    void interviewWritesRecord() {
        when(taskMapper.findById(TASK_A)).thenReturn(task(TASK_A, BATCH, WANGXM, "completed"));
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        when(taskMapper.updateInterview(eq(TASK_A), eq(1), eq("已电话访谈，状态平稳"), eq(LISI), any()))
                .thenReturn(1);

        Result<?> r = service.interview(LISI, "teacher", TASK_A, body("note", "已电话访谈，状态平稳"));

        assertOk(r);
        verify(taskMapper).updateInterview(eq(TASK_A), eq(1), eq("已电话访谈，状态平稳"), eq(LISI), any());
    }

    @Test
    @DisplayName("90 interview：显式 interviewed=0（未访谈）也能落库；CAS 0 行 400")
    void interviewCasFail() {
        when(taskMapper.findById(TASK_A)).thenReturn(task(TASK_A, BATCH, WANGXM, "completed"));
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        when(taskMapper.updateInterview(eq(TASK_A), eq(0), isNull(), eq(LISI), any())).thenReturn(1);
        assertOk(service.interview(LISI, "teacher", TASK_A, body("interviewed", 0)));
        verify(taskMapper).updateInterview(eq(TASK_A), eq(0), isNull(), eq(LISI), any());

        when(taskMapper.updateInterview(eq(TASK_A), any(), any(), any(), any())).thenReturn(0);
        Result<?> r = service.interview(LISI, "teacher", TASK_A, body("note", "已面谈"));
        assertCode(r, 400);
        assertMsg(r, "访谈记录写入失败，请刷新后重试");
    }

    @Test
    @DisplayName("91 结果等级只能登记 / 导入：PsycheService 里不存在任何「按分数推导等级」的方法（§9 口径 3）")
    void noLevelInferencePath() {
        for (Method m : PsycheService.class.getDeclaredMethods()) {
            String name = m.getName().toLowerCase();
            assertFalse(name.contains("inferlevel") || name.contains("derivelevel") || name.contains("calclevel")
                            || name.contains("autolevel") || name.contains("judgelevel")
                            || name.contains("guesslevel"),
                    "不应存在按分数推导结果等级的方法：" + m.getName());
        }
    }

    // ================================================================
    // 七、关注台账（§9 口径 6：只对 admin 与该生辅导员可见可写；列表脱敏）
    // ================================================================

    @Test
    @DisplayName("92 listFocus：admin 不带范围（scope=null），教师范围收窄到自己")
    void listFocusScope() {
        when(focusMapper.list(any(), any(), any(), any(), isNull(), anyInt(), anyInt())).thenReturn(new ArrayList<>());
        when(focusMapper.count(any(), any(), any(), any(), isNull())).thenReturn(0);
        assertOk(service.listFocus(ADMIN, "admin", null, null, null, null, 1, 20));
        verify(focusMapper).list(any(), any(), any(), any(), isNull(), eq(0), eq(20));

        when(focusMapper.list(any(), any(), any(), any(), eq(LISI), anyInt(), anyInt())).thenReturn(new ArrayList<>());
        when(focusMapper.count(any(), any(), any(), any(), eq(LISI))).thenReturn(0);
        assertOk(service.listFocus(LISI, "teacher", null, null, null, null, 1, 20));
        verify(focusMapper).list(any(), any(), any(), any(), eq(LISI), eq(0), eq(20));
    }

    @Test
    @DisplayName("93 listFocus：列表脱敏（姓名留姓、学号只留后四位）")
    void listFocusMasksRows() {
        PsycheFocus row = focus(901L, WANGXM, "open");
        row.setStudentName("王小明");
        row.setStudentNo("2024001");
        when(focusMapper.list(any(), any(), any(), any(), isNull(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(row)));
        when(focusMapper.count(any(), any(), any(), any(), isNull())).thenReturn(1);

        Result<?> r = service.listFocus(ADMIN, "admin", null, null, null, null, 1, 20);

        assertOk(r);
        List<PsycheFocus> list = focusList(dataMap(r));
        assertAll(
                () -> assertEquals(1, list.size()),
                () -> assertEquals("王*", list.get(0).getStudentName()),
                () -> assertEquals("4001", list.get(0).getStudentNo()));
    }

    @Test
    @DisplayName("94 listFocus：未登录 401、学生 403（学生看不到自己的关注等级台账）")
    void listFocusGuards() {
        assertAll(
                () -> assertCode(service.listFocus(null, "teacher", null, null, null, null, 1, 20), 401),
                () -> assertCode(service.listFocus(WANGXM, "student", null, null, null, null, 1, 20), 403));
        verifyNoInteractions(focusMapper);
    }

    @Test
    @DisplayName("95 createFocus：学生 id 为空 400「请指定学生」；档案不存在 400「学生不存在」")
    void createFocusValidatesStudent() {
        assertAll(
                () -> assertMsg(service.createFocus(LISI, "teacher", body("summary", "近期情绪低落")), "请指定学生"),
                () -> assertMsg(service.createFocus(LISI, "teacher", body("studentId", WANGXM)), "学生不存在"));
    }

    @Test
    @DisplayName("96 createFocus：跨辅导员 403「只能给自己名下的学生建关注台账」")
    void createFocusCrossCounselorDenied() {
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));

        Result<?> r = service.createFocus(ZHANGSAN, "teacher", body("studentId", WANGXM, "summary", "越权写入"));

        assertCode(r, 403);
        assertMsg(r, "只能给自己名下的学生建关注台账");
        verify(focusMapper, never()).insert(any());
    }

    @Test
    @DisplayName("97 createFocus：关注等级 / 来源非枚举 400")
    void createFocusRejectsUnknownEnums() {
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        assertMsg(service.createFocus(LISI, "teacher", body("studentId", WANGXM, "level", "high")),
                "关注等级只接受 general / key / risk");
        assertMsg(service.createFocus(LISI, "teacher", body("studentId", WANGXM, "source", "weibo")),
                "关注来源只接受 task / apply / counselor / appointment / referral / other");
        verify(focusMapper, never()).insert(any());
    }

    @Test
    @DisplayName("98 createFocus：关注缘由超 500、已采取措施超 1000 → 400")
    void createFocusRejectsLongText() {
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        assertMsg(service.createFocus(LISI, "teacher", body("studentId", WANGXM, "summary", "缘".repeat(501))),
                "关注缘由不能超过 500 个字符");
        assertMsg(service.createFocus(LISI, "teacher",
                body("studentId", WANGXM, "summary", "x", "measures", "措".repeat(1001))),
                "已采取措施不能超过 1000 个字符");
    }

    @Test
    @DisplayName("99 createFocus：成功——等级缺省 general、来源缺省 task、counselorId 取学生档案、status=open")
    void createFocusWritesRow() {
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));

        Result<?> r = service.createFocus(LISI, "teacher", body("studentId", WANGXM, "summary", "近期情绪低落"));

        assertOk(r);
        ArgumentCaptor<PsycheFocus> cap = ArgumentCaptor.forClass(PsycheFocus.class);
        verify(focusMapper).insert(cap.capture());
        PsycheFocus created = cap.getValue();
        assertAll(
                () -> assertEquals(WANGXM, created.getStudentId().longValue()),
                () -> assertEquals("general", created.getLevel()),
                () -> assertEquals("task", created.getSource()),
                () -> assertEquals("open", created.getStatus()),
                () -> assertEquals(LISI, created.getCounselorId().longValue(), "跟随学生档案的 counselor_id"),
                () -> assertEquals("近期情绪低落", created.getSummary()),
                () -> assertNotNull(created.getOpenedAt()));
    }

    @Test
    @DisplayName("100 updateFocus：台账不存在 404「关注台账不存在」；跨辅导员 403「只能改自己名下学生的关注台账」")
    void updateFocusNotFoundAndCrossCounselor() {
        assertMsg(service.updateFocus(LISI, "teacher", 999L, body("status", "following")), "关注台账不存在");

        when(focusMapper.findById(901L)).thenReturn(focus(901L, WANGXM, "open"));
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        Result<?> denied = service.updateFocus(ZHANGSAN, "teacher", 901L, body("status", "following"));
        assertCode(denied, 403);
        assertMsg(denied, "只能改自己名下学生的关注台账");
        verify(focusMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("101 updateFocus：状态非枚举 400「台账状态只接受 open / following / closed」")
    void updateFocusRejectsUnknownStatus() {
        when(focusMapper.findById(901L)).thenReturn(focus(901L, WANGXM, "open"));
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));

        Result<?> r = service.updateFocus(LISI, "teacher", 901L, body("status", "done"));

        assertCode(r, 400);
        assertMsg(r, "台账状态只接受 open / following / closed");
    }

    @Test
    @DisplayName("102 updateFocus：closed 时补 closedAt，CAS 带上原状态")
    void updateFocusClosesWithTimestamp() {
        when(focusMapper.findById(901L)).thenReturn(focus(901L, WANGXM, "following"));
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        when(focusMapper.update(any(), eq("following"))).thenReturn(1);

        Result<?> r = service.updateFocus(LISI, "teacher", 901L, body("status", "closed"));

        assertOk(r);
        ArgumentCaptor<PsycheFocus> cap = ArgumentCaptor.forClass(PsycheFocus.class);
        verify(focusMapper).update(cap.capture(), eq("following"));
        assertAll(
                () -> assertEquals("closed", cap.getValue().getStatus()),
                () -> assertNotNull(cap.getValue().getClosedAt(), "closed 应补 closedAt"));
    }

    @Test
    @DisplayName("103 updateFocus：CAS 0 行 400「状态已变化，请刷新后重试」")
    void updateFocusCasFail() {
        when(focusMapper.findById(901L)).thenReturn(focus(901L, WANGXM, "open"));
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        when(focusMapper.update(any(), any())).thenReturn(0);

        Result<?> r = service.updateFocus(LISI, "teacher", 901L, body("status", "following"));

        assertCode(r, 400);
        assertMsg(r, CAS_FAIL);
    }

    @Test
    @DisplayName("104 addFollowup：台账不存在 404、跨辅导员 403「只能给自己名下学生的台账登记跟进」")
    void addFollowupNotFoundAndCrossCounselor() {
        assertMsg(service.addFollowup(LISI, "teacher", 999L, body("content", "已电话联系")), "关注台账不存在");

        when(focusMapper.findById(901L)).thenReturn(focus(901L, WANGXM, "open"));
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        Result<?> denied = service.addFollowup(ZHANGSAN, "teacher", 901L, body("content", "越权跟进"));
        assertCode(denied, 403);
        assertMsg(denied, "只能给自己名下学生的台账登记跟进");
        verify(followupMapper, never()).insert(any());
    }

    @Test
    @DisplayName("105 addFollowup：跟进内容为空 400、超 1000 字 400")
    void addFollowupValidatesContent() {
        when(focusMapper.findById(901L)).thenReturn(focus(901L, WANGXM, "open"));
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        assertMsg(service.addFollowup(LISI, "teacher", 901L, body()), "请填写跟进内容");
        assertMsg(service.addFollowup(LISI, "teacher", 901L, body("content", "跟".repeat(1001))),
                "跟进内容不能超过 1000 个字符");
        verify(followupMapper, never()).insert(any());
    }

    @Test
    @DisplayName("106 addFollowup：成功——插跟进并把台账推进 following，返回跟进串与最新台账")
    void addFollowupWritesAndMarksFollowing() {
        when(focusMapper.findById(901L)).thenReturn(focus(901L, WANGXM, "open"));
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        PsycheFollowup saved = new PsycheFollowup();
        saved.setId(950L);
        saved.setFocusId(901L);
        saved.setContent("已电话联系，学生情绪平稳");
        when(followupMapper.listByFocus(901L)).thenReturn(new ArrayList<>(List.of(saved)));

        Result<?> r = service.addFollowup(LISI, "teacher", 901L,
                body("content", "已电话联系，学生情绪平稳", "nextPlan", "两周后回访"));

        assertOk(r);
        Map<String, Object> data = dataMap(r);
        ArgumentCaptor<PsycheFollowup> cap = ArgumentCaptor.forClass(PsycheFollowup.class);
        verify(followupMapper).insert(cap.capture());
        assertAll(
                () -> assertEquals(901L, cap.getValue().getFocusId().longValue()),
                () -> assertEquals("已电话联系，学生情绪平稳", cap.getValue().getContent()),
                () -> assertEquals("两周后回访", cap.getValue().getNextPlan()),
                () -> assertEquals(LISI, cap.getValue().getOperatorId().longValue()),
                () -> assertNotNull(cap.getValue().getHappenedAt()),
                () -> assertNotNull(data.get("followup")),
                () -> assertNotNull(data.get("focus")),
                () -> assertEquals(1, mapList(data.get("followups")).size()));
        verify(focusMapper).markFollowing(901L);
    }

    // ================================================================
    // 八、转介（§9 口径 6：同样只对 admin 与该生辅导员可见可写；列表脱敏）
    // ================================================================

    @Test
    @DisplayName("107 listReferrals：教师 scope=自己、admin 不带范围，列表脱敏")
    void listReferralsScopeAndMask() {
        PsycheReferral row = referral(902L, WANGXM, "open");
        row.setStudentName("王小明");
        row.setStudentNo("2024001");
        when(referralMapper.list(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(row)));
        when(referralMapper.count(any(), any(), any())).thenReturn(1);

        Result<?> r = service.listReferrals(LISI, "teacher", null, null, 1, 20);

        assertOk(r);
        List<PsycheReferral> list = referralList(dataMap(r));
        assertAll(
                () -> assertEquals("王*", list.get(0).getStudentName()),
                () -> assertEquals("4001", list.get(0).getStudentNo()));
        verify(referralMapper).list(isNull(), isNull(), eq(LISI), eq(0), eq(20));
    }

    @Test
    @DisplayName("108 listReferrals：未登录 401、学生 403")
    void listReferralsGuards() {
        assertAll(
                () -> assertCode(service.listReferrals(null, "teacher", null, null, 1, 20), 401),
                () -> assertCode(service.listReferrals(WANGXM, "student", null, null, 1, 20), 403));
        verifyNoInteractions(referralMapper);
    }

    @Test
    @DisplayName("109 createReferral：学生 id 为空 400、档案不存在 400「学生不存在」")
    void createReferralValidatesStudent() {
        assertAll(
                () -> assertMsg(service.createReferral(LISI, "teacher", body("reason", "需住院治疗")), "请指定学生"),
                () -> assertMsg(service.createReferral(LISI, "teacher", body("studentId", WANGXM)), "学生不存在"));
    }

    @Test
    @DisplayName("110 createReferral：跨辅导员 403「只能给自己名下的学生登记转介」")
    void createReferralCrossCounselorDenied() {
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));

        Result<?> r = service.createReferral(ZHANGSAN, "teacher", body("studentId", WANGXM, "reason", "越权转介"));

        assertCode(r, 403);
        assertMsg(r, "只能给自己名下的学生登记转介");
        verify(referralMapper, never()).insert(any());
    }

    @Test
    @DisplayName("111 createReferral：来源 / 去向非枚举 400")
    void createReferralRejectsUnknownEnums() {
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        assertMsg(service.createReferral(LISI, "teacher", body("studentId", WANGXM, "sourceType", "guess")),
                "转介来源只接受 task / focus / appointment / other");
        assertMsg(service.createReferral(LISI, "teacher", body("studentId", WANGXM, "target", "internet")),
                "转介去向只接受 campus / hospital / family");
        verify(referralMapper, never()).insert(any());
    }

    @Test
    @DisplayName("112 createReferral：成功——来源缺省 focus、去向缺省 campus、status=open、referredBy=操作人")
    void createReferralWritesRow() {
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));

        Result<?> r = service.createReferral(LISI, "teacher",
                body("studentId", WANGXM, "reason", "情绪持续低落，建议到中心面谈"));

        assertOk(r);
        ArgumentCaptor<PsycheReferral> cap = ArgumentCaptor.forClass(PsycheReferral.class);
        verify(referralMapper).insert(cap.capture());
        PsycheReferral created = cap.getValue();
        assertAll(
                () -> assertEquals(WANGXM, created.getStudentId().longValue()),
                () -> assertEquals("focus", created.getSourceType()),
                () -> assertEquals("campus", created.getTarget()),
                () -> assertEquals("open", created.getStatus()),
                () -> assertEquals(LISI, created.getReferredBy().longValue()),
                () -> assertNotNull(created.getReferredAt()));
    }

    @Test
    @DisplayName("113 updateReferral：不存在 404「转介记录不存在」、跨辅导员 403「只能改自己名下学生的转介记录」")
    void updateReferralNotFoundAndCrossCounselor() {
        assertMsg(service.updateReferral(LISI, "teacher", 999L, body("status", "following")), "转介记录不存在");

        when(referralMapper.findById(902L)).thenReturn(referral(902L, WANGXM, "open"));
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        Result<?> denied = service.updateReferral(ZHANGSAN, "teacher", 902L, body("status", "following"));
        assertCode(denied, 403);
        assertMsg(denied, "只能改自己名下学生的转介记录");
        verify(referralMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("114 updateReferral：状态非枚举 400「转介状态只接受 open / following / closed」")
    void updateReferralRejectsUnknownStatus() {
        when(referralMapper.findById(902L)).thenReturn(referral(902L, WANGXM, "open"));
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));

        Result<?> r = service.updateReferral(LISI, "teacher", 902L, body("status", "closedx"));

        assertCode(r, 400);
        assertMsg(r, "转介状态只接受 open / following / closed");
    }

    @Test
    @DisplayName("115 updateReferral：closed 补 closedAt、CAS 带原状态；0 行 400")
    void updateReferralCloseAndCasFail() {
        when(referralMapper.findById(902L)).thenReturn(referral(902L, WANGXM, "following"));
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        when(referralMapper.update(any(), eq("following"))).thenReturn(1);

        Result<?> ok = service.updateReferral(LISI, "teacher", 902L, body("status", "closed"));

        assertOk(ok);
        ArgumentCaptor<PsycheReferral> cap = ArgumentCaptor.forClass(PsycheReferral.class);
        verify(referralMapper).update(cap.capture(), eq("following"));
        assertAll(
                () -> assertEquals("closed", cap.getValue().getStatus()),
                () -> assertNotNull(cap.getValue().getClosedAt(), "closed 应补 closedAt"));

        when(referralMapper.update(any(), any())).thenReturn(0);
        Result<?> fail = service.updateReferral(LISI, "teacher", 902L, body("status", "closed"));
        assertCode(fail, 400);
        assertMsg(fail, CAS_FAIL);
    }

    // ================================================================
    // 九、统计（§9 口径 8：只回聚合数，不回可反查个人的明细）
    // ================================================================

    @Test
    @DisplayName("116 batchStats：未登录 401、缺 batchId 400「请指定批次」、批次不存在 404")
    void batchStatsGuards() {
        assertAll(
                () -> assertCode(service.batchStats(null, "admin", BATCH), 401),
                () -> assertCode(service.batchStats(ADMIN, "admin", null), 400),
                () -> assertCode(service.batchStats(ADMIN, "admin", 999L), 404));
    }

    @Test
    @DisplayName("117 batchStats：只出聚合块（状态计数 / 班级 / 专业 / 关注等级），不含任何个体明细")
    void batchStatsAggregatesOnly() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "open"));
        when(taskMapper.countGroupByStatus(BATCH)).thenReturn(List.of(
                countRow("status", "completed", 3),
                countRow("status", "pending", 1)));
        when(taskMapper.countGroupByClass(BATCH)).thenReturn(List.of(groupCountRow("class_name", "软工2401", 4, 3)));
        when(taskMapper.countGroupByMajor(BATCH)).thenReturn(List.of(groupCountRow("major", "软件工程", 4, 3)));
        when(focusMapper.countGroupByLevel(isNull(), eq(BATCH))).thenReturn(List.of(countRow("level", "key", 2)));

        Result<?> r = service.batchStats(ADMIN, "admin", BATCH);

        assertOk(r);
        Map<String, Object> data = dataMap(r);
        assertAll(
                () -> assertEquals(4, intOf(data.get("total"))),
                () -> assertEquals(3, intOf(data.get("completed"))),
                () -> assertEquals(0.7500, ((BigDecimal) data.get("completionRate")).doubleValue()),
                () -> assertEquals(75.00, ((BigDecimal) data.get("completionPercent")).doubleValue()),
                () -> assertEquals(1, mapList(data.get("byClass")).size()),
                () -> assertEquals("软工2401", mapList(data.get("byClass")).get(0).get("name")),
                () -> assertEquals(2, intOf(((Map<String, Object>) data.get("focusLevels")).get("key"))));
        assertNoIndividualRows(data, "batchStats");
    }

    @Test
    @DisplayName("118 stats：admin 全校聚合——批次完成率 + 班级 / 专业 / 关注等级 / 预约量，无个体明细")
    void statsAggregatesOnly() {
        when(batchMapper.list(eq(false), isNull(), isNull()))
                .thenReturn(new ArrayList<>(List.of(batch(BATCH, "open"))));
        when(taskMapper.countGroupByStatus(BATCH)).thenReturn(List.of(
                countRow("status", "completed", 2),
                countRow("status", "absent", 2)));
        when(taskMapper.countGroupByClass(isNull())).thenReturn(List.of(groupCountRow("class_name", "计科2401", 4, 2)));
        when(taskMapper.countGroupByMajor(isNull())).thenReturn(List.of(groupCountRow("major", "计算机", 4, 2)));
        when(focusMapper.countGroupByLevel(isNull(), isNull())).thenReturn(List.of(countRow("level", "risk", 1)));
        when(appointmentMapper.countGroupByStatus(isNull())).thenReturn(List.of(
                countRow("status", "done", 3),
                countRow("status", "cancelled", 1)));

        Result<?> r = service.stats(ADMIN, "admin");

        assertOk(r);
        Map<String, Object> data = dataMap(r);
        assertAll(
                () -> assertEquals(1, mapList(data.get("batches")).size()),
                () -> assertEquals(4, intOf(mapList(data.get("batches")).get(0).get("total"))),
                () -> assertEquals(0.5000, ((BigDecimal) mapList(data.get("batches")).get(0).get("completionRate"))
                        .doubleValue()),
                () -> assertEquals(1, intOf(((Map<String, Object>) data.get("focusLevels")).get("risk"))),
                () -> assertEquals(4, intOf(((Map<String, Object>) data.get("appointments")).get("total"))),
                () -> assertEquals(75.00, ((BigDecimal) ((Map<String, Object>) data.get("appointments"))
                        .get("completionPercent")).doubleValue()));
        assertNoIndividualRows(data, "stats");
    }

    @Test
    @DisplayName("119 stats：教师 scope 收窄到自己名下（关注等级与预约都带自己的 scope）")
    void statsNarrowsTeacherScope() {
        when(batchMapper.list(eq(true), isNull(), isNull())).thenReturn(new ArrayList<>());
        when(taskMapper.countGroupByClass(isNull())).thenReturn(new ArrayList<>());
        when(taskMapper.countGroupByMajor(isNull())).thenReturn(new ArrayList<>());
        when(focusMapper.countGroupByLevel(eq(LISI), isNull())).thenReturn(new ArrayList<>());
        when(appointmentMapper.countGroupByStatus(eq(LISI))).thenReturn(new ArrayList<>());

        assertOk(service.stats(LISI, "teacher"));

        verify(focusMapper).countGroupByLevel(eq(LISI), isNull());
        verify(appointmentMapper).countGroupByStatus(eq(LISI));
    }

    @Test
    @DisplayName("120 stats：未登录 401、学生 403「无权访问」")
    void statsGuards() {
        assertAll(
                () -> assertCode(service.stats(null, "admin"), 401),
                () -> assertCode(service.stats(WANGXM, "student"), 403));
        verifyNoInteractions(appointmentMapper);
    }

    // ================================================================
    // 十、预约（PsycheAppointmentService —— S16 时间制度 / S17 线上确认与续约）
    // ================================================================

    @Test
    @DisplayName("121 create：未登录 401「请先登录」、教师 403「仅学生可用」")
    void createAppointmentGuards() {
        assertAll(
                () -> assertCode(appointmentService.create(null, "student", body()), 401),
                () -> assertCode(appointmentService.create(LISI, "teacher", body()), 403));
        verifyNoInteractions(appointmentMapper);
    }

    @Test
    @DisplayName("122 create：预约方式非枚举 400「预约方式只接受 online / office」")
    void createAppointmentRejectsUnknownMode() {
        Result<?> r = appointmentService.create(WANGXM, "student",
                body("mode", "wechat", "mainIssue", "睡不好", "contactPhone", "13800000004"));
        assertCode(r, 400);
        assertMsg(r, "预约方式只接受 online / office");
    }

    @Test
    @DisplayName("123 create：主要咨询问题为空 / 超 500 字 400")
    void createAppointmentValidatesMainIssue() {
        assertMsg(appointmentService.create(WANGXM, "student", body("contactPhone", "13800000004")),
                "请填写主要咨询问题");
        assertMsg(appointmentService.create(WANGXM, "student",
                body("mainIssue", "问".repeat(501), "contactPhone", "13800000004")),
                "主要咨询问题不能超过 500 个字符");
    }

    @Test
    @DisplayName("124 create：联系方式为空 400「请填写联系方式」、超 32 字 400")
    void createAppointmentValidatesPhone() {
        assertMsg(appointmentService.create(WANGXM, "student", body("mainIssue", "睡不好")), "请填写联系方式");
        assertMsg(appointmentService.create(WANGXM, "student",
                body("mainIssue", "睡不好", "contactPhone", "1".repeat(33))),
                "联系方式不能超过 32 个字符");
    }

    @Test
    @DisplayName("125 create：期望时间缺失 / 格式错 400「请选择期望咨询时间（yyyy-MM-dd HH:mm:ss）」")
    void createAppointmentValidatesExpectStart() {
        assertMsg(appointmentService.create(WANGXM, "student",
                body("mainIssue", "睡不好", "contactPhone", "13800000004")),
                "请选择期望咨询时间（yyyy-MM-dd HH:mm:ss）");
        assertMsg(appointmentService.create(WANGXM, "student",
                body("mainIssue", "睡不好", "contactPhone", "13800000004", "expectStart", "下周三下午")),
                "请选择期望咨询时间（yyyy-MM-dd HH:mm:ss）");
    }

    @Test
    @DisplayName("126 create：时长越界 400「单次咨询时长必须在 1~240 分钟之间（默认 50 分钟）」")
    void createAppointmentValidatesDuration() {
        LocalDateTime start = workdayAt(2, 10, 0);
        assertMsg(appointmentService.create(WANGXM, "student", body("mainIssue", "睡不好",
                "contactPhone", "13800000004", "expectStart", FMT.format(start), "duration_minutes", 241)),
                "单次咨询时长必须在 1~240 分钟之间（默认 50 分钟）");
        assertMsg(appointmentService.create(WANGXM, "student", body("mainIssue", "睡不好",
                "contactPhone", "13800000004", "expectStart", FMT.format(start), "expectedMinutes", 0)),
                "单次咨询时长必须在 1~240 分钟之间（默认 50 分钟）");
    }

    @Test
    @DisplayName("127 create：不足「提前 1 个工作日」400，且文案含紧急电话 0596-6288525（S16）")
    void createAppointmentRejectsLessThanOneWorkingDay() {
        LocalDateTime boundary = PsycheAppointmentService.addWorkingDays(LocalDateTime.now().withNano(0), 1);
        LocalDateTime tooEarly = boundary.minusMinutes(1);

        Result<?> r = appointmentService.create(WANGXM, "student", body("mainIssue", "睡不好",
                "contactPhone", "13800000004", "expectStart", FMT.format(tooEarly)));

        assertCode(r, 400);
        assertMsg(r, ADVANCE_MSG);
        assertTrue(r.getMessage().contains("0596-6288525"), "提前量不足时要给出紧急联系电话");
        verify(appointmentMapper, never()).insert(any());
    }

    @Test
    @DisplayName("128 create：恰好「提前 1 个工作日」不再被提前量拦截（落到服务时段判定）")
    void createAppointmentAcceptsExactWorkingDayBoundary() {
        LocalDateTime boundary = PsycheAppointmentService.addWorkingDays(LocalDateTime.now().withNano(0), 1);
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        when(appointmentMapper.countPendingByStudent(WANGXM)).thenReturn(0);
        when(appointmentMapper.findById(any()))
                .thenReturn(appointment(901L, WANGXM, LISI, "office", "requested", boundary));

        Result<?> r = appointmentService.create(WANGXM, "student", body("mainIssue", "睡不好",
                "contactPhone", "13800000004", "expectStart", FMT.format(boundary)));

        assertNotNull(r);
        assertFalse(ADVANCE_MSG.equals(r.getMessage()), "恰好 1 个工作日应通过提前量判定，实际：" + r.getMessage());
        assertTrue(ok(r) || WINDOW_MSG.equals(r.getMessage()),
                "边界时刻只应被服务时段拦下，实际：" + r.getCode() + " " + r.getMessage());
    }

    @Test
    @DisplayName("129 create：满足提前量 → 成功，时长缺省 50 分钟、expectEnd 自动推算、咨询师取学生档案")
    void createAppointmentSucceeds() {
        LocalDateTime start = workdayAt(2, 10, 0);
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        when(appointmentMapper.countPendingByStudent(WANGXM)).thenReturn(0);
        when(appointmentMapper.findById(any()))
                .thenReturn(appointment(901L, WANGXM, LISI, "office", "requested", start));

        Result<?> r = appointmentService.create(WANGXM, "student", body("mainIssue", "最近睡不好",
                "contactPhone", "13800000004", "expectStart", FMT.format(start)));

        assertOk(r);
        ArgumentCaptor<PsycheAppointment> cap = ArgumentCaptor.forClass(PsycheAppointment.class);
        verify(appointmentMapper).insert(cap.capture());
        PsycheAppointment created = cap.getValue();
        assertAll(
                () -> assertEquals(WANGXM, created.getStudentId().longValue()),
                () -> assertEquals(LISI, created.getCounselorId().longValue(), "咨询师取学生档案的 counselor_id"),
                () -> assertEquals("office", created.getMode(), "方式缺省 office"),
                () -> assertEquals(Integer.valueOf(50), created.getDurationMinutes(), "duration 缺省 50 分钟"),
                () -> assertEquals(start, created.getExpectStart()),
                () -> assertEquals(start.plusMinutes(50), created.getExpectEnd()),
                () -> assertEquals("requested", created.getStatus()),
                () -> assertEquals("最近睡不好", created.getMainIssue()));
    }

    @Test
    @DisplayName("130 create：线上咨询（online）同样可提交——「提前半天」不在提交环节拦截（S17）")
    void createOnlineAppointmentIsNotBlockedByHalfDayRule() {
        LocalDateTime start = workdayAt(2, 14, 0);
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        when(appointmentMapper.countPendingByStudent(WANGXM)).thenReturn(0);
        when(appointmentMapper.findById(any()))
                .thenReturn(appointment(901L, WANGXM, LISI, "online", "requested", start));

        Result<?> r = appointmentService.create(WANGXM, "student", body("mode", "online", "mainIssue", "睡不好",
                "contactPhone", "13800000004", "expectStart", FMT.format(start)));

        assertOk(r);
        ArgumentCaptor<PsycheAppointment> cap = ArgumentCaptor.forClass(PsycheAppointment.class);
        verify(appointmentMapper).insert(cap.capture());
        assertEquals("online", cap.getValue().getMode());
    }

    @Test
    @DisplayName("131 create：工作日 7:00 与周末 10:00 → 400「心理咨询预约时间为周一至周五 8:00-17:00」")
    void createAppointmentRejectsOutOfServiceWindow() {
        LocalDateTime beforeWork = workdayAt(2, 7, 0);
        Result<?> early = appointmentService.create(WANGXM, "student", body("mainIssue", "睡不好",
                "contactPhone", "13800000004", "expectStart", FMT.format(beforeWork)));
        assertCode(early, 400);
        assertMsg(early, WINDOW_MSG);

        LocalDateTime weekend = nextSaturdayWindow();
        Result<?> sat = appointmentService.create(WANGXM, "student", body("mainIssue", "睡不好",
                "contactPhone", "13800000004", "expectStart", FMT.format(weekend)));
        assertCode(sat, 400);
        assertMsg(sat, WINDOW_MSG);
        verify(appointmentMapper, never()).insert(any());
    }

    @Test
    @DisplayName("132 create：17:00 整仍算服务时段内（含边界）")
    void createAppointmentAcceptsWindowEdge() {
        LocalDateTime edge = workdayAt(2, 17, 0);
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        when(appointmentMapper.countPendingByStudent(WANGXM)).thenReturn(0);
        when(appointmentMapper.findById(any()))
                .thenReturn(appointment(901L, WANGXM, LISI, "office", "requested", edge));

        assertOk(appointmentService.create(WANGXM, "student", body("mainIssue", "睡不好",
                "contactPhone", "13800000004", "expectStart", FMT.format(edge))));
    }

    @Test
    @DisplayName("133 create：已有一条待处理预约 400「你已有待处理的预约」")
    void createAppointmentRejectsPendingDuplicate() {
        LocalDateTime start = workdayAt(2, 10, 0);
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        when(appointmentMapper.countPendingByStudent(WANGXM)).thenReturn(1);

        Result<?> r = appointmentService.create(WANGXM, "student", body("mainIssue", "睡不好",
                "contactPhone", "13800000004", "expectStart", FMT.format(start)));

        assertCode(r, 400);
        assertMsg(r, PENDING_MSG);
        verify(appointmentMapper, never()).insert(any());
    }

    @Test
    @DisplayName("134 addWorkingDays：跳过周六周日（周五+1=周一、+3=周三、周六+1=周一）")
    void addWorkingDaysSkipsWeekend() {
        LocalDate friday = LocalDate.of(2024, 3, 8);
        assertAll(
                () -> assertEquals(LocalDate.of(2024, 3, 11), PsycheAppointmentService.addWorkingDays(friday, 1)),
                () -> assertEquals(LocalDate.of(2024, 3, 13), PsycheAppointmentService.addWorkingDays(friday, 3)),
                () -> assertEquals(LocalDate.of(2024, 3, 11),
                        PsycheAppointmentService.addWorkingDays(LocalDate.of(2024, 3, 9), 1)),
                () -> assertEquals(LocalDate.of(2024, 3, 11),
                        PsycheAppointmentService.addWorkingDays(LocalDate.of(2024, 3, 10), 1)),
                () -> assertEquals(friday, PsycheAppointmentService.addWorkingDays(friday, 0)));
    }

    @Test
    @DisplayName("135 addWorkingDays(LocalDateTime)：保留时分秒、丢弃纳秒")
    void addWorkingDaysKeepsTimeOfDay() {
        LocalDateTime start = LocalDateTime.of(2024, 3, 8, 15, 30, 45, 123456789);
        assertEquals(LocalDateTime.of(2024, 3, 11, 15, 30, 45),
                PsycheAppointmentService.addWorkingDays(start, 1));
    }

    @Test
    @DisplayName("136 myList：学生只看自己的预约且 note 被抹掉；教师 403「仅学生可用」")
    void myListHidesInternalNote() {
        PsycheAppointment mine = appointment(901L, WANGXM, LISI, "office", "requested", pastWindowTime());
        mine.setNote("中心内部备注：家长已联系");
        when(appointmentMapper.listByStudent(WANGXM)).thenReturn(new ArrayList<>(List.of(mine)));

        Result<?> r = appointmentService.myList(WANGXM, "student");

        assertOk(r);
        @SuppressWarnings("unchecked")
        List<PsycheAppointment> list = (List<PsycheAppointment>) r.getData();
        assertAll(
                () -> assertEquals(1, list.size()),
                () -> assertNull(list.get(0).getNote(), "学生侧不下发中心内部备注"));
        Result<?> denied = appointmentService.myList(LISI, "teacher");
        assertCode(denied, 403);
        assertMsg(denied, "仅学生可用");
    }

    @Test
    @DisplayName("137 list：教师 scope=自己、姓名学号脱敏、抹掉咨询问题全文与内部备注")
    void listMasksAndDropsMainIssue() {
        PsycheAppointment row = appointment(901L, WANGXM, LISI, "office", "requested", pastWindowTime());
        row.setStudentName("王小明");
        row.setStudentNo("2024001");
        when(appointmentMapper.list(isNull(), isNull(), isNull(), eq(LISI), eq(0), eq(20)))
                .thenReturn(new ArrayList<>(List.of(row)));
        when(appointmentMapper.count(isNull(), isNull(), isNull(), eq(LISI))).thenReturn(1);

        Result<?> r = appointmentService.list(LISI, "teacher", null, null, null, 1, 20);

        assertOk(r);
        List<PsycheAppointment> list = appointmentList(dataMap(r));
        assertAll(
                () -> assertEquals("王*", list.get(0).getStudentName()),
                () -> assertEquals("4001", list.get(0).getStudentNo()),
                () -> assertNull(list.get(0).getMainIssue(), "列表不下发主要咨询问题全文"),
                () -> assertNull(list.get(0).getNote(), "列表不下发中心内部备注"),
                () -> assertEquals(1, intOf(dataMap(r).get("total"))));
    }

    @Test
    @DisplayName("138 list：未登录 401、学生 403「无权访问」")
    void listAppointmentsGuards() {
        assertAll(
                () -> assertCode(appointmentService.list(null, "teacher", null, null, null, 1, 20), 401),
                () -> assertCode(appointmentService.list(WANGXM, "student", null, null, null, 1, 20), 403));
        verifyNoInteractions(appointmentMapper);
    }

    @Test
    @DisplayName("139 confirm：不存在 404、跨辅导员 403「只能处理名下学生的预约」、CAS 0 行 400")
    void confirmAppointmentGuards() {
        assertMsg(appointmentService.confirm(LISI, "teacher", 999L), "预约不存在");

        when(appointmentMapper.findById(901L))
                .thenReturn(appointment(901L, WANGXM, LISI, "office", "requested", pastWindowTime()));
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        Result<?> denied = appointmentService.confirm(ZHANGSAN, "teacher", 901L);
        assertCode(denied, 403);
        assertMsg(denied, "只能处理名下学生的预约");

        when(appointmentMapper.confirm(anyLong(), any(), any(), anyLong())).thenReturn(0);
        Result<?> fail = appointmentService.confirm(LISI, "teacher", 901L);
        assertCode(fail, 400);
        assertMsg(fail, CAS_FAIL);
    }

    @Test
    @DisplayName("140 confirm：距开始不足半天仍确认成功（非阻塞），只把 S17 提示放进 message")
    void confirmTooLateIsNonBlocking() {
        LocalDateTime soon = LocalDateTime.now().withNano(0).plusHours(1);
        PsycheAppointment ap = appointment(901L, WANGXM, LISI, "online", "requested", soon);
        PsycheAppointment updated = appointment(901L, WANGXM, LISI, "online", "confirmed", soon);
        when(appointmentMapper.findById(901L)).thenReturn(ap, updated);
        when(appointmentMapper.confirm(eq(901L), eq("requested"), any(), eq(LISI))).thenReturn(1);

        Result<?> r = appointmentService.confirm(LISI, "teacher", 901L);

        assertOk(r);
        assertEquals("已确认预约；" + HALF_DAY_MSG, r.getMessage());
    }

    @Test
    @DisplayName("141 confirm：距开始超过半天 → 200 且 message 为 success（无额外提示）")
    void confirmInTimeHasNoHint() {
        LocalDateTime later = LocalDateTime.now().withNano(0).plusHours(20);
        PsycheAppointment ap = appointment(901L, WANGXM, LISI, "office", "requested", later);
        when(appointmentMapper.findById(901L)).thenReturn(ap, ap);
        when(appointmentMapper.confirm(eq(901L), eq("requested"), any(), eq(LISI))).thenReturn(1);

        Result<?> r = appointmentService.confirm(LISI, "teacher", 901L);

        assertOk(r);
        assertEquals("success", r.getMessage());
    }

    @Test
    @DisplayName("142 complete：不存在 404、跨辅导员 403、成功 200（CAS confirmed→done）")
    void completeAppointmentFlow() {
        assertMsg(appointmentService.complete(LISI, "teacher", 999L), "预约不存在");

        PsycheAppointment ap = appointment(901L, WANGXM, LISI, "office", "confirmed", pastWindowTime());
        when(appointmentMapper.findById(901L)).thenReturn(ap);
        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        Result<?> denied = appointmentService.complete(ZHANGSAN, "teacher", 901L);
        assertCode(denied, 403);

        when(appointmentMapper.complete(eq(901L), eq("confirmed"), any())).thenReturn(1);
        assertOk(appointmentService.complete(LISI, "teacher", 901L));
        verify(appointmentMapper).complete(eq(901L), eq("confirmed"), any());
    }

    @Test
    @DisplayName("143 cancel：学生取消自己的预约，原因缺省「学生取消」，返回体不含中心备注")
    void cancelByOwnerUsesDefaultReason() {
        PsycheAppointment ap = appointment(901L, WANGXM, LISI, "office", "requested", pastWindowTime());
        PsycheAppointment cancelled = appointment(901L, WANGXM, LISI, "office", "cancelled", pastWindowTime());
        cancelled.setNote("中心内部备注");
        when(appointmentMapper.findById(901L)).thenReturn(ap, cancelled);
        when(appointmentMapper.cancel(eq(901L), eq("requested"), eq("学生取消"))).thenReturn(1);

        Result<?> r = appointmentService.cancel(WANGXM, "student", 901L, body());

        assertOk(r);
        assertNull(((PsycheAppointment) r.getData()).getNote(), "学生侧不下发中心内部备注");
        verify(appointmentMapper).cancel(eq(901L), eq("requested"), eq("学生取消"));
    }

    @Test
    @DisplayName("144 cancel：他人且非 staff 403「无权取消该预约」；staff 非名下 403「只能处理名下学生的预约」")
    void cancelPermissionDenied() {
        PsycheAppointment ap = appointment(901L, WANGXM, LISI, "office", "requested", pastWindowTime());
        when(appointmentMapper.findById(901L)).thenReturn(ap);

        Result<?> other = appointmentService.cancel(LIUSQ, "student", 901L, body());
        assertCode(other, 403);
        assertMsg(other, "无权取消该预约");

        when(profileMapper.findByUserId(WANGXM)).thenReturn(profile(WANGXM, LISI));
        Result<?> staff = appointmentService.cancel(ZHANGSAN, "teacher", 901L, body());
        assertCode(staff, 403);
        assertMsg(staff, "只能处理名下学生的预约");
        verify(appointmentMapper, never()).cancel(anyLong(), any(), any());
    }

    @Test
    @DisplayName("145 cancel：不存在 404、未登录 401、原因超 500 字 400、CAS 0 行 400")
    void cancelEdgeCases() {
        assertCode(appointmentService.cancel(null, "student", 901L, body()), 401);
        assertMsg(appointmentService.cancel(WANGXM, "student", 999L, body()), "预约不存在");

        PsycheAppointment ap = appointment(901L, WANGXM, LISI, "office", "requested", pastWindowTime());
        when(appointmentMapper.findById(901L)).thenReturn(ap);
        assertMsg(appointmentService.cancel(WANGXM, "student", 901L, body("cancelReason", "因".repeat(501))),
                "取消原因不能超过 500 个字符");

        when(appointmentMapper.cancel(anyLong(), any(), any())).thenReturn(0);
        Result<?> fail = appointmentService.cancel(WANGXM, "student", 901L, body("reason", "临时有事"));
        assertCode(fail, 400);
        assertMsg(fail, CAS_FAIL);
    }

    @Test
    @DisplayName("146 renew：不存在 404、非本人 403「只能续约自己的预约」、已取消不能续约")
    void renewGuards() {
        assertMsg(appointmentService.renew(WANGXM, "student", 999L, body()), "预约不存在");

        PsycheAppointment others = appointment(901L, LIUSQ, ZHANGSAN, "office", "confirmed", pastWindowTime());
        when(appointmentMapper.findById(901L)).thenReturn(others);
        Result<?> denied = appointmentService.renew(WANGXM, "student", 901L, body());
        assertCode(denied, 403);
        assertMsg(denied, "只能续约自己的预约");

        PsycheAppointment cancelled = appointment(901L, WANGXM, LISI, "office", "cancelled", pastWindowTime());
        when(appointmentMapper.findById(901L)).thenReturn(cancelled);
        Result<?> no = appointmentService.renew(WANGXM, "student", 901L, body());
        assertCode(no, 400);
        assertMsg(no, "已取消的预约不能续约");
    }

    @Test
    @DisplayName("147 renew：续约时间仍必须落在周一至周五 8:00-17:00")
    void renewStillRequiresServiceWindow() {
        PsycheAppointment origin = appointment(901L, WANGXM, LISI, "online", "confirmed", pastWindowTime());
        when(appointmentMapper.findById(901L)).thenReturn(origin);

        Result<?> r = appointmentService.renew(WANGXM, "student", 901L,
                body("expectStart", FMT.format(nextSaturdayWindow())));

        assertCode(r, 400);
        assertMsg(r, WINDOW_MSG);
        verify(appointmentMapper, never()).insert(any());
    }

    @Test
    @DisplayName("148 renew：成功——沿用同一咨询师与方式、写 renewed_from、继承时长与咨询问题")
    void renewKeepsSameCounselor() {
        PsycheAppointment origin = appointment(901L, WANGXM, LISI, "online", "confirmed", pastWindowTime());
        origin.setDurationMinutes(60);
        origin.setMainIssue("上次聊到考试焦虑");
        origin.setContactPhone("13800000004");
        LocalDateTime start = workdayAt(2, 14, 0);
        // 续约流程只查两次库：先按原预约 id 取原单，insert 后再按新 id 回读（连续桩，避免 any() 覆盖 901L 的桩）
        PsycheAppointment refreshed = appointment(902L, WANGXM, LISI, "online", "requested", start);
        when(appointmentMapper.findById(any())).thenReturn(origin, refreshed);

        Result<?> r = appointmentService.renew(WANGXM, "student", 901L, body("expectStart", FMT.format(start)));

        assertOk(r);
        ArgumentCaptor<PsycheAppointment> cap = ArgumentCaptor.forClass(PsycheAppointment.class);
        verify(appointmentMapper).insert(cap.capture());
        PsycheAppointment created = cap.getValue();
        assertAll(
                () -> assertEquals(LISI, created.getCounselorId().longValue(), "续约沿用同一咨询师"),
                () -> assertEquals("online", created.getMode(), "续约沿用原方式"),
                () -> assertEquals(901L, created.getRenewedFrom().longValue(), "写 renewed_from"),
                () -> assertEquals(Integer.valueOf(60), created.getDurationMinutes(), "时长缺省继承原预约"),
                () -> assertEquals("上次聊到考试焦虑", created.getMainIssue()),
                () -> assertEquals("13800000004", created.getContactPhone()),
                () -> assertEquals("requested", created.getStatus()),
                () -> assertEquals(start.plusMinutes(60), created.getExpectEnd()));
    }

    @Test
    @DisplayName("149 renew：不再复核「提前一个工作日」（S17：续约直接找咨询师，免网上预约程序）")
    void renewSkipsAdvanceRule() {
        PsycheAppointment origin = appointment(901L, WANGXM, LISI, "office", "confirmed", pastWindowTime());
        when(appointmentMapper.findById(901L)).thenReturn(origin);
        when(appointmentMapper.findById(any())).thenReturn(origin);
        LocalDateTime beforeThreshold = pastWindowTime();
        assertTrue(beforeThreshold.isBefore(
                PsycheAppointmentService.addWorkingDays(LocalDateTime.now().withNano(0), 1)),
                "用例前置：该时刻必须早于「提前一个工作日」门槛");

        Result<?> r = appointmentService.renew(WANGXM, "student", 901L,
                body("expectStart", FMT.format(beforeThreshold)));

        assertOk(r);
        assertFalse(ADVANCE_MSG.equals(r.getMessage()), "续约不应受提前一个工作日限制");
    }

    @Test
    @DisplayName("150 renew：未登录 401、教师 403「仅学生可用」")
    void renewAppointmentGuards() {
        assertAll(
                () -> assertCode(appointmentService.renew(null, "student", 901L, body()), 401),
                () -> assertCode(appointmentService.renew(LISI, "teacher", 901L, body()), 403));
        verifyNoInteractions(appointmentMapper);
    }

    @Test
    @DisplayName("151 预约状态机：confirm 只接受 requested（已 confirmed → 400，且不落 SQL）")
    void confirmRejectsNonRequested() {
        when(appointmentMapper.findById(901L))
                .thenReturn(appointment(901L, WANGXM, LISI, "office", "confirmed", pastWindowTime()));

        Result<?> r = appointmentService.confirm(LISI, "teacher", 901L);

        assertCode(r, 400);
        assertMsg(r, CAS_FAIL);
        // P14 缺陷回归：CAS 的 fromStatus 取自刚读到的状态 ⇒ 单靠 SQL 挡不住非法迁移，必须在服务层挡。
        verify(appointmentMapper, never()).confirm(anyLong(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("152 预约状态机：complete 只接受 confirmed（已 done → 400，且不落 SQL）")
    void completeRejectsNonConfirmed() {
        when(appointmentMapper.findById(901L))
                .thenReturn(appointment(901L, WANGXM, LISI, "office", "done", pastWindowTime()));

        Result<?> r = appointmentService.complete(LISI, "teacher", 901L);

        assertCode(r, 400);
        assertMsg(r, CAS_FAIL);
        verify(appointmentMapper, never()).complete(anyLong(), any(), any());
    }

    @Test
    @DisplayName("153 预约状态机：cancel 只接受 requested / confirmed（done 与 cancelled 都 400，且不落 SQL）")
    void cancelRejectsTerminalStates() {
        when(appointmentMapper.findById(901L))
                .thenReturn(appointment(901L, WANGXM, LISI, "office", "done", pastWindowTime()))
                .thenReturn(appointment(901L, WANGXM, LISI, "office", "cancelled", pastWindowTime()));

        Result<?> done = appointmentService.cancel(LISI, "teacher", 901L, body());
        Result<?> cancelled = appointmentService.cancel(LISI, "teacher", 901L, body());

        assertAll(
                () -> assertCode(done, 400),
                () -> assertMsg(done, CAS_FAIL),
                () -> assertCode(cancelled, 400),
                () -> assertMsg(cancelled, CAS_FAIL));
        verify(appointmentMapper, never()).cancel(anyLong(), any(), any());
    }
}
