package com.umi.nest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.umi.nest.common.Result;
import com.umi.nest.controller.CommunityController;
import com.umi.nest.entity.CommunityActivity;
import com.umi.nest.entity.CommunityActivitySignup;
import com.umi.nest.entity.CommunityBatch;
import com.umi.nest.entity.CommunityCivilRoom;
import com.umi.nest.entity.CommunityInspection;
import com.umi.nest.entity.CommunityLate;
import com.umi.nest.entity.CommunityViolation;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.CommunityActivityMapper;
import com.umi.nest.mapper.CommunityActivitySignupMapper;
import com.umi.nest.mapper.CommunityBatchMapper;
import com.umi.nest.mapper.CommunityCivilRoomMapper;
import com.umi.nest.mapper.CommunityInspectionMapper;
import com.umi.nest.mapper.CommunityLateMapper;
import com.umi.nest.mapper.CommunityViolationMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P15 社区管理（§10）—— {@link CommunityService} / {@link CommunityLateService} /
 * {@link CommunityActivityService} 的服务层单测，外加
 * {@link CommunityController#export} 的 CSV BOM 出口验证。
 *
 * <p>口径出处：《学工平台功能对齐说明》§10（P15 spec，S11 / S19 / S20），其中 §10.2 的 11 条
 * 「实现口径」为验收依据；本类逐条把口径写成断言：第 2 条（不做评分表 / 演示评分项标注）、
 * 第 3 条（晚归判定基准 6:00-23:30，时段之外才是晚归）、第 4 条（处理只到教育惩戒与建议、
 * rule_ref 必填、不生成处分决定）、第 6 条（文明宿舍按 term 汇总已发布批次 / 楼栋内排名 /
 * 名额不自动截断）、第 7 条（整改闭环 fail 或 rectify_required=1 触发、CAS 全程）、
 * 第 8 条（活动名额 / 唯一报名 / 报名截止）、第 11 条（单测验收清单：等级边界 90/80/60、
 * criteria_json 校验、晚归时段判定、整改与批次 CAS、文明宿舍均分与楼内排名、活动名额与去重、
 * 脱敏函数、导出 BOM）。</p>
 *
 * <p>风格与 PsycheServiceTest / AidReviewServiceTest / EvalServiceTest 一致：纯 Mockito
 * （@ExtendWith(MockitoExtension.class) + @MockitoSettings(LENIENT) + @InjectMocks），不起 Spring 容器、
 * 不连库、不写任何数据行，因此不需要 @Transactional / 清理，可反复跑且绝不动演示数据。
 * 唯一例外是 ObjectMapper 必须用 @Spy（真实实现），否则 criteria_json 解析全为 null。</p>
 *
 * <p><b>关于 CAS 断言（P14 踩过的坑）</b>：服务层每个状态变更入口都是「先读当前状态 → 服务层
 * 前置态校验 → CAS 带 fromStatus」。因为 {@code WHERE id=? AND status=?} 的 fromStatus 取自
 * 刚读到的状态，CAS 本身对**非法前置态**几乎恒成立（读到的就是当前值），所以真正拦住非法迁移的是
 * 服务层前置校验。本类因此对非法前置态一律断言 400 且
 * {@code verify(mapper, never())} 写入口**绝不落到 SQL**；对合法迁移则用 ArgumentCaptor 断言
 * fromStatus 恰为**读到的旧状态**（不是目标态）。</p>
 *
 * <p>账号口径取自 sql\nest_seed.sql 与 P14 同阶段测试：admin=1、zhangsan=2、lisi=3；
 * 学生 wangxm=4 / zhaoxh=5 / chendw=6 的 student_profile.counselor_id=3=lisi，
 * liusq=7 / zhoujie=8 的 counselor_id=2=zhangsan。真实姓名与学号：王小明/2024001 等。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Community*Service - P15 社区管理（检查评分 · 晚归 · 违规台账 · 文明宿舍 · 活动）口径")
class CommunityServiceTest {

    // ---------------------------------------------------------------- 真实账号

    private static final long ADMIN = 1L;
    private static final long ZHANGSAN = 2L;
    private static final long LISI = 3L;
    private static final long WANGXM = 4L;
    private static final long ZHAOXH = 5L;
    private static final long CHENDW = 6L;

    // ---------------------------------------------------------------- 固定 id 与文案

    private static final long BATCH = 901L;
    private static final long INSPECTION = 902L;
    private static final long LATE = 903L;
    private static final long VIOLATION = 904L;
    private static final long CIVIL = 905L;
    private static final long ACTIVITY = 906L;
    private static final long SIGNUP = 907L;

    private static final String TERM = "2025-2026-1";
    private static final String BUILDING = "A 栋";
    private static final String ROOM = "A-305";
    private static final String CAS_FAIL = CommunityService.CAS_FAIL;
    private static final String DEMO_MARK = CommunityService.DEMO_CRITERIA_MARK;
    private static final String DEFAULT_CRITERIA = CommunityService.DEFAULT_CRITERIA_JSON;
    private static final String CRITERIA_BLANK_MSG =
            "请填写评分项 criteriaJson（学校发布口径；留空则用平台演示评分项）";

    @Mock
    private CommunityBatchMapper batchMapper;

    @Mock
    private CommunityInspectionMapper inspectionMapper;

    @Mock
    private CommunityCivilRoomMapper civilRoomMapper;

    @Mock
    private CommunityLateMapper lateMapper;

    @Mock
    private CommunityViolationMapper violationMapper;

    @Mock
    private CommunityActivityMapper activityMapper;

    @Mock
    private CommunityActivitySignupMapper signupMapper;

    @Mock
    private StudentProfileMapper studentProfileMapper;

    /** 必须是真的 ObjectMapper（@Spy），否则 criteria_json 的解析全为 null。 */
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private CommunityService service;

    @InjectMocks
    private CommunityLateService lateService;

    @InjectMocks
    private CommunityActivityService activityService;

    private final CommunityController controller = new CommunityController();

    @BeforeEach
    void wireController() {
        ReflectionTestUtils.setField(controller, "communityService", service);
        ReflectionTestUtils.setField(controller, "communityLateService", lateService);
        ReflectionTestUtils.setField(controller, "communityActivityService", activityService);
    }

    // ---------------------------------------------------------------- 断言与构造工具

    private static void assertOk(Result<?> r) {
        assertNotNull(r);
        assertEquals(200, r.getCode().intValue(), "应成功，实际：" + r.getMessage());
    }

    private static void assertCode(Result<?> r, int code) {
        assertNotNull(r);
        assertEquals(code, r.getCode().intValue(), "message=" + r.getMessage());
    }

    private static void assertMsg(Result<?> r, String message) {
        assertNotNull(r);
        assertEquals(message, r.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataMap(Result<?> r) {
        Object data = r.getData();
        assertTrue(data instanceof Map, "data 应为 Map，实际：" + data);
        return (Map<String, Object>) data;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listData(Result<?> r) {
        Object data = r.getData();
        assertTrue(data instanceof List, "data 应为 List，实际：" + data);
        return (List<Object>) data;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return (Map<String, Object>) value;
    }

    private static int intOf(Object value) {
        return ((Number) value).intValue();
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    /** 造超长字符串用（长度校验用例）。 */
    private static String repeat(String unit, int times) {
        StringBuilder sb = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 实体工厂

    private static StudentProfile profile(long userId, String name, String studentNo, long counselorId) {
        StudentProfile p = new StudentProfile();
        p.setId(userId + 1000);
        p.setUserId(userId);
        p.setName(name);
        p.setStudentNo(studentNo);
        p.setClassName("计算机 2024-1");
        p.setCounselorId(counselorId);
        p.setDormBuilding(BUILDING);
        p.setDormRoom(ROOM);
        return p;
    }

    private static StudentProfile wangxm() {
        return profile(WANGXM, "王小明", "2024001", LISI);
    }

    private static CommunityBatch batch(long id, String status) {
        CommunityBatch b = new CommunityBatch();
        b.setId(id);
        b.setBatchName("2025-2026-1 卫生检查");
        b.setTerm(TERM);
        b.setCheckType("hygiene");
        b.setStatus(status);
        b.setCriteriaJson(DEFAULT_CRITERIA);
        b.setStartDate(LocalDate.of(2025, 3, 1));
        b.setEndDate(LocalDate.of(2025, 3, 31));
        b.setCreatedBy(ADMIN);
        return b;
    }

    private static CommunityInspection inspection(long id, String building, String room) {
        CommunityInspection i = new CommunityInspection();
        i.setId(id);
        i.setBatchId(BATCH);
        i.setBuilding(building);
        i.setRoomNo(room);
        i.setCheckDate(LocalDate.of(2025, 3, 12));
        i.setScore(new BigDecimal("88.50"));
        i.setGrade("good");
        i.setRectifyStatus("none");
        i.setSource("manual");
        i.setBatchStatus("published");
        i.setBatchName("2025-2026-1 卫生检查");
        i.setTerm(TERM);
        i.setInspectorName("李四");
        return i;
    }

    private static CommunityLate late(long id, String type, String handling, Integer confirmed) {
        CommunityLate l = new CommunityLate();
        l.setId(id);
        l.setStudentId(WANGXM);
        l.setBuilding(BUILDING);
        l.setRoomNo(ROOM);
        l.setOccurDate(LocalDate.of(2025, 3, 10));
        l.setType(type);
        l.setOutTime(LocalTime.of(23, 45));
        l.setInTime(LocalTime.of(23, 55));
        l.setSource("report");
        l.setReason("late_return");
        l.setReasonNote("参加社团活动");
        l.setStudentNote("本人说明");
        l.setConfirmed(confirmed);
        l.setCounselorId(LISI);
        l.setHandling(handling);
        l.setHandlingNote("内部处理备注");
        l.setStudentName("王小明");
        l.setStudentNo("2024001");
        return l;
    }

    private static CommunityViolation violation(long id, String handling) {
        CommunityViolation v = new CommunityViolation();
        v.setId(id);
        v.setStudentId(WANGXM);
        v.setBuilding(BUILDING);
        v.setRoomNo(ROOM);
        v.setType("appliance");
        v.setOccurredAt(LocalDateTime.of(2025, 3, 10, 21, 30));
        v.setDetail("使用电热毯");
        v.setRuleRef("园区管理规定第四十一条第九项");
        v.setAdvice("education");
        v.setHandling(handling);
        v.setCounselorId(LISI);
        v.setResultNote("已批评教育");
        v.setHandledBy(LISI);
        v.setHandledByName("李四");
        v.setStudentName("王小明");
        v.setStudentNo("2024001");
        return v;
    }

    private static CommunityCivilRoom civilRoom(long id, String roomNo, String avg, String min) {
        CommunityCivilRoom c = new CommunityCivilRoom();
        c.setId(id);
        c.setTerm(TERM);
        c.setBuilding(BUILDING);
        c.setRoomNo(roomNo);
        c.setAvgScore(new BigDecimal(avg));
        c.setMinScore(new BigDecimal(min));
        c.setMemberCount(4);
        c.setAwarded(0);
        c.setBatchIds("[901]");
        return c;
    }

    private static CommunityActivity activity(long id, String status) {
        CommunityActivity a = new CommunityActivity();
        a.setId(id);
        a.setTitle("园区文化节");
        a.setActivityType("culture_festival");
        a.setTerm(TERM);
        a.setLevel("community");
        a.setStatus(status);
        a.setStartAt(LocalDateTime.of(2025, 3, 20, 14, 0));
        a.setEndAt(LocalDateTime.of(2025, 3, 20, 17, 0));
        a.setSignupDeadline(LocalDateTime.of(2025, 3, 18, 23, 59));
        a.setQuota(2);
        a.setCreatedBy(ADMIN);
        return a;
    }

    private static CommunityActivitySignup signup(long id, long activityId, long studentId, String status) {
        CommunityActivitySignup s = new CommunityActivitySignup();
        s.setId(id);
        s.setActivityId(activityId);
        s.setStudentId(studentId);
        s.setBuilding(BUILDING);
        s.setRoomNo(ROOM);
        s.setStatus(status);
        s.setStudentName("王小明");
        s.setStudentNo("2024001");
        return s;
    }

    /** 文明宿舍 aggregate 的一行（列名与服务读取的键一致）。 */
    private static Map<String, Object> aggRow(String roomNo, String avg, String min, int members, String batchIds) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("building", BUILDING);
        row.put("room_no", roomNo);
        row.put("avg_score", avg);
        row.put("min_score", min);
        row.put("member_count", members);
        row.put("batch_ids", batchIds);
        return row;
    }

    // ==================================================================================
    // 1. 静态工具：等级边界 / 名次 / 脱敏 / 标签 / 解析 / 守卫（§10.2-11 单测清单）
    // ==================================================================================

    @Test
    @DisplayName("01 gradeOf：null 一律 fail（不能 NPE）")
    void gradeOfNull() {
        assertEquals("fail", CommunityService.gradeOf(null));
    }

    @Test
    @DisplayName("02 gradeOf：90 分边界＝excellent（90.00 也算）")
    void gradeOfBoundary90() {
        assertAll(
                () -> assertEquals("excellent", CommunityService.gradeOf(new BigDecimal("90"))),
                () -> assertEquals("excellent", CommunityService.gradeOf(new BigDecimal("90.00"))),
                () -> assertEquals("excellent", CommunityService.gradeOf(new BigDecimal("90.000"))),
                () -> assertEquals("excellent", CommunityService.gradeOf(new BigDecimal("100"))));
    }

    @Test
    @DisplayName("03 gradeOf：89.5 / 80 分边界＝good")
    void gradeOfBoundary80() {
        assertAll(
                () -> assertEquals("good", CommunityService.gradeOf(new BigDecimal("89.5"))),
                () -> assertEquals("good", CommunityService.gradeOf(new BigDecimal("89.99"))),
                () -> assertEquals("good", CommunityService.gradeOf(new BigDecimal("80"))),
                () -> assertEquals("good", CommunityService.gradeOf(new BigDecimal("80.00"))));
    }

    @Test
    @DisplayName("04 gradeOf：79.9 / 60 分边界＝pass")
    void gradeOfBoundary60() {
        assertAll(
                () -> assertEquals("pass", CommunityService.gradeOf(new BigDecimal("79.9"))),
                () -> assertEquals("pass", CommunityService.gradeOf(new BigDecimal("60"))),
                () -> assertEquals("pass", CommunityService.gradeOf(new BigDecimal("60.00"))));
    }

    @Test
    @DisplayName("05 gradeOf：59.9 / 0 / 负数＝fail（<60 触发整改）")
    void gradeOfBoundaryFail() {
        assertAll(
                () -> assertEquals("fail", CommunityService.gradeOf(new BigDecimal("59.9"))),
                () -> assertEquals("fail", CommunityService.gradeOf(new BigDecimal("59.99"))),
                () -> assertEquals("fail", CommunityService.gradeOf(new BigDecimal("0"))),
                () -> assertEquals("fail", CommunityService.gradeOf(new BigDecimal("-1"))));
    }

    @Test
    @DisplayName("06 isDemoCriteria：与演示评分项精确相等（trim 后）才算演示口径")
    void isDemoCriteriaExactEquals() {
        assertAll(
                () -> assertTrue(CommunityService.isDemoCriteria(DEFAULT_CRITERIA)),
                () -> assertTrue(CommunityService.isDemoCriteria("  " + DEFAULT_CRITERIA + "  ")),
                () -> assertFalse(CommunityService.isDemoCriteria(null), "null 不是演示评分项"),
                () -> assertFalse(CommunityService.isDemoCriteria("[]")),
                () -> assertFalse(CommunityService.isDemoCriteria(DEFAULT_CRITERIA.replace("power", "pow"))));
    }

    @Test
    @DisplayName("07 rankInBuilding：楼栋内均分降序，同分并列（1 + 严格高于自己的间数）")
    void rankInBuildingTies() {
        List<BigDecimal> scores = Arrays.asList(
                new BigDecimal("90.00"), new BigDecimal("85.00"), new BigDecimal("85.00"), new BigDecimal("80.00"));
        assertAll(
                () -> assertEquals(1, CommunityService.rankInBuilding(scores, new BigDecimal("90.00"))),
                () -> assertEquals(2, CommunityService.rankInBuilding(scores, new BigDecimal("85.00")), "同分并列第 2"),
                () -> assertEquals(4, CommunityService.rankInBuilding(scores, new BigDecimal("80.00"))));
    }

    @Test
    @DisplayName("08 rankInBuilding：比自己低 / 自己就是唯一 → 第 1")
    void rankInBuildingTop() {
        List<BigDecimal> scores = Arrays.asList(new BigDecimal("70.00"), new BigDecimal("60.00"));
        assertEquals(1, CommunityService.rankInBuilding(scores, new BigDecimal("95.00")));
    }

    @Test
    @DisplayName("09 rankInBuilding：空表 / null 参数 → 1（不 NPE）")
    void rankInBuildingNull() {
        assertAll(
                () -> assertEquals(1, CommunityService.rankInBuilding(null, new BigDecimal("90"))),
                () -> assertEquals(1, CommunityService.rankInBuilding(new ArrayList<>(), new BigDecimal("90"))),
                () -> assertEquals(1, CommunityService.rankInBuilding(Arrays.asList(new BigDecimal("90")), null)));
    }

    @Test
    @DisplayName("10 rankInBuilding：忽略表中的 null 元素，不把 null 当高分")
    void rankInBuildingIgnoresNullEntries() {
        List<BigDecimal> scores = Arrays.asList(null, new BigDecimal("88.00"), null);
        assertEquals(1, CommunityService.rankInBuilding(scores, new BigDecimal("88.00")));
    }

    @Test
    @DisplayName("11 toJsonArray：逗号串 → JSON 数组；已是数组原样；空白 → null")
    void toJsonArray() {
        assertAll(
                () -> assertEquals("[901,902]", CommunityService.toJsonArray("901,902")),
                () -> assertEquals("[901,902]", CommunityService.toJsonArray("901, 902"), "每段 trim 后拼接"),
                () -> assertEquals("[901]", CommunityService.toJsonArray(" 901 ")),
                () -> assertEquals("[901,902]", CommunityService.toJsonArray("[901,902]")),
                () -> assertNull(CommunityService.toJsonArray(null)),
                () -> assertNull(CommunityService.toJsonArray("   ")));
    }

    @Test
    @DisplayName("12 maskName：保留首个码点，其余打一个星；单字/空白/null 原样或空串")
    void maskName() {
        assertAll(
                () -> assertEquals("王*", CommunityService.maskName("王小明")),
                () -> assertEquals("王", CommunityService.maskName("王")),
                () -> assertEquals("A*", CommunityService.maskName("Alexander")),
                () -> assertEquals("", CommunityService.maskName(null)),
                () -> assertEquals("", CommunityService.maskName("   ")),
                () -> assertEquals("王*", CommunityService.maskName("  王小明  ")));
    }

    @Test
    @DisplayName("13 tail4：学号只留后 4 位；≤4 位原样；null/空 → 空串")
    void tail4() {
        assertAll(
                () -> assertEquals("4001", CommunityService.tail4("2024001")),
                () -> assertEquals("2024", CommunityService.tail4("2024")),
                () -> assertEquals("123", CommunityService.tail4("123")),
                () -> assertEquals("", CommunityService.tail4(null)),
                () -> assertEquals("", CommunityService.tail4("")));
    }

    @Test
    @DisplayName("14 csvCell：含逗号/引号/换行才加引号且内部引号翻倍")
    void csvCell() {
        assertAll(
                () -> assertEquals("正常", CommunityService.csvCell("正常")),
                () -> assertEquals("", CommunityService.csvCell(null)),
                () -> assertEquals("", CommunityService.csvCell("")),
                () -> assertEquals("\"a,b\"", CommunityService.csvCell("a,b")),
                () -> assertEquals("\"a\"\"b\"", CommunityService.csvCell("a\"b")),
                () -> assertEquals("\"a\nb\"", CommunityService.csvCell("a\nb")));
    }

    @Test
    @DisplayName("15 statusLabel：状态 / 处理态中文文案统一一处，null → '-'")
    void statusLabel() {
        assertAll(
                () -> assertEquals("草稿", CommunityService.statusLabel("draft")),
                () -> assertEquals("检查中", CommunityService.statusLabel("checking")),
                () -> assertEquals("已评分", CommunityService.statusLabel("scored")),
                () -> assertEquals("已公布", CommunityService.statusLabel("published")),
                () -> assertEquals("已结束", CommunityService.statusLabel("finished")),
                () -> assertEquals("已取消", CommunityService.statusLabel("cancelled")),
                () -> assertEquals("待处理", CommunityService.statusLabel("pending")),
                () -> assertEquals("已联系", CommunityService.statusLabel("contacted")),
                () -> assertEquals("已告知", CommunityService.statusLabel("notified")),
                () -> assertEquals("已了结", CommunityService.statusLabel("closed")),
                () -> assertEquals("报名中", CommunityService.statusLabel("signup")),
                () -> assertEquals("进行中", CommunityService.statusLabel("ongoing")),
                () -> assertEquals("未知态", CommunityService.statusLabel("未知态")),
                () -> assertEquals("-", CommunityService.statusLabel(null)));
    }

    @Test
    @DisplayName("16 rectifyLabel / gradeLabel：整改五态与等级四档中文文案")
    void rectifyAndGradeLabel() {
        assertAll(
                () -> assertEquals("无需整改", CommunityService.rectifyLabel("none")),
                () -> assertEquals("待整改", CommunityService.rectifyLabel("pending")),
                () -> assertEquals("已提交说明", CommunityService.rectifyLabel("submitted")),
                () -> assertEquals("复查通过", CommunityService.rectifyLabel("accepted")),
                () -> assertEquals("复查未通过", CommunityService.rectifyLabel("rejected")),
                () -> assertEquals("优秀", CommunityService.gradeLabel("excellent")),
                () -> assertEquals("良好", CommunityService.gradeLabel("good")),
                () -> assertEquals("合格", CommunityService.gradeLabel("pass")),
                () -> assertEquals("不合格", CommunityService.gradeLabel("fail")));
    }

    @Test
    @DisplayName("17 violationTypeLabel / adviceLabel：违规类型与处理建议（教育惩戒口径）文案")
    void violationLabels() {
        assertAll(
                () -> assertEquals("晚归", CommunityService.violationTypeLabel("late")),
                () -> assertEquals("夜不归宿", CommunityService.violationTypeLabel("absence")),
                () -> assertEquals("违规电器", CommunityService.violationTypeLabel("appliance")),
                () -> assertEquals("消防隐患", CommunityService.violationTypeLabel("fire")),
                () -> assertEquals("饲养宠物", CommunityService.violationTypeLabel("pet")),
                () -> assertEquals("出租床位", CommunityService.violationTypeLabel("rent")),
                () -> assertEquals("攀爬", CommunityService.violationTypeLabel("climb")),
                () -> assertEquals("噪音", CommunityService.violationTypeLabel("noise")),
                () -> assertEquals("其他", CommunityService.violationTypeLabel("unknown")),
                () -> assertEquals("教育", CommunityService.adviceLabel("education")),
                () -> assertEquals("通报", CommunityService.adviceLabel("circulated")),
                () -> assertEquals("警告", CommunityService.adviceLabel("warning")),
                () -> assertEquals("严重警告", CommunityService.adviceLabel("serious_warning")),
                () -> assertEquals("记录在案", CommunityService.adviceLabel("recorded")));
    }

    @Test
    @DisplayName("18 flag：Boolean / 非 0 数字 / true·1·是·yes 视为 1，其余 0")
    void flagParsing() {
        assertAll(
                () -> assertEquals(1, CommunityService.flag(Boolean.TRUE)),
                () -> assertEquals(0, CommunityService.flag(Boolean.FALSE)),
                () -> assertEquals(1, CommunityService.flag(2)),
                () -> assertEquals(0, CommunityService.flag(0)),
                () -> assertEquals(1, CommunityService.flag("true")),
                () -> assertEquals(1, CommunityService.flag("TRUE")),
                () -> assertEquals(1, CommunityService.flag("1")),
                () -> assertEquals(1, CommunityService.flag("是")),
                () -> assertEquals(1, CommunityService.flag("yes")),
                () -> assertEquals(0, CommunityService.flag("no")),
                () -> assertEquals(0, CommunityService.flag("  ")),
                () -> assertEquals(0, CommunityService.flag(null)));
    }

    @Test
    @DisplayName("19 integer / longOf / toInt：数字与数字串可解析，坏串 → null/兜底")
    void integerParsing() {
        assertAll(
                () -> assertEquals(3, CommunityService.integer(3)),
                () -> assertEquals(3, CommunityService.integer(3.9d)),
                () -> assertEquals(3, CommunityService.integer("3.9")),
                () -> assertEquals(20, CommunityService.integer("20")),
                () -> assertNull(CommunityService.integer("abc")),
                () -> assertNull(CommunityService.integer(null)),
                () -> assertNull(CommunityService.integer("   ")),
                () -> assertEquals(7, CommunityService.toInt(null, 7)),
                () -> assertEquals(7L, CommunityService.longOf(7)),
                () -> assertEquals(7L, CommunityService.longOf("7")),
                () -> assertNull(CommunityService.longOf("x")));
    }

    @Test
    @DisplayName("20 dec / scale2：金额分数两位小数 HALF_UP，null → 0.00")
    void decAndScale2() {
        assertAll(
                () -> assertEquals(new BigDecimal("89.5"), CommunityService.dec("89.5")),
                () -> assertEquals(new BigDecimal("89.5"), CommunityService.dec(new BigDecimal("89.5"))),
                () -> assertNull(CommunityService.dec("abc")),
                () -> assertNull(CommunityService.dec("")),
                () -> assertEquals(new BigDecimal("0.00"), CommunityService.scale2(null)),
                () -> assertEquals(new BigDecimal("89.44"), CommunityService.scale2("89.444")),
                () -> assertEquals(new BigDecimal("89.45"), CommunityService.scale2("89.445"), "HALF_UP"));
    }

    @Test
    @DisplayName("21 parseDate：取前 10 位；坏串 → null")
    void parseDate() {
        assertAll(
                () -> assertEquals(LocalDate.of(2025, 3, 12), CommunityService.parseDate("2025-03-12")),
                () -> assertEquals(LocalDate.of(2025, 3, 12),
                        CommunityService.parseDate("2025-03-12 08:30:00"), "带时间的取前 10 位"),
                () -> assertNull(CommunityService.parseDate("2025/03/12")),
                () -> assertNull(CommunityService.parseDate(null)));
    }

    @Test
    @DisplayName("22 parseTime：HH:mm 补秒、超长截断；坏串 → null")
    void parseTime() {
        assertAll(
                () -> assertEquals(LocalTime.of(6, 0), CommunityService.parseTime("06:00")),
                () -> assertEquals(LocalTime.of(23, 30), CommunityService.parseTime("23:30")),
                () -> assertEquals(LocalTime.of(23, 30), CommunityService.parseTime("23:30:00")),
                () -> assertEquals(LocalTime.of(23, 31), CommunityService.parseTime("23:31:00.000")),
                () -> assertNull(CommunityService.parseTime("abc")),
                () -> assertNull(CommunityService.parseTime(null)));
    }

    @Test
    @DisplayName("23 parseDateTime：支持 yyyy-MM-dd / 带 T / 到分钟三种形态")
    void parseDateTime() {
        assertAll(
                () -> assertEquals(LocalDateTime.of(2025, 3, 1, 0, 0),
                        CommunityService.parseDateTime("2025-03-01")),
                () -> assertEquals(LocalDateTime.of(2025, 3, 1, 8, 30),
                        CommunityService.parseDateTime("2025-03-01T08:30")),
                () -> assertEquals(LocalDateTime.of(2025, 3, 1, 8, 30, 15),
                        CommunityService.parseDateTime("2025-03-01 08:30:15")),
                () -> assertNull(CommunityService.parseDateTime("2025-03")),
                () -> assertNull(CommunityService.parseDateTime("not-a-date")));
    }

    @Test
    @DisplayName("24 pick / has / trimToNull：pick 取首个非 null，has 只看 key 存在（可为 null）")
    void pickHasTrim() {
        Map<String, Object> b = body("roomNo", null, "room_no", "A-305");
        assertAll(
                () -> assertEquals("A-305", CommunityService.pick(b, "roomNo", "room_no"),
                        "第一个 key 存在但为 null → 继续找下一个"),
                () -> assertEquals("A-305", CommunityService.pick(b, "room_no")),
                () -> assertNull(CommunityService.pick(b, "building")),
                () -> assertTrue(CommunityService.has(b, "roomNo"), "has 只判 containsKey"),
                () -> assertFalse(CommunityService.has(b, "building")),
                () -> assertNull(CommunityService.trimToNull("   ")),
                () -> assertEquals("x", CommunityService.trimToNull(" x ")),
                () -> assertTrue(CommunityService.isBlank("  ")),
                () -> assertFalse(CommunityService.isBlank("a")));
    }

    @Test
    @DisplayName("25 pageOf：list/total/page/size 四键，键序固定")
    void pageOf() {
        Map<String, Object> page = CommunityService.pageOf(Arrays.asList("a", "b"), 2, 1, 20);
        assertEquals(Arrays.asList("list", "total", "page", "size"),
                new ArrayList<>(page.keySet()));
        assertEquals(2, intOf(page.get("total")));
        assertEquals(20, intOf(page.get("size")));
    }

    @Test
    @DisplayName("26 守卫：未登录 401、非教职工 403、非管理员 403、辅导员只看自己名下")
    void guards() {
        assertAll(
                () -> assertCode(CommunityService.guardLogin(null), 401),
                () -> assertMsg(CommunityService.guardLogin(null), "请先登录"),
                () -> assertNull(CommunityService.guardLogin(WANGXM)),
                () -> assertCode(CommunityService.guardStaff(null, "admin"), 401),
                () -> assertCode(CommunityService.guardStaff(LISI, "student"), 403),
                () -> assertMsg(CommunityService.guardStaff(LISI, "student"), "无权访问"),
                () -> assertNull(CommunityService.guardStaff(LISI, "teacher")),
                () -> assertNull(CommunityService.guardStaff(ADMIN, "admin")),
                () -> assertCode(CommunityService.guardAdmin(LISI, "teacher"), 403),
                () -> assertMsg(CommunityService.guardAdmin(LISI, "teacher"), "仅管理员可操作"),
                () -> assertNull(CommunityService.guardAdmin(ADMIN, "admin")),
                () -> assertTrue(CommunityService.isStaff("teacher")),
                () -> assertTrue(CommunityService.isStudent("student")),
                () -> assertFalse(CommunityService.isAdmin("teacher")),
                () -> assertNull(CommunityService.scopeCounselor(ADMIN, "admin"), "管理员看全部"),
                () -> assertEquals(LISI, CommunityService.scopeCounselor(LISI, "teacher").longValue()),
                () -> assertEquals(WANGXM, CommunityService.scopeCounselor(WANGXM, "student").longValue()));
    }

    // ==================================================================================
    // 2. criteria_json 校验与逐项汇总（§10.2-2：不做评分表，学校口径 + 平台演示标注）
    // ==================================================================================

    @Test
    @DisplayName("27 validateCriteria：空 / 空白 → 提示留空则用平台演示评分项")
    void validateCriteriaBlank() {
        assertAll(
                () -> assertEquals(CRITERIA_BLANK_MSG, service.validateCriteria(null)),
                () -> assertEquals(CRITERIA_BLANK_MSG, service.validateCriteria("   ")));
    }

    @Test
    @DisplayName("28 validateCriteria：JSON 坏 → 「评分项 JSON 不正确」（不是 List 也算坏）")
    void validateCriteriaBadJson() {
        assertAll(
                () -> assertTrue(service.validateCriteria("{不是 JSON").startsWith("评分项 JSON 不正确")),
                () -> assertTrue(service.validateCriteria("\"abc\"").startsWith("评分项 JSON 不正确"),
                        "合法 JSON 但不是数组 → 同样判坏"));
    }

    @Test
    @DisplayName("29 validateCriteria：空数组 → 至少 1 项")
    void validateCriteriaEmptyArray() {
        assertEquals("评分项至少要有 1 项", service.validateCriteria("[]"));
    }

    @Test
    @DisplayName("30 validateCriteria：项缺 key（含 {}）→ 评分项缺少 key")
    void validateCriteriaMissingKey() {
        assertAll(
                () -> assertEquals("评分项缺少 key", service.validateCriteria("[{}]")),
                () -> assertEquals("评分项缺少 key",
                        service.validateCriteria("[{\"name\":\"用电安全\",\"max\":100}]")));
    }

    @Test
    @DisplayName("31 validateCriteria：key 重复 → 评分项 key 重复：power")
    void validateCriteriaDuplicateKey() {
        String json = "[{\"key\":\"power\",\"name\":\"用电安全\",\"max\":50},"
                + "{\"key\":\"power\",\"name\":\"重复\",\"max\":50}]";
        assertEquals("评分项 key 重复：power", service.validateCriteria(json));
    }

    @Test
    @DisplayName("32 validateCriteria：项缺 name → 评分项 power 缺少 name")
    void validateCriteriaMissingName() {
        assertEquals("评分项 power 缺少 name",
                service.validateCriteria("[{\"key\":\"power\",\"max\":100}]"));
    }

    @Test
    @DisplayName("33 validateCriteria：max 缺失 / 0 / 负数 / 非数 → 必须是正整数")
    void validateCriteriaBadMax() {
        assertAll(
                () -> assertEquals("评分项 power 的 max 必须是正整数",
                        service.validateCriteria("[{\"key\":\"power\",\"name\":\"用电安全\"}]")),
                () -> assertEquals("评分项 power 的 max 必须是正整数",
                        service.validateCriteria("[{\"key\":\"power\",\"name\":\"用电安全\",\"max\":0}]")),
                () -> assertEquals("评分项 power 的 max 必须是正整数",
                        service.validateCriteria("[{\"key\":\"power\",\"name\":\"用电安全\",\"max\":-5}]")),
                () -> assertEquals("评分项 power 的 max 必须是正整数",
                        service.validateCriteria("[{\"key\":\"power\",\"name\":\"用电安全\",\"max\":\"abc\"}]")));
    }

    @Test
    @DisplayName("34 validateCriteria：满分合计必须 100（99 / 101 都拦）")
    void validateCriteriaSum() {
        assertAll(
                () -> assertEquals("评分项满分合计应为 100，当前为 99",
                        service.validateCriteria("[{\"key\":\"power\",\"name\":\"用电安全\",\"max\":99}]")),
                () -> assertEquals("评分项满分合计应为 100，当前为 101",
                        service.validateCriteria("[{\"key\":\"a\",\"name\":\"甲\",\"max\":50},"
                                + "{\"key\":\"b\",\"name\":\"乙\",\"max\":51}]")));
    }

    @Test
    @DisplayName("35 validateCriteria：合计 100 的合法口径 → null（通过）")
    void validateCriteriaLegal() {
        assertAll(
                () -> assertNull(service.validateCriteria(DEFAULT_CRITERIA), "平台演示评分项自身合法"),
                () -> assertNull(service.validateCriteria("[{\"key\":\"a\",\"name\":\"甲\",\"max\":60},"
                        + "{\"key\":\"b\",\"name\":\"乙\",\"max\":40}]")));
    }

    @Test
    @DisplayName("36 criteriaMaxMap：留空用演示评分项（5 项各 20 分），坏 JSON → 空表不炸")
    void criteriaMaxMap() {
        Map<String, Integer> demo = service.criteriaMaxMap(null);
        Map<String, Integer> blank = service.criteriaMaxMap("   ");
        assertAll(
                () -> assertEquals(5, demo.size()),
                () -> assertEquals(20, demo.get("power")),
                () -> assertEquals(20, demo.get("fire")),
                () -> assertEquals(20, demo.get("hygiene")),
                () -> assertEquals(20, demo.get("property")),
                () -> assertEquals(20, demo.get("civil")),
                () -> assertEquals(5, blank.size()),
                () -> assertTrue(service.criteriaMaxMap("{坏 JSON").isEmpty()));
    }

    @Test
    @DisplayName("37 criteriaOf：返回逐项列表；留空 / 坏 JSON → 空列表")
    void criteriaOf() {
        assertAll(
                () -> assertEquals(5, service.criteriaOf(DEFAULT_CRITERIA).size()),
                () -> assertTrue(service.criteriaOf(null).isEmpty()),
                () -> assertTrue(service.criteriaOf("   ").isEmpty()),
                () -> assertTrue(service.criteriaOf("{坏").isEmpty()),
                () -> assertEquals(2, service.criteriaOf("[{\"key\":\"a\",\"name\":\"甲\",\"max\":60},"
                        + "{\"key\":\"b\",\"name\":\"乙\",\"max\":40}]").size()));
    }

    // ==================================================================================
    // 3. 检查批次：状态机 draft→checking→scored→published→finished（§10.2-10 CAS）
    // ==================================================================================

    @Test
    @DisplayName("38 listBatches：未登录 401 / 学生 403 / 教职工放行")
    void listBatchesGuard() {
        assertCode(service.listBatches(null, "admin", TERM, null, null, null), 401);
        assertCode(service.listBatches(WANGXM, "student", TERM, null, null, null), 403);
        when(batchMapper.list(TERM, null, null, null, false)).thenReturn(Arrays.asList(batch(BATCH, "draft")));
        Result<?> r = service.listBatches(LISI, "teacher", TERM, null, null, null);
        assertOk(r);
        assertEquals(1, listData(r).size());
    }

    @Test
    @DisplayName("39 createBatch：body 为空 / 缺批次名 / 批次名超 128 字")
    void createBatchRequiredFields() {
        assertAll(
                () -> assertMsg(service.createBatch(ADMIN, "admin", null), "请求内容不能为空"),
                () -> assertMsg(service.createBatch(ADMIN, "admin", body("term", TERM)), "请填写批次名称"),
                () -> assertMsg(service.createBatch(ADMIN, "admin", body("batchName", repeat("名", 129))),
                        "批次名称不能超过 128 字"));
    }

    @Test
    @DisplayName("40 createBatch：缺学期 / 检查类型非法（只支持 hygiene/safety/joint）")
    void createBatchTermAndCheckType() {
        assertAll(
                () -> assertMsg(service.createBatch(ADMIN, "admin", body("batchName", "检查")),
                        "请填写学期（如 2025-2026-1）"),
                () -> assertMsg(service.createBatch(ADMIN, "admin",
                                body("batchName", "检查", "term", TERM, "checkType", "electric")),
                        "检查类型只支持 hygiene（卫生）/ safety（安全）/ joint（联合）"));
    }

    @Test
    @DisplayName("41 createBatch：criteria_json 留空 → 用演示评分项并在 note 标注「非学校发布口径」（§10.2-2）")
    void createBatchDemoCriteriaMarksNote() {
        when(batchMapper.insert(any())).thenReturn(1);
        when(batchMapper.findById(any())).thenReturn(batch(BATCH, "draft"));

        Result<?> r = service.createBatch(ADMIN, "admin", body("batchName", "2025-2026-1 卫生检查", "term", TERM));

        assertOk(r);
        ArgumentCaptor<CommunityBatch> cap = ArgumentCaptor.forClass(CommunityBatch.class);
        verify(batchMapper).insert(cap.capture());
        CommunityBatch saved = cap.getValue();
        assertAll(
                () -> assertEquals(DEFAULT_CRITERIA, saved.getCriteriaJson()),
                () -> assertTrue(saved.getNote().contains(DEMO_MARK), "必须标注演示评分项"),
                () -> assertEquals("draft", saved.getStatus()),
                () -> assertEquals(ADMIN, saved.getCreatedBy().longValue()),
                () -> assertEquals("hygiene", saved.getCheckType(), "检查类型缺省 hygiene"));
    }

    @Test
    @DisplayName("42 createBatch：自填 criteria_json 走同一套校验（错误原文回传）")
    void createBatchCustomCriteriaValidated() {
        Result<?> r = service.createBatch(ADMIN, "admin",
                body("batchName", "检查", "term", TERM, "criteriaJson", "[{\"key\":\"a\",\"name\":\"甲\",\"max\":99}]"));
        assertCode(r, 400);
        assertMsg(r, "评分项满分合计应为 100，当前为 99");
        verify(batchMapper, never()).insert(any());
    }

    @Test
    @DisplayName("43 createBatch：结束日期早于开始日期 / 批次说明超 500 字")
    void createBatchDatesAndNote() {
        assertAll(
                () -> assertMsg(service.createBatch(ADMIN, "admin", body("batchName", "检查", "term", TERM,
                                "startDate", "2025-03-31", "endDate", "2025-03-01")),
                        "检查结束日期不能早于开始日期"),
                () -> assertMsg(service.createBatch(ADMIN, "admin",
                                body("batchName", "检查", "term", TERM, "note", repeat("n", 501))),
                        "批次说明不能超过 500 字"));
    }

    @Test
    @DisplayName("44 createBatch：合法请求落库并回读（返回 findById 结果）")
    void createBatchSuccess() {
        when(batchMapper.insert(any())).thenReturn(1);
        when(batchMapper.findById(any())).thenReturn(batch(BATCH, "draft"));

        Result<?> r = service.createBatch(ADMIN, "admin",
                body("batchName", "2025-2026-1 安全联合检查", "term", TERM, "checkType", "joint",
                        "criteriaJson", "[{\"key\":\"a\",\"name\":\"甲\",\"max\":100}]",
                        "startDate", "2025-03-01", "endDate", "2025-03-31", "note", "常规检查"));

        assertOk(r);
        ArgumentCaptor<CommunityBatch> cap = ArgumentCaptor.forClass(CommunityBatch.class);
        verify(batchMapper).insert(cap.capture());
        assertAll(
                () -> assertEquals("joint", cap.getValue().getCheckType()),
                () -> assertEquals("常规检查", cap.getValue().getNote(), "自填评分项不加演示标注"),
                () -> assertEquals(LocalDate.of(2025, 3, 1), cap.getValue().getStartDate()),
                () -> assertEquals(LocalDate.of(2025, 3, 31), cap.getValue().getEndDate()));
    }

    @Test
    @DisplayName("45 updateBatch：批次不存在 404")
    void updateBatchNotFound() {
        Result<?> r = service.updateBatch(ADMIN, "admin", 999L, body("batchName", "改名"));
        assertCode(r, 404);
        assertMsg(r, "批次不存在");
    }

    @Test
    @DisplayName("46 updateBatch：已公布 / 已结束批次 400，且绝不落到 SQL（P14 的 CAS 恒成立坑）")
    void updateBatchPublishedBlocked() {
        when(batchMapper.findById(BATCH))
                .thenReturn(batch(BATCH, "published"))
                .thenReturn(batch(BATCH, "finished"));
        assertAll(
                () -> assertMsg(service.updateBatch(ADMIN, "admin", BATCH, body("batchName", "改名")),
                        "批次已公布或已结束，不能再修改（当前 已公布）"),
                () -> assertMsg(service.updateBatch(ADMIN, "admin", BATCH, body("batchName", "改名")),
                        "批次已公布或已结束，不能再修改（当前 已结束）"));
        verify(batchMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("47 updateBatch：目标态 published / finished 必须走专用接口（不在这里改状态）")
    void updateBatchTargetHints() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "draft"));
        assertAll(
                () -> assertMsg(service.updateBatch(ADMIN, "admin", BATCH, body("status", "published")),
                        "批次公布请用 POST /api/community/batches/" + BATCH + "/publish"),
                () -> assertMsg(service.updateBatch(ADMIN, "admin", BATCH, body("status", "finished")),
                        "批次结束请用 POST /api/community/batches/" + BATCH + "/close"));
        verify(batchMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("48 updateBatch：目标态不在 draft/checking/scored → 只支持三态")
    void updateBatchBadTargetStatus() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "draft"));
        assertMsg(service.updateBatch(ADMIN, "admin", BATCH, body("status", "cancelled")),
                "批次状态只支持 draft / checking / scored");
        verify(batchMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("49 updateBatch：状态必须逐级推进（draft → scored 跳级被拦）")
    void updateBatchJumpLevel() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "draft"));
        assertMsg(service.updateBatch(ADMIN, "admin", BATCH, body("status", "scored")),
                "批次状态只能逐级推进 draft → checking → scored（当前 草稿，目标 已评分）");
        verify(batchMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("50 updateBatch：draft → checking 合法，CAS 的 fromStatus 恰为读到的旧状态")
    void updateBatchStepForward() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "draft"));
        when(batchMapper.update(any(), any())).thenReturn(1);

        Result<?> r = service.updateBatch(ADMIN, "admin", BATCH, body("status", "checking"));

        assertOk(r);
        ArgumentCaptor<CommunityBatch> cap = ArgumentCaptor.forClass(CommunityBatch.class);
        ArgumentCaptor<String> from = ArgumentCaptor.forClass(String.class);
        verify(batchMapper).update(cap.capture(), from.capture());
        assertAll(
                () -> assertEquals("draft", from.getValue(), "fromStatus 必须是读到的旧状态"),
                () -> assertEquals("checking", cap.getValue().getStatus()),
                () -> assertEquals(BATCH, cap.getValue().getId().longValue()));
    }

    @Test
    @DisplayName("51 updateBatch：CAS 0 行 → 「状态已变化，请刷新后重试」")
    void updateBatchCasFail() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "checking"));
        when(batchMapper.update(any(), any())).thenReturn(0);
        Result<?> r = service.updateBatch(ADMIN, "admin", BATCH, body("batchName", "改名"));
        assertCode(r, 400);
        assertMsg(r, CAS_FAIL);
    }

    @Test
    @DisplayName("52 updateBatch：未传字段保持现值（合并实体断言）")
    void updateBatchKeepsUntouchedFields() {
        CommunityBatch exist = batch(BATCH, "checking");
        exist.setScopeBuilding(BUILDING);
        exist.setNote("原说明");
        exist.setPublishDate(LocalDateTime.of(2025, 4, 1, 10, 0));
        when(batchMapper.findById(BATCH)).thenReturn(exist);
        when(batchMapper.update(any(), any())).thenReturn(1);

        assertOk(service.updateBatch(ADMIN, "admin", BATCH, body("batchName", "新名称")));

        ArgumentCaptor<CommunityBatch> cap = ArgumentCaptor.forClass(CommunityBatch.class);
        verify(batchMapper).update(cap.capture(), eq("checking"));
        CommunityBatch merged = cap.getValue();
        assertAll(
                () -> assertEquals("新名称", merged.getBatchName()),
                () -> assertEquals(TERM, merged.getTerm(), "未传 → 保持现值"),
                () -> assertEquals("hygiene", merged.getCheckType()),
                () -> assertEquals(BUILDING, merged.getScopeBuilding()),
                () -> assertEquals("原说明", merged.getNote()),
                () -> assertEquals(DEFAULT_CRITERIA, merged.getCriteriaJson()),
                () -> assertEquals("checking", merged.getStatus(), "未传状态 → 保持当前"),
                () -> assertEquals(LocalDateTime.of(2025, 4, 1, 10, 0), merged.getPublishDate()));
    }

    @Test
    @DisplayName("53 publishBatch：不存在 404；draft 可公布（fromStatus=draft）")
    void publishBatchFlow() {
        assertCode(service.publishBatch(ADMIN, "admin", 999L), 404);

        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "draft"));
        when(batchMapper.publish(eq(BATCH), eq("draft"), any())).thenReturn(1);
        Result<?> r = service.publishBatch(ADMIN, "admin", BATCH);
        assertOk(r);
        assertMsg(r, "批次已公布");
        verify(batchMapper).publish(eq(BATCH), eq("draft"), any());
    }

    @Test
    @DisplayName("54 publishBatch：已公布 / 已结束前置态 400，绝不落到 SQL")
    void publishBatchIllegalPrestate() {
        when(batchMapper.findById(BATCH))
                .thenReturn(batch(BATCH, "published"))
                .thenReturn(batch(BATCH, "finished"));
        assertAll(
                () -> assertMsg(service.publishBatch(ADMIN, "admin", BATCH),
                        "只有草稿 / 检查中 / 已评分的批次可以公布（当前 已公布）"),
                () -> assertMsg(service.publishBatch(ADMIN, "admin", BATCH),
                        "只有草稿 / 检查中 / 已评分的批次可以公布（当前 已结束）"));
        verify(batchMapper, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("55 publishBatch：CAS 0 行 → CAS_FAIL")
    void publishBatchCasFail() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "scored"));
        when(batchMapper.publish(eq(BATCH), eq("scored"), any())).thenReturn(0);
        Result<?> r = service.publishBatch(ADMIN, "admin", BATCH);
        assertCode(r, 400);
        assertMsg(r, CAS_FAIL);
    }

    @Test
    @DisplayName("56 closeBatch：scored 与 published 都可结束；draft 400 且不落 SQL")
    void closeBatchFlow() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "scored"));
        when(batchMapper.finish(eq(BATCH), eq("scored"))).thenReturn(1);
        Result<?> r = service.closeBatch(ADMIN, "admin", BATCH);
        assertOk(r);
        assertMsg(r, "批次已结束");
        verify(batchMapper).finish(BATCH, "scored");

        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "published"));
        when(batchMapper.finish(eq(BATCH), eq("published"))).thenReturn(1);
        assertOk(service.closeBatch(ADMIN, "admin", BATCH));

        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "draft"));
        assertMsg(service.closeBatch(ADMIN, "admin", BATCH),
                "只有已公布 / 已评分的批次可以结束（当前 草稿）");
        verify(batchMapper, never()).finish(BATCH, "draft");
    }

    @Test
    @DisplayName("57 closeBatch：不存在 404 / CAS 0 行 CAS_FAIL")
    void closeBatchErrors() {
        assertCode(service.closeBatch(ADMIN, "admin", 999L), 404);
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "published"));
        when(batchMapper.finish(eq(BATCH), eq("published"))).thenReturn(0);
        Result<?> r = service.closeBatch(ADMIN, "admin", BATCH);
        assertMsg(r, CAS_FAIL);
    }

    @Test
    @DisplayName("58 batchStats：不存在 404；成功含 batch/criteria/criteriaDemo/summary/gradeCount/rectifyCount")
    void batchStats() {
        assertCode(service.batchStats(ADMIN, "admin", 999L), 404);

        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "scored"));
        when(inspectionMapper.summary(any(), any(), any(), any())).thenReturn(new LinkedHashMap<>());
        when(inspectionMapper.countGroupByGrade(any(), any(), any(), any())).thenReturn(new ArrayList<>());
        when(inspectionMapper.countGroupByRectify(any(), any(), any(), any())).thenReturn(new ArrayList<>());

        Result<?> r = service.batchStats(LISI, "teacher", BATCH);
        assertOk(r);
        Map<String, Object> data = dataMap(r);
        assertAll(
                () -> assertEquals(Arrays.asList("batch", "criteria", "criteriaDemo", "summary",
                        "gradeCount", "rectifyCount"), new ArrayList<>(data.keySet())),
                () -> assertEquals(5, ((List<?>) data.get("criteria")).size()),
                () -> assertEquals(Boolean.TRUE, data.get("criteriaDemo"), "演示评分项要能被前端识别"));
    }

    // ==================================================================================
    // 4. 检查记录：录入 / 逐项评分 / 导入 / 删除 / 复查 / 学生本宿舍视图
    // ==================================================================================

    /** 教职工查检查记录的通用桩（list + countList 各一次）。 */
    private void stubInspectionPage(int total) {
        when(inspectionMapper.list(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), anyInt(), anyInt())).thenReturn(new ArrayList<>());
        when(inspectionMapper.countList(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean())).thenReturn(total);
    }

    @Test
    @DisplayName("59 listInspections：未登录 401 / 学生 403（检查结果按教职工侧）")
    void listInspectionsGuard() {
        assertCode(service.listInspections(null, "admin", null, TERM, null, null, null, null, null, null, 1, 20), 401);
        assertCode(service.listInspections(WANGXM, "student", null, TERM, null, null, null, null, null, null, 1, 20), 403);
    }

    @Test
    @DisplayName("60 listInspections：学生无档案 403；有档案但无宿舍 → 空页且不查库")
    void listInspectionsStudentScope() {
        assertCode(service.listInspections(WANGXM, "student", null, TERM, null, null, null, null, null, null, 1, 20), 403);

        StudentProfile p = profile(WANGXM, "王小明", "2024001", LISI);
        p.setDormBuilding(null);
        p.setDormRoom(null);
        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(p);

        Result<?> r = service.listInspections(WANGXM, "student", null, TERM, null, null, null, null, null, null, 1, 20);
        assertOk(r);
        assertTrue(((List<?>) dataMap(r).get("list")).isEmpty());
    }

    @Test
    @DisplayName("61 listInspections：学生查询被强制为本宿舍 + publishedOnly=true（看不见未公布结果）")
    void listInspectionsStudentForcedOwnRoom() {
        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(wangxm());
        stubInspectionPage(0);

        Result<?> r = service.listInspections(WANGXM, "student", null, TERM, BUILDING, ROOM,
                null, null, null, null, null, null);

        assertOk(r);
        Map<String, Object> page = dataMap(r);
        assertAll(
                () -> assertEquals(1, intOf(page.get("page")), "page 缺省 1"),
                () -> assertEquals(20, intOf(page.get("size")), "size 缺省 20"));
        verify(inspectionMapper).list(any(), eq(TERM), eq(BUILDING), eq(ROOM), any(), any(), any(), any(),
                any(), eq(true), eq(false), eq(0), eq(20));
    }

    @Test
    @DisplayName("62 listInspections：page<1 归 1、size 下限 20、上限 200（防全表拉取）")
    void listInspectionsPaging() {
        stubInspectionPage(3);
        assertOk(service.listInspections(LISI, "teacher", null, TERM, null, null, null, null, null, null, 0, 0));
        verify(inspectionMapper).list(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), eq(0), eq(20));

        stubInspectionPage(500);
        assertOk(service.listInspections(LISI, "teacher", null, TERM, null, null, null, null, null, null, 1, 500));
        verify(inspectionMapper).list(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), eq(0), eq(200));
    }

    @Test
    @DisplayName("63 listInspections：辅导员被限定自己名下（counselorId=自己），管理员不限")
    void listInspectionsCounselorScope() {
        stubInspectionPage(0);
        assertOk(service.listInspections(LISI, "teacher", null, TERM, null, null, null, null, null, null, 1, 20));
        verify(inspectionMapper).list(any(), any(), any(), any(), any(), any(), any(), any(), eq(LISI),
                anyBoolean(), anyBoolean(), anyInt(), anyInt());

        stubInspectionPage(0);
        assertOk(service.listInspections(ADMIN, "admin", null, TERM, null, null, null, null, null, null, 1, 20));
        verify(inspectionMapper).list(any(), any(), any(), any(), any(), any(), any(), any(), isNull(),
                anyBoolean(), anyBoolean(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("64 inspectionDetail：不存在 404；学生看别宿舍 403；未公布批次 403；本宿舍已公布放行")
    void inspectionDetailScope() {
        assertCode(service.inspectionDetail(LISI, "teacher", 999L), 404);

        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(wangxm());
        when(inspectionMapper.findById(INSPECTION)).thenReturn(inspection(INSPECTION, "B 栋", "B-101"));
        Result<?> other = service.inspectionDetail(WANGXM, "student", INSPECTION);
        assertCode(other, 403);
        assertMsg(other, "只能查看本宿舍的检查结果");

        CommunityInspection unpublished = inspection(INSPECTION, BUILDING, ROOM);
        unpublished.setBatchStatus("checking");
        when(inspectionMapper.findById(INSPECTION)).thenReturn(unpublished);
        Result<?> draft = service.inspectionDetail(WANGXM, "student", INSPECTION);
        assertCode(draft, 403);
        assertMsg(draft, "该批次检查结果尚未公布");

        when(inspectionMapper.findById(INSPECTION)).thenReturn(inspection(INSPECTION, BUILDING, ROOM));
        assertOk(service.inspectionDetail(WANGXM, "student", INSPECTION));
    }

    @Test
    @DisplayName("65 inspectionDetail：辅导员跨范围（非名下宿舍）403")
    void inspectionDetailCrossCounselor() {
        when(inspectionMapper.findById(INSPECTION)).thenReturn(inspection(INSPECTION, BUILDING, ROOM));
        when(inspectionMapper.countRoomOfCounselor(LISI, BUILDING, ROOM)).thenReturn(0);

        Result<?> r = service.inspectionDetail(LISI, "teacher", INSPECTION);

        assertCode(r, 403);
        assertMsg(r, "只能操作名下学生所在宿舍（" + BUILDING + " " + ROOM + " 不在你的负责范围）");
    }

    @Test
    @DisplayName("66 createInspection：缺 batchId / 批次不存在 404 / 批次不在 checking·scored 400")
    void createInspectionPrestate() {
        assertMsg(service.createInspection(ADMIN, "admin", body("building", BUILDING, "roomNo", ROOM, "score", 90)),
                "请指定检查批次 batchId");

        Result<?> notFound = service.createInspection(ADMIN, "admin",
                body("batchId", 999L, "building", BUILDING, "roomNo", ROOM, "score", 90));
        assertCode(notFound, 404);

        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "draft"));
        assertMsg(service.createInspection(ADMIN, "admin",
                        body("batchId", BATCH, "building", BUILDING, "roomNo", ROOM, "score", 90)),
                "批次不在检查中 / 已评分状态，不能录入检查记录（当前 草稿）");

        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "published"));
        assertMsg(service.createInspection(ADMIN, "admin",
                        body("batchId", BATCH, "building", BUILDING, "roomNo", ROOM, "score", 90)),
                "批次不在检查中 / 已评分状态，不能录入检查记录（当前 已公布）");
        verify(inspectionMapper, never()).insert(any());
    }

    @Test
    @DisplayName("67 createInspection：同批次同房间只允许一条（已在库则提示改用复查）")
    void createInspectionDuplicateRoom() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "checking"));
        when(inspectionMapper.findByBatchRoom(BATCH, BUILDING, ROOM))
                .thenReturn(inspection(INSPECTION, BUILDING, ROOM));

        Result<?> r = service.createInspection(ADMIN, "admin",
                body("batchId", BATCH, "building", BUILDING, "roomNo", ROOM, "score", 90));

        assertCode(r, 400);
        assertMsg(r, "该批次此房间已有检查记录（同一批次同一房间只允许一条），请改用复查或删除后重建");
        verify(inspectionMapper, never()).insert(any());
    }

    @Test
    @DisplayName("68 createInspection：楼栋 / 房号必填，且受批次 scopeBuilding 约束")
    void createInspectionRoomFields() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "checking"));
        assertAll(
                () -> assertMsg(service.createInspection(ADMIN, "admin", body("batchId", BATCH, "roomNo", ROOM)),
                        "请填写楼栋 building（student_profile.dorm_building 口径，如「A 栋」）"),
                () -> assertMsg(service.createInspection(ADMIN, "admin", body("batchId", BATCH, "building", BUILDING)),
                        "请填写房间号 roomNo（如「A-305」）"));

        CommunityBatch scoped = batch(BATCH, "checking");
        scoped.setScopeBuilding("B 栋");
        when(batchMapper.findById(BATCH)).thenReturn(scoped);
        assertMsg(service.createInspection(ADMIN, "admin",
                        body("batchId", BATCH, "building", BUILDING, "roomNo", ROOM, "score", 90)),
                "该批次只覆盖楼栋「B 栋」，不能录入「A 栋」");
    }

    @Test
    @DisplayName("69 createInspection：辅导员只能录入名下学生宿舍，跨范围 403")
    void createInspectionCrossCounselor() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "checking"));
        when(inspectionMapper.countRoomOfCounselor(LISI, BUILDING, ROOM)).thenReturn(0);

        Result<?> r = service.createInspection(LISI, "teacher",
                body("batchId", BATCH, "building", BUILDING, "roomNo", ROOM, "score", 90));

        assertCode(r, 403);
        verify(inspectionMapper, never()).insert(any());
    }

    @Test
    @DisplayName("70 createInspection：只填总分 → 自动算等级；checkDate 缺省今天；source 缺省 manual")
    void createInspectionScoreOnly() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "checking"));
        when(inspectionMapper.insert(any())).thenReturn(1);
        when(inspectionMapper.findById(any())).thenReturn(inspection(INSPECTION, BUILDING, ROOM));

        Result<?> r = service.createInspection(ADMIN, "admin", body("batchId", BATCH, "building", BUILDING,
                "roomNo", ROOM, "score", 88.5, "checkDate", "2025-03-12"));

        assertOk(r);
        CommunityInspection saved = captureInsertedInspection();
        assertAll(
                () -> assertEquals(new BigDecimal("88.50"), saved.getScore()),
                () -> assertEquals("good", saved.getGrade()),
                () -> assertEquals(0, saved.getRectifyRequired().intValue()),
                () -> assertNull(saved.getRectifyDeadline(), "不整改则无截止日"),
                () -> assertEquals("none", saved.getRectifyStatus()),
                () -> assertEquals("manual", saved.getSource()),
                () -> assertEquals(LocalDate.of(2025, 3, 12), saved.getCheckDate()),
                () -> assertEquals(BATCH, saved.getBatchId().longValue()),
                () -> assertEquals(ADMIN, saved.getInspectorId().longValue()),
                () -> assertNull(saved.getItemJson()));
    }

    @Test
    @DisplayName("71 createInspection：空分 / 超满分 / 负分 / 未知评分项逐项校验（§10.2-2 逐项汇总）")
    void createInspectionItemValidation() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "checking"));

        assertAll(
                () -> assertMsg(service.createInspection(ADMIN, "admin", body("batchId", BATCH, "building", BUILDING,
                                "roomNo", ROOM, "itemJson", "{\"power\":18,")),
                        "itemJson 格式不正确，应为 {\"power\":18,\"fire\":19}"),
                () -> assertTrue(service.createInspection(ADMIN, "admin", body("batchId", BATCH, "building", BUILDING,
                                "roomNo", ROOM, "itemJson", body("unknown", 10))).getMessage()
                        .startsWith("评分项「unknown」不在批次评分项里")),
                () -> assertMsg(service.createInspection(ADMIN, "admin", body("batchId", BATCH, "building", BUILDING,
                        "roomNo", ROOM, "itemJson", body("power", -1))), "评分项「power」的得分必须是非负数"),
                () -> assertMsg(service.createInspection(ADMIN, "admin", body("batchId", BATCH, "building", BUILDING,
                        "roomNo", ROOM, "itemJson", body("power", 25))), "评分项「power」超出该项满分 20"));
        verify(inspectionMapper, never()).insert(any());
    }

    @Test
    @DisplayName("72 createInspection：既无总分也无逐项分 400；分数越界 400")
    void createInspectionScoreRequired() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "checking"));
        assertAll(
                () -> assertMsg(service.createInspection(ADMIN, "admin",
                                body("batchId", BATCH, "building", BUILDING, "roomNo", ROOM)),
                        "请填写总分 score，或按批次评分项填写逐项得分 itemJson"),
                () -> assertMsg(service.createInspection(ADMIN, "admin",
                                body("batchId", BATCH, "building", BUILDING, "roomNo", ROOM, "score", 101)),
                        "总分必须在 0-100 之间"),
                () -> assertMsg(service.createInspection(ADMIN, "admin",
                                body("batchId", BATCH, "building", BUILDING, "roomNo", ROOM, "score", -1)),
                        "总分必须在 0-100 之间"));
    }

    @Test
    @DisplayName("73 createInspection：逐项分求和作为总分（各 20 分共 80 → good）")
    void createInspectionScoreFromItems() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "checking"));
        when(inspectionMapper.insert(any())).thenReturn(1);
        when(inspectionMapper.findById(any())).thenReturn(inspection(INSPECTION, BUILDING, ROOM));

        Map<String, Object> items = body("power", 20, "fire", 20, "hygiene", 20, "property", 20);
        Result<?> r = service.createInspection(ADMIN, "admin", body("batchId", BATCH, "building", BUILDING,
                "roomNo", ROOM, "itemJson", items));

        assertOk(r);
        CommunityInspection saved = captureInsertedInspection();
        assertAll(
                () -> assertEquals(new BigDecimal("80.00"), saved.getScore()),
                () -> assertEquals("good", saved.getGrade()),
                () -> assertNotNull(saved.getItemJson(), "逐项分明细要落库"),
                () -> assertEquals(0, saved.getRectifyRequired().intValue()));
    }

    @Test
    @DisplayName("74 createInspection：fail(<60) 触发整改（§10.2-7）：pending + 截止日 = 检查日 + 7 天")
    void createInspectionFailTriggersRectify() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "scored"));
        when(inspectionMapper.insert(any())).thenReturn(1);
        when(inspectionMapper.findById(any())).thenReturn(inspection(INSPECTION, BUILDING, ROOM));

        assertOk(service.createInspection(ADMIN, "admin", body("batchId", BATCH, "building", BUILDING,
                "roomNo", ROOM, "score", 59.9, "checkDate", "2025-03-12", "problems", "阳台堆放杂物")));

        CommunityInspection saved = captureInsertedInspection();
        assertAll(
                () -> assertEquals("fail", saved.getGrade()),
                () -> assertEquals(1, saved.getRectifyRequired().intValue()),
                () -> assertEquals("pending", saved.getRectifyStatus()),
                () -> assertEquals(LocalDate.of(2025, 3, 19), saved.getRectifyDeadline(), "检查日 + 7 天"),
                () -> assertEquals("阳台堆放杂物", saved.getProblems()));
    }

    @Test
    @DisplayName("75 createInspection：分数合格但 rectify_required=1 也触发整改；问题描述超 500 字被拦")
    void createInspectionRectifyFlagAndProblems() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "checking"));
        when(inspectionMapper.insert(any())).thenReturn(1);
        when(inspectionMapper.findById(any())).thenReturn(inspection(INSPECTION, BUILDING, ROOM));

        assertOk(service.createInspection(ADMIN, "admin", body("batchId", BATCH, "building", BUILDING,
                "roomNo", ROOM, "score", 95, "rectifyRequired", 1)));
        CommunityInspection saved = captureInsertedInspection();
        assertAll(
                () -> assertEquals("excellent", saved.getGrade()),
                () -> assertEquals(1, saved.getRectifyRequired().intValue(), "人工要求整改"),
                () -> assertEquals("pending", saved.getRectifyStatus()));

        assertMsg(service.createInspection(ADMIN, "admin", body("batchId", BATCH, "building", BUILDING,
                "roomNo", ROOM, "score", 95, "problems", repeat("问", 501))), "问题描述不能超过 500 字");
    }

    private CommunityInspection captureInsertedInspection() {
        ArgumentCaptor<CommunityInspection> cap = ArgumentCaptor.forClass(CommunityInspection.class);
        verify(inspectionMapper).insert(cap.capture());
        return cap.getValue();
    }

    @Test
    @DisplayName("76 importInspections：无 rows 也无 csv → 提示传数据；缺 batchId 先拦")
    void importInspectionsNoData() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "checking"));
        assertAll(
                () -> assertMsg(service.importInspections(ADMIN, "admin", body("csv", "a")),
                        "请指定检查批次 batchId"),
                () -> assertMsg(service.importInspections(ADMIN, "admin", body("batchId", BATCH)),
                        "没有可导入的数据：请传 rows 数组或 csv 文本（表头 building,roomNo,score,problems,checkDate）"));
    }

    @Test
    @DisplayName("77 importInspections：单次导入上限 2000 行")
    void importInspectionsTooManyRows() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "checking"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 2001; i++) {
            rows.add(body("building", BUILDING, "roomNo", "A-" + i, "score", 90));
        }
        Result<?> r = service.importInspections(ADMIN, "admin", body("batchId", BATCH, "rows", rows));
        assertCode(r, 400);
        assertMsg(r, "单次导入不超过 2000 行，当前 2001 行");
        verify(inspectionMapper, never()).insert(any());
    }

    @Test
    @DisplayName("78 importInspections：同一份数据里重复房间被逐条报错（row 从 2 起算）")
    void importInspectionsDuplicateRoom() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "checking"));
        when(inspectionMapper.insert(any())).thenReturn(1);

        List<Map<String, Object>> rows = Arrays.asList(
                body("building", BUILDING, "roomNo", ROOM, "score", 90),
                body("building", BUILDING, "roomNo", ROOM, "score", 80));

        Result<?> r = service.importInspections(ADMIN, "admin", body("batchId", BATCH, "rows", rows));

        assertOk(r);
        Map<String, Object> data = dataMap(r);
        List<?> errors = (List<?>) data.get("errors");
        assertAll(
                () -> assertEquals(2, intOf(data.get("total"))),
                () -> assertEquals(1, intOf(data.get("inserted"))),
                () -> assertEquals(1, intOf(data.get("failed"))),
                () -> assertEquals(1, errors.size()),
                () -> assertEquals("同一份数据里重复的房间：" + BUILDING + "|" + ROOM,
                        mapOf(errors.get(0)).get("message")),
                () -> assertEquals(3, intOf(mapOf(errors.get(0)).get("row"))));
    }

    @Test
    @DisplayName("79 importInspections：CSV 文本导入（英文表头，source 置 csv）")
    void importInspectionsCsvEnglishHeader() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "checking"));
        when(inspectionMapper.insert(any())).thenReturn(1);

        String csv = "building,roomNo,score,problems,checkDate\n"
                + BUILDING + "," + ROOM + ",88.5,阳台堆放杂物,2025-03-12\n";

        Result<?> r = service.importInspections(ADMIN, "admin", body("batchId", BATCH, "csv", csv));

        assertOk(r);
        Map<String, Object> data = dataMap(r);
        assertAll(
                () -> assertEquals(1, intOf(data.get("total"))),
                () -> assertEquals(1, intOf(data.get("inserted"))),
                () -> assertEquals(0, intOf(data.get("failed"))));
        CommunityInspection saved = captureInsertedInspection();
        assertAll(
                () -> assertEquals("csv", saved.getSource()),
                () -> assertEquals(new BigDecimal("88.50"), saved.getScore()),
                () -> assertEquals(BUILDING, saved.getBuilding()),
                () -> assertEquals(ROOM, saved.getRoomNo()),
                () -> assertEquals(LocalDate.of(2025, 3, 12), saved.getCheckDate()));
    }

    @Test
    @DisplayName("80 importInspections：CSV 中文表头（楼栋/房间号/总分）同样识别")
    void importInspectionsCsvChineseHeader() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "checking"));
        when(inspectionMapper.insert(any())).thenReturn(1);

        String csv = "楼栋,房间号,总分,问题描述\n" + BUILDING + ",A-306,91,无\n";

        Result<?> r = service.importInspections(ADMIN, "admin", body("batchId", BATCH, "csv", csv));

        assertOk(r);
        CommunityInspection saved = captureInsertedInspection();
        assertAll(
                () -> assertEquals(new BigDecimal("91.00"), saved.getScore()),
                () -> assertEquals("A-306", saved.getRoomNo()),
                () -> assertEquals("无", saved.getProblems()));
    }

    @Test
    @DisplayName("81 importInspections：全部未导入 → 400 汇总错误（首条原因带出）")
    void importInspectionsAllFailed() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "checking"));

        List<Map<String, Object>> rows = Arrays.asList(body("building", BUILDING));

        Result<?> r = service.importInspections(ADMIN, "admin", body("batchId", BATCH, "rows", rows));

        assertCode(r, 400);
        assertTrue(r.getMessage().startsWith("导入失败：共 1 行全部未导入（首条原因："));
        assertTrue(r.getMessage().contains("请填写房间号 roomNo"));
    }

    @Test
    @DisplayName("82 importInspections：部分失败仍成功返回，errors 逐行带 row/message")
    void importInspectionsPartial() {
        when(batchMapper.findById(BATCH)).thenReturn(batch(BATCH, "checking"));
        when(inspectionMapper.insert(any())).thenReturn(1);

        List<Map<String, Object>> rows = Arrays.asList(
                body("building", BUILDING, "roomNo", ROOM, "score", 90),
                body("building", BUILDING, "roomNo", "A-999", "score", 120));

        Result<?> r = service.importInspections(ADMIN, "admin", body("batchId", BATCH, "rows", rows));

        assertOk(r);
        Map<String, Object> data = dataMap(r);
        List<?> errors = (List<?>) data.get("errors");
        assertAll(
                () -> assertEquals(2, intOf(data.get("total"))),
                () -> assertEquals(1, intOf(data.get("inserted"))),
                () -> assertEquals(1, intOf(data.get("failed"))),
                () -> assertEquals("总分必须在 0-100 之间", mapOf(errors.get(0)).get("message")),
                () -> assertEquals(3, intOf(mapOf(errors.get(0)).get("row"))));
    }

    @Test
    @DisplayName("83 deleteInspection：不存在 404 / 已公布批次禁止删除 / CAS 0 行 400 / 正常删除")
    void deleteInspectionFlow() {
        assertCode(service.deleteInspection(ADMIN, "admin", 999L), 404);

        when(inspectionMapper.findById(INSPECTION)).thenReturn(inspection(INSPECTION, BUILDING, ROOM));
        assertMsg(service.deleteInspection(ADMIN, "admin", INSPECTION),
                "批次已公布或已结束，检查记录不能再删除（批次状态 已公布）");

        CommunityInspection inChecking = inspection(INSPECTION, BUILDING, ROOM);
        inChecking.setBatchStatus("checking");
        when(inspectionMapper.findById(INSPECTION)).thenReturn(inChecking);
        when(inspectionMapper.logicalDelete(INSPECTION)).thenReturn(0);
        assertMsg(service.deleteInspection(ADMIN, "admin", INSPECTION), CAS_FAIL);

        when(inspectionMapper.logicalDelete(INSPECTION)).thenReturn(1);
        Result<?> r = service.deleteInspection(ADMIN, "admin", INSPECTION);
        assertOk(r);
        assertMsg(r, "已删除检查记录");
    }

    @Test
    @DisplayName("84 recheckInspection：不存在 404 / 未提交整改 400 / 缺结论 400 / 结论非法 400")
    void recheckValidation() {
        assertCode(service.recheckInspection(ADMIN, "admin", 999L, body("recheckResult", "pass")), 404);

        when(inspectionMapper.findById(INSPECTION)).thenReturn(inspection(INSPECTION, BUILDING, ROOM));
        assertMsg(service.recheckInspection(ADMIN, "admin", INSPECTION, body("recheckResult", "pass")),
                "只有学生已提交整改说明的记录可以复查（当前 无需整改）");

        CommunityInspection submitted = inspection(INSPECTION, BUILDING, ROOM);
        submitted.setRectifyStatus("submitted");
        when(inspectionMapper.findById(INSPECTION)).thenReturn(submitted);
        assertAll(
                () -> assertMsg(service.recheckInspection(ADMIN, "admin", INSPECTION, null),
                        "请给出复查结论 recheckResult（pass 通过 / fail 未通过）"),
                () -> assertMsg(service.recheckInspection(ADMIN, "admin", INSPECTION, body("recheckResult", "maybe")),
                        "复查结论只支持 pass / fail"));
        verify(inspectionMapper, never()).recheck(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("85 recheckInspection：pass → accepted（CAS fromStatus=submitted），fail → rejected 退回整改")
    void recheckPassAndFail() {
        CommunityInspection submitted = inspection(INSPECTION, BUILDING, ROOM);
        submitted.setRectifyStatus("submitted");
        when(inspectionMapper.findById(INSPECTION)).thenReturn(submitted);
        when(inspectionMapper.recheck(eq(INSPECTION), eq("submitted"), eq("accepted"), any(), any(), any()))
                .thenReturn(1);

        Result<?> pass = service.recheckInspection(ADMIN, "admin", INSPECTION, body("recheckResult", "pass"));
        assertOk(pass);
        assertMsg(pass, "复查通过，整改闭环");
        verify(inspectionMapper).recheck(eq(INSPECTION), eq("submitted"), eq("accepted"), any(), any(), any());

        when(inspectionMapper.recheck(eq(INSPECTION), eq("submitted"), eq("rejected"), any(), any(), any()))
                .thenReturn(1);
        Result<?> fail = service.recheckInspection(ADMIN, "admin", INSPECTION, body("result", "fail"));
        assertOk(fail);
        assertMsg(fail, "复查未通过，已退回整改");
    }

    @Test
    @DisplayName("86 recheckInspection：CAS 0 行 → CAS_FAIL")
    void recheckCasFail() {
        CommunityInspection submitted = inspection(INSPECTION, BUILDING, ROOM);
        submitted.setRectifyStatus("submitted");
        when(inspectionMapper.findById(INSPECTION)).thenReturn(submitted);
        when(inspectionMapper.recheck(any(), any(), any(), any(), any(), any())).thenReturn(0);

        Result<?> r = service.recheckInspection(ADMIN, "admin", INSPECTION, body("recheckResult", "pass"));
        assertCode(r, 400);
        assertMsg(r, CAS_FAIL);
    }

    @Test
    @DisplayName("87 myRoom：学生无档案 403；无宿舍信息 → 空列表")
    void myRoomScope() {
        assertCode(service.myRoom(WANGXM, "student"), 403);

        StudentProfile noDorm = profile(WANGXM, "王小明", "2024001", LISI);
        noDorm.setDormBuilding(null);
        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(noDorm);
        Result<?> r = service.myRoom(WANGXM, "student");
        assertOk(r);
        assertTrue(listData(r).isEmpty());
    }

    @Test
    @DisplayName("88 myRoom：按本宿舍 + publishedOnly 查询，上限 200 条")
    void myRoomQuery() {
        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(wangxm());

        Result<?> r = service.myRoom(WANGXM, "student");

        assertOk(r);
        verify(inspectionMapper).list(isNull(), isNull(), eq(BUILDING), eq(ROOM), any(), any(), any(), any(),
                any(), eq(true), eq(false), eq(0), eq(200));
    }

    // ==================================================================================
    // 5. 整改闭环（§10.2-7）：pending/rejected 可提交 → submitted → 复查 pass/fail
    // ==================================================================================

    @Test
    @DisplayName("89 submitRectify：未登录 401 / 无档案 403 / 记录不存在 404")
    void submitRectifyGuard() {
        assertCode(service.submitRectify(null, "student", INSPECTION, body("note", "已整改")), 401);
        assertCode(service.submitRectify(WANGXM, "student", INSPECTION, body("note", "已整改")), 403);
        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(wangxm());
        assertCode(service.submitRectify(WANGXM, "student", 999L, body("note", "已整改")), 404);
    }

    @Test
    @DisplayName("90 submitRectify：只能提交本宿舍记录的整改说明（跨宿舍 403）")
    void submitRectifyOtherRoom() {
        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(wangxm());
        when(inspectionMapper.findById(INSPECTION)).thenReturn(inspection(INSPECTION, "B 栋", "B-101"));

        Result<?> r = service.submitRectify(WANGXM, "student", INSPECTION, body("note", "已整改"));

        assertCode(r, 403);
        assertMsg(r, "只能提交本宿舍检查记录的整改说明");
        verify(inspectionMapper, never()).submitRectify(any(), any(), any(), any());
    }

    @Test
    @DisplayName("91 submitRectify：只有 pending / rejected 可提交（none·accepted 一律 400）")
    void submitRectifyNotSubmittable() {
        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(wangxm());

        CommunityInspection none = inspection(INSPECTION, BUILDING, ROOM);
        none.setRectifyStatus("none");
        when(inspectionMapper.findById(INSPECTION)).thenReturn(none);
        assertMsg(service.submitRectify(WANGXM, "student", INSPECTION, body("note", "已整改")),
                "只有待整改 / 复查未通过的记录可以提交整改说明（当前 无需整改）");

        CommunityInspection accepted = inspection(INSPECTION, BUILDING, ROOM);
        accepted.setRectifyStatus("accepted");
        when(inspectionMapper.findById(INSPECTION)).thenReturn(accepted);
        assertMsg(service.submitRectify(WANGXM, "student", INSPECTION, body("note", "已整改")),
                "只有待整改 / 复查未通过的记录可以提交整改说明（当前 复查通过）");

        verify(inspectionMapper, never()).submitRectify(any(), any(), any(), any());
    }

    @Test
    @DisplayName("92 submitRectify：整改说明必填、≤500 字")
    void submitRectifyNote() {
        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(wangxm());
        CommunityInspection pending = inspection(INSPECTION, BUILDING, ROOM);
        pending.setRectifyStatus("pending");
        when(inspectionMapper.findById(INSPECTION)).thenReturn(pending);

        assertAll(
                () -> assertMsg(service.submitRectify(WANGXM, "student", INSPECTION, body("other", 1)),
                        "请填写整改说明"),
                () -> assertMsg(service.submitRectify(WANGXM, "student", INSPECTION, body("note", repeat("整", 501))),
                        "整改说明不能超过 500 字"));
    }

    @Test
    @DisplayName("93 submitRectify：pending 提交成功 → submitted（CAS fromStatus=pending）")
    void submitRectifyOk() {
        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(wangxm());
        CommunityInspection pending = inspection(INSPECTION, BUILDING, ROOM);
        pending.setRectifyStatus("pending");
        when(inspectionMapper.findById(INSPECTION)).thenReturn(pending);
        when(inspectionMapper.submitRectify(eq(INSPECTION), eq("pending"), any(), any())).thenReturn(1);

        Result<?> r = service.submitRectify(WANGXM, "student", INSPECTION, body("rectifyNote", "已清理阳台"));

        assertOk(r);
        assertMsg(r, "整改说明已提交，等待复查");
        verify(inspectionMapper).submitRectify(eq(INSPECTION), eq("pending"), eq("已清理阳台"), any());
    }

    @Test
    @DisplayName("94 submitRectify：复查未通过（rejected）后可再次提交（§10.2-7 闭环）")
    void submitRectifyRejectedResubmit() {
        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(wangxm());
        CommunityInspection rejected = inspection(INSPECTION, BUILDING, ROOM);
        rejected.setRectifyStatus("rejected");
        when(inspectionMapper.findById(INSPECTION)).thenReturn(rejected);
        when(inspectionMapper.submitRectify(eq(INSPECTION), eq("rejected"), any(), any())).thenReturn(1);

        Result<?> r = service.submitRectify(WANGXM, "student", INSPECTION, body("note", "二次整改"));

        assertOk(r);
        verify(inspectionMapper).submitRectify(eq(INSPECTION), eq("rejected"), eq("二次整改"), any());
    }

    @Test
    @DisplayName("95 submitRectify：CAS 0 行 → CAS_FAIL")
    void submitRectifyCasFail() {
        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(wangxm());
        CommunityInspection pending = inspection(INSPECTION, BUILDING, ROOM);
        pending.setRectifyStatus("pending");
        when(inspectionMapper.findById(INSPECTION)).thenReturn(pending);
        when(inspectionMapper.submitRectify(any(), any(), any(), any())).thenReturn(0);

        Result<?> r = service.submitRectify(WANGXM, "student", INSPECTION, body("note", "已整改"));
        assertCode(r, 400);
        assertMsg(r, CAS_FAIL);
    }

    @Test
    @DisplayName("96 myRectifications：无档案 403；无宿舍 → 空；否则按本宿舍 + 只看整改记录查询")
    void myRectifications() {
        assertCode(service.myRectifications(WANGXM, "student"), 403);

        StudentProfile noDorm = profile(WANGXM, "王小明", "2024001", LISI);
        noDorm.setDormBuilding(null);
        noDorm.setDormRoom(null);
        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(noDorm);
        assertTrue(listData(service.myRectifications(WANGXM, "student")).isEmpty());

        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(wangxm());
        when(inspectionMapper.list(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), anyInt(), anyInt())).thenReturn(new ArrayList<>());
        assertOk(service.myRectifications(WANGXM, "student"));
        verify(inspectionMapper).list(isNull(), isNull(), eq(BUILDING), eq(ROOM), any(), any(), any(), any(),
                any(), eq(true), eq(true), eq(0), eq(200));
    }

    // ==================================================================================
    // 6. 文明宿舍（§10.2-6）：只认已发布批次、楼栋内排名、名额不自动截断
    // ==================================================================================

    @Test
    @DisplayName("97 listCivilRooms：学生 403 / 教职工放行并附筛选条件")
    void listCivilRooms() {
        assertCode(service.listCivilRooms(WANGXM, "student", TERM, BUILDING, null), 403);

        when(civilRoomMapper.list(TERM, BUILDING, null, false))
                .thenReturn(Arrays.asList(civilRoom(CIVIL, ROOM, "90.00", "80.00")));
        Result<?> r = service.listCivilRooms(LISI, "teacher", TERM, BUILDING, null);
        assertOk(r);
        assertEquals(1, listData(r).size());
    }

    @Test
    @DisplayName("98 computeCivilRooms：缺 semester / 无已公布批次 / 已发布批次里无检查记录 分别拦")
    void computeCivilRoomsPreconditions() {
        assertMsg(service.computeCivilRooms(ADMIN, "admin", body()), "请指定学期 term（如 2025-2026-1）");

        when(batchMapper.listByTermAndStatuses(TERM, CommunityService.PUBLISHED_STATUSES))
                .thenReturn(new ArrayList<>());
        assertMsg(service.computeCivilRooms(ADMIN, "admin", body("term", TERM)),
                "该学期没有已公布 / 已结束的检查批次，无法汇总文明宿舍（公示口径只认已发布批次）");

        when(batchMapper.listByTermAndStatuses(TERM, CommunityService.PUBLISHED_STATUSES))
                .thenReturn(Arrays.asList(batch(BATCH, "published")));
        when(civilRoomMapper.aggregate(TERM, CommunityService.PUBLISHED_STATUSES)).thenReturn(new ArrayList<>());
        assertMsg(service.computeCivilRooms(ADMIN, "admin", body("term", TERM)),
                "该学期已发布批次里没有检查记录，无法汇总文明宿舍");
    }

    @Test
    @DisplayName("99 computeCivilRooms：楼栋内均分排名 + 新建候选（awarded=0、batchIds 转 JSON 数组）")
    void computeCivilRoomsRankAndInsert() {
        stubCivilAggregate(Arrays.asList(
                aggRow(ROOM, "90.00", "80.00", 4, "901,902"),
                aggRow("A-306", "85.00", "70.00", 4, "901")));
        when(civilRoomMapper.findByTermRoom(any(), any(), any())).thenReturn(null);
        when(civilRoomMapper.insert(any())).thenReturn(1);
        when(civilRoomMapper.list(TERM, null, null, false))
                .thenReturn(Arrays.asList(civilRoom(CIVIL, ROOM, "90.00", "80.00")));

        Result<?> r = service.computeCivilRooms(ADMIN, "admin", body("term", TERM));

        assertOk(r);
        Map<String, Object> data = dataMap(r);
        assertAll(
                () -> assertEquals(TERM, data.get("term")),
                () -> assertEquals(1, intOf(data.get("batchCount"))),
                () -> assertEquals(2, intOf(data.get("created"))),
                () -> assertEquals(0, intOf(data.get("refreshed"))));

        ArgumentCaptor<CommunityCivilRoom> cap = ArgumentCaptor.forClass(CommunityCivilRoom.class);
        verify(civilRoomMapper, times(2)).insert(cap.capture());
        List<CommunityCivilRoom> saved = cap.getAllValues();
        assertAll(
                () -> assertEquals(new BigDecimal("90.00"), saved.get(0).getAvgScore()),
                () -> assertEquals(1, saved.get(0).getRankInBuilding().intValue(), "均分最高 → 第 1"),
                () -> assertEquals(2, saved.get(1).getRankInBuilding().intValue(), "次高 → 第 2"),
                () -> assertEquals(new BigDecimal("80.00"), saved.get(0).getMinScore()),
                () -> assertEquals(4, saved.get(0).getMemberCount().intValue()),
                () -> assertEquals(0, saved.get(0).getAwarded().intValue(), "候选默认未获评"),
                () -> assertEquals("[901,902]", saved.get(0).getBatchIds()),
                () -> assertEquals(TERM, saved.get(0).getTerm()));
        verify(civilRoomMapper).deleteDraftByTerm(TERM);
    }

    @Test
    @DisplayName("100 computeCivilRooms：同分并列同名次且名额不自动截断（多间同分全部保留）")
    void computeCivilRoomsNoQuotaTruncation() {
        stubCivilAggregate(Arrays.asList(
                aggRow("A-301", "90.00", "90.00", 4, "901"),
                aggRow("A-302", "90.00", "90.00", 4, "901"),
                aggRow("A-303", "90.00", "90.00", 4, "901")));
        when(civilRoomMapper.findByTermRoom(any(), any(), any())).thenReturn(null);
        when(civilRoomMapper.insert(any())).thenReturn(1);
        when(civilRoomMapper.list(TERM, null, null, false)).thenReturn(new ArrayList<>());

        Result<?> r = service.computeCivilRooms(ADMIN, "admin", body("term", TERM));

        assertOk(r);
        ArgumentCaptor<CommunityCivilRoom> cap = ArgumentCaptor.forClass(CommunityCivilRoom.class);
        verify(civilRoomMapper, times(3)).insert(cap.capture());
        for (CommunityCivilRoom saved : cap.getAllValues()) {
            assertEquals(1, saved.getRankInBuilding().intValue(), "同分并列第 1");
            assertEquals(0, saved.getAwarded().intValue(), "名额与比例未公布 → 只给候选，不自动截断");
        }
        assertEquals(3, intOf(dataMap(r).get("created")));
    }

    @Test
    @DisplayName("101 computeCivilRooms：同宿舍已存在 → refresh 刷新而非新建；CAS 0 行 400")
    void computeCivilRoomsRefresh() {
        stubCivilAggregate(Arrays.asList(aggRow(ROOM, "90.00", "80.00", 4, "901")));
        when(civilRoomMapper.findByTermRoom(TERM, BUILDING, ROOM))
                .thenReturn(civilRoom(CIVIL, ROOM, "88.00", "78.00"));
        when(civilRoomMapper.refresh(any())).thenReturn(1);
        when(civilRoomMapper.list(TERM, null, null, false)).thenReturn(new ArrayList<>());

        Result<?> r = service.computeCivilRooms(ADMIN, "admin", body("term", TERM));
        assertOk(r);
        assertAll(
                () -> assertEquals(0, intOf(dataMap(r).get("created"))),
                () -> assertEquals(1, intOf(dataMap(r).get("refreshed"))));
        ArgumentCaptor<CommunityCivilRoom> cap = ArgumentCaptor.forClass(CommunityCivilRoom.class);
        verify(civilRoomMapper).refresh(cap.capture());
        assertAll(
                () -> assertEquals(CIVIL, cap.getValue().getId().longValue(), "刷新保留原 id"),
                () -> assertEquals(new BigDecimal("90.00"), cap.getValue().getAvgScore()));
        verify(civilRoomMapper, never()).insert(any());

        when(civilRoomMapper.refresh(any())).thenReturn(0);
        Result<?> casFail = service.computeCivilRooms(ADMIN, "admin", body("term", TERM));
        assertCode(casFail, 400);
        assertMsg(casFail, CAS_FAIL);
    }

    private void stubCivilAggregate(List<Map<String, Object>> rows) {
        when(batchMapper.listByTermAndStatuses(TERM, CommunityService.PUBLISHED_STATUSES))
                .thenReturn(Arrays.asList(batch(BATCH, "published")));
        when(civilRoomMapper.aggregate(TERM, CommunityService.PUBLISHED_STATUSES)).thenReturn(rows);
        when(civilRoomMapper.deleteDraftByTerm(TERM)).thenReturn(0);
    }

    @Test
    @DisplayName("102 publishCivilRoom：不存在 404 / 已公示不能重复 / awarded 与 awardLevel 枚举校验")
    void publishCivilRoomValidation() {
        assertCode(service.publishCivilRoom(ADMIN, "admin", 999L, body()), 404);

        CommunityCivilRoom published = civilRoom(CIVIL, ROOM, "90.00", "80.00");
        published.setPublishedAt(LocalDateTime.of(2025, 4, 1, 9, 0));
        when(civilRoomMapper.findById(CIVIL)).thenReturn(published);
        assertMsg(service.publishCivilRoom(ADMIN, "admin", CIVIL, body()),
                "该宿舍已公示，不能重复公示");

        when(civilRoomMapper.findById(CIVIL)).thenReturn(civilRoom(CIVIL, ROOM, "90.00", "80.00"));
        assertMsg(service.publishCivilRoom(ADMIN, "admin", CIVIL, body("awarded", 5)),
                "awarded 只能是 0（候选）或 1（已评文明宿舍）");
        assertMsg(service.publishCivilRoom(ADMIN, "admin", CIVIL, body("awardLevel", "national")),
                "获奖等级只支持 school（校级）/ college（院级）");
        verify(civilRoomMapper, never()).publish(any(), any(), any(), any());
    }

    @Test
    @DisplayName("103 publishCivilRoom：awarded 缺省 1 且等级缺省院级；显式校级可用；CAS 0 行 400")
    void publishCivilRoomFlow() {
        when(civilRoomMapper.findById(CIVIL)).thenReturn(civilRoom(CIVIL, ROOM, "90.00", "80.00"));
        when(civilRoomMapper.publish(eq(CIVIL), eq(1), eq("college"), any())).thenReturn(1);

        Result<?> r = service.publishCivilRoom(ADMIN, "admin", CIVIL, body());
        assertOk(r);
        assertMsg(r, "已公示");
        verify(civilRoomMapper).publish(eq(CIVIL), eq(1), eq("college"), any());

        when(civilRoomMapper.publish(eq(CIVIL), eq(1), eq("school"), any())).thenReturn(1);
        assertOk(service.publishCivilRoom(ADMIN, "admin", CIVIL,
                body("awarded", 1, "awardLevel", "school")));

        when(civilRoomMapper.publish(any(), any(), any(), any())).thenReturn(0);
        Result<?> casFail = service.publishCivilRoom(ADMIN, "admin", CIVIL, body());
        assertCode(casFail, 400);
        assertMsg(casFail, CAS_FAIL);
    }

    @Test
    @DisplayName("104 civilPublicity：匿名 401（公示页登录必需）；学生登录可见")
    void civilPublicityGuard() {
        Result<?> anonymous = service.civilPublicity(null, null, TERM, BUILDING);
        assertCode(anonymous, 401);
        assertMsg(anonymous, "请先登录");
        verifyNoInteractions(civilRoomMapper);

        when(civilRoomMapper.list(TERM, BUILDING, null, true))
                .thenReturn(Arrays.asList(civilRoom(CIVIL, ROOM, "90.00", "80.00")));
        assertOk(service.civilPublicity(WANGXM, "student", TERM, BUILDING));
    }

    @Test
    @DisplayName("105 civilPublicity：只露楼栋/房号/均分/等级，不含姓名学号（§10.2-5 公示脱敏）")
    void civilPublicityDesensitized() {
        CommunityCivilRoom room = civilRoom(CIVIL, ROOM, "90.00", "80.00");
        room.setAwardLevel("college");
        when(civilRoomMapper.list(TERM, BUILDING, null, true)).thenReturn(Arrays.asList(room));

        Result<?> r = service.civilPublicity(WANGXM, "student", TERM, BUILDING);

        assertOk(r);
        Map<String, Object> row = mapOf(listData(r).get(0));
        assertAll(
                () -> assertEquals(Arrays.asList("building", "roomNo", "avgScore", "awardLevel"),
                        new ArrayList<>(row.keySet()), "只允许这四个字段"),
                () -> assertEquals(BUILDING, row.get("building")),
                () -> assertEquals(ROOM, row.get("roomNo")),
                () -> assertEquals("college", row.get("awardLevel")),
                () -> assertFalse(row.containsKey("memberCount")),
                () -> assertFalse(row.containsKey("minScore")),
                () -> assertFalse(row.containsKey("leaderStudentId")));
    }

    // ==================================================================================
    // 7. 统计与导出（§10.2-11）：BOM / 表头 / 行数与脱敏
    // ==================================================================================

    @Test
    @DisplayName("106 stats：学生 403；教职工拿到 17 项汇总（覆盖检查/晚归/违规/活动/文明宿舍）")
    void stats() {
        assertCode(service.stats(WANGXM, "student", TERM, null, null), 403);

        Result<?> r = service.stats(LISI, "teacher", TERM, "2025-03-01", "2025-03-31");

        assertOk(r);
        Map<String, Object> data = dataMap(r);
        assertAll(
                () -> assertEquals(Arrays.asList("term", "startDate", "endDate", "batches", "batchStatus",
                        "inspections", "inspectionGrade", "inspectionRectify", "lates", "lateType", "lateHandling",
                        "violations", "violationType", "violationAdvice", "activities", "activityStatus",
                        "civilRooms"), new ArrayList<>(data.keySet())),
                () -> assertEquals(TERM, data.get("term")));
    }

    @Test
    @DisplayName("107 exportCsv：学生 403（errorCode 落 CsvExport 而非抛异常）；缺 type / 非法 type → 400")
    void exportCsvTypeValidation() {
        CommunityService.CsvExport denied = service.exportCsv(WANGXM, "student", "inspections", null, null, null, null, null, null);
        assertAll(
                () -> assertEquals(403, denied.errorCode.intValue()),
                () -> assertNull(denied.csv));

        CommunityService.CsvExport missing = service.exportCsv(LISI, "teacher", null, null, null, null, null, null, null);
        assertAll(
                () -> assertEquals(400, missing.errorCode.intValue()),
                () -> assertEquals("请指定导出类型 type（inspections / lates / violations / civil-rooms）",
                        missing.errorMessage));

        CommunityService.CsvExport bad = service.exportCsv(LISI, "teacher", "rooms", null, null, null, null, null, null);
        assertAll(
                () -> assertEquals(400, bad.errorCode.intValue()),
                () -> assertEquals("导出类型只支持 inspections / lates / violations / civil-rooms",
                        bad.errorMessage));
    }

    @Test
    @DisplayName("108 exportCsv：inspections 表头固定、行数与库一致、服务层 csv 不含 BOM（BOM 由控制器加）")
    void exportCsvInspections() {
        when(inspectionMapper.list(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(Arrays.asList(inspection(INSPECTION, BUILDING, ROOM), inspection(9030L, "A-306", "A-306")));

        CommunityService.CsvExport out = service.exportCsv(LISI, "teacher", "inspections", TERM, BUILDING, null, null, null, null);

        assertNull(out.errorCode);
        assertEquals("community-inspections.csv", out.filename);
        assertFalse(out.csv.startsWith("\uFEFF"), "服务层不带 BOM");
        String[] lines = out.csv.split("\r\n");
        assertAll(
                () -> assertEquals("批次,学期,楼栋,房号,检查日期,总分,等级,整改状态,整改截止,问题描述,检查人,来源", lines[0]),
                () -> assertEquals(3, lines.length, "表头 + 与库一致的两行"),
                () -> assertTrue(lines[1].startsWith("2025-2026-1 卫生检查," + TERM + "," + BUILDING + "," + ROOM)),
                () -> assertTrue(lines[1].contains("2025-03-12")),
                () -> assertTrue(lines[1].contains("88.50")),
                () -> assertTrue(lines[1].contains("良好"), "等级要转中文"),
                () -> assertTrue(lines[1].contains("无需整改")));
    }

    @Test
    @DisplayName("109 exportCsv：lates 姓名掩码 / 学号后 4 位 / 类型与核实态中文化（导出一律脱敏）")
    void exportCsvLates() {
        when(lateMapper.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Arrays.asList(late(LATE, "late", "pending", 0), late(9031L, "absent", "notified", 1)));

        CommunityService.CsvExport out = service.exportCsv(LISI, "teacher", "lates", TERM, BUILDING, null, null, null, null);

        assertNull(out.errorCode);
        assertEquals("community-lates.csv", out.filename);
        String[] lines = out.csv.split("\r\n");
        assertAll(
                () -> assertEquals("发生日期,类型,姓名,学号,楼栋,房号,外出时间,回园区时间,来源,原因说明,是否核实,处理状态",
                        lines[0]),
                () -> assertEquals(3, lines.length),
                () -> assertFalse(out.csv.contains("王小明"), "导出不得出现完整姓名"),
                () -> assertFalse(out.csv.contains("2024001"), "导出不得出现完整学号"),
                () -> assertTrue(lines[1].contains("王*"), "姓名掩码"),
                () -> assertTrue(lines[1].contains("4001"), "学号后 4 位"),
                () -> assertTrue(lines[1].contains("晚归")),
                () -> assertTrue(lines[1].contains("未核实")),
                () -> assertTrue(lines[2].contains("夜不归宿")),
                () -> assertTrue(lines[2].contains("已核实")));
    }

    @Test
    @DisplayName("110 exportCsv：violations 表头不含处理结果/处理人，姓名学号同样脱敏")
    void exportCsvViolations() {
        when(violationMapper.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Arrays.asList(violation(VIOLATION, "notified")));

        CommunityService.CsvExport out = service.exportCsv(LISI, "teacher", "violations", TERM, BUILDING, null, null, null, null);

        assertNull(out.errorCode);
        assertEquals("community-violations.csv", out.filename);
        String[] lines = out.csv.split("\r\n");
        assertAll(
                () -> assertEquals("发生时间,类型,姓名,学号,楼栋,房号,违规事实,依据条文,处理建议,处理状态", lines[0]),
                () -> assertFalse(out.csv.contains("王小明")),
                () -> assertFalse(out.csv.contains("2024001")),
                () -> assertFalse(out.csv.contains("已批评教育"), "结果说明不外发"),
                () -> assertFalse(out.csv.contains("李四"), "处理人不外发"),
                () -> assertTrue(lines[1].contains("王*")),
                () -> assertTrue(lines[1].contains("4001")),
                () -> assertTrue(lines[1].contains("违规电器")),
                () -> assertTrue(lines[1].contains("园区管理规定第四十一条第九项"), "依据条文必须导出"),
                () -> assertTrue(lines[1].contains("教育")));
    }

    @Test
    @DisplayName("111 exportCsv：civil-rooms 表头固定、获评态中文化")
    void exportCsvCivilRooms() {
        CommunityCivilRoom awarded = civilRoom(CIVIL, ROOM, "90.00", "80.00");
        awarded.setAwarded(1);
        awarded.setAwardLevel("college");
        awarded.setPublishedAt(LocalDateTime.of(2025, 4, 1, 9, 0));
        when(civilRoomMapper.list(TERM, BUILDING, null, false)).thenReturn(Arrays.asList(awarded));

        CommunityService.CsvExport out = service.exportCsv(LISI, "teacher", "civil-rooms", TERM, BUILDING, null, null, null, null);

        assertNull(out.errorCode);
        assertEquals("community-civil-rooms.csv", out.filename);
        String[] lines = out.csv.split("\r\n");
        assertAll(
                () -> assertEquals("学期,楼栋,房号,学期均分,最低分,楼栋内名次,是否获评,获奖等级,公示时间", lines[0]),
                () -> assertEquals(2, lines.length),
                () -> assertTrue(lines[1].contains("已评")),
                () -> assertTrue(lines[1].contains("college")),
                () -> assertTrue(lines[1].contains("2025-04-01 09:00:00")));
    }

    @Test
    @DisplayName("112 exportCsv：行数超 100000 上限 → 400 提示缩小范围（防 OOM）")
    void exportCsvTooManyRows() {
        List<CommunityInspection> huge = new AbstractList<CommunityInspection>() {
            @Override
            public int size() {
                return CommunityService.MAX_EXPORT + 1;
            }

            @Override
            public CommunityInspection get(int index) {
                throw new IndexOutOfBoundsException();
            }
        };
        when(inspectionMapper.list(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), anyInt(), anyInt())).thenReturn(huge);

        CommunityService.CsvExport out = service.exportCsv(ADMIN, "admin", "inspections", null, null, null, null, null, null);

        assertAll(
                () -> assertEquals(400, out.errorCode.intValue()),
                () -> assertNull(out.csv),
                () -> assertTrue(out.errorMessage.startsWith("导出数据量超过 100000 行")));
    }

    @Test
    @DisplayName("113 controller /export：首字节 UTF-8 BOM + text/csv + attachment 文件名 + Content-Length")
    void controllerExportAddsBom() {
        when(inspectionMapper.list(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(Arrays.asList(inspection(INSPECTION, BUILDING, ROOM)));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", ADMIN);
        session.setAttribute("role", "admin");

        ResponseEntity<byte[]> resp = controller.export(session, "inspections", TERM, BUILDING, null, null, null, null);

        assertEquals(200, resp.getStatusCode().value());
        byte[] bytes = resp.getBody();
        assertNotNull(bytes);
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        assertAll(
                () -> assertEquals(0xEF, bytes[0] & 0xFF),
                () -> assertEquals(0xBB, bytes[1] & 0xFF),
                () -> assertEquals(0xBF, bytes[2] & 0xFF),
                () -> assertTrue(text.startsWith("\uFEFF批次,学期,楼栋,房号,检查日期,总分,等级,整改状态,整改截止,问题描述,检查人,来源")),
                () -> assertTrue(resp.getHeaders().getContentType().toString().contains("text/csv")),
                () -> assertEquals("attachment; filename=\"community-inspections.csv\"",
                        resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)),
                () -> assertEquals(bytes.length, resp.getHeaders().getContentLength()));
    }

    @Test
    @DisplayName("114 controller /export：业务失败回该状态码且无 body（非法 type 400 / 未登录 401）")
    void controllerExportErrorStatus() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", ADMIN);
        session.setAttribute("role", "admin");

        ResponseEntity<byte[]> badType = controller.export(session, "rooms", null, null, null, null, null, null);
        assertAll(
                () -> assertEquals(400, badType.getStatusCode().value()),
                () -> assertNull(badType.getBody()));

        ResponseEntity<byte[]> anonymous = controller.export(new MockHttpSession(), "inspections", null, null, null, null, null, null);
        assertAll(
                () -> assertEquals(401, anonymous.getStatusCode().value()),
                () -> assertNull(anonymous.getBody()));
    }

    // ==================================================================================
    // 8. 晚归 / 夜不归宿（§10.2-3）：正常出入 6:00-23:30，时段之外才是晚归
    // ==================================================================================

    /** 晚归查询的通用桩。 */
    private void stubLatePage(int total) {
        when(lateMapper.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
        when(lateMapper.countList(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(total);
    }

    /** 辅导员可操作名下学生的桩。 */
    private void allowStudent() {
        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(wangxm());
        when(lateMapper.countStudentOfCounselor(LISI, WANGXM)).thenReturn(1);
    }

    @Test
    @DisplayName("115 listLates：未登录 401 / 学生 403 / 教职工放行")
    void listLatesGuard() {
        assertCode(lateService.listLates(null, "admin", null, null, null, null, null, null, null, null, 1, 20), 401);
        assertCode(lateService.listLates(WANGXM, "student", null, null, null, null, null, null, null, null, 1, 20), 403);

        stubLatePage(0);
        assertOk(lateService.listLates(LISI, "teacher", null, null, null, null, null, null, null, null, 1, 20));
    }

    @Test
    @DisplayName("116 listLates：confirmed 字符串 false → 0 传入 SQL；辅导员被限定名下")
    void listLatesConfirmedFilter() {
        stubLatePage(0);

        assertOk(lateService.listLates(LISI, "teacher", null, BUILDING, "late", "pending", "false", null, null, null, 1, 20));

        verify(lateMapper).list(isNull(), eq(BUILDING), eq("late"), eq("pending"), eq(0), isNull(), eq(LISI),
                isNull(), isNull(), eq(0), eq(20));
    }

    @Test
    @DisplayName("117 createLate：缺 studentId / 学生档案不存在 404 / 跨辅导员 403")
    void createLateGuard() {
        assertMsg(lateService.createLate(LISI, "teacher", body()), "请指定学生 studentId");

        Result<?> noProfile = lateService.createLate(LISI, "teacher", body("studentId", WANGXM));
        assertCode(noProfile, 404);
        assertMsg(noProfile, "学生档案不存在（student_profile 里没有该学生）");

        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(wangxm());
        when(lateMapper.countStudentOfCounselor(LISI, WANGXM)).thenReturn(0);
        Result<?> cross = lateService.createLate(LISI, "teacher", body("studentId", WANGXM));
        assertCode(cross, 403);
        assertMsg(cross, "只能操作名下学生的记录（该学生不在你的负责范围）");
        verify(lateMapper, never()).insert(any());
    }

    @Test
    @DisplayName("118 createLate：缺发生日期 / 类型非法 / 来源非法")
    void createLateEnums() {
        allowStudent();
        assertAll(
                () -> assertMsg(lateService.createLate(LISI, "teacher", body("studentId", WANGXM)),
                        "请填写发生日期 occurDate（yyyy-MM-dd）"),
                () -> assertMsg(lateService.createLate(LISI, "teacher",
                                body("studentId", WANGXM, "occurDate", "2025-03-10", "type", "night")),
                        "类型只支持 late（晚归）/ absent（夜不归宿）"),
                () -> assertMsg(lateService.createLate(LISI, "teacher", body("studentId", WANGXM,
                                "occurDate", "2025-03-10", "source", "gate")),
                        "来源只支持 card（门禁导入）/ check（巡查）/ report（上报）"));
        verify(lateMapper, never()).insert(any());
    }

    @Test
    @DisplayName("119 createLate：时间格式错 / 晚归必须至少给一个时间")
    void createLateTimes() {
        allowStudent();
        assertAll(
                () -> assertTrue(lateService.createLate(LISI, "teacher", body("studentId", WANGXM,
                        "occurDate", "2025-03-10", "outTime", "25:00")).getMessage().startsWith("外出时间 outTime 格式不正确")),
                () -> assertTrue(lateService.createLate(LISI, "teacher", body("studentId", WANGXM,
                        "occurDate", "2025-03-10", "inTime", "abc")).getMessage().startsWith("回园区时间 inTime 格式不正确")),
                () -> assertMsg(lateService.createLate(LISI, "teacher",
                                body("studentId", WANGXM, "occurDate", "2025-03-10", "type", "late")),
                        "晚归记录至少要有一个时间：外出时间 outTime 或回园区时间 inTime"));
    }

    @Test
    @DisplayName("120 createLate：正常出入 6:00-23:30 内不算晚归（06:00 / 23:30 逐点断言）；23:31 / 05:59 才算")
    void createLateWindowBoundary() {
        allowStudent();

        Result<?> atOpen = lateService.createLate(LISI, "teacher", body("studentId", WANGXM,
                "occurDate", "2025-03-10", "outTime", "06:00"));
        Result<?> atClose = lateService.createLate(LISI, "teacher", body("studentId", WANGXM,
                "occurDate", "2025-03-10", "outTime", "23:30"));
        assertAll(
                () -> assertCode(atOpen, 400),
                () -> assertTrue(atOpen.getMessage().startsWith("晚归时间必须落在 6:00-23:30 之外")),
                () -> assertCode(atClose, 400),
                () -> assertTrue(atClose.getMessage().startsWith("晚归时间必须落在 6:00-23:30 之外")),
                () -> assertTrue(atClose.getMessage().contains("23:30"), "边界时刻在正常时段内"));

        when(lateMapper.insert(any())).thenReturn(1);
        when(lateMapper.findById(any())).thenReturn(late(LATE, "late", "pending", 0));
        assertOk(lateService.createLate(LISI, "teacher", body("studentId", WANGXM,
                "occurDate", "2025-03-10", "outTime", "23:31")));
        assertOk(lateService.createLate(LISI, "teacher", body("studentId", WANGXM,
                "occurDate", "2025-03-11", "outTime", "05:59")));
    }

    @Test
    @DisplayName("121 createLate：reason 缺省 late_return / night_out，且长度受限")
    void createLateReasonDefaults() {
        allowStudent();
        when(lateMapper.insert(any())).thenReturn(1);
        when(lateMapper.findById(any())).thenReturn(late(LATE, "late", "pending", 0));

        assertOk(lateService.createLate(LISI, "teacher", body("studentId", WANGXM,
                "occurDate", "2025-03-10", "outTime", "23:45")));
        ArgumentCaptor<CommunityLate> cap = ArgumentCaptor.forClass(CommunityLate.class);
        verify(lateMapper).insert(cap.capture());
        assertEquals("late_return", cap.getValue().getReason());

        assertOk(lateService.createLate(LISI, "teacher", body("studentId", WANGXM,
                "occurDate", "2025-03-11", "type", "absent")));
        verify(lateMapper, times(2)).insert(cap.capture());
        assertEquals("night_out", cap.getValue().getReason(), "夜不归宿缺省 night_out");

        assertAll(
                () -> assertMsg(lateService.createLate(LISI, "teacher", body("studentId", WANGXM,
                        "occurDate", "2025-03-12", "outTime", "23:45", "reason", repeat("r", 33))),
                        "原因分类码不能超过 32 字"),
                () -> assertMsg(lateService.createLate(LISI, "teacher", body("studentId", WANGXM,
                        "occurDate", "2025-03-12", "outTime", "23:45", "reasonNote", repeat("n", 256))),
                        "原因说明不能超过 255 字"));
    }

    @Test
    @DisplayName("122 createLate：同一学生同一天同一类型只登记一条（DB 唯一约束的服务层口径）")
    void createLateDuplicate() {
        allowStudent();
        when(lateMapper.findByStudentDate(WANGXM, LocalDate.of(2025, 3, 10), "late"))
                .thenReturn(late(LATE, "late", "pending", 0));

        Result<?> r = lateService.createLate(LISI, "teacher", body("studentId", WANGXM,
                "occurDate", "2025-03-10", "outTime", "23:45"));

        assertCode(r, 400);
        assertMsg(r, "该学生当天已有同类型记录（同一学生同一天同一类型只登记一条）");
        verify(lateMapper, never()).insert(any());
    }

    @Test
    @DisplayName("123 createLate：夜不归宿不强制时间；落库默认 confirmed=0 / handling=pending / 楼栋房号取档案")
    void createLateSuccessDefaults() {
        allowStudent();
        when(lateMapper.insert(any())).thenReturn(1);
        when(lateMapper.findById(any())).thenReturn(late(LATE, "late", "pending", 0));

        Result<?> r = lateService.createLate(LISI, "teacher", body("studentId", WANGXM,
                "occurDate", "2025-03-10", "outTime", "23:45", "source", "card"));

        assertOk(r);
        ArgumentCaptor<CommunityLate> cap = ArgumentCaptor.forClass(CommunityLate.class);
        verify(lateMapper).insert(cap.capture());
        CommunityLate saved = cap.getValue();
        assertAll(
                () -> assertEquals("late", saved.getType()),
                () -> assertEquals("card", saved.getSource()),
                () -> assertEquals(0, saved.getConfirmed().intValue()),
                () -> assertEquals("pending", saved.getHandling()),
                () -> assertEquals(LISI, saved.getCounselorId().longValue()),
                () -> assertEquals(BUILDING, saved.getBuilding(), "楼栋取档案"),
                () -> assertEquals(ROOM, saved.getRoomNo(), "房号取档案"),
                () -> assertEquals(WANGXM, saved.getStudentId().longValue()),
                () -> assertEquals(LocalTime.of(23, 45), saved.getOutTime()));
    }

    @Test
    @DisplayName("124 updateLate：不存在 404 / 已了结不能再改 / CAS 带原 handling")
    void updateLateFlow() {
        allowStudent();
        assertCode(lateService.updateLate(LISI, "teacher", 999L, body("reasonNote", "x")), 404);

        when(lateMapper.findById(LATE)).thenReturn(late(LATE, "late", "closed", 0));
        assertMsg(lateService.updateLate(LISI, "teacher", LATE, body("reasonNote", "x")),
                "已了结的记录不能再修改（如需变更请先重开处理状态）");

        when(lateMapper.findById(LATE)).thenReturn(late(LATE, "late", "contacted", 0));
        when(lateMapper.update(any(), any())).thenReturn(1);
        assertOk(lateService.updateLate(LISI, "teacher", LATE, body("reasonNote", "补充说明")));

        ArgumentCaptor<CommunityLate> cap = ArgumentCaptor.forClass(CommunityLate.class);
        ArgumentCaptor<String> from = ArgumentCaptor.forClass(String.class);
        verify(lateMapper).update(cap.capture(), from.capture());
        assertAll(
                () -> assertEquals("contacted", from.getValue(), "fromStatus 是读到的旧 handling"),
                () -> assertEquals("补充说明", cap.getValue().getReasonNote()),
                () -> assertNull(cap.getValue().getStudentId(), "更新载荷不携带 studentId：归属不可篡改"));

        when(lateMapper.update(any(), any())).thenReturn(0);
        assertMsg(lateService.updateLate(LISI, "teacher", LATE, body("reasonNote", "x")), CAS_FAIL);
    }

    @Test
    @DisplayName("125 updateLate：改动时间仍必须落在正常出入时段之外；发生日期格式错")
    void updateLateTimeAndDate() {
        allowStudent();
        when(lateMapper.findById(LATE)).thenReturn(late(LATE, "late", "pending", 0));

        assertAll(
                () -> assertTrue(lateService.updateLate(LISI, "teacher", LATE,
                        body("outTime", "22:00")).getMessage().startsWith("晚归时间必须落在 6:00-23:30 之外")),
                () -> assertTrue(lateService.updateLate(LISI, "teacher", LATE,
                        body("occurDate", "2025/03/10")).getMessage().startsWith("发生日期 occurDate 格式不正确")),
                () -> assertMsg(lateService.updateLate(LISI, "teacher", LATE, body("type", "night")),
                        "类型只支持 late（晚归）/ absent（夜不归宿）"));
        verify(lateMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("126 confirmLate：不存在 404 / 重复核实 400 / CAS 0 行 400 / 正常核实")
    void confirmLateFlow() {
        allowStudent();
        assertCode(lateService.confirmLate(LISI, "teacher", 999L), 404);

        when(lateMapper.findById(LATE)).thenReturn(late(LATE, "late", "pending", 1));
        Result<?> again = lateService.confirmLate(LISI, "teacher", LATE);
        assertCode(again, 400);
        assertMsg(again, "该记录已核实，无需重复核实");

        when(lateMapper.findById(LATE)).thenReturn(late(LATE, "late", "pending", 0));
        when(lateMapper.confirm(eq(LATE), any(), any())).thenReturn(0);
        assertMsg(lateService.confirmLate(LISI, "teacher", LATE), CAS_FAIL);

        when(lateMapper.confirm(eq(LATE), any(), any())).thenReturn(1);
        Result<?> ok = lateService.confirmLate(LISI, "teacher", LATE);
        assertOk(ok);
        assertMsg(ok, "已核实");
    }

    @Test
    @DisplayName("127 handleLate：已了结 400 / 缺 handling / 非法 handling / 倒退 400")
    void handleLateValidation() {
        allowStudent();

        when(lateMapper.findById(LATE)).thenReturn(late(LATE, "late", "closed", 0));
        assertMsg(lateService.handleLate(LISI, "teacher", LATE, body("handling", "closed")),
                "该记录已了结，不能再推进处理状态");

        when(lateMapper.findById(LATE)).thenReturn(late(LATE, "late", "contacted", 0));
        assertAll(
                () -> assertMsg(lateService.handleLate(LISI, "teacher", LATE, body()),
                        "请给出处理状态 handling（pending / contacted / closed）"),
                () -> assertMsg(lateService.handleLate(LISI, "teacher", LATE, body("handling", "done")),
                        "处理状态只支持 pending / contacted / closed"),
                () -> assertMsg(lateService.handleLate(LISI, "teacher", LATE, body("handling", "pending")),
                        "处理状态只能向前推进（当前 已联系 → 目标 待处理）"));
        verify(lateMapper, never()).handle(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("128 handleLate：备注 ≤500 字；关联违规台账必须存在且属于该学生；推进成功 fromStatus=旧态")
    void handleLateFlow() {
        allowStudent();
        when(lateMapper.findById(LATE)).thenReturn(late(LATE, "late", "pending", 0));

        assertMsg(lateService.handleLate(LISI, "teacher", LATE,
                body("handling", "contacted", "handlingNote", repeat("备", 501))), "处理备注不能超过 500 字");

        when(violationMapper.findById(VIOLATION)).thenReturn(null);
        Result<?> noViolation = lateService.handleLate(LISI, "teacher", LATE,
                body("handling", "contacted", "violationId", VIOLATION));
        assertCode(noViolation, 404);
        assertMsg(noViolation, "关联的违规台账不存在");

        CommunityViolation other = violation(VIOLATION, "pending");
        other.setStudentId(ZHAOXH);
        when(violationMapper.findById(VIOLATION)).thenReturn(other);
        assertMsg(lateService.handleLate(LISI, "teacher", LATE,
                        body("handling", "contacted", "violationId", VIOLATION)),
                "关联的违规台账不是该学生的记录");

        when(violationMapper.findById(VIOLATION)).thenReturn(violation(VIOLATION, "pending"));
        when(lateMapper.handle(eq(LATE), eq("pending"), eq("contacted"), any(), eq(VIOLATION))).thenReturn(1);
        Result<?> ok = lateService.handleLate(LISI, "teacher", LATE,
                body("handling", "contacted", "handlingNote", "已电话联系", "violationId", VIOLATION));
        assertOk(ok);
        assertMsg(ok, "已更新处理状态");
        verify(lateMapper).handle(eq(LATE), eq("pending"), eq("contacted"), eq("已电话联系"), eq(VIOLATION));

        when(lateMapper.handle(any(), any(), any(), any(), any())).thenReturn(0);
        assertMsg(lateService.handleLate(LISI, "teacher", LATE, body("handling", "closed")), CAS_FAIL);
    }

    @Test
    @DisplayName("129 myLates：无档案 403；学生只拿到自己的记录且处理备注被置空")
    void myLatesMasking() {
        assertCode(lateService.myLates(WANGXM, "student"), 403);

        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(wangxm());
        when(lateMapper.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Arrays.asList(late(LATE, "late", "pending", 0)));

        Result<?> r = lateService.myLates(WANGXM, "student");

        assertOk(r);
        CommunityLate row = (CommunityLate) listData(r).get(0);
        assertAll(
                () -> assertNull(row.getHandlingNote(), "内部处理备注不下发学生端"),
                () -> assertEquals(WANGXM, row.getStudentId().longValue()));
        verify(lateMapper).list(eq(WANGXM), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), eq(0), eq(200));
    }

    @Test
    @DisplayName("130 submitMyNote：不存在 404 / 非本人 403 / 缺说明 / 超 500 字 / CAS 失败文案 / 成功")
    void submitMyNoteFlow() {
        assertCode(lateService.submitMyNote(WANGXM, "student", 999L, body("note", "x")), 404);

        when(lateMapper.findById(LATE)).thenReturn(late(LATE, "late", "pending", 0));
        CommunityLate otherStudent = late(LATE, "late", "pending", 0);
        otherStudent.setStudentId(ZHAOXH);
        when(lateMapper.findById(LATE)).thenReturn(otherStudent);
        Result<?> notMine = lateService.submitMyNote(WANGXM, "student", LATE, body("note", "x"));
        assertCode(notMine, 403);
        assertMsg(notMine, "只能补充本人晚归记录的说明");

        when(lateMapper.findById(LATE)).thenReturn(late(LATE, "late", "pending", 0));
        assertAll(
                () -> assertMsg(lateService.submitMyNote(WANGXM, "student", LATE, body()),
                        "请填写说明内容"),
                () -> assertMsg(lateService.submitMyNote(WANGXM, "student", LATE, body("note", repeat("n", 501))),
                        "说明不能超过 500 字"));

        when(lateMapper.submitNote(eq(LATE), eq(WANGXM), eq("当晚在医院"))) .thenReturn(0);
        Result<?> casFail = lateService.submitMyNote(WANGXM, "student", LATE, body("note", "当晚在医院"));
        assertMsg(casFail, "提交失败：该记录不存在或不属于你");

        when(lateMapper.submitNote(eq(LATE), eq(WANGXM), eq("当晚在医院"))).thenReturn(1);
        assertOk(lateService.submitMyNote(WANGXM, "student", LATE, body("note", "当晚在医院")));
    }

    // ==================================================================================
    // 9. 违规台账（§10.2-4）：rule_ref 必填、advice 五枚举、不生成处分决定
    // ==================================================================================

    @Test
    @DisplayName("131 createViolation：缺 studentId / 档案不存在 404 / 跨辅导员 403")
    void createViolationGuard() {
        assertMsg(lateService.createViolation(LISI, "teacher", body()), "请指定学生 studentId");

        Result<?> noProfile = lateService.createViolation(LISI, "teacher", body("studentId", WANGXM));
        assertCode(noProfile, 404);

        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(wangxm());
        when(lateMapper.countStudentOfCounselor(LISI, WANGXM)).thenReturn(0);
        Result<?> cross = lateService.createViolation(LISI, "teacher", body("studentId", WANGXM));
        assertCode(cross, 403);
        assertMsg(cross, "只能操作名下学生的记录（该学生不在你的负责范围）");
        verify(violationMapper, never()).insert(any());
    }

    @Test
    @DisplayName("132 createViolation：类型非法 / 违规事实必填且 ≤500 字")
    void createViolationTypeAndDetail() {
        allowStudent();
        assertAll(
                () -> assertMsg(lateService.createViolation(LISI, "teacher",
                                body("studentId", WANGXM, "type", "smoking")),
                        "违规类型只支持 late / absence / appliance / fire / pet / rent / climb / noise / other"),
                () -> assertMsg(lateService.createViolation(LISI, "teacher",
                                body("studentId", WANGXM, "type", "appliance")),
                        "请填写违规事实描述 detail"),
                () -> assertMsg(lateService.createViolation(LISI, "teacher", body("studentId", WANGXM,
                                "detail", repeat("d", 501))), "违规事实描述不能超过 500 字"));
        verify(violationMapper, never()).insert(any());
    }

    @Test
    @DisplayName("133 createViolation：rule_ref 必填（null / 空白都拦）且 ≤128 字（§10.2-4 必须写明依据）")
    void createViolationRuleRef() {
        allowStudent();
        assertAll(
                () -> assertMsg(lateService.createViolation(LISI, "teacher",
                                body("studentId", WANGXM, "detail", "使用电热毯")),
                        "请填写依据条文 ruleRef（处理只到教育惩戒与建议，必须写明依据）"),
                () -> assertMsg(lateService.createViolation(LISI, "teacher",
                                body("studentId", WANGXM, "detail", "使用电热毯", "ruleRef", "   ")),
                        "请填写依据条文 ruleRef（处理只到教育惩戒与建议，必须写明依据）"),
                () -> assertMsg(lateService.createViolation(LISI, "teacher", body("studentId", WANGXM,
                                "detail", "使用电热毯", "ruleRef", repeat("r", 129))), "依据条文不能超过 128 字"));
        verify(violationMapper, never()).insert(any());
    }

    @Test
    @DisplayName("134 createViolation：advice 五枚举全部可用，非法值被拦")
    void createViolationAdvice() {
        allowStudent();
        when(violationMapper.insert(any())).thenReturn(1);
        when(violationMapper.findById(any())).thenReturn(violation(VIOLATION, "pending"));

        assertMsg(lateService.createViolation(LISI, "teacher", body("studentId", WANGXM,
                        "detail", "使用电热毯", "ruleRef", "园区管理规定第四十一条", "advice", "expel")),
                "处理建议只支持 education / circulated / warning / serious_warning / recorded");

        List<String> advices = Arrays.asList("education", "circulated", "warning", "serious_warning", "recorded");
        for (String advice : advices) {
            Result<?> r = lateService.createViolation(LISI, "teacher", body("studentId", WANGXM,
                    "detail", "使用电热毯", "ruleRef", "园区管理规定第四十一条", "advice", advice));
            assertOk(r);
        }
        ArgumentCaptor<CommunityViolation> cap = ArgumentCaptor.forClass(CommunityViolation.class);
        verify(violationMapper, times(5)).insert(cap.capture());
        assertEquals("recorded", cap.getAllValues().get(4).getAdvice());
    }

    @Test
    @DisplayName("135 createViolation：关联晚归记录必须存在且属于同一学生；成功时回写 late.violation_id")
    void createViolationLinkedLate() {
        allowStudent();

        when(lateMapper.findById(LATE)).thenReturn(null);
        Result<?> missing = lateService.createViolation(LISI, "teacher", body("studentId", WANGXM,
                "detail", "使用电热毯", "ruleRef", "园区管理规定第四十一条", "lateId", LATE));
        assertCode(missing, 404);
        assertMsg(missing, "关联的晚归记录不存在");

        CommunityLate otherStudent = late(LATE, "late", "pending", 0);
        otherStudent.setStudentId(ZHAOXH);
        when(lateMapper.findById(LATE)).thenReturn(otherStudent);
        assertMsg(lateService.createViolation(LISI, "teacher", body("studentId", WANGXM,
                        "detail", "使用电热毯", "ruleRef", "园区管理规定第四十一条", "lateId", LATE)),
                "关联的晚归记录不是该学生的记录");

        when(lateMapper.findById(LATE)).thenReturn(late(LATE, "late", "pending", 0));
        when(violationMapper.insert(any())).thenReturn(1);
        when(violationMapper.findById(any())).thenReturn(violation(VIOLATION, "pending"));

        assertOk(lateService.createViolation(LISI, "teacher", body("studentId", WANGXM,
                "detail", "使用电热毯", "ruleRef", "园区管理规定第四十一条", "lateId", LATE)));

        ArgumentCaptor<CommunityViolation> cap = ArgumentCaptor.forClass(CommunityViolation.class);
        verify(violationMapper).insert(cap.capture());
        assertAll(
                () -> assertEquals("pending", cap.getValue().getHandling()),
                () -> assertEquals("education", cap.getValue().getAdvice(), "处理建议缺省教育"),
                () -> assertEquals(LISI, cap.getValue().getCounselorId().longValue()),
                () -> assertEquals(LATE, cap.getValue().getLateId().longValue()));
        verify(lateMapper).linkViolation(eq(LATE), any());
    }

    @Test
    @DisplayName("136 updateViolation：不存在 404 / 已办结 400 / rule_ref 缺失拦 / 发生时间格式错 / CAS 带原 handling")
    void updateViolationFlow() {
        allowStudent();
        assertCode(lateService.updateViolation(LISI, "teacher", 999L, body("detail", "x")), 404);

        when(violationMapper.findById(VIOLATION)).thenReturn(violation(VIOLATION, "closed"));
        assertMsg(lateService.updateViolation(LISI, "teacher", VIOLATION, body("detail", "x")),
                "已办结的台账不能再修改");

        CommunityViolation noRule = violation(VIOLATION, "pending");
        noRule.setRuleRef(null);
        when(violationMapper.findById(VIOLATION)).thenReturn(noRule);
        assertMsg(lateService.updateViolation(LISI, "teacher", VIOLATION, body("detail", "x")),
                "请填写依据条文 ruleRef");

        when(violationMapper.findById(VIOLATION)).thenReturn(violation(VIOLATION, "pending"));
        assertAll(
                () -> assertMsg(lateService.updateViolation(LISI, "teacher", VIOLATION, body("detail", null)),
                        "违规事实描述不能为空"),
                () -> assertTrue(lateService.updateViolation(LISI, "teacher", VIOLATION,
                        body("occurredAt", "昨天")).getMessage().startsWith("发生时间 occurredAt 格式不正确")),
                () -> assertMsg(lateService.updateViolation(LISI, "teacher", VIOLATION,
                        body("advice", "expel")),
                        "处理建议只支持 education / circulated / warning / serious_warning / recorded"));

        when(violationMapper.update(any(), any())).thenReturn(1);
        assertOk(lateService.updateViolation(LISI, "teacher", VIOLATION, body("detail", "使用大功率电吹风")));
        ArgumentCaptor<CommunityViolation> cap = ArgumentCaptor.forClass(CommunityViolation.class);
        verify(violationMapper).update(cap.capture(), eq("pending"));
        assertAll(
                () -> assertEquals("使用大功率电吹风", cap.getValue().getDetail()),
                () -> assertEquals("园区管理规定第四十一条第九项", cap.getValue().getRuleRef(), "未传 → 保留原依据"));

        when(violationMapper.update(any(), any())).thenReturn(0);
        assertMsg(lateService.updateViolation(LISI, "teacher", VIOLATION, body("detail", "x")), CAS_FAIL);
    }

    @Test
    @DisplayName("137 handleViolation：已办结 400 / 缺·非法 handling / 倒退 400 / 结果说明 ≤500 字 / 推进成功")
    void handleViolationFlow() {
        allowStudent();

        when(violationMapper.findById(VIOLATION)).thenReturn(violation(VIOLATION, "closed"));
        assertMsg(lateService.handleViolation(LISI, "teacher", VIOLATION, body("handling", "notified")),
                "该台账已办结，不能再推进处理状态");

        when(violationMapper.findById(VIOLATION)).thenReturn(violation(VIOLATION, "pending"));
        assertAll(
                () -> assertMsg(lateService.handleViolation(LISI, "teacher", VIOLATION, body()),
                        "请给出处理状态 handling（pending / notified / closed）"),
                () -> assertMsg(lateService.handleViolation(LISI, "teacher", VIOLATION, body("handling", "done")),
                        "处理状态只支持 pending / notified / closed"),
                () -> assertMsg(lateService.handleViolation(LISI, "teacher", VIOLATION,
                        body("handling", "pending")), "处理状态只能向前推进（当前 待处理 → 目标 待处理）"),
                () -> assertMsg(lateService.handleViolation(LISI, "teacher", VIOLATION,
                                body("handling", "notified", "resultNote", repeat("r", 501))),
                        "处理结果说明不能超过 500 字"));

        when(violationMapper.handle(eq(VIOLATION), eq("pending"), eq("notified"), any(), any(), any()))
                .thenReturn(1);
        Result<?> ok = lateService.handleViolation(LISI, "teacher", VIOLATION,
                body("handling", "notified", "resultNote", "已通报批评"));
        assertOk(ok);
        assertMsg(ok, "已更新处理状态");
        verify(violationMapper).handle(eq(VIOLATION), eq("pending"), eq("notified"), eq("已通报批评"), any(), any());

        when(violationMapper.handle(any(), any(), any(), any(), any(), any())).thenReturn(0);
        assertMsg(lateService.handleViolation(LISI, "teacher", VIOLATION, body("handling", "closed")), CAS_FAIL);
    }

    @Test
    @DisplayName("138 myViolations：无档案 403；学生只拿到自己的记录且结果说明/处理人被置空")
    void myViolationsMasking() {
        assertCode(lateService.myViolations(WANGXM, "student"), 403);

        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(wangxm());
        when(violationMapper.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Arrays.asList(violation(VIOLATION, "notified")));

        Result<?> r = lateService.myViolations(WANGXM, "student");

        assertOk(r);
        CommunityViolation row = (CommunityViolation) listData(r).get(0);
        assertAll(
                () -> assertNull(row.getResultNote(), "处理结果说明不下发学生端"),
                () -> assertNull(row.getHandledBy(), "处理人不下发学生端"),
                () -> assertNull(row.getHandledByName()),
                () -> assertEquals("园区管理规定第四十一条第九项", row.getRuleRef(), "依据条文学生可自查"));
        verify(violationMapper).list(eq(WANGXM), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), eq(0), eq(200));
    }

    @Test
    @DisplayName("139 §10.2-4：服务层与台账实体都没有「处分决定」写入口与字段（正式处分走线下）")
    void noPunishmentDecisionApi() {
        List<String> banned = Arrays.asList("punishment", "decision", "disposition", "sanction", "expel", "probation");
        for (Field field : CommunityViolation.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase();
            for (String word : banned) {
                assertFalse(name.contains(word), "CommunityViolation 不应有处分决定字段：" + field.getName());
            }
        }
        for (Class<?> type : Arrays.asList(CommunityService.class, CommunityLateService.class, CommunityActivityService.class)) {
            for (Method method : type.getDeclaredMethods()) {
                String name = method.getName().toLowerCase();
                for (String word : banned) {
                    assertFalse(name.contains(word), type.getSimpleName() + " 不应有处分决定入口：" + method.getName());
                }
            }
        }

        Map<String, Object> enums = CommunityLateService.enums();
        assertAll(
                () -> assertEquals(Arrays.asList("late", "absent"), enums.get("lateType")),
                () -> assertEquals(Arrays.asList("card", "check", "report"), enums.get("lateSource")),
                () -> assertEquals(Arrays.asList("late", "absence", "appliance", "fire", "pet", "rent", "climb",
                        "noise", "other"), enums.get("violationType")),
                () -> assertEquals(Arrays.asList("education", "circulated", "warning", "serious_warning", "recorded"),
                        enums.get("violationAdvice"), "处理建议只到教育惩戒与建议，不含记过及以上"));
    }

    // ==================================================================================
    // 10. 社区活动（§10.2-8）：quota / 唯一报名 / 报名截止 / 审核签到与获奖
    // ==================================================================================

    @Test
    @DisplayName("140 listActivities：未登录 401；学生看不到草稿（hideDraft=true），教职工能看到")
    void listActivitiesHideDraft() {
        assertCode(activityService.listActivities(null, "admin", TERM, null, null, null, null), 401);

        when(activityMapper.list(TERM, null, null, null, null, true)).thenReturn(new ArrayList<>());
        assertOk(activityService.listActivities(WANGXM, "student", TERM, null, null, null, null));
        verify(activityMapper).list(TERM, null, null, null, null, true);

        when(activityMapper.list(TERM, null, null, null, null, false)).thenReturn(new ArrayList<>());
        assertOk(activityService.listActivities(LISI, "teacher", TERM, null, null, null, null));
        verify(activityMapper).list(TERM, null, null, null, null, false);
    }

    @Test
    @DisplayName("141 createActivity：学生 403 / 缺 title / title 超 128 字")
    void createActivityTitle() {
        assertCode(activityService.createActivity(WANGXM, "student", body("title", "园区文化节")), 403);
        assertAll(
                () -> assertMsg(activityService.createActivity(LISI, "teacher", body()), "请填写活动名称 title"),
                () -> assertMsg(activityService.createActivity(LISI, "teacher", body("title", repeat("活", 129))),
                        "活动名称不能超过 128 字"));
    }

    @Test
    @DisplayName("142 createActivity：活动类型 / 级别枚举校验")
    void createActivityTypeAndLevel() {
        assertAll(
                () -> assertMsg(activityService.createActivity(LISI, "teacher", body("title", "活动", "activityType", "party")),
                        "活动类型只支持 culture_festival / room_contest / room_olympic / design_contest / volunteer / other"),
                () -> assertMsg(activityService.createActivity(LISI, "teacher", body("title", "活动", "level", "national")),
                        "活动级别只支持 school / college / community"));
    }

    @Test
    @DisplayName("143 createActivity：时间格式错 / 结束早于开始 / 报名截止格式错 / quota 非法")
    void createActivityTimesAndQuota() {
        assertAll(
                () -> assertTrue(activityService.createActivity(LISI, "teacher", body("title", "活动",
                        "startAt", "明天")).getMessage().startsWith("开始时间 startAt 格式不正确")),
                () -> assertTrue(activityService.createActivity(LISI, "teacher", body("title", "活动",
                        "endAt", "abc")).getMessage().startsWith("结束时间 endAt 格式不正确")),
                () -> assertTrue(activityService.createActivity(LISI, "teacher", body("title", "活动",
                        "signupDeadline", "周五")).getMessage().startsWith("报名截止时间 signupDeadline 格式不正确")),
                () -> assertMsg(activityService.createActivity(LISI, "teacher", body("title", "活动",
                        "startAt", "2025-03-20 14:00:00", "endAt", "2025-03-20 09:00:00")),
                        "结束时间不能早于开始时间"),
                () -> assertMsg(activityService.createActivity(LISI, "teacher", body("title", "活动", "quota", "很多")),
                        "名额 quota 必须是数字（0 表示不限）"),
                () -> assertMsg(activityService.createActivity(LISI, "teacher", body("title", "活动", "quota", -1)),
                        "名额 quota 不能为负数（0 表示不限）"));
    }

    @Test
    @DisplayName("144 createActivity：缺省类型 other / 级别 community / 状态 draft / createdBy")
    void createActivityDefaults() {
        when(activityMapper.insert(any())).thenReturn(1);
        when(activityMapper.findById(any())).thenReturn(activity(ACTIVITY, "draft"));

        assertOk(activityService.createActivity(LISI, "teacher", body("title", "宿舍吉尼斯")));

        ArgumentCaptor<CommunityActivity> cap = ArgumentCaptor.forClass(CommunityActivity.class);
        verify(activityMapper).insert(cap.capture());
        CommunityActivity saved = cap.getValue();
        assertAll(
                () -> assertEquals("other", saved.getActivityType()),
                () -> assertEquals("community", saved.getLevel()),
                () -> assertEquals("draft", saved.getStatus()),
                () -> assertEquals(LISI, saved.getCreatedBy().longValue()),
                () -> assertEquals("宿舍吉尼斯", saved.getTitle()));
    }

    @Test
    @DisplayName("145 updateActivity：不存在 404 / 已结束或已取消不能再改（含取消入口不可达说明）")
    void updateActivityFinalStates() {
        assertCode(activityService.updateActivity(LISI, "teacher", 999L, body("title", "改")), 404);

        when(activityMapper.findById(ACTIVITY)).thenReturn(activity(ACTIVITY, "finished"));
        assertMsg(activityService.updateActivity(LISI, "teacher", ACTIVITY, body("title", "改")),
                "已结束或已取消的活动不能再修改");

        when(activityMapper.findById(ACTIVITY)).thenReturn(activity(ACTIVITY, "cancelled"));
        assertMsg(activityService.updateActivity(LISI, "teacher", ACTIVITY, body("title", "改")),
                "已结束或已取消的活动不能再修改");
        verify(activityMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("146 updateActivity：非法目标态 / 状态不能倒退 / 取消只能从非终态发起")
    void updateActivityStatusMachine() {
        when(activityMapper.findById(ACTIVITY)).thenReturn(activity(ACTIVITY, "ongoing"));
        assertAll(
                () -> assertMsg(activityService.updateActivity(LISI, "teacher", ACTIVITY, body("status", "paused")),
                        "活动状态只支持 draft / signup / ongoing / finished / cancelled"),
                () -> assertMsg(activityService.updateActivity(LISI, "teacher", ACTIVITY, body("status", "signup")),
                        "活动状态只能向前推进（当前 进行中 → 目标 报名中）"));

        when(activityMapper.findById(ACTIVITY)).thenReturn(activity(ACTIVITY, "draft"));
        when(activityMapper.update(any(), any())).thenReturn(1);
        assertOk(activityService.updateActivity(LISI, "teacher", ACTIVITY, body("status", "cancelled")));
        verify(activityMapper).update(any(), eq("draft"));
    }

    @Test
    @DisplayName("147 updateActivity：signup → ongoing 合法（CAS fromStatus=读到的旧态）；CAS 0 行 400")
    void updateActivityForward() {
        when(activityMapper.findById(ACTIVITY)).thenReturn(activity(ACTIVITY, "signup"));
        when(activityMapper.update(any(), any())).thenReturn(1);

        Result<?> r = activityService.updateActivity(LISI, "teacher", ACTIVITY, body("status", "ongoing"));

        assertOk(r);
        ArgumentCaptor<CommunityActivity> cap = ArgumentCaptor.forClass(CommunityActivity.class);
        ArgumentCaptor<String> from = ArgumentCaptor.forClass(String.class);
        verify(activityMapper).update(cap.capture(), from.capture());
        assertAll(
                () -> assertEquals("signup", from.getValue(), "fromStatus 是读到的旧状态"),
                () -> assertEquals("ongoing", cap.getValue().getStatus()),
                () -> assertEquals("园区文化节", cap.getValue().getTitle(), "未传字段保持现值"));

        when(activityMapper.update(any(), any())).thenReturn(0);
        Result<?> casFail = activityService.updateActivity(LISI, "teacher", ACTIVITY, body("status", "ongoing"));
        assertCode(casFail, 400);
        assertMsg(casFail, CAS_FAIL);
    }

    /** 报名用活动：报名中 + 远期截止。 */
    private CommunityActivity signupOpen() {
        CommunityActivity a = activity(ACTIVITY, "signup");
        a.setSignupDeadline(LocalDateTime.of(2999, 1, 1, 0, 0));
        return a;
    }

    @Test
    @DisplayName("148 signupActivity：活动不存在 404 / 无学生档案 403（只有学生本人能报名）")
    void signupActivityGuards() {
        assertCode(activityService.signupActivity(WANGXM, "student", 999L, body()), 404);

        when(activityMapper.findById(ACTIVITY)).thenReturn(signupOpen());
        Result<?> noProfile = activityService.signupActivity(WANGXM, "student", ACTIVITY, body());
        assertCode(noProfile, 403);
        assertMsg(noProfile, "仅学生本人可报名活动（未找到你的学生档案）");
        verify(signupMapper, never()).insert(any());
    }

    @Test
    @DisplayName("149 signupActivity：非报名中状态 400 / signup_deadline 过期 400（§10.2-8）")
    void signupActivityStatusAndDeadline() {
        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(wangxm());

        when(activityMapper.findById(ACTIVITY)).thenReturn(activity(ACTIVITY, "ongoing"));
        assertMsg(activityService.signupActivity(WANGXM, "student", ACTIVITY, body()),
                "该活动当前不在报名中（当前状态：进行中）");

        CommunityActivity expired = signupOpen();
        expired.setSignupDeadline(LocalDateTime.of(2020, 1, 1, 0, 0));
        when(activityMapper.findById(ACTIVITY)).thenReturn(expired);
        Result<?> late = activityService.signupActivity(WANGXM, "student", ACTIVITY, body());
        assertCode(late, 400);
        assertTrue(late.getMessage().startsWith("报名已截止（截止时间 2020-01-01 00:00:00）"));
        verify(signupMapper, never()).insert(any());
    }

    @Test
    @DisplayName("150 signupActivity：同一活动同一学生重复报名被拦（UNIQUE 约束的服务层口径）")
    void signupActivityDuplicate() {
        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(wangxm());
        when(activityMapper.findById(ACTIVITY)).thenReturn(signupOpen());
        when(signupMapper.findByActivityStudent(ACTIVITY, WANGXM))
                .thenReturn(signup(SIGNUP, ACTIVITY, WANGXM, "signed"));

        Result<?> r = activityService.signupActivity(WANGXM, "student", ACTIVITY, body());

        assertCode(r, 400);
        assertMsg(r, "你已报名该活动（同一活动同一学生只能报名一次）");
        verify(signupMapper, never()).insert(any());
    }

    @Test
    @DisplayName("151 signupActivity：quota 满 400；quota=0 / null 表示不限")
    void signupActivityQuota() {
        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(wangxm());

        when(activityMapper.findById(ACTIVITY)).thenReturn(signupOpen());
        when(signupMapper.occupancy(ACTIVITY)).thenReturn(2);
        Result<?> full = activityService.signupActivity(WANGXM, "student", ACTIVITY, body());
        assertCode(full, 400);
        assertMsg(full, "名额已满（2/2）");

        CommunityActivity unlimited = signupOpen();
        unlimited.setQuota(0);
        when(activityMapper.findById(ACTIVITY)).thenReturn(unlimited);
        when(signupMapper.insert(any())).thenReturn(1);
        when(signupMapper.findById(any())).thenReturn(signup(SIGNUP, ACTIVITY, WANGXM, "signed"));
        assertOk(activityService.signupActivity(WANGXM, "student", ACTIVITY, body()));

        CommunityActivity noLimit = signupOpen();
        noLimit.setQuota(null);
        when(activityMapper.findById(ACTIVITY)).thenReturn(noLimit);
        assertOk(activityService.signupActivity(WANGXM, "student", ACTIVITY, body()));
    }

    @Test
    @DisplayName("152 signupActivity：报名成功置 signed，楼栋房号取学生档案")
    void signupActivitySuccess() {
        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(wangxm());
        when(activityMapper.findById(ACTIVITY)).thenReturn(signupOpen());
        when(signupMapper.insert(any())).thenReturn(1);
        when(signupMapper.findById(any())).thenReturn(signup(SIGNUP, ACTIVITY, WANGXM, "signed"));

        Result<?> r = activityService.signupActivity(WANGXM, "student", ACTIVITY, body());

        assertOk(r);
        ArgumentCaptor<CommunityActivitySignup> cap = ArgumentCaptor.forClass(CommunityActivitySignup.class);
        verify(signupMapper).insert(cap.capture());
        CommunityActivitySignup saved = cap.getValue();
        assertAll(
                () -> assertEquals("signed", saved.getStatus()),
                () -> assertEquals(ACTIVITY, saved.getActivityId().longValue()),
                () -> assertEquals(WANGXM, saved.getStudentId().longValue()),
                () -> assertEquals(BUILDING, saved.getBuilding()),
                () -> assertEquals(ROOM, saved.getRoomNo()),
                () -> assertNotNull(saved.getSignupAt()));
    }

    @Test
    @DisplayName("153 myActivities：无档案 403；按本人 + 可选 activityId 查询")
    void myActivities() {
        assertCode(activityService.myActivities(WANGXM, "student", null), 403);

        when(studentProfileMapper.findByUserId(WANGXM)).thenReturn(wangxm());
        when(signupMapper.listMine(eq(WANGXM), isNull())).thenReturn(new ArrayList<>());

        assertOk(activityService.myActivities(WANGXM, "student", null));
        verify(signupMapper).listMine(WANGXM, null);
    }

    @Test
    @DisplayName("154 reviewSignups：活动不存在 404 / 缺 action / 非法 action / 驳回原因超 255 / 未指定记录")
    void reviewSignupsValidation() {
        assertCode(activityService.reviewSignups(LISI, "teacher", 999L, body("action", "approve")), 404);

        when(activityMapper.findById(ACTIVITY)).thenReturn(signupOpen());
        assertAll(
                () -> assertMsg(activityService.reviewSignups(LISI, "teacher", ACTIVITY, null),
                        "请求内容不能为空"),
                () -> assertMsg(activityService.reviewSignups(LISI, "teacher", ACTIVITY, body()),
                        "请给出审核动作 action（approve / reject）"),
                () -> assertMsg(activityService.reviewSignups(LISI, "teacher", ACTIVITY, body("action", "hold")),
                        "审核动作只支持 approve（通过）/ reject（驳回）"),
                () -> assertMsg(activityService.reviewSignups(LISI, "teacher", ACTIVITY,
                        body("action", "reject", "rejectReason", repeat("r", 256))),
                        "驳回原因不能超过 255 字"),
                () -> assertMsg(activityService.reviewSignups(LISI, "teacher", ACTIVITY, body("action", "approve")),
                        "请指定要审核的报名 signupId 或 studentId（也可用 items 数组批量）"));
        verify(signupMapper, never()).review(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("155 reviewSignups：approve → approved（CAS fromStatus=signed）；reject → rejected 带原因")
    void reviewSignupsApproveAndReject() {
        when(activityMapper.findById(ACTIVITY)).thenReturn(signupOpen());
        when(signupMapper.findById(SIGNUP)).thenReturn(signup(SIGNUP, ACTIVITY, WANGXM, "signed"));

        when(signupMapper.review(eq(SIGNUP), eq("signed"), eq("approved"), any(), any(), any())).thenReturn(1);
        Result<?> approve = activityService.reviewSignups(LISI, "teacher", ACTIVITY,
                body("action", "approve", "signupId", SIGNUP));
        assertOk(approve);
        Map<String, Object> data = dataMap(approve);
        assertAll(
                () -> assertEquals(1, intOf(data.get("total"))),
                () -> assertEquals(1, intOf(data.get("updated"))),
                () -> assertEquals(0, intOf(data.get("failed"))),
                () -> assertEquals("approve", data.get("action")));
        verify(signupMapper).review(eq(SIGNUP), eq("signed"), eq("approved"), any(), any(), any());

        when(signupMapper.review(eq(SIGNUP), eq("signed"), eq("rejected"), eq("材料不全"), any(), any())).thenReturn(1);
        Result<?> reject = activityService.reviewSignups(LISI, "teacher", ACTIVITY,
                body("action", "reject", "signupId", SIGNUP, "rejectReason", "材料不全"));
        assertOk(reject);
        assertEquals("reject", dataMap(reject).get("action"));
        verify(signupMapper).review(eq(SIGNUP), eq("signed"), eq("rejected"), eq("材料不全"), any(), any());
    }

    @Test
    @DisplayName("156 reviewSignups：非本活动 / 非 signed 的记录逐条报错，其余照常审核")
    void reviewSignupsPartialErrors() {
        when(activityMapper.findById(ACTIVITY)).thenReturn(signupOpen());
        when(signupMapper.findById(SIGNUP)).thenReturn(signup(SIGNUP, ACTIVITY, WANGXM, "signed"));
        when(signupMapper.review(eq(SIGNUP), eq("signed"), eq("approved"), any(), any(), any())).thenReturn(1);
        when(signupMapper.findById(9999L)).thenReturn(signup(9999L, 1L, ZHAOXH, "signed"));
        when(signupMapper.findById(9998L)).thenReturn(signup(9998L, ACTIVITY, ZHAOXH, "approved"));

        Result<?> r = activityService.reviewSignups(LISI, "teacher", ACTIVITY, body("action", "approve",
                "items", Arrays.asList(body("signupId", SIGNUP), body("signupId", 9999L), body("signupId", 9998L))));

        assertOk(r);
        Map<String, Object> data = dataMap(r);
        List<?> errors = (List<?>) data.get("errors");
        assertAll(
                () -> assertEquals(3, intOf(data.get("total"))),
                () -> assertEquals(1, intOf(data.get("updated"))),
                () -> assertEquals(2, intOf(data.get("failed"))),
                () -> assertEquals("报名记录不存在或不属于该活动", mapOf(errors.get(0)).get("message")),
                () -> assertTrue(String.valueOf(mapOf(errors.get(1)).get("message")).startsWith("只有已报名（signed）的记录可以审核")));
    }

    @Test
    @DisplayName("157 markAttendance：活动 404 / 空 body / 未指定记录 / 缺 status / 非法 status / 非法 award")
    void markAttendanceValidation() {
        assertCode(activityService.markAttendance(LISI, "teacher", 999L, body()), 404);

        when(activityMapper.findById(ACTIVITY)).thenReturn(signupOpen());
        assertAll(
                () -> assertMsg(activityService.markAttendance(LISI, "teacher", ACTIVITY, null),
                        "请求内容不能为空"),
                () -> assertMsg(activityService.markAttendance(LISI, "teacher", ACTIVITY, body()),
                        "请指定签到记录 signupId 或 studentId（也可用 items 数组批量）"));

        when(signupMapper.findById(SIGNUP)).thenReturn(signup(SIGNUP, ACTIVITY, WANGXM, "signed"));
        List<?> missingStatus = (List<?>) dataMap(activityService.markAttendance(LISI, "teacher", ACTIVITY,
                body("signupId", SIGNUP))).get("errors");
        assertEquals("请给出签到状态 status（attended / absent）", mapOf(missingStatus.get(0)).get("message"));

        List<?> badStatus = (List<?>) dataMap(activityService.markAttendance(LISI, "teacher", ACTIVITY,
                body("signupId", SIGNUP, "status", "maybe"))).get("errors");
        assertEquals("签到状态只支持 attended / absent", mapOf(badStatus.get(0)).get("message"));

        List<?> badAward = (List<?>) dataMap(activityService.markAttendance(LISI, "teacher", ACTIVITY,
                body("signupId", SIGNUP, "status", "attended", "award", "gold"))).get("errors");
        assertEquals("奖项只支持 none / first / second / third / excellent", mapOf(badAward.get(0)).get("message"));
    }

    @Test
    @DisplayName("158 markAttendance：奖项只能给已参加；已是该状态 / 前置态非法逐条报错")
    void markAttendanceRules() {
        when(activityMapper.findById(ACTIVITY)).thenReturn(signupOpen());

        when(signupMapper.findById(SIGNUP)).thenReturn(signup(SIGNUP, ACTIVITY, WANGXM, "signed"));
        List<?> awardOnAbsent = (List<?>) dataMap(activityService.markAttendance(LISI, "teacher", ACTIVITY,
                body("signupId", SIGNUP, "status", "absent", "award", "first"))).get("errors");
        assertEquals("只有已参加（attended）才能登记奖项", mapOf(awardOnAbsent.get(0)).get("message"));

        when(signupMapper.findById(SIGNUP)).thenReturn(signup(SIGNUP, ACTIVITY, WANGXM, "attended"));
        List<?> same = (List<?>) dataMap(activityService.markAttendance(LISI, "teacher", ACTIVITY,
                body("signupId", SIGNUP, "status", "attended"))).get("errors");
        assertTrue(String.valueOf(mapOf(same.get(0)).get("message")).startsWith("该报名已经是"));

        when(signupMapper.findById(SIGNUP)).thenReturn(signup(SIGNUP, ACTIVITY, WANGXM, "rejected"));
        List<?> illegalFrom = (List<?>) dataMap(activityService.markAttendance(LISI, "teacher", ACTIVITY,
                body("signupId", SIGNUP, "status", "attended"))).get("errors");
        assertTrue(String.valueOf(mapOf(illegalFrom.get(0)).get("message"))
                .startsWith("只有已报名 / 已通过的记录可以签到"));
        verify(signupMapper, never()).markAttendance(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("159 markAttendance：signed → attended + award 登记成功（CAS fromStatus=旧态）")
    void markAttendanceOk() {
        when(activityMapper.findById(ACTIVITY)).thenReturn(signupOpen());
        when(signupMapper.findById(SIGNUP)).thenReturn(signup(SIGNUP, ACTIVITY, WANGXM, "signed"));
        when(signupMapper.markAttendance(eq(SIGNUP), eq("signed"), eq("attended"), eq("first"), any(), any(), any(), any()))
                .thenReturn(1);

        Result<?> r = activityService.markAttendance(LISI, "teacher", ACTIVITY,
                body("signupId", SIGNUP, "status", "attended", "award", "first"));

        assertOk(r);
        assertEquals(1, intOf(dataMap(r).get("updated")));
        verify(signupMapper).markAttendance(eq(SIGNUP), eq("signed"), eq("attended"), eq("first"), any(), any(), any(), any());
    }

    @Test
    @DisplayName("160 markAttendance：可用 studentId 定位报名记录；学生调用一律 403（只由 staff 登记）")
    void markAttendanceByStudentIdAndGuard() {
        assertCode(activityService.markAttendance(WANGXM, "student", ACTIVITY,
                body("studentId", WANGXM, "status", "attended")), 403);

        when(activityMapper.findById(ACTIVITY)).thenReturn(signupOpen());
        when(signupMapper.findByActivityStudent(ACTIVITY, WANGXM))
                .thenReturn(signup(SIGNUP, ACTIVITY, WANGXM, "signed"));
        when(signupMapper.findById(SIGNUP)).thenReturn(signup(SIGNUP, ACTIVITY, WANGXM, "signed"));
        when(signupMapper.markAttendance(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        Result<?> r = activityService.markAttendance(LISI, "teacher", ACTIVITY,
                body("studentId", WANGXM, "status", "absent"));

        assertOk(r);
        verify(signupMapper).markAttendance(eq(SIGNUP), eq("signed"), eq("absent"), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("161 signupStats：活动不存在 404；成功含 activity/byStatus/occupancy/quota")
    void signupStats() {
        assertCode(activityService.signupStats(LISI, "teacher", 999L), 404);

        when(activityMapper.findById(ACTIVITY)).thenReturn(signupOpen());
        when(signupMapper.countGroupByStatus(ACTIVITY)).thenReturn(new ArrayList<>());
        when(signupMapper.occupancy(ACTIVITY)).thenReturn(null);

        Result<?> r = activityService.signupStats(LISI, "teacher", ACTIVITY);

        assertOk(r);
        Map<String, Object> data = dataMap(r);
        assertAll(
                () -> assertEquals(Arrays.asList("activity", "byStatus", "occupancy", "quota"),
                        new ArrayList<>(data.keySet())),
                () -> assertEquals(0, intOf(data.get("occupancy")), "occupancy 为 null 时回 0"),
                () -> assertEquals(2, intOf(data.get("quota"))));
    }
}
