package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.controller.PartyController;
import com.umi.nest.entity.AuditLog;
import com.umi.nest.entity.PartyApplication;
import com.umi.nest.entity.PartyMaterial;
import com.umi.nest.entity.PartyObservation;
import com.umi.nest.entity.PartyPublicity;
import com.umi.nest.entity.PartyStage;
import com.umi.nest.entity.PartyTraining;
import com.umi.nest.entity.PartyTrainingMember;
import com.umi.nest.mapper.PartyApplicationMapper;
import com.umi.nest.mapper.PartyMaterialMapper;
import com.umi.nest.mapper.PartyObservationMapper;
import com.umi.nest.mapper.PartyPublicityMapper;
import com.umi.nest.mapper.PartyStageMapper;
import com.umi.nest.mapper.PartyTrainingMapper;
import com.umi.nest.mapper.PartyTrainingMemberMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P16 学生党建（§11）—— {@link PartyService} / {@link PartyTrainingService} 的服务层单测，
 * 外加 {@link PartyController#export} 的 CSV BOM 出口验证。
 *
 * <p>口径出处：《学工平台功能对齐说明》§11（S22 校内五阶段 30 步 / S23 校规厦大嘉委综〔2016〕12号 /
 * S24 国标《中国共产党发展党员工作细则》），§11.2 的 11 条实现口径为验收依据。本类逐条把口径写成断言：
 * 第 1 条（不做自动资格判定，只给带条款引用的提示文案）、第 3 条（国标与校规期限两条并存且各带来源）、
 * 第 4 条（学生端服务层抹掉 internalNote / internalDiscussion / internalRemark）、
 * 第 6 条（公示默认 5 个工作日、导出只给学号后四位且不含身份证号/联系方式）、
 * 第 7 条（党校报名→审核→结业，证书默认颁发日 + 1 年、可人工覆盖）、
 * 第 9 条（状态机 = 服务层显式前置校验 + CAS，advance 的 fromStage 取显式枚举前置态）、
 * 第 10 条（publish 只接受 draft / close 只接受 publicizing，<b>已结束再 close 必须 400</b>）。</p>
 *
 * <p>风格与 CommunityServiceTest / PsycheServiceTest / AidReviewServiceTest 一致：纯 Mockito
 * （@ExtendWith(MockitoExtension.class) + @MockitoSettings(LENIENT) + @InjectMocks），不起 Spring 容器、
 * 不连库、不写任何数据行，因此不需要 @Transactional / 清理，可反复跑且绝不动演示数据。</p>
 *
 * <p><b>关于 CAS 断言（P14/P15 踩过的坑）</b>：服务层每个状态变更入口都是「先读当前状态 → 服务层
 * 前置态校验 → CAS 带 fromStatus/fromStage」。P16 的硬口径更进一步（§11.2-9）：{@code advance} 的
 * {@code fromStage} 必须取<b>显式枚举前置态</b>（{@link PartyService#PREVIOUS_STAGE}），不能把
 * 「刚读到的 stage」当参数——否则 {@code WHERE id=? AND stage=?} 恒真、CAS 退化成无条件更新。
 * 本类因此对非法前置态一律断言 400 且 {@code verify(mapper, never())} 写入口<b>绝不落到 SQL</b>；
 * 对合法迁移用 ArgumentCaptor 断言 fromStage/fromStatus 恰为显式前置枚举（不是目标态）。</p>
 *
 * <p><b>关于审计（@Mock AuditLogService）</b>：本类首轮发现 {@code PartyService.auditLogService} 被声明为
 * {@code private static}——Spring 的字段注入对静态字段**只告警、不注入**，配合 {@code audit(...)} 的兜底
 * catch，生产环境 P16 审计会被静默吞掉（启动日志实测告警见该字段 javadoc）。**主代码已按报告修复为实例字段**，
 * 因此这里由 {@code @InjectMocks} 直接注入，不再反射绕行；回归钉子见
 * {@link #auditLogServiceIsInstanceFieldNotStatic()} 与 {@link #updateTrainingWithNullQuotaNoLongerThrows()}。</p>
 *
 * <p>账号口径与 sql\nest_seed.sql 一致：admin=1、lisi=3（teacher/counselor）、
 * 学生 wangxm=4 / zhaoxh=5。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Party*Service - P16 学生党建（五阶段 · 公示 · 考察 · 党校 · 材料 · 统计导出）口径")
class PartyServiceTest {

    // ---------------------------------------------------------------- 真实账号

    private static final long ADMIN = 1L;
    private static final long COUNSELOR = 3L;
    private static final long WANGXM = 4L;
    private static final long ZHAOXH = 5L;

    // ---------------------------------------------------------------- 固定 id

    private static final long APPLICATION_ID = 9101L;
    private static final long STAGE_ID = 9102L;
    private static final long OBSERVATION_ID = 9103L;
    private static final long PUBLICITY_ID = 9104L;
    private static final long MATERIAL_ID = 9105L;
    private static final long TRAINING_ID = 9106L;
    private static final long MEMBER_ID = 9107L;

    private static final String BRANCH = "计算机系本科生党支部";
    private static final String CAS_FAIL = CommunityService.CAS_FAIL;
    private static final int MAX_EXPORT = CommunityService.MAX_EXPORT;

    @Mock
    private PartyApplicationMapper applicationMapper;

    @Mock
    private PartyStageMapper stageMapper;

    @Mock
    private PartyObservationMapper observationMapper;

    @Mock
    private PartyPublicityMapper publicityMapper;

    @Mock
    private PartyMaterialMapper materialMapper;

    @Mock
    private PartyTrainingMapper trainingMapper;

    @Mock
    private PartyTrainingMemberMapper trainingMemberMapper;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private PartyService service;

    @InjectMocks
    private PartyTrainingService trainingService;

    private final PartyController controller = new PartyController();

    @BeforeEach
    void wire() {
        // P16 修复后：PartyService.auditLogService 已是实例字段，@InjectMocks 按类型注入，无需反射绕行。
        ReflectionTestUtils.setField(controller, "partyService", service);
        ReflectionTestUtils.setField(controller, "partyTrainingService", trainingService);
    }

    // ---------------------------------------------------------------- 断言工具

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

    private static void assertMsgStartsWith(Result<?> r, String prefix) {
        assertNotNull(r);
        assertNotNull(r.getMessage());
        assertTrue(r.getMessage().startsWith(prefix), "message 应以「" + prefix + "」开头，实际：" + r.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataMap(Result<?> r) {
        Object data = r.getData();
        assertTrue(data instanceof Map, "data 应为 Map，实际：" + data);
        return (Map<String, Object>) data;
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> pageList(Result<?> r) {
        return (List<T>) dataMap(r).get("list");
    }

    /** 文案必须带来源标记（S23 校规 / S24 国标 / 条款号）——§11.1 的「不编造」底线。 */
    private static void assertHasSource(String text, String... markers) {
        assertNotNull(text, "文案不能为空");
        for (String marker : markers) {
            assertTrue(text.contains(marker), "文案应带来源「" + marker + "」，实际：" + text);
        }
    }

    private AuditLog capturedAudit(String expectedAction) {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, times(1)).record(captor.capture());
        AuditLog entry = captor.getValue();
        assertEquals(AuditLogService.TYPE_PARTY, entry.getEventType(), "审计事件类型必须是 TYPE_PARTY");
        assertEquals(expectedAction, entry.getEventAction(), "审计动作");
        assertNotNull(entry.getDetail(), "审计 detail 不能为空");
        return entry;
    }

    // ---------------------------------------------------------------- 构造工具

    private static PartyStage stage(long id, long userId, String code) {
        PartyStage s = new PartyStage();
        s.setId(id);
        s.setUserId(userId);
        s.setStage(code);
        s.setBranchName(BRANCH);
        return s;
    }

    private static PartyApplication application(long id, long userId) {
        PartyApplication a = new PartyApplication();
        a.setId(id);
        a.setUserId(userId);
        a.setBranchName(BRANCH);
        a.setApplyDate(LocalDate.now());
        a.setStatus("active");
        a.setUserName("王小明");
        a.setStudentNo("2024001");
        a.setClassName("计算机 2401");
        return a;
    }

    private static PartyObservation observation(long id, long userId, String type, String reviewStatus) {
        PartyObservation o = new PartyObservation();
        o.setId(id);
        o.setUserId(userId);
        o.setType(type);
        o.setReviewStatus(reviewStatus);
        o.setSubmittedAt(LocalDateTime.now().withNano(0));
        o.setUserName("王小明");
        o.setStudentNo("2024001");
        return o;
    }

    private static PartyPublicity publicity(long id, String status) {
        PartyPublicity p = new PartyPublicity();
        p.setId(id);
        p.setUserId(WANGXM);
        p.setNodeType("develop_party");
        p.setTitle("关于拟接收王小明同志为预备党员的公示");
        p.setStage("probationary");
        p.setStartDate(LocalDate.now());
        p.setEndDate(LocalDate.now().plusDays(7));
        p.setWorkdays(5);
        p.setNoticeText(PartyService.PUBLICITY_RULE);
        p.setObjectionChannel(PartyService.DEFAULT_OBJECTION_CHANNEL);
        p.setStatus(status);
        p.setUserName("王小明");
        p.setStudentNo("2024001");
        return p;
    }

    private static PartyMaterial material(long id, long userId, String stageCode, int required, int submitted) {
        PartyMaterial m = new PartyMaterial();
        m.setId(id);
        m.setUserId(userId);
        m.setStage(stageCode);
        m.setMaterialName("入党申请书");
        m.setRequired(required);
        m.setSubmitted(submitted);
        m.setAttachmentName("申请书_王小明.pdf");
        m.setUserName("王小明");
        m.setStudentNo("2024001");
        return m;
    }

    private static PartyTraining training(long id, String status) {
        PartyTraining t = new PartyTraining();
        t.setId(id);
        t.setTrainingName("2026 年第一期入党积极分子培训班");
        t.setTerm("2025-2026-2");
        t.setStartDate(LocalDate.now());
        t.setEndDate(LocalDate.now().plusDays(7));
        t.setStatus(status);
        // 库表实际落库值是 0（createTraining 对 null/负数归 0），这里如实还原，避免踩 PartyTrainingService:338 的三元拆箱 NPE
        t.setQuota(0);
        return t;
    }

    private static PartyTrainingMember member(long id, long trainingId, long userId, String status) {
        PartyTrainingMember m = new PartyTrainingMember();
        m.setId(id);
        m.setTrainingId(trainingId);
        m.setUserId(userId);
        m.setStatus(status);
        m.setSignupAt(LocalDateTime.now().withNano(0));
        m.setUserName("王小明");
        m.setStudentNo("2024001");
        m.setTrainingName("2026 年第一期入党积极分子培训班");
        return m;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    // =========================================================================
    // A. 期限常量 / 来源文案 / 标签 / 提示装配（§11.1 + §11.2-1、3）
    // =========================================================================

    @Test
    @DisplayName("A1 期限常量逐条：30 / 60 / 365 / 180+90 / 3天·24学时 + 5-7天·40学时 / 365 / 12 / 6-12 / 3-6 / 5 / 5-7 / 90 / 730")
    void periodConstants() {
        assertAll(
                () -> assertEquals(30, PartyService.TALK_WITHIN_DAYS, "申请后一个月内谈话（S24 第七条 / S23）"),
                () -> assertEquals(60, PartyService.ACTIVIST_MIN_EDU_DAYS, "两个月以上培养教育（S23）"),
                () -> assertEquals(365, PartyService.CANDIDATE_MIN_DAYS, "一年以上培养教育（S24 第十三条 / S23）"),
                () -> assertEquals(180, PartyService.OBSERVE_INTERVAL_DAYS_NATIONAL, "国标每半年考察一次"),
                () -> assertEquals(90, PartyService.OBSERVE_INTERVAL_DAYS_SCHOOL, "校规每季度考察一次"),
                () -> assertEquals(3, PartyService.TRAINING_MIN_DAYS_NATIONAL, "国标培训不少于三天"),
                () -> assertEquals(24, PartyService.TRAINING_MIN_HOURS_NATIONAL, "国标培训不少于二十四个学时"),
                () -> assertEquals(5, PartyService.TRAINING_MIN_DAYS_SCHOOL_LOW, "校规培训五至七天（下限）"),
                () -> assertEquals(7, PartyService.TRAINING_MIN_DAYS_SCHOOL_HIGH, "校规培训五至七天（上限）"),
                () -> assertEquals(40, PartyService.TRAINING_MIN_HOURS_SCHOOL, "校规培训不少于四十个学时"),
                () -> assertEquals(365, PartyService.TRAINING_CERT_VALID_DAYS, "结业证书一年内有效（S23）"),
                () -> assertEquals(12, PartyService.PROBATION_MONTHS, "预备期一年（S24 第三十二条）"),
                () -> assertEquals(6, PartyService.PROBATION_EXTEND_MIN_MONTHS, "延长预备期不少于半年"),
                () -> assertEquals(12, PartyService.PROBATION_EXTEND_MAX_MONTHS, "延长预备期最长不超过一年"),
                () -> assertEquals(3, PartyService.APPROVAL_WITHIN_MONTHS, "上级党委审批三个月内（S24 第二十六条）"),
                () -> assertEquals(6, PartyService.APPROVAL_MAX_MONTHS, "审批最长不超过六个月"),
                () -> assertEquals(5, PartyService.PUBLICITY_WORKDAYS_S22, "S22 公示 5 个工作日"),
                () -> assertEquals(5, PartyService.PUBLICITY_DAYS_SCHOOL_LOW, "校规公示 5-7 天（下限）"),
                () -> assertEquals(7, PartyService.PUBLICITY_DAYS_SCHOOL_HIGH, "校规公示 5-7 天（上限）"),
                () -> assertEquals(5, PartyService.PUBLICITY_DEFAULT_WORKDAYS, "公示默认 5 个工作日"),
                () -> assertEquals(90, PartyService.THOUGHT_REPORT_INTERVAL_DAYS, "思想汇报每季度一次"),
                () -> assertEquals(90, PartyService.THOUGHT_REPORT_WARN_DAYS, "超过三个月未交 → 提示"),
                () -> assertEquals(730, PartyService.RE_ACTIVIST_BAN_DAYS, "2 年内不得再次确定为积极分子")
        );
    }

    @Test
    @DisplayName("A2 每一条 *_RULE 文案都确实带来源字样（S23 校规 / S24 国标 / 条款号），不编造")
    void ruleTextsCarrySourceMarkers() {
        assertAll(
                () -> assertHasSource(PartyService.TALK_RULE, "S24", "第七条", "S23", "一个月内"),
                () -> assertHasSource(PartyService.ACTIVIST_RULE, "S23", "两个月以上", "入党积极分子"),
                () -> assertHasSource(PartyService.CANDIDATE_RULE, "S24", "第十三条", "S23", "一年以上"),
                () -> assertHasSource(PartyService.OBSERVE_RULE_NATIONAL, "S24", "第十一条", "每半年"),
                () -> assertHasSource(PartyService.OBSERVE_RULE_SCHOOL, "S23", "每季度"),
                () -> assertHasSource(PartyService.TRAINING_RULE_NATIONAL, "S24", "第十七条", "三天", "二十四个学时"),
                () -> assertHasSource(PartyService.TRAINING_RULE_SCHOOL, "S23", "五至七天", "四十个学时"),
                () -> assertHasSource(PartyService.TRAINING_CERT_RULE, "S23", "一年内有效"),
                () -> assertHasSource(PartyService.PROBATION_RULE, "S24", "第三十二条", "一年"),
                () -> assertHasSource(PartyService.PROBATION_EXTEND_RULE, "S24", "半年", "一年"),
                () -> assertHasSource(PartyService.APPROVAL_RULE, "S24", "第二十六条", "三个月", "六个月"),
                () -> assertHasSource(PartyService.PUBLICITY_RULE, "S22", "S23", "5 个工作日", "5-7 天"),
                () -> assertHasSource(PartyService.THOUGHT_REPORT_RULE, "S23", "每季度", "三个月"),
                () -> assertHasSource(PartyService.RE_ACTIVIST_RULE, "S23", "2 年"),
                () -> assertHasSource(PartyService.CANDIDATE_TO_PROBATIONARY_RULE, "S24", "第三十三条", "S23")
        );
    }

    @Test
    @DisplayName("A3 双口径并存：国标与校规不一致处两条都保留，且平台不替学校选（§11.2-3）")
    void nationalAndSchoolRulesCoexist() {
        String hoursRule = PartyService.TRAINING_HOURS_RULE;
        assertAll(
                () -> assertTrue(PartyService.OBSERVE_INTERVAL_DAYS_NATIONAL != PartyService.OBSERVE_INTERVAL_DAYS_SCHOOL,
                        "考察频率两条口径不同源，必须并存"),
                () -> assertTrue(PartyService.TRAINING_MIN_DAYS_NATIONAL != PartyService.TRAINING_MIN_DAYS_SCHOOL_LOW,
                        "培训天数两条口径不同源，必须并存"),
                () -> assertTrue(PartyService.TRAINING_MIN_HOURS_NATIONAL != PartyService.TRAINING_MIN_HOURS_SCHOOL,
                        "培训学时两条口径不同源，必须并存"),
                () -> assertHasSource(hoursRule, "国标", "S24 第十七条", "校规", "S23", "不替学校选"),
                () -> assertTrue(PartyService.OBSERVE_RULE_NATIONAL.contains("每半年")
                                && PartyService.OBSERVE_RULE_SCHOOL.contains("每季度"),
                        "每半年（国标）与每季度（校规）必须同时保留"),
                () -> assertHasSource(PartyService.PUBLICITY_RULE, "5 个工作日", "5-7 天", "平台不替学校选")
        );
    }

    @Test
    @DisplayName("A4 rules()：全部期限键齐备、数值正确、note 声明不做自动资格判定与不替学校选")
    void rulesMap() {
        Map<String, Object> rules = PartyService.rules();
        assertAll(
                () -> assertEquals(30, rules.get("talkWithinDays")),
                () -> assertEquals(PartyService.TALK_RULE, rules.get("talkRule")),
                () -> assertEquals(60, rules.get("activistMinEduDays")),
                () -> assertEquals(PartyService.ACTIVIST_RULE, rules.get("activistRule")),
                () -> assertEquals(365, rules.get("candidateMinDays")),
                () -> assertEquals(PartyService.CANDIDATE_RULE, rules.get("candidateRule")),
                () -> assertEquals(180, rules.get("observeIntervalDaysNational")),
                () -> assertEquals(90, rules.get("observeIntervalDaysSchool")),
                () -> assertEquals(PartyService.TRAINING_RULE_NATIONAL, rules.get("trainingRuleNational")),
                () -> assertEquals(PartyService.TRAINING_RULE_SCHOOL, rules.get("trainingRuleSchool")),
                () -> assertEquals(PartyService.TRAINING_HOURS_RULE, rules.get("trainingHoursRule")),
                () -> assertEquals(365, rules.get("trainingCertValidDays")),
                () -> assertEquals(PartyService.TRAINING_CERT_RULE, rules.get("trainingCertRule")),
                () -> assertEquals(12, rules.get("probationMonths")),
                () -> assertEquals(PartyService.PROBATION_EXTEND_RULE, rules.get("probationExtendRule")),
                () -> assertEquals(PartyService.APPROVAL_RULE, rules.get("approvalRule")),
                () -> assertEquals(5, rules.get("publicityWorkdaysS22")),
                () -> assertEquals(5, rules.get("publicityDaysSchoolLow")),
                () -> assertEquals(7, rules.get("publicityDaysSchoolHigh")),
                () -> assertEquals(PartyService.PUBLICITY_RULE, rules.get("publicityRule")),
                () -> assertEquals(90, rules.get("thoughtReportIntervalDays")),
                () -> assertEquals(PartyService.THOUGHT_REPORT_RULE, rules.get("thoughtReportRule")),
                () -> assertEquals(730, rules.get("reActivistBanDays")),
                () -> assertEquals(PartyService.RE_ACTIVIST_RULE, rules.get("reActivistRule")),
                () -> assertHasSource(String.valueOf(rules.get("note")), "§11.2-1", "§11.2-3", "不做自动资格判定")
        );
    }

    @Test
    @DisplayName("A5 阶段枚举与显式前置态：STAGE_FLOW 五阶段有序，PREVIOUS_STAGE 逐级映射且从不含 applicant")
    void stageFlowAndPreviousStage() {
        assertAll(
                () -> assertEquals(Arrays.asList("applicant", "activist", "candidate", "probationary", "full"),
                        PartyService.STAGE_FLOW),
                () -> assertEquals(5, PartyService.STAGE_FLOW.size()),
                () -> assertEquals("applicant", PartyService.PREVIOUS_STAGE.get("activist")),
                () -> assertEquals("activist", PartyService.PREVIOUS_STAGE.get("candidate")),
                () -> assertEquals("candidate", PartyService.PREVIOUS_STAGE.get("probationary")),
                () -> assertEquals("probationary", PartyService.PREVIOUS_STAGE.get("full")),
                () -> assertNull(PartyService.PREVIOUS_STAGE.get("applicant"), "第一阶段没有前置态"),
                () -> assertNull(PartyService.PREVIOUS_STAGE.get("unknown")),
                () -> assertEquals(4, PartyService.PREVIOUS_STAGE.size())
        );
    }

    @Test
    @DisplayName("A6 标签口径：阶段 / 公示节点 / 公示态 / 审核态 / 申请态 / 考察类型中文文案")
    void labelHelpers() {
        assertAll(
                () -> assertEquals("入党申请人", PartyService.stageText("applicant")),
                () -> assertEquals("入党积极分子", PartyService.stageText("activist")),
                () -> assertEquals("发展对象", PartyService.stageText("candidate")),
                () -> assertEquals("预备党员", PartyService.stageText("probationary")),
                () -> assertEquals("正式党员", PartyService.stageText("full")),
                () -> assertNull(PartyService.stageText(null)),
                () -> assertEquals("x", PartyService.stageText("x"), "未知枚举原样透传（不编造中文名）"),
                () -> assertEquals("确定党校学员公示", PartyService.nodeTypeText("training_student")),
                () -> assertEquals("发展党员公示", PartyService.nodeTypeText("develop_party")),
                () -> assertEquals("拟转正对象公示", PartyService.nodeTypeText("probation_convert")),
                () -> assertEquals("草稿", PartyService.publicityStatusText("draft")),
                () -> assertEquals("公示中", PartyService.publicityStatusText("publicizing")),
                () -> assertEquals("已结束", PartyService.publicityStatusText("closed")),
                () -> assertEquals("待审核", PartyService.reviewStatusText("pending")),
                () -> assertEquals("已审核", PartyService.reviewStatusText("reviewed")),
                () -> assertEquals("已退回", PartyService.reviewStatusText("returned")),
                () -> assertEquals("有效", PartyService.applicationStatusText("active")),
                () -> assertEquals("已撤回", PartyService.applicationStatusText("withdrawn")),
                () -> assertEquals("考察记录", PartyService.observationTypeText("observation")),
                () -> assertEquals("思想汇报", PartyService.observationTypeText("thought_report")),
                () -> assertEquals("入党积极分子", PartyService.materialStageText("activist"))
        );
    }

    @Test
    @DisplayName("A7 nextStage/stageIndex/isStage：只向前且 full 无下一步")
    void stageNavigation() {
        assertAll(
                () -> assertEquals(0, PartyService.stageIndex("applicant")),
                () -> assertEquals(4, PartyService.stageIndex("full")),
                () -> assertEquals(-1, PartyService.stageIndex("unknown")),
                () -> assertEquals(-1, PartyService.stageIndex(null)),
                () -> assertTrue(PartyService.isStage("probationary")),
                () -> assertFalse(PartyService.isStage("graduate")),
                () -> assertEquals("activist", PartyService.nextStage("applicant")),
                () -> assertEquals("full", PartyService.nextStage("probationary")),
                () -> assertNull(PartyService.nextStage("full"), "已是最后阶段"),
                () -> assertNull(PartyService.nextStage("unknown"))
        );
    }

    @Test
    @DisplayName("A8 nextStageTip：未建台账 / 五级每一步的下一步文案都带来源，且只提示不阻断")
    void nextStageTipBranches() {
        assertAll(
                () -> assertEquals("台账尚未建立，请先登记入党申请与谈话。", PartyService.nextStageTip(null)),
                () -> assertEquals("台账尚未建立，请先登记入党申请与谈话。",
                        PartyService.nextStageTip(stage(1L, WANGXM, null))),
                () -> assertHasSource(PartyService.nextStageTip(stage(1L, WANGXM, "applicant")),
                        "下一步：确定为入党积极分子", "S23", "两个月以上"),
                () -> assertHasSource(PartyService.nextStageTip(stage(1L, WANGXM, "activist")),
                        "下一步：列为发展对象", "S24", "第十三条"),
                () -> assertHasSource(PartyService.nextStageTip(stage(1L, WANGXM, "candidate")),
                        "下一步：接收为预备党员", "第三十三条", "第二十六条"),
                () -> assertHasSource(PartyService.nextStageTip(stage(1L, WANGXM, "probationary")),
                        "下一步：转为正式党员", "第三十二条", "延长"),
                () -> assertHasSource(PartyService.nextStageTip(stage(1L, WANGXM, "full")),
                        "已完成五阶段台账", "第三十二条")
        );
    }

    @Test
    @DisplayName("A9 时限倒计时只提示不阻断：起始日期缺失提示不可算 / 未满提示还有 N 天 / 已满提示可推进")
    void countdownTips() {
        PartyStage applicantNoDate = stage(1L, WANGXM, "applicant");
        PartyStage applicantToday = stage(1L, WANGXM, "applicant");
        applicantToday.setApplicantDate(LocalDate.now());
        PartyStage applicantLongAgo = stage(1L, WANGXM, "applicant");
        applicantLongAgo.setApplicantDate(LocalDate.now().minusDays(PartyService.ACTIVIST_MIN_EDU_DAYS));

        PartyStage candidateToday = stage(1L, WANGXM, "activist");
        candidateToday.setActivistDate(LocalDate.now());

        assertAll(
                () -> assertTrue(PartyService.nextStageTip(applicantNoDate).contains("倒计时不可算"),
                        "起始日期未登记 → 提示不可算，不阻断"),
                () -> assertTrue(PartyService.nextStageTip(applicantToday).contains("距满 60 天还有 60 天"),
                        "刚确定申请人 → 还要满 60 天"),
                () -> assertTrue(PartyService.nextStageTip(applicantLongAgo)
                                .contains("已满 60 天，可由支部书记按实际情况推进"),
                        "已满也只提示，不阻断"),
                () -> assertTrue(PartyService.nextStageTip(applicantLongAgo).contains("平台不做自动资格判定")),
                () -> assertTrue(PartyService.nextStageTip(candidateToday).contains("距满 365 天还有 365 天"))
        );
    }

    @Test
    @DisplayName("A10 申请装配 fill：谈话超期提示三态（未登记且超期 / 未登记未超期 / 迟到谈话）")
    void applicationTalkTips() {
        PartyApplication overdue = application(APPLICATION_ID, WANGXM);
        overdue.setApplyDate(LocalDate.now().minusDays(31));
        PartyApplication fresh = application(APPLICATION_ID, WANGXM);
        PartyApplication late = application(APPLICATION_ID, WANGXM);
        late.setApplyDate(LocalDate.now().minusDays(31));
        late.setTalkDate(LocalDate.now()); // 申请后第 31 天才谈话 → 超过一个月
        PartyApplication onTime = application(APPLICATION_ID, WANGXM);
        onTime.setApplyDate(LocalDate.now());
        onTime.setTalkDate(LocalDate.now());

        when(applicationMapper.findById(APPLICATION_ID)).thenReturn(overdue, fresh, late, onTime);

        Result<?> r1 = service.applicationDetail(WANGXM, "student", APPLICATION_ID);
        Result<?> r2 = service.applicationDetail(WANGXM, "student", APPLICATION_ID);
        Result<?> r3 = service.applicationDetail(WANGXM, "student", APPLICATION_ID);
        Result<?> r4 = service.applicationDetail(WANGXM, "student", APPLICATION_ID);

        assertAll(
                () -> assertEquals(1, ((PartyApplication) r1.getData()).getTalkOverdue().intValue()),
                () -> assertTrue(((PartyApplication) r1.getData()).getTalkLimitTip().contains("超过一个月仍未登记谈话")),
                () -> assertEquals(0, ((PartyApplication) r2.getData()).getTalkOverdue().intValue()),
                () -> assertTrue(((PartyApplication) r2.getData()).getTalkLimitTip().contains("请在一个月内登记谈话")),
                () -> assertEquals(1, ((PartyApplication) r3.getData()).getTalkOverdue().intValue()),
                () -> assertTrue(((PartyApplication) r3.getData()).getTalkLimitTip().contains("谈话登记时间晚于递交后一个月")),
                () -> assertEquals(0, ((PartyApplication) r4.getData()).getTalkOverdue().intValue()),
                () -> assertEquals(PartyService.TALK_RULE, ((PartyApplication) r4.getData()).getTalkLimitTip())
        );
    }

    @Test
    @DisplayName("A11 台账装配 fill：stageText/nextTip 与公示 remainDays、材料必备/已交文案")
    void stagePublicityMaterialFill() {
        PartyStage s = stage(STAGE_ID, WANGXM, "activist");
        when(stageMapper.findById(STAGE_ID)).thenReturn(s);
        Result<?> stageResult = service.stageDetail(COUNSELOR, "teacher", STAGE_ID);

        PartyPublicity publicizing = publicity(PUBLICITY_ID, "publicizing");
        publicizing.setEndDate(LocalDate.now().plusDays(3));
        PartyPublicity draft = publicity(PUBLICITY_ID, "draft");
        when(publicityMapper.findById(PUBLICITY_ID)).thenReturn(publicizing, draft);
        Result<?> pubResult = service.publicityDetail(COUNSELOR, "teacher", PUBLICITY_ID);
        Result<?> draftResult = service.publicityDetail(COUNSELOR, "teacher", PUBLICITY_ID);

        PartyMaterial requiredUnsubmitted = material(MATERIAL_ID, WANGXM, "activist", 1, 0);
        PartyMaterial optionalSubmitted = material(MATERIAL_ID, WANGXM, "activist", 0, 1);
        when(materialMapper.list(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Arrays.asList(requiredUnsubmitted, optionalSubmitted));
        when(materialMapper.count(any(), any(), any(), any())).thenReturn(2L);
        Result<?> materialResult = service.listMaterials(COUNSELOR, "teacher", WANGXM, null, null, null, null);

        List<PartyMaterial> materials = pageList(materialResult);

        assertAll(
                () -> assertEquals("入党积极分子", ((PartyStage) stageResult.getData()).getStageText()),
                () -> assertTrue(((PartyStage) stageResult.getData()).getNextTip().contains("列为发展对象")),
                () -> assertEquals("公示中", ((PartyPublicity) pubResult.getData()).getStatusText()),
                () -> assertEquals("发展党员公示", ((PartyPublicity) pubResult.getData()).getNodeTypeText()),
                () -> assertEquals(3, ((PartyPublicity) pubResult.getData()).getRemainDays().intValue()),
                () -> assertNull(((PartyPublicity) draftResult.getData()).getRemainDays(), "非公示中不给剩余天数"),
                () -> assertEquals("必备", materials.get(0).getRequiredText()),
                () -> assertEquals("未交", materials.get(0).getSubmittedText()),
                () -> assertEquals("选备", materials.get(1).getRequiredText()),
                () -> assertEquals("已交", materials.get(1).getSubmittedText()),
                () -> assertEquals("入党积极分子", materials.get(0).getStageText())
        );
    }

    @Test
    @DisplayName("A12 materialTip：未登记 / 缺必备材料 / 必备齐备 三态，且明示不生成官方表格 PDF")
    void materialTipThreeStates() {
        PartyMaterial missing = material(1L, WANGXM, "activist", 1, 0);
        PartyMaterial optionalMissing = material(2L, WANGXM, "activist", 0, 0);
        PartyMaterial done = material(3L, WANGXM, "activist", 1, 1);

        assertAll(
                () -> assertTrue(PartyService.materialTip("activist", null).contains("尚未登记")),
                () -> assertTrue(PartyService.materialTip("activist", null).contains("不生成官方表格 PDF")),
                () -> assertTrue(PartyService.materialTip("activist", new ArrayList<>()).contains("尚未登记")),
                () -> assertEquals("本阶段还有 1 项必备材料未登记为已交（只提示，不阻断阶段推进）。",
                        PartyService.materialTip("activist", Arrays.asList(missing, optionalMissing, done))),
                () -> assertEquals("本阶段必备材料均已登记为已交。",
                        PartyService.materialTip("activist", Arrays.asList(optionalMissing, done)))
        );
    }

    @Test
    @DisplayName("A13 addWorkdays：跳过周六周日，工作日从起始日次日起算（跨周末 5 个工作日）")
    void addWorkdaysCrossWeekend() {
        // 2026-01-05 是周一；+5 个工作日 → 跳过 1/10（六）、1/11（日）→ 1/12 周一
        assertEquals(LocalDate.of(2026, 1, 12), PartyService.addWorkdays(LocalDate.of(2026, 1, 5), 5));
        assertEquals(DayOfWeek.MONDAY, LocalDate.of(2026, 1, 5).getDayOfWeek(), "夹具前提：1/5 是周一");
        assertEquals(DayOfWeek.MONDAY, PartyService.addWorkdays(LocalDate.of(2026, 1, 5), 5).getDayOfWeek(),
                "推算结果必须落在工作日");
    }

    @Test
    @DisplayName("A14 addWorkdays 边界：起点为周六 / 周日 / 跨月 / null / 0 个工作日")
    void addWorkdaysEdges() {
        assertAll(
                () -> assertEquals(DayOfWeek.SATURDAY, LocalDate.of(2026, 1, 10).getDayOfWeek(), "夹具前提：1/10 是周六"),
                () -> assertEquals(DayOfWeek.SUNDAY, LocalDate.of(2026, 1, 11).getDayOfWeek(), "夹具前提：1/11 是周日"),
                () -> assertEquals(LocalDate.of(2026, 1, 12), PartyService.addWorkdays(LocalDate.of(2026, 1, 10), 1),
                        "起点是周六 → 顺延到周一"),
                () -> assertEquals(LocalDate.of(2026, 1, 12), PartyService.addWorkdays(LocalDate.of(2026, 1, 11), 1),
                        "起点是周日 → 顺延到周一"),
                () -> assertEquals(LocalDate.of(2026, 2, 2), PartyService.addWorkdays(LocalDate.of(2026, 1, 30), 1),
                        "周五 + 1 个工作日 → 跳过周末，跨月到 2/2 周一"),
                () -> assertEquals(LocalDate.of(2026, 2, 6), PartyService.addWorkdays(LocalDate.of(2026, 1, 30), 5),
                        "跨月 + 跨周末 5 个工作日 → 2/6 周五"),
                () -> assertNull(PartyService.addWorkdays(null, 5)),
                () -> assertEquals(LocalDate.of(2026, 1, 5),
                        PartyService.addWorkdays(LocalDate.of(2026, 1, 5), 0), "0 个工作日 = 起始日当天"),
                () -> assertFalse(PartyService.addWorkdays(LocalDate.of(2026, 1, 5), 5).getDayOfWeek()
                        .equals(DayOfWeek.SATURDAY)),
                () -> assertFalse(PartyService.addWorkdays(LocalDate.of(2026, 1, 5), 5).getDayOfWeek()
                        .equals(DayOfWeek.SUNDAY))
        );
    }

    @Test
    @DisplayName("A15 公示节点与状态集合、异议入口口径（只写校级渠道，不写支部个人联系方式）")
    void publicitySetsAndChannel() {
        assertAll(
                () -> assertEquals(3, PartyService.PUBLICITY_NODE_TYPES.size(), "只登记 S22 第 11/14/25 步三个节点"),
                () -> assertTrue(PartyService.PUBLICITY_NODE_TYPES.contains("training_student")),
                () -> assertTrue(PartyService.PUBLICITY_NODE_TYPES.contains("develop_party")),
                () -> assertTrue(PartyService.PUBLICITY_NODE_TYPES.contains("probation_convert")),
                () -> assertTrue(PartyService.PUBLICITY_DRAFT.contains("draft")),
                () -> assertFalse(PartyService.PUBLICITY_DRAFT.contains("publicizing"), "publish 前置只能是 draft"),
                () -> assertTrue(PartyService.PUBLICITY_PUBLICIZING.contains("publicizing")),
                () -> assertFalse(PartyService.PUBLICITY_PUBLICIZING.contains("closed"), "close 前置只能是 publicizing"),
                () -> assertTrue(PartyService.REVIEW_PENDING.contains("pending")),
                () -> assertHasSource(PartyService.DEFAULT_OBJECTION_CHANNEL, "学校学生工作部", "校级渠道",
                        "不登记支部个人联系方式")
        );
    }

    @Test
    @DisplayName("A16 审计字段装配不落到主流程：record 抛异常时业务仍然成功（审计失败绝不影响业务）")
    void auditFailureDoesNotBreakBusiness() {
        doThrowOnAudit();
        PartyStage exist = stage(STAGE_ID, WANGXM, "applicant");
        PartyStage saved = stage(STAGE_ID, WANGXM, "activist");
        when(stageMapper.findById(STAGE_ID)).thenReturn(exist, saved);
        when(stageMapper.advance(any(), any(), any(), any(), any(), any())).thenReturn(1);

        Result<?> r = service.advanceStage(COUNSELOR, "teacher", STAGE_ID, body());

        assertOk(r);
        assertEquals("已推进到「入党积极分子」", r.getMessage());
    }

    private void doThrowOnAudit() {
        org.mockito.Mockito.doThrow(new RuntimeException("审计库挂了"))
                .when(auditLogService).record(any(AuditLog.class));
    }

    // =========================================================================
    // B. 阶段推进：显式枚举前置态 + CAS（§11.2-9）
    // =========================================================================

    @Test
    @DisplayName("B1 未登录 401 / 学生 403：推进是 staff 操作，越权一律不落 SQL")
    void advancePermission() {
        Result<?> anonymous = service.advanceStage(null, "teacher", STAGE_ID, body());
        Result<?> student = service.advanceStage(WANGXM, "student", STAGE_ID, body());

        assertAll(
                () -> assertCode(anonymous, 401),
                () -> assertMsg(anonymous, "请先登录"),
                () -> assertCode(student, 403),
                () -> verifyNoInteractions(stageMapper)
        );
    }

    @Test
    @DisplayName("B2 advance 前置校验：缺 id 400 / 台账不存在 404 / 阶段枚举非法 400")
    void advancePreconditions() {
        when(stageMapper.findById(STAGE_ID)).thenReturn(null);
        PartyStage illegal = stage(STAGE_ID, WANGXM, "graduate");
        when(stageMapper.findById(2L)).thenReturn(illegal);

        Result<?> noId = service.advanceStage(COUNSELOR, "teacher", null, body());
        Result<?> notFound = service.advanceStage(COUNSELOR, "teacher", STAGE_ID, body());
        Result<?> badEnum = service.advanceStage(COUNSELOR, "teacher", 2L, body());

        assertAll(
                () -> assertCode(noId, 400),
                () -> assertMsg(noId, "缺少 id"),
                () -> assertCode(notFound, 404),
                () -> assertMsg(notFound, "党建台账不存在"),
                () -> assertCode(badEnum, 400),
                () -> assertMsg(badEnum, "台账阶段枚举非法：graduate"),
                () -> verify(stageMapper, never()).advance(any(), any(), any(), any(), any(), any())
        );
    }

    @Test
    @DisplayName("B3 applicant→activist：CAS 的 fromStage 恰是显式枚举前置态 applicant（ArgumentCaptor 抓 mapper 入参）")
    void advanceFromApplicantCapturesExplicitFromStage() {
        PartyStage exist = stage(STAGE_ID, WANGXM, "applicant");
        PartyStage saved = stage(STAGE_ID, WANGXM, "activist");
        when(stageMapper.findById(STAGE_ID)).thenReturn(exist, saved);
        when(stageMapper.advance(any(), any(), any(), any(), any(), any())).thenReturn(1);

        Result<?> r = service.advanceStage(COUNSELOR, "teacher", STAGE_ID, body());

        ArgumentCaptor<String> fromCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<String> refCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> branchCaptor = ArgumentCaptor.forClass(String.class);
        verify(stageMapper).advance(eq(STAGE_ID), fromCaptor.capture(), toCaptor.capture(), dateCaptor.capture(),
                refCaptor.capture(), branchCaptor.capture());

        assertAll(
                () -> assertOk(r),
                () -> assertEquals(PartyService.PREVIOUS_STAGE.get("activist"), fromCaptor.getValue()),
                () -> assertEquals("applicant", fromCaptor.getValue(), "fromStage 必须是显式枚举前置态"),
                () -> assertEquals("activist", toCaptor.getValue()),
                () -> assertFalse(fromCaptor.getValue().equals(toCaptor.getValue()), "fromStage 绝不能取目标态"),
                () -> assertEquals(LocalDate.now(), dateCaptor.getValue(), "未传 stepDate 默认今天"),
                () -> assertEquals(PartyService.ACTIVIST_RULE, refCaptor.getValue(), "未传 ref 用默认条款引用"),
                () -> assertEquals(BRANCH, branchCaptor.getValue(), "未传 branchName 沿用台账原值"),
                () -> assertEquals("已推进到「入党积极分子」", r.getMessage())
        );
        assertEquals("activist", toCaptor.getValue());
        assertTrue(capturedAudit("STAGE_ADVANCE").getDetail().contains("阶段推进 applicant -> activist"),
                "审计 detail 必须记录 from -> to，便于追责");
    }

    @Test
    @DisplayName("B4 activist→candidate：fromStage=activist（显式前置态），toStage=candidate，默认引用 CANDIDATE_RULE")
    void advanceFromActivistCapturesExplicitFromStage() {
        PartyStage exist = stage(STAGE_ID, WANGXM, "activist");
        exist.setApplicantDate(LocalDate.now().minusDays(400));
        PartyStage saved = stage(STAGE_ID, WANGXM, "candidate");
        when(stageMapper.findById(STAGE_ID)).thenReturn(exist, saved);
        when(stageMapper.advance(any(), any(), any(), any(), any(), any())).thenReturn(1);

        Result<?> r = service.advanceStage(COUNSELOR, "teacher", STAGE_ID, body("ref", "支部会议通过"));

        ArgumentCaptor<String> fromCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> refCaptor = ArgumentCaptor.forClass(String.class);
        verify(stageMapper).advance(eq(STAGE_ID), fromCaptor.capture(), toCaptor.capture(), any(LocalDate.class),
                refCaptor.capture(), anyString());

        assertAll(
                () -> assertOk(r),
                () -> assertEquals("activist", fromCaptor.getValue()),
                () -> assertEquals("candidate", toCaptor.getValue()),
                () -> assertEquals("支部会议通过", refCaptor.getValue(), "显式传 ref 时不被默认值覆盖")
        );
    }

    @Test
    @DisplayName("B5 candidate→probationary：fromStage=candidate，默认引用 CANDIDATE_TO_PROBATIONARY_RULE（S24 第三十三条）")
    void advanceFromCandidate() {
        PartyStage exist = stage(STAGE_ID, WANGXM, "candidate");
        PartyStage saved = stage(STAGE_ID, WANGXM, "probationary");
        when(stageMapper.findById(STAGE_ID)).thenReturn(exist, saved);
        when(stageMapper.advance(any(), any(), any(), any(), any(), any())).thenReturn(1);

        Result<?> r = service.advanceStage(COUNSELOR, "teacher", STAGE_ID, body());

        ArgumentCaptor<String> fromCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> refCaptor = ArgumentCaptor.forClass(String.class);
        verify(stageMapper).advance(eq(STAGE_ID), fromCaptor.capture(), toCaptor.capture(), any(LocalDate.class),
                refCaptor.capture(), anyString());

        assertAll(
                () -> assertOk(r),
                () -> assertEquals("candidate", fromCaptor.getValue()),
                () -> assertEquals("probationary", toCaptor.getValue()),
                () -> assertEquals(PartyService.CANDIDATE_TO_PROBATIONARY_RULE, refCaptor.getValue())
        );
    }

    @Test
    @DisplayName("B6 probationary→full：fromStage=probationary，默认引用 PROBATION_RULE（S24 第三十二条）")
    void advanceFromProbationary() {
        PartyStage exist = stage(STAGE_ID, WANGXM, "probationary");
        PartyStage saved = stage(STAGE_ID, WANGXM, "full");
        when(stageMapper.findById(STAGE_ID)).thenReturn(exist, saved);
        when(stageMapper.advance(any(), any(), any(), any(), any(), any())).thenReturn(1);

        Result<?> r = service.advanceStage(COUNSELOR, "teacher", STAGE_ID, body());

        ArgumentCaptor<String> fromCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> refCaptor = ArgumentCaptor.forClass(String.class);
        verify(stageMapper).advance(eq(STAGE_ID), fromCaptor.capture(), toCaptor.capture(), any(LocalDate.class),
                refCaptor.capture(), anyString());

        assertAll(
                () -> assertOk(r),
                () -> assertEquals("probationary", fromCaptor.getValue()),
                () -> assertEquals("full", toCaptor.getValue()),
                () -> assertEquals(PartyService.PROBATION_RULE, refCaptor.getValue()),
                () -> assertEquals("已推进到「正式党员」", r.getMessage())
        );
    }

    @Test
    @DisplayName("B7 已是 full 再推进 → 400（只向前，无下一步），且绝不落 SQL")
    void advanceFromFullRejected() {
        when(stageMapper.findById(STAGE_ID)).thenReturn(stage(STAGE_ID, WANGXM, "full"));

        Result<?> r = service.advanceStage(COUNSELOR, "teacher", STAGE_ID, body());

        assertAll(
                () -> assertCode(r, 400),
                () -> assertMsg(r, "已是最后阶段（正式党员），不能继续推进"),
                () -> verify(stageMapper, never()).advance(any(), any(), any(), any(), any(), any())
        );
    }

    @Test
    @DisplayName("B8 越级推进 → 400（applicant 不能直接到 candidate / probationary / full）")
    void advanceSkipStageRejected() {
        when(stageMapper.findById(STAGE_ID)).thenReturn(stage(STAGE_ID, WANGXM, "applicant"));

        Result<?> skipOne = service.advanceStage(COUNSELOR, "teacher", STAGE_ID, body("toStage", "candidate"));
        Result<?> skipAll = service.advanceStage(COUNSELOR, "teacher", STAGE_ID, body("stage", "full"));

        assertAll(
                () -> assertCode(skipOne, 400),
                () -> assertMsg(skipOne, "阶段只能逐级向前，当前阶段 入党申请人 的下一步只能是 入党积极分子"),
                () -> assertCode(skipAll, 400),
                () -> assertMsg(skipAll, "阶段只能逐级向前，当前阶段 入党申请人 的下一步只能是 入党积极分子"),
                () -> verify(stageMapper, never()).advance(any(), any(), any(), any(), any(), any())
        );
    }

    @Test
    @DisplayName("B9 倒退请求（toStage 指向前一阶段）同样被拒：只允许逐级向前")
    void advanceBackwardRejected() {
        when(stageMapper.findById(STAGE_ID)).thenReturn(stage(STAGE_ID, WANGXM, "candidate"));

        Result<?> back = service.advanceStage(COUNSELOR, "teacher", STAGE_ID, body("toStage", "activist"));

        assertAll(
                () -> assertCode(back, 400),
                () -> assertMsg(back, "阶段只能逐级向前，当前阶段 发展对象 的下一步只能是 预备党员"),
                () -> verify(stageMapper, never()).advance(any(), any(), any(), any(), any(), any())
        );
    }

    @Test
    @DisplayName("B10 日期倒序 → 400：不做资格判定，只做「不能倒序填日期」的一致性校验")
    void advanceDateOrderRejected() {
        PartyStage exist = stage(STAGE_ID, WANGXM, "activist");
        exist.setApplicantDate(LocalDate.of(2026, 1, 10));
        when(stageMapper.findById(STAGE_ID)).thenReturn(exist);

        Result<?> r = service.advanceStage(COUNSELOR, "teacher", STAGE_ID, body("stepDate", "2026-01-05"));

        assertAll(
                () -> assertCode(r, 400),
                () -> assertMsg(r, "发展对象日期不能早于入党积极分子日期（2026-01-10）"),
                () -> verify(stageMapper, never()).advance(any(), any(), any(), any(), any(), any())
        );
    }

    @Test
    @DisplayName("B11 不做资格判定：start 日期未满 60 天也允许推进（满不满只提示，不阻断）")
    void advanceDoesNotEnforceEligibility() {
        PartyStage exist = stage(STAGE_ID, WANGXM, "applicant");
        exist.setApplicantDate(LocalDate.now());
        PartyStage saved = stage(STAGE_ID, WANGXM, "activist");
        when(stageMapper.findById(STAGE_ID)).thenReturn(exist, saved);
        when(stageMapper.advance(any(), any(), any(), any(), any(), any())).thenReturn(1);

        Result<?> r = service.advanceStage(COUNSELOR, "teacher", STAGE_ID, body("stepDate", LocalDate.now()));

        assertOk(r);
        assertEquals("已推进到「入党积极分子」", r.getMessage());
    }

    @Test
    @DisplayName("B12 CAS 失效（rows!=1）→ 状态已变化提示，且不读回、不写审计")
    void advanceCasFailure() {
        PartyStage exist = stage(STAGE_ID, WANGXM, "applicant");
        when(stageMapper.findById(STAGE_ID)).thenReturn(exist);
        when(stageMapper.advance(any(), any(), any(), any(), any(), any())).thenReturn(0);

        Result<?> r = service.advanceStage(COUNSELOR, "teacher", STAGE_ID, body());

        assertAll(
                () -> assertCode(r, 400),
                () -> assertMsg(r, CAS_FAIL),
                () -> verify(stageMapper, times(1)).findById(STAGE_ID),
                () -> verifyNoInteractions(auditLogService)
        );
    }

    @Test
    @DisplayName("B13 rollback 仅 admin：teacher / student 一律 403「仅管理员可操作」，绝不落 SQL")
    void rollbackAdminOnly() {
        Result<?> teacher = service.rollbackStage(COUNSELOR, "teacher", STAGE_ID, body("toStage", "applicant",
                "reason", "登记有误"));
        Result<?> student = service.rollbackStage(WANGXM, "student", STAGE_ID, body("toStage", "applicant",
                "reason", "登记有误"));

        assertAll(
                () -> assertCode(teacher, 403),
                () -> assertMsg(teacher, "仅管理员可操作"),
                () -> assertCode(student, 403),
                () -> assertMsg(student, "仅管理员可操作"),
                () -> verifyNoInteractions(stageMapper)
        );
    }

    @Test
    @DisplayName("B14 rollback 前置校验：目标阶段非法 / 第一阶段不可回退 / 只支持逐级回退 均 400")
    void rollbackPreconditions() {
        when(stageMapper.findById(STAGE_ID)).thenReturn(stage(STAGE_ID, WANGXM, "applicant"));
        PartyStage candidate = stage(2L, WANGXM, "candidate");
        when(stageMapper.findById(2L)).thenReturn(candidate);

        Result<?> missing = service.rollbackStage(ADMIN, "admin", STAGE_ID, body());
        Result<?> illegal = service.rollbackStage(ADMIN, "admin", STAGE_ID, body("toStage", "graduate"));
        Result<?> firstStage = service.rollbackStage(ADMIN, "admin", STAGE_ID, body("toStage", "applicant"));
        Result<?> multiStep = service.rollbackStage(ADMIN, "admin", 2L, body("toStage", "applicant"));

        assertAll(
                () -> assertCode(missing, 400),
                () -> assertMsg(missing, "请指定合法的目标阶段（applicant/activist/candidate/probationary/full）"),
                () -> assertCode(illegal, 400),
                () -> assertMsg(illegal, "请指定合法的目标阶段（applicant/activist/candidate/probationary/full）"),
                () -> assertCode(firstStage, 400),
                () -> assertMsg(firstStage, "当前处于第一阶段（入党申请人），无法回退"),
                () -> assertCode(multiStep, 400),
                () -> assertMsg(multiStep, "只支持逐级回退：当前 发展对象 只能回退到 入党积极分子"),
                () -> verify(stageMapper, never()).rollback(any(), any(), any(), any(), any())
        );
    }

    @Test
    @DisplayName("B15 rollback 必须填原因：备注缺失 400，且绝不落 SQL")
    void rollbackReasonRequired() {
        when(stageMapper.findById(STAGE_ID)).thenReturn(stage(STAGE_ID, WANGXM, "activist"));

        Result<?> blank = service.rollbackStage(ADMIN, "admin", STAGE_ID, body("toStage", "applicant", "reason", "  "));
        Result<?> absent = service.rollbackStage(ADMIN, "admin", STAGE_ID, body("toStage", "applicant"));

        assertAll(
                () -> assertCode(blank, 400),
                () -> assertMsg(blank, "阶段回退必须填写原因"),
                () -> assertCode(absent, 400),
                () -> assertMsg(absent, "阶段回退必须填写原因"),
                () -> verify(stageMapper, never()).rollback(any(), any(), any(), any(), any())
        );
    }

    @Test
    @DisplayName("B16 rollback 成功：CAS 传显式前置态与目标态 + 原因，并写审计 STAGE_ROLLBACK")
    void rollbackSuccess() {
        PartyStage exist = stage(STAGE_ID, WANGXM, "candidate");
        PartyStage saved = stage(STAGE_ID, WANGXM, "activist");
        when(stageMapper.findById(STAGE_ID)).thenReturn(exist, saved);
        when(stageMapper.rollback(any(), any(), any(), any(), any())).thenReturn(1);

        Result<?> r = service.rollbackStage(ADMIN, "admin", STAGE_ID,
                body("toStage", "activist", "reason", "支部大会表决记录有误，重新考察"));

        ArgumentCaptor<String> fromCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> atCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(stageMapper).rollback(eq(STAGE_ID), fromCaptor.capture(), toCaptor.capture(), reasonCaptor.capture(),
                atCaptor.capture());

        ScanAudit audit = scanAudit("STAGE_ROLLBACK");

        assertAll(
                () -> assertOk(r),
                () -> assertEquals("已回退到「入党积极分子」", r.getMessage()),
                () -> assertEquals("candidate", fromCaptor.getValue()),
                () -> assertEquals("activist", toCaptor.getValue()),
                () -> assertEquals("支部大会表决记录有误，重新考察", reasonCaptor.getValue()),
                () -> assertNotNull(atCaptor.getValue()),
                () -> assertEquals(0, atCaptor.getValue().getNano(), "回退时间戳截断到秒"),
                () -> assertEquals("STAGE_ROLLBACK", audit.action),
                () -> assertTrue(audit.detail.contains("原因：支部大会表决记录有误，重新考察"))
        );
    }

    @Test
    @DisplayName("B17 rollback CAS 失效 → 状态已变化，不写审计")
    void rollbackCasFailure() {
        when(stageMapper.findById(STAGE_ID)).thenReturn(stage(STAGE_ID, WANGXM, "activist"));
        when(stageMapper.rollback(any(), any(), any(), any(), any())).thenReturn(0);

        Result<?> r = service.rollbackStage(ADMIN, "admin", STAGE_ID, body("toStage", "applicant", "reason", "误操作"));

        assertAll(
                () -> assertMsg(r, CAS_FAIL),
                () -> verifyNoInteractions(auditLogService)
        );
    }

    /** 审计捕获结果（避免 ArgumentCaptor 与 assertAll lambda 的作用域麻烦）。 */
    private static final class ScanAudit {
        final String action;
        final String type;
        final String detail;
        final Long userId;
        final String target;

        ScanAudit(AuditLog entry) {
            this.action = entry.getEventAction();
            this.type = entry.getEventType();
            this.detail = entry.getDetail();
            this.userId = entry.getUserId();
            this.target = entry.getTarget();
        }
    }

    private ScanAudit scanAudit(String expectedAction) {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, times(1)).record(captor.capture());
        AuditLog entry = captor.getValue();
        assertEquals(AuditLogService.TYPE_PARTY, entry.getEventType(), "审计事件类型必须是 TYPE_PARTY");
        assertEquals(expectedAction, entry.getEventAction(), "审计动作");
        return new ScanAudit(entry);
    }

    @Test
    @DisplayName("B18 bindStagePublicity：缺 publicityId 400 / 公示不存在 404 / 不属于该生 400 / CAS 带显式 stage")
    void bindStagePublicityPreconditions() {
        when(stageMapper.findById(STAGE_ID)).thenReturn(stage(STAGE_ID, WANGXM, "candidate"));
        when(publicityMapper.findById(PUBLICITY_ID)).thenReturn(null);
        PartyPublicity others = publicity(PUBLICITY_ID, "closed");
        others.setUserId(ZHAOXH);
        when(publicityMapper.findById(2L)).thenReturn(others);

        Result<?> missing = service.bindStagePublicity(COUNSELOR, "teacher", STAGE_ID, body());
        Result<?> notFound = service.bindStagePublicity(COUNSELOR, "teacher", STAGE_ID, body("publicityId", PUBLICITY_ID));
        Result<?> foreign = service.bindStagePublicity(COUNSELOR, "teacher", STAGE_ID, body("publicityId", 2L));

        assertAll(
                () -> assertCode(missing, 400),
                () -> assertMsg(missing, "请指定 publicityId"),
                () -> assertCode(notFound, 404),
                () -> assertMsg(notFound, "公示记录不存在"),
                () -> assertCode(foreign, 400),
                () -> assertMsg(foreign, "该公示不属于本学生，不能绑定"),
                () -> verify(stageMapper, never()).bindPublicity(any(), any(), any())
        );
    }

    @Test
    @DisplayName("B19 bindStagePublicity 成功：CAS 传显式当前 stage + 写审计 STAGE_BIND_PUBLICITY")
    void bindStagePublicitySuccess() {
        when(stageMapper.findById(STAGE_ID)).thenReturn(stage(STAGE_ID, WANGXM, "candidate"),
                stage(STAGE_ID, WANGXM, "candidate"));
        when(publicityMapper.findById(PUBLICITY_ID)).thenReturn(publicity(PUBLICITY_ID, "closed"));
        when(stageMapper.bindPublicity(any(), any(), any())).thenReturn(1);

        Result<?> r = service.bindStagePublicity(COUNSELOR, "teacher", STAGE_ID, body("publicityId", PUBLICITY_ID));

        ArgumentCaptor<String> stageCaptor = ArgumentCaptor.forClass(String.class);
        verify(stageMapper).bindPublicity(eq(STAGE_ID), stageCaptor.capture(), eq(PUBLICITY_ID));

        assertAll(
                () -> assertOk(r),
                () -> assertEquals("已绑定公示", r.getMessage()),
                () -> assertEquals("candidate", stageCaptor.getValue(), "CAS 必须带显式当前阶段"),
                () -> assertEquals("STAGE_BIND_PUBLICITY", scanAudit("STAGE_BIND_PUBLICITY").action)
        );
    }

    @Test
    @DisplayName("B20 updateStageInternal：CAS 传显式当前 stage，未传键沿用原值，学生端不下发内部字段")
    void updateStageInternal() {
        PartyStage exist = stage(STAGE_ID, WANGXM, "activist");
        exist.setInternalNote("原有考察意见");
        exist.setInternalDiscussion("原有推优讨论");
        PartyStage saved = stage(STAGE_ID, WANGXM, "activist");
        saved.setInternalNote("新考察意见");
        saved.setInternalDiscussion("原有推优讨论");
        when(stageMapper.findById(STAGE_ID)).thenReturn(exist, saved);
        when(stageMapper.updateInternal(any(), any(), any(), any(), any())).thenReturn(1);

        Result<?> r = service.updateStageInternal(COUNSELOR, "teacher", STAGE_ID, body("internalNote", "新考察意见"));

        ArgumentCaptor<String> stageCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> noteCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> discussionCaptor = ArgumentCaptor.forClass(String.class);
        verify(stageMapper).updateInternal(eq(STAGE_ID), stageCaptor.capture(), eq(BRANCH), noteCaptor.capture(),
                discussionCaptor.capture());

        assertAll(
                () -> assertOk(r),
                () -> assertEquals("已保存（内部考察意见仅 staff 可见，学生端不下发）", r.getMessage()),
                () -> assertEquals("activist", stageCaptor.getValue()),
                () -> assertEquals("新考察意见", noteCaptor.getValue()),
                () -> assertEquals("原有推优讨论", discussionCaptor.getValue(), "未传的键沿用原值，不被清空"),
                () -> verifyNoInteractions(auditLogService)
        );
    }

    // =========================================================================
    // C. 公示状态机：draft → publicizing → closed（§11.2-6、10）
    // =========================================================================

    @Test
    @DisplayName("C1 createPublicity：nodeType 只认 S22 第 11/14/25 步三个节点，其它值 400")
    void createPublicityNodeTypeValidation() {
        Result<?> missing = service.createPublicity(COUNSELOR, "teacher", body("title", "公示"));
        Result<?> illegal = service.createPublicity(COUNSELOR, "teacher",
                body("nodeType", "develop_party_x", "title", "公示"));

        assertAll(
                () -> assertCode(missing, 400),
                () -> assertMsg(missing, "nodeType 只能是 training_student / develop_party / probation_convert"
                        + "（只登记 S22 第 11/14/25 步这三个有来源的公示节点）"),
                () -> assertCode(illegal, 400),
                () -> assertMsg(illegal, "nodeType 只能是 training_student / develop_party / probation_convert"
                        + "（只登记 S22 第 11/14/25 步这三个有来源的公示节点）"),
                () -> verifyNoInteractions(publicityMapper)
        );
    }

    @Test
    @DisplayName("C2 createPublicity：缺标题 400 / 非法 stage 400 / 结束早于开始 400")
    void createPublicityFieldValidation() {
        Result<?> noTitle = service.createPublicity(COUNSELOR, "teacher", body("nodeType", "develop_party"));
        Result<?> badStage = service.createPublicity(COUNSELOR, "teacher",
                body("nodeType", "develop_party", "title", "公示", "stage", "graduate"));
        Result<?> badRange = service.createPublicity(COUNSELOR, "teacher", body("nodeType", "develop_party",
                "title", "公示", "startDate", "2026-01-10", "endDate", "2026-01-05"));

        assertAll(
                () -> assertMsg(noTitle, "公示标题不能为空"),
                () -> assertMsg(badStage, "stage 只能是 applicant/activist/candidate/probationary/full"),
                () -> assertMsg(badRange, "公示结束日期不能早于起始日期"),
                () -> verify(publicityMapper, never()).insert(any())
        );
    }

    @Test
    @DisplayName("C3 createPublicity 默认口径：5 个工作日 + 起止推算 + 通知文案用 PUBLICITY_RULE + 校级异议入口 + 草稿态")
    void createPublicityDefaults() {
        when(publicityMapper.insert(any())).thenAnswer(invocation -> {
            ((PartyPublicity) invocation.getArgument(0)).setId(PUBLICITY_ID);
            return 1;
        });

        Result<?> r = service.createPublicity(COUNSELOR, "teacher", body("nodeType", "develop_party",
                "title", "关于拟接收王小明同志为预备党员的公示", "startDate", "2026-01-05"));

        ArgumentCaptor<PartyPublicity> captor = ArgumentCaptor.forClass(PartyPublicity.class);
        verify(publicityMapper).insert(captor.capture());
        PartyPublicity inserted = captor.getValue();

        assertAll(
                () -> assertOk(r),
                () -> assertEquals("公示已创建（草稿）", r.getMessage()),
                () -> assertEquals(5, inserted.getWorkdays().intValue(), "S22 默认公示 5 个工作日"),
                () -> assertEquals(LocalDate.of(2026, 1, 5), inserted.getStartDate()),
                () -> assertEquals(LocalDate.of(2026, 1, 12), inserted.getEndDate(),
                        "5 个工作日跳周末 → 1/12 周一"),
                () -> assertEquals("draft", inserted.getStatus()),
                () -> assertEquals(PartyService.PUBLICITY_RULE, inserted.getNoticeText()),
                () -> assertEquals(PartyService.DEFAULT_OBJECTION_CHANNEL, inserted.getObjectionChannel()),
                () -> assertEquals(COUNSELOR, inserted.getCreatedBy().longValue()),
                () -> assertTrue(inserted.getObjectionChannel().contains("不登记支部个人联系方式"),
                        "异议入口必须声明不登记支部个人联系方式"),
                () -> assertTrue(inserted.getObjectionChannel().contains("学校学生工作部"))
        );
    }

    @Test
    @DisplayName("C4 createPublicity：显式 workdays 覆盖默认值，并据此推算结束日期")
    void createPublicityCustomWorkdays() {
        when(publicityMapper.insert(any())).thenAnswer(invocation -> {
            ((PartyPublicity) invocation.getArgument(0)).setId(PUBLICITY_ID);
            return 1;
        });

        service.createPublicity(COUNSELOR, "teacher", body("nodeType", "training_student", "title", "确定党校学员公示",
                "startDate", "2026-01-05", "workdays", 3));

        ArgumentCaptor<PartyPublicity> captor = ArgumentCaptor.forClass(PartyPublicity.class);
        verify(publicityMapper).insert(captor.capture());

        assertAll(
                () -> assertEquals(3, captor.getValue().getWorkdays().intValue()),
                () -> assertEquals(LocalDate.of(2026, 1, 8), captor.getValue().getEndDate(), "1/5 周一 + 3 工作日 = 1/8 周四")
        );
    }

    @Test
    @DisplayName("C5 createPublicity：workdays<1 回落到默认 5；指定学生时自动绑定该生台账的当前阶段公示")
    void createPublicityBindsStudentStage() {
        when(publicityMapper.insert(any())).thenAnswer(invocation -> {
            ((PartyPublicity) invocation.getArgument(0)).setId(PUBLICITY_ID);
            return 1;
        });
        PartyStage studentStage = stage(STAGE_ID, WANGXM, "probationary");
        when(stageMapper.findByUserId(WANGXM)).thenReturn(studentStage);
        when(stageMapper.bindPublicity(any(), any(), any())).thenReturn(1);

        service.createPublicity(COUNSELOR, "teacher", body("nodeType", "probation_convert", "title", "拟转正对象公示",
                "userId", WANGXM, "workdays", 0));

        ArgumentCaptor<PartyPublicity> captor = ArgumentCaptor.forClass(PartyPublicity.class);
        verify(publicityMapper).insert(captor.capture());
        ArgumentCaptor<String> stageCaptor = ArgumentCaptor.forClass(String.class);
        verify(stageMapper).bindPublicity(eq(STAGE_ID), stageCaptor.capture(), eq(PUBLICITY_ID));

        assertAll(
                () -> assertEquals(5, captor.getValue().getWorkdays().intValue(), "workdays<1 → 默认 5"),
                () -> assertEquals(WANGXM, captor.getValue().getUserId().longValue()),
                () -> assertEquals("probationary", stageCaptor.getValue(), "绑定必须用台账当前阶段的显式值"),
                () -> assertNotNull(scanAudit("PUBLICITY_CREATE"))
        );
    }

    @Test
    @DisplayName("C6 publish 只接受 draft：publicizing / closed 再 publish 一律 400，绝不落 SQL")
    void publishOnlyFromDraft() {
        when(publicityMapper.findById(PUBLICITY_ID)).thenReturn(publicity(PUBLICITY_ID, "publicizing"));
        Result<?> publicizing = service.publishPublicity(COUNSELOR, "teacher", PUBLICITY_ID);

        when(publicityMapper.findById(2L)).thenReturn(publicity(2L, "closed"));
        Result<?> closed = service.publishPublicity(COUNSELOR, "teacher", 2L);

        assertAll(
                () -> assertCode(publicizing, 400),
                () -> assertMsg(publicizing, "只有「草稿」状态的公示可以发布，当前状态：公示中"),
                () -> assertCode(closed, 400),
                () -> assertMsg(closed, "只有「草稿」状态的公示可以发布，当前状态：已结束"),
                () -> verify(publicityMapper, never()).publish(any(), any(), any())
        );
    }

    @Test
    @DisplayName("C7 publish 成功：CAS 传 fromStatus=\"draft\" + 写审计 PUBLICITY_PUBLISH")
    void publishSuccess() {
        when(publicityMapper.findById(PUBLICITY_ID)).thenReturn(publicity(PUBLICITY_ID, "draft"),
                publicity(PUBLICITY_ID, "publicizing"));
        when(publicityMapper.publish(any(), any(), any())).thenReturn(1);

        Result<?> r = service.publishPublicity(COUNSELOR, "teacher", PUBLICITY_ID);

        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> atCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(publicityMapper).publish(eq(PUBLICITY_ID), statusCaptor.capture(), atCaptor.capture());

        assertAll(
                () -> assertOk(r),
                () -> assertEquals("公示已发布", r.getMessage()),
                () -> assertEquals("draft", statusCaptor.getValue(), "显式前置态 = draft"),
                () -> assertEquals(0, atCaptor.getValue().getNano()),
                () -> assertEquals("PUBLICITY_PUBLISH", scanAudit("PUBLICITY_PUBLISH").action)
        );
    }

    @Test
    @DisplayName("C8 已结束（closed）再 close 必须 400，不得做幂等 200（P15 E2E 缺陷 D2 的反例）")
    void closeClosedPublicityMustFail() {
        when(publicityMapper.findById(PUBLICITY_ID)).thenReturn(publicity(PUBLICITY_ID, "closed"));

        Result<?> r = service.closePublicity(COUNSELOR, "teacher", PUBLICITY_ID, body());

        assertAll(
                () -> assertCode(r, 400),
                () -> assertMsg(r, "只有「公示中」的公示可以结束，当前状态：已结束"),
                () -> verify(publicityMapper, never()).close(any(), any(), any(), any()),
                () -> verifyNoInteractions(auditLogService)
        );
    }

    @Test
    @DisplayName("C9 close 只接受 publicizing：draft 也 400")
    void closeDraftPublicityRejected() {
        when(publicityMapper.findById(PUBLICITY_ID)).thenReturn(publicity(PUBLICITY_ID, "draft"));

        Result<?> r = service.closePublicity(COUNSELOR, "teacher", PUBLICITY_ID, body());

        assertAll(
                () -> assertCode(r, 400),
                () -> assertMsg(r, "只有「公示中」的公示可以结束，当前状态：草稿"),
                () -> verify(publicityMapper, never()).close(any(), any(), any(), any())
        );
    }

    @Test
    @DisplayName("C10 close 成功：CAS 传 fromStatus=\"publicizing\" + 默认结论文案 + 写审计 PUBLICITY_CLOSE")
    void closeSuccess() {
        when(publicityMapper.findById(PUBLICITY_ID)).thenReturn(publicity(PUBLICITY_ID, "publicizing"),
                publicity(PUBLICITY_ID, "closed"));
        when(publicityMapper.close(any(), any(), any(), any())).thenReturn(1);

        Result<?> r = service.closePublicity(COUNSELOR, "teacher", PUBLICITY_ID, body());

        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
        verify(publicityMapper).close(eq(PUBLICITY_ID), statusCaptor.capture(), resultCaptor.capture(), any());

        assertAll(
                () -> assertOk(r),
                () -> assertEquals("公示已结束", r.getMessage()),
                () -> assertEquals("publicizing", statusCaptor.getValue(), "显式前置态 = publicizing"),
                () -> assertEquals("公示期满，未收到异议。", resultCaptor.getValue()),
                () -> assertEquals("PUBLICITY_CLOSE", scanAudit("PUBLICITY_CLOSE").action)
        );
    }

    @Test
    @DisplayName("C11 close 可带人工结论；CAS 失效 → 状态已变化且不写审计")
    void closeWithResultAndCasFailure() {
        when(publicityMapper.findById(PUBLICITY_ID)).thenReturn(publicity(PUBLICITY_ID, "publicizing"));
        when(publicityMapper.close(any(), any(), any(), any())).thenReturn(0);

        Result<?> r = service.closePublicity(COUNSELOR, "teacher", PUBLICITY_ID, body("result", "公示期满，收到 1 条异议，已转支部核查。"));

        ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
        verify(publicityMapper).close(eq(PUBLICITY_ID), eq("publicizing"), resultCaptor.capture(), any());

        assertAll(
                () -> assertEquals("公示期满，收到 1 条异议，已转支部核查。", resultCaptor.getValue()),
                () -> assertMsg(r, CAS_FAIL),
                () -> verifyNoInteractions(auditLogService)
        );
    }

    @Test
    @DisplayName("C12 已结束再 PUT（更新）失败：只有草稿可改，publicizing 之后内容冻结")
    void updatePublicityOnlyDraft() {
        when(publicityMapper.findById(PUBLICITY_ID)).thenReturn(publicity(PUBLICITY_ID, "closed"));
        Result<?> closed = service.updatePublicity(COUNSELOR, "teacher", PUBLICITY_ID, body("title", "改标题"));

        when(publicityMapper.findById(2L)).thenReturn(publicity(2L, "publicizing"));
        Result<?> publicizing = service.updatePublicity(COUNSELOR, "teacher", 2L, body("title", "改标题"));

        assertAll(
                () -> assertCode(closed, 400),
                () -> assertMsg(closed, "只有「草稿」状态的公示可以修改，当前状态：已结束"),
                () -> assertCode(publicizing, 400),
                () -> assertMsg(publicizing, "只有「草稿」状态的公示可以修改，当前状态：公示中"),
                () -> verify(publicityMapper, never()).update(any(), any())
        );
    }

    @Test
    @DisplayName("C13 更新公示：缺标题 400 / 异议入口留空 400（只写校级受理渠道）；成功时 CAS 传 fromStatus=\"draft\"")
    void updatePublicityValidationAndSuccess() {
        PartyPublicity draft = publicity(PUBLICITY_ID, "draft");
        when(publicityMapper.findById(PUBLICITY_ID)).thenReturn(draft);

        Result<?> noTitle = service.updatePublicity(COUNSELOR, "teacher", PUBLICITY_ID, body("title", "  "));

        PartyPublicity blankChannel = publicity(PUBLICITY_ID, "draft");
        blankChannel.setObjectionChannel("原渠道");
        when(publicityMapper.findById(PUBLICITY_ID)).thenReturn(blankChannel);
        Result<?> emptyChannel = service.updatePublicity(COUNSELOR, "teacher", PUBLICITY_ID,
                body("title", "公示", "objectionChannel", "   "));

        when(publicityMapper.findById(PUBLICITY_ID)).thenReturn(publicity(PUBLICITY_ID, "draft"),
                publicity(PUBLICITY_ID, "draft"));
        when(publicityMapper.update(any(), any())).thenReturn(1);
        Result<?> ok = service.updatePublicity(COUNSELOR, "teacher", PUBLICITY_ID, body("title", "改后的公示标题"));

        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(publicityMapper).update(any(), statusCaptor.capture());

        assertAll(
                () -> assertMsg(noTitle, "公示标题不能为空"),
                () -> assertCode(emptyChannel, 400),
                () -> assertMsg(emptyChannel, "异议入口不能为空（只写校级受理渠道，不写支部个人联系方式）"),
                () -> assertOk(ok),
                () -> assertEquals("已保存", ok.getMessage()),
                () -> assertEquals("draft", statusCaptor.getValue()),
                () -> verifyNoInteractions(auditLogService)
        );
    }

    @Test
    @DisplayName("C14 学生端只下发已公示记录：onlyOpen=true 且 status 传 null（草稿永不下发）")
    void listPublicitiesStudentOnlyOpen() {
        when(publicityMapper.list(any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(Arrays.asList(publicity(PUBLICITY_ID, "publicizing")));
        when(publicityMapper.count(any(), any(), any(), any(), anyBoolean())).thenReturn(1L);

        Result<?> r = service.listPublicities(WANGXM, "student", null, "develop_party", "publicizing", null, null);

        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> openCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(publicityMapper).list(eq(WANGXM), isNull(), eq("develop_party"), statusCaptor.capture(), openCaptor.capture(),
                anyInt(), anyInt());

        assertAll(
                () -> assertOk(r),
                () -> assertEquals(Boolean.TRUE, openCaptor.getValue(), "学生端 onlyOpen=true"),
                () -> assertNull(statusCaptor.getValue(), "学生端忽略传入状态，只取已公示记录"),
                () -> assertEquals("公示中", ((PartyPublicity) pageList(r).get(0)).getStatusText())
        );
    }

    @Test
    @DisplayName("C15 staff 端公示列表：onlyOpen=false 且状态条件透传；辅导员范围 = 名下学生")
    void listPublicitiesStaffScope() {
        when(publicityMapper.list(any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(new ArrayList<>());
        when(publicityMapper.count(any(), any(), any(), any(), anyBoolean())).thenReturn(0L);

        service.listPublicities(COUNSELOR, "teacher", null, null, "draft", null, null);

        verify(publicityMapper).list(isNull(), eq(COUNSELOR), isNull(), eq("draft"), eq(false), anyInt(), anyInt());
    }

    @Test
    @DisplayName("C16 公示详情：学生看草稿 404（等同不存在）；学生看他人公示 403；staff 可见草稿")
    void publicityDetailVisibility() {
        when(publicityMapper.findById(PUBLICITY_ID)).thenReturn(publicity(PUBLICITY_ID, "draft"));
        Result<?> studentDraft = service.publicityDetail(WANGXM, "student", PUBLICITY_ID);

        PartyPublicity others = publicity(PUBLICITY_ID, "publicizing");
        others.setUserId(ZHAOXH);
        when(publicityMapper.findById(PUBLICITY_ID)).thenReturn(others);
        Result<?> studentForeign = service.publicityDetail(WANGXM, "student", PUBLICITY_ID);

        when(publicityMapper.findById(PUBLICITY_ID)).thenReturn(publicity(PUBLICITY_ID, "draft"));
        Result<?> staffDraft = service.publicityDetail(COUNSELOR, "teacher", PUBLICITY_ID);

        assertAll(
                () -> assertCode(studentDraft, 404),
                () -> assertMsg(studentDraft, "公示记录不存在"),
                () -> assertCode(studentForeign, 403),
                () -> assertMsg(studentForeign, "无权查看他人公示"),
                () -> assertOk(staffDraft),
                () -> assertEquals("草稿", ((PartyPublicity) staffDraft.getData()).getStatusText())
        );
    }

    @Test
    @DisplayName("C17 公示详情：未登录 401 / 缺 id 400 / 不存在 404")
    void publicityDetailPreconditions() {
        Result<?> anonymous = service.publicityDetail(null, "student", PUBLICITY_ID);
        Result<?> noId = service.publicityDetail(WANGXM, "student", null);
        when(publicityMapper.findById(PUBLICITY_ID)).thenReturn(null);
        Result<?> notFound = service.publicityDetail(WANGXM, "student", PUBLICITY_ID);

        assertAll(
                () -> assertCode(anonymous, 401),
                () -> assertMsg(noId, "缺少 id"),
                () -> assertCode(notFound, 404),
                () -> assertMsg(notFound, "公示记录不存在")
        );
    }

    @Test
    @DisplayName("C18 公示的其它硬前置：publish/close/update 缺 id 400、记录不存在 404、学生与未登录越权")
    void publicityPermissionAndMissingId() {
        Result<?> publishAnonymous = service.publishPublicity(null, "student", PUBLICITY_ID);
        Result<?> publishStudent = service.publishPublicity(WANGXM, "student", PUBLICITY_ID);
        Result<?> closeNoId = service.closePublicity(COUNSELOR, "teacher", null, body());
        Result<?> updateNotFound = service.updatePublicity(COUNSELOR, "teacher", PUBLICITY_ID, body("title", "x"));

        assertAll(
                () -> assertCode(publishAnonymous, 401),
                () -> assertCode(publishStudent, 403),
                () -> assertMsg(publishStudent, "无权访问"),
                () -> assertMsg(closeNoId, "缺少 id"),
                () -> assertCode(updateNotFound, 404)
        );
    }

    // =========================================================================
    // D. 学生端保密：服务层抹掉支部内部字段（§11.2-4）
    // =========================================================================

    @Test
    @DisplayName("D1 我的台账：internalNote / internalDiscussion 被抹掉（不靠前端不渲染），staff 写入的库值不受影响")
    void myStageStripsInternalFields() {
        PartyStage mine = stage(STAGE_ID, WANGXM, "candidate");
        mine.setInternalNote("支部内部：群众反映其组织纪律性一般");
        mine.setInternalDiscussion("推优会讨论：3 人同意 1 人保留");
        when(stageMapper.findByUserId(WANGXM)).thenReturn(mine);

        Result<?> r = service.myStage(WANGXM, "student");

        PartyStage out = (PartyStage) r.getData();
        assertAll(
                () -> assertOk(r),
                () -> assertNull(out.getInternalNote(), "内部考察意见严禁下发学生端"),
                () -> assertNull(out.getInternalDiscussion(), "推优讨论内容严禁下发学生端"),
                () -> assertEquals("发展对象", out.getStageText()),
                () -> assertNotNull(out.getNextTip())
        );
    }

    @Test
    @DisplayName("D2 我的台账：尚未建立台账时返回提示结构，且申请记录里的 internalRemark 同样被抹掉")
    void myStageWithoutStage() {
        when(stageMapper.findByUserId(WANGXM)).thenReturn(null);
        PartyApplication application = application(APPLICATION_ID, WANGXM);
        application.setInternalRemark("支部内部备注：家庭情况特殊");
        when(applicationMapper.findByUserId(WANGXM)).thenReturn(application);

        Result<?> r = service.myStage(WANGXM, "student");
        Map<String, Object> data = dataMap(r);

        assertAll(
                () -> assertOk(r),
                () -> assertNull(data.get("stage")),
                () -> assertEquals("尚未建立党建台账；请先登记入党申请。", data.get("message")),
                () -> assertEquals(PartyService.TALK_RULE, data.get("ruleRef")),
                () -> assertNull(((PartyApplication) data.get("application")).getInternalRemark())
        );
    }

    @Test
    @DisplayName("D3 台账详情：学生本人拿到的是抹除后的对象；teacher 视图保留内部字段（同一实体不被写库）")
    void stageDetailSanitizeByRole() {
        PartyStage studentView = stage(STAGE_ID, WANGXM, "activist");
        studentView.setInternalNote("内部意见");
        studentView.setInternalDiscussion("内部讨论");
        when(stageMapper.findById(STAGE_ID)).thenReturn(studentView);
        Result<?> asStudent = service.stageDetail(WANGXM, "student", STAGE_ID);

        PartyStage staffView = stage(STAGE_ID, WANGXM, "activist");
        staffView.setInternalNote("内部意见");
        when(stageMapper.findById(STAGE_ID)).thenReturn(staffView);
        Result<?> asTeacher = service.stageDetail(COUNSELOR, "teacher", STAGE_ID);

        assertAll(
                () -> assertNull(((PartyStage) asStudent.getData()).getInternalNote()),
                () -> assertNull(((PartyStage) asStudent.getData()).getInternalDiscussion()),
                () -> assertEquals("内部意见", ((PartyStage) asTeacher.getData()).getInternalNote(),
                        "辅导员视图保留内部考察意见")
        );
    }

    @Test
    @DisplayName("D4 申请详情：学生视图抹掉 internalRemark，teacher 视图保留")
    void applicationDetailSanitizeByRole() {
        PartyApplication row = application(APPLICATION_ID, WANGXM);
        row.setInternalRemark("内部备注：材料不全，已口头提醒");
        when(applicationMapper.findById(APPLICATION_ID)).thenReturn(row);
        Result<?> asStudent = service.applicationDetail(WANGXM, "student", APPLICATION_ID);

        PartyApplication row2 = application(APPLICATION_ID, WANGXM);
        row2.setInternalRemark("内部备注：材料不全，已口头提醒");
        when(applicationMapper.findById(APPLICATION_ID)).thenReturn(row2);
        Result<?> asTeacher = service.applicationDetail(COUNSELOR, "teacher", APPLICATION_ID);

        assertAll(
                () -> assertNull(((PartyApplication) asStudent.getData()).getInternalRemark()),
                () -> assertEquals("内部备注：材料不全，已口头提醒",
                        ((PartyApplication) asTeacher.getData()).getInternalRemark())
        );
    }

    @Test
    @DisplayName("D5 列表逐行脱敏：/applications 与 /observations 的学生视图逐行抹掉 internal 字段")
    void listRowsSanitizedPerRow() {
        PartyApplication a1 = application(APPLICATION_ID, WANGXM);
        a1.setInternalRemark("备注一");
        PartyApplication a2 = application(2L, WANGXM);
        a2.setInternalRemark("备注二");
        when(applicationMapper.list(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Arrays.asList(a1, a2));
        when(applicationMapper.count(any(), any(), any(), any())).thenReturn(2L);

        PartyObservation o1 = observation(OBSERVATION_ID, WANGXM, "observation", "pending");
        o1.setInternalNote("内部考察意见");
        PartyObservation o2 = observation(2L, WANGXM, "thought_report", "reviewed");
        o2.setInternalNote("另一条内部意见");
        when(observationMapper.list(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Arrays.asList(o1, o2));
        when(observationMapper.count(any(), any(), any(), any())).thenReturn(2L);

        Result<?> apps = service.listApplications(WANGXM, "student", null, null, null, null, null);
        Result<?> observations = service.listObservations(WANGXM, "student", null, null, null, null, null);

        List<PartyApplication> appRows = pageList(apps);
        List<PartyObservation> obsRows = pageList(observations);

        assertAll(
                () -> assertNull(appRows.get(0).getInternalRemark()),
                () -> assertNull(appRows.get(1).getInternalRemark()),
                () -> assertNull(obsRows.get(0).getInternalNote(), "学生视图逐行脱敏内部字段"),
                () -> assertNull(obsRows.get(1).getInternalNote()),
                () -> assertEquals("考察记录", obsRows.get(0).getTypeText()),
                () -> assertEquals("思想汇报", obsRows.get(1).getTypeText())
        );
    }

    @Test
    @DisplayName("D6 学生拿他人数据 → 403 显式拒绝（不静默改写成本人），且绝不查库")
    void studentCannotReadOthersData() {
        Result<?> others = service.listApplications(WANGXM, "student", ZHAOXH, null, null, null, null);
        Result<?> othersStages = service.listStages(WANGXM, "student", ZHAOXH, null, null, null, null, null);
        Result<?> othersObservations = service.listObservations(WANGXM, "student", ZHAOXH, null, null, null, null);

        assertAll(
                () -> assertCode(others, 403),
                () -> assertMsg(others, "无权查看他人党建台账"),
                () -> assertCode(othersStages, 403),
                () -> assertMsg(othersStages, "无权查看他人党建台账"),
                () -> assertCode(othersObservations, 403),
                () -> assertMsg(othersObservations, "无权查看他人党建台账"),
                () -> verifyNoInteractions(applicationMapper, stageMapper, observationMapper)
        );
    }

    @Test
    @DisplayName("D7 单条记录可见性：学生看他人台账/申请 403，且文案点明「他人」")
    void checkTargetForStudent() {
        when(stageMapper.findById(STAGE_ID)).thenReturn(stage(STAGE_ID, ZHAOXH, "applicant"));
        when(applicationMapper.findById(APPLICATION_ID)).thenReturn(application(APPLICATION_ID, ZHAOXH));

        Result<?> otherStage = service.stageDetail(WANGXM, "student", STAGE_ID);
        Result<?> otherApplication = service.applicationDetail(WANGXM, "student", APPLICATION_ID);

        assertAll(
                () -> assertCode(otherStage, 403),
                () -> assertMsg(otherStage, "无权查看他人党建台账"),
                () -> assertCode(otherApplication, 403),
                () -> assertMsg(otherApplication, "无权查看他人入党申请记录")
        );
    }

    @Test
    @DisplayName("D8 辅导员台账里查自己不是学生 → 403「辅导员请从名下学生列表进入」，避免误读全量")
    void checkTargetForCounselorSelf() {
        when(stageMapper.findById(STAGE_ID)).thenReturn(stage(STAGE_ID, COUNSELOR, "applicant"));

        Result<?> r = service.stageDetail(COUNSELOR, "teacher", STAGE_ID);

        assertAll(
                () -> assertCode(r, 403),
                () -> assertMsg(r, "辅导员请从名下学生列表进入党建台账")
        );
    }

    @Test
    @DisplayName("D9 未登录 401 / 未知角色 403：scope 前置拦截（列表与统计）")
    void scopeGuards() {
        Result<?> anonymousList = service.listStages(null, "student", null, null, null, null, null, null);
        Result<?> unknownRole = service.listStages(WANGXM, "guest", null, null, null, null, null, null);

        assertAll(
                () -> assertCode(anonymousList, 401),
                () -> assertMsg(anonymousList, "请先登录"),
                () -> assertCode(unknownRole, 403),
                () -> assertMsg(unknownRole, "无权访问"),
                () -> verifyNoInteractions(stageMapper)
        );
    }

    @Test
    @DisplayName("D10 scope 范围：学生 = 本人 + counselorId 空；辅导员 = 名下（counselorId=本人）；admin = 全量")
    void scopeRanges() {
        when(applicationMapper.list(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(new ArrayList<>());
        when(applicationMapper.count(any(), any(), any(), any())).thenReturn(0L);

        service.listApplications(WANGXM, "student", null, null, null, null, null);
        verify(applicationMapper).list(eq(WANGXM), isNull(), any(), any(), anyInt(), anyInt());

        service.listApplications(COUNSELOR, "teacher", ZHAOXH, null, null, null, null);
        verify(applicationMapper).list(eq(ZHAOXH), eq(COUNSELOR), any(), any(), anyInt(), anyInt());

        service.listApplications(ADMIN, "admin", null, null, null, null, null);
        verify(applicationMapper).list(isNull(), isNull(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("D11 我的考察/思想汇报：逐行脱敏 + 最近一次超期提示（无记录 / 有记录两态）")
    void myObservationsSanitizeAndTip() {
        PartyObservation row = observation(OBSERVATION_ID, WANGXM, "thought_report", "pending");
        row.setInternalNote("内部意见");
        when(observationMapper.list(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Arrays.asList(row));
        when(observationMapper.count(any(), any(), any(), any())).thenReturn(1L);
        when(observationMapper.findLatest(WANGXM, "thought_report")).thenReturn(null);

        Result<?> noLatest = service.myObservations(WANGXM, "student", null, null, null);
        Map<String, Object> noLatestData = dataMap(noLatest);

        PartyObservation old = observation(2L, WANGXM, "thought_report", "reviewed");
        old.setSubmittedAt(LocalDateTime.now().minusDays(120));
        when(observationMapper.findLatest(WANGXM, "thought_report")).thenReturn(old);
        Result<?> withLatest = service.myObservations(WANGXM, "student", null, null, null);
        Map<String, Object> latestData = dataMap(withLatest);
        String tip = String.valueOf(latestData.get("thoughtReportTip"));

        assertAll(
                () -> assertOk(noLatest),
                () -> assertNull(((PartyObservation) pageList(noLatest).get(0)).getInternalNote()),
                () -> assertEquals("尚未提交思想汇报。" + PartyService.THOUGHT_REPORT_RULE,
                        noLatestData.get("thoughtReportTip")),
                () -> assertEquals(PartyService.THOUGHT_REPORT_RULE, noLatestData.get("thoughtReportRule")),
                () -> assertEquals(PartyService.OBSERVE_RULE_NATIONAL, noLatestData.get("observeRuleNational")),
                () -> assertEquals(PartyService.OBSERVE_RULE_SCHOOL, noLatestData.get("observeRuleSchool")),
                () -> assertTrue(tip.contains("最近一次思想汇报提交于")),
                () -> assertTrue(tip.contains("距今 120 天"), "只提示不阻断，实际：" + tip),
                () -> assertHasSource(tip, "S23", "每季度")
        );
    }

    @Test
    @DisplayName("E1 addMaterial 前置校验：非 staff 403 / 缺 userId 400 / 缺材料名 400 / 非法 stage 400")
    void addMaterialValidation() {
        Result<?> student = service.addMaterial(WANGXM, "student", body("userId", WANGXM, "materialName", "入党申请书",
                "stage", "applicant"));
        Result<?> noUser = service.addMaterial(COUNSELOR, "teacher", body("materialName", "入党申请书", "stage", "applicant"));
        Result<?> noName = service.addMaterial(COUNSELOR, "teacher", body("userId", WANGXM, "stage", "applicant"));
        Result<?> badStage = service.addMaterial(COUNSELOR, "teacher",
                body("userId", WANGXM, "materialName", "入党申请书", "stage", "graduate"));

        assertAll(
                () -> assertCode(student, 403),
                () -> assertCode(noUser, 400),
                () -> assertMsg(noUser, "请指定 userId"),
                () -> assertMsg(noName, "材料名称不能为空"),
                () -> assertMsg(badStage, "stage 只能是 applicant/activist/candidate/probationary/full"),
                () -> verify(materialMapper, never()).insert(any())
        );
    }

    @Test
    @DisplayName("E2 addMaterial 登记：默认必备/未交，附件文件名与备注落 insert；明确声明平台不生成官方表格 PDF")
    void addMaterialRegistersNameAndStatusOnly() {
        Result<?> r = service.addMaterial(COUNSELOR, "teacher", body("userId", WANGXM, "materialName", "入党志愿书",
                "stage", "candidate", "submitDate", "2026-02-01", "attachmentName", "志愿书_王小明.pdf",
                "note", "纸质版已收"));

        ArgumentCaptor<PartyMaterial> captor = ArgumentCaptor.forClass(PartyMaterial.class);
        verify(materialMapper).insert(captor.capture());
        PartyMaterial inserted = captor.getValue();

        assertAll(
                () -> assertOk(r),
                () -> assertTrue(r.getMessage().contains("平台不生成官方表格 PDF"), "只登记名称与状态，不出官方 PDF"),
                () -> assertEquals(1, inserted.getRequired().intValue(), "默认必备"),
                () -> assertEquals(0, inserted.getSubmitted().intValue(), "默认未交"),
                () -> assertEquals("入党志愿书", inserted.getMaterialName()),
                () -> assertEquals("candidate", inserted.getStage()),
                () -> assertEquals(LocalDate.of(2026, 2, 1), inserted.getSubmitDate()),
                () -> assertEquals("志愿书_王小明.pdf", inserted.getAttachmentName()),
                () -> assertEquals("纸质版已收", inserted.getNote()),
                () -> assertEquals(COUNSELOR, inserted.getCreatedBy().longValue()),
                () -> assertEquals("必备", ((PartyMaterial) r.getData()).getRequiredText()),
                () -> assertEquals("未交", ((PartyMaterial) r.getData()).getSubmittedText())
        );
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("E3 listMaterials：按 scope 过滤 + 阶段/已交条件透传，材质行只装配名称与状态文案（无内部字段）")
    void listMaterialsScopeAndFill() {
        when(materialMapper.list(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Arrays.asList(material(MATERIAL_ID, WANGXM, "applicant", 1, 1)));
        when(materialMapper.count(any(), any(), any(), any())).thenReturn(1L);

        Result<?> student = service.listMaterials(WANGXM, "student", null, "applicant", 1, null, null);
        verify(materialMapper).list(eq(WANGXM), isNull(), eq("applicant"), eq(1), anyInt(), anyInt());

        Result<?> teacher = service.listMaterials(COUNSELOR, "teacher", ZHAOXH, null, null, null, null);
        verify(materialMapper).list(eq(ZHAOXH), eq(COUNSELOR), isNull(), isNull(), anyInt(), anyInt());

        assertAll(
                () -> assertOk(student),
                () -> assertOk(teacher),
                () -> assertEquals("入党申请人", ((PartyMaterial) pageList(student).get(0)).getStageText()),
                () -> assertEquals("已交", ((PartyMaterial) pageList(student).get(0)).getSubmittedText())
        );
    }

    @Test
    @DisplayName("E4 submitMaterial：缺 id 400 / 不存在 404 / submitted 非法 400 / CAS 失效")
    void submitMaterialValidation() {
        Result<?> noId = service.submitMaterial(COUNSELOR, "teacher", null, body());
        when(materialMapper.findById(MATERIAL_ID)).thenReturn(null);
        Result<?> notFound = service.submitMaterial(COUNSELOR, "teacher", MATERIAL_ID, body());
        when(materialMapper.findById(MATERIAL_ID)).thenReturn(material(MATERIAL_ID, WANGXM, "applicant", 1, 0));
        Result<?> illegal = service.submitMaterial(COUNSELOR, "teacher", MATERIAL_ID, body("submitted", 2));
        when(materialMapper.submit(any(), any(), any(), any(), any())).thenReturn(0);
        Result<?> casFail = service.submitMaterial(COUNSELOR, "teacher", MATERIAL_ID, body("submitted", 1));

        assertAll(
                () -> assertMsg(noId, "缺少 id"),
                () -> assertCode(notFound, 404),
                () -> assertMsg(notFound, "材料项不存在"),
                () -> assertCode(illegal, 400),
                () -> assertMsg(illegal, "submitted 只能是 0（未交）或 1（已交）"),
                () -> assertMsg(casFail, CAS_FAIL),
                () -> verify(materialMapper, never()).submit(any(), eq(2), any(), any(), any())
        );
    }

    @Test
    @DisplayName("E5 submitMaterial 登记交件：默认已交且未传交件日期时补今天，附件名与备注一并落库")
    void submitMaterialDefaults() {
        when(materialMapper.findById(MATERIAL_ID)).thenReturn(material(MATERIAL_ID, WANGXM, "candidate", 1, 0));
        when(materialMapper.submit(any(), any(), any(), any(), any())).thenReturn(1);

        Result<?> r = service.submitMaterial(COUNSELOR, "teacher", MATERIAL_ID,
                body("attachmentName", "志愿书.pdf", "note", "已核对原件"));

        ArgumentCaptor<Integer> submittedCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<String> attachmentCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> noteCaptor = ArgumentCaptor.forClass(String.class);
        verify(materialMapper).submit(eq(MATERIAL_ID), submittedCaptor.capture(), dateCaptor.capture(),
                attachmentCaptor.capture(), noteCaptor.capture());

        assertAll(
                () -> assertOk(r),
                () -> assertEquals("已登记交件", r.getMessage()),
                () -> assertEquals(1, submittedCaptor.getValue().intValue()),
                () -> assertEquals(LocalDate.now(), dateCaptor.getValue(), "已交且未传日期 → 默认今天"),
                () -> assertEquals("志愿书.pdf", attachmentCaptor.getValue()),
                () -> assertEquals("已核对原件", noteCaptor.getValue())
        );
    }

    @Test
    @DisplayName("E6 submitMaterial 标记未交：submitted=0 时不补交件日期，状态文案为「已标记为未交」")
    void submitMaterialMarkUnsubmitted() {
        when(materialMapper.findById(MATERIAL_ID)).thenReturn(material(MATERIAL_ID, WANGXM, "candidate", 1, 1));
        when(materialMapper.submit(any(), any(), any(), any(), any())).thenReturn(1);

        Result<?> r = service.submitMaterial(COUNSELOR, "teacher", MATERIAL_ID, body("submitted", 0));

        ArgumentCaptor<Integer> submittedCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(materialMapper).submit(eq(MATERIAL_ID), submittedCaptor.capture(), dateCaptor.capture(), any(), any());

        assertAll(
                () -> assertOk(r),
                () -> assertEquals("已标记为未交", r.getMessage()),
                () -> assertEquals(0, submittedCaptor.getValue().intValue()),
                () -> assertNull(dateCaptor.getValue(), "标记未交不补日期")
        );
    }

    // =========================================================================
    // F. 统计聚合（§11.2 报表口径）
    // =========================================================================

    private static Map<String, Object> group(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            row.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return row;
    }

    @Test
    @DisplayName("F1 stats：七个分组键齐备且数值/中文分组正确（含 observationReviewStatus 用 review_status 键）")
    void statsAggregation() {
        when(stageMapper.countGroupByStage()).thenReturn(Arrays.asList(
                group("stage", "applicant", "total", 3),
                group("stage", "activist", "total", 2)));
        when(applicationMapper.countGroupByStatus()).thenReturn(Arrays.asList(group("status", "active", "total", 4)));
        when(applicationMapper.count(any(), any(), any(), any())).thenReturn(4L);
        when(publicityMapper.countGroupByStatus()).thenReturn(Arrays.asList(
                group("status", "draft", "total", 1),
                group("status", "closed", "total", 2)));
        when(observationMapper.countGroupByReviewStatus()).thenReturn(Arrays.asList(
                group("review_status", "pending", "total", 2),
                group("review_status", "reviewed", "total", 5)));
        when(trainingMapper.countGroupByStatus()).thenReturn(Arrays.asList(group("status", "signup", "total", 1)));
        when(trainingMemberMapper.countGroupByStatusAll()).thenReturn(Arrays.asList(
                group("status", "signed", "total", 3),
                group("status", "completed", "total", 7)));
        when(materialMapper.countGroupByStage()).thenReturn(Arrays.asList(group("stage", "activist", "total", 6)));

        Result<?> r = service.stats(COUNSELOR, "teacher");
        Map<String, Object> data = dataMap(r);

        List<Map<String, Object>> stageDistribution = asList(data.get("stageDistribution"));
        Map<String, Object> applicationStatus = asMap(data.get("applicationStatus"));
        Map<String, Object> publicityStatus = asMap(data.get("publicityStatus"));
        Map<String, Object> observationReviewStatus = asMap(data.get("observationReviewStatus"));
        List<Map<String, Object>> trainingStatus = asList(data.get("trainingStatus"));
        List<Map<String, Object>> trainingMemberStatus = asList(data.get("trainingMemberStatus"));
        List<Map<String, Object>> materialByStage = asList(data.get("materialByStage"));

        assertAll(
                () -> assertOk(r),
                () -> assertEquals(2, stageDistribution.size()),
                () -> assertEquals("入党申请人", stageDistribution.get(0).get("stageText")),
                () -> assertEquals(3, stageDistribution.get(0).get("total")),
                () -> assertEquals("入党积极分子", stageDistribution.get(1).get("stageText")),
                () -> assertEquals(5L, ((Number) data.get("totalStage")).longValue()),
                () -> assertEquals(4, applicationStatus.get("active"), "按 status 聚合"),
                () -> assertEquals(4L, ((Number) data.get("applicationTotal")).longValue()),
                () -> assertEquals(1, publicityStatus.get("draft")),
                () -> assertEquals(2, publicityStatus.get("closed")),
                () -> assertEquals(2, observationReviewStatus.get("pending"), "键取 review_status（SQL 列名）"),
                () -> assertEquals(5, observationReviewStatus.get("reviewed")),
                () -> assertEquals("报名中", trainingStatus.get(0).get("statusText")),
                () -> assertEquals("已报名", trainingMemberStatus.get(0).get("statusText")),
                () -> assertEquals("已结业", trainingMemberStatus.get(1).get("statusText")),
                () -> assertEquals("入党积极分子", materialByStage.get(0).get("stageText")),
                () -> assertEquals(6, materialByStage.get(0).get("total")),
                () -> assertNotNull(data.get("rules"), "统计必须回带期限口径")
        );
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        assertTrue(value instanceof List, "应为 List，实际：" + value);
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        assertTrue(value instanceof Map, "应为 Map，实际：" + value);
        return (Map<String, Object>) value;
    }

    @Test
    @DisplayName("F2 stats：分组为空时 totalStage=0 且各分组仍给出空结构；未登录 401")
    void statsEmptyAndUnauthorized() {
        when(stageMapper.countGroupByStage()).thenReturn(new ArrayList<>());
        when(applicationMapper.countGroupByStatus()).thenReturn(new ArrayList<>());
        when(publicityMapper.countGroupByStatus()).thenReturn(new ArrayList<>());
        when(observationMapper.countGroupByReviewStatus()).thenReturn(new ArrayList<>());
        when(trainingMapper.countGroupByStatus()).thenReturn(new ArrayList<>());
        when(trainingMemberMapper.countGroupByStatusAll()).thenReturn(new ArrayList<>());
        when(materialMapper.countGroupByStage()).thenReturn(new ArrayList<>());

        Result<?> r = service.stats(ADMIN, "admin");
        Map<String, Object> data = dataMap(r);
        Result<?> anonymous = service.stats(null, "admin");

        assertAll(
                () -> assertOk(r),
                () -> assertEquals(0L, ((Number) data.get("totalStage")).longValue()),
                () -> assertTrue(asList(data.get("stageDistribution")).isEmpty()),
                () -> assertTrue(asMap(data.get("applicationStatus")).isEmpty()),
                () -> assertTrue(asMap(data.get("publicityStatus")).isEmpty()),
                () -> assertTrue(asMap(data.get("observationReviewStatus")).isEmpty()),
                () -> assertCode(anonymous, 401),
                () -> assertMsg(anonymous, "请先登录")
        );
    }

    // =========================================================================
    // G. 导出：脱敏 CSV + BOM（§11.2-6）
    // =========================================================================

    @Test
    @DisplayName("G1 exportCsv 权限与 type 校验：未登录 401 / 学生 403 / 非法 type 400 / 空 type 默认 stages")
    void exportCsvGuards() {
        when(stageMapper.list(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(new ArrayList<>());

        CommunityService.CsvExport anonymous = service.exportCsv(null, "teacher", "stages", null, null);
        CommunityService.CsvExport student = service.exportCsv(WANGXM, "student", "stages", null, null);
        CommunityService.CsvExport badType = service.exportCsv(COUNSELOR, "teacher", "materials", null, null);
        CommunityService.CsvExport blankType = service.exportCsv(COUNSELOR, "teacher", "  ", null, null);

        assertAll(
                () -> assertEquals(401, anonymous.errorCode.intValue()),
                () -> assertEquals("请先登录", anonymous.errorMessage),
                () -> assertNull(anonymous.csv),
                () -> assertEquals(403, student.errorCode.intValue()),
                () -> assertEquals("仅辅导员/管理员可导出", student.errorMessage),
                () -> assertEquals(400, badType.errorCode.intValue()),
                () -> assertEquals("type 只能是 stages（阶段台账）或 applications（申请登记）", badType.errorMessage),
                () -> assertNull(blankType.errorCode, "空 type 默认 stages，不算参数错误"),
                () -> assertNotNull(blankType.csv)
        );
    }

    @Test
    @DisplayName("G2 stages 导出：表头固定、只给学号后四位、不含身份证号/联系方式，日期为空留空")
    void exportCsvStages() {
        PartyStage row = stage(STAGE_ID, WANGXM, "candidate");
        row.setStudentNo("2024001234");
        row.setUserName("王小明");
        row.setClassName("计算机 2401");
        row.setApplicantDate(LocalDate.of(2024, 9, 1));
        row.setActivistDate(LocalDate.of(2025, 1, 1));
        when(stageMapper.list(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Arrays.asList(row));

        CommunityService.CsvExport out = service.exportCsv(COUNSELOR, "teacher", "stages", "计算机系本科生党支部", "candidate");

        String[] lines = out.csv.split("\n");
        assertAll(
                () -> assertNull(out.errorCode),
                () -> assertEquals("学号后四位,姓名,班级,当前阶段,申请人日期,积极分子日期,发展对象日期,预备党员日期,正式党员日期,党支部",
                        lines[0], "表头不含身份证号/联系方式"),
                () -> assertEquals("1234,王小明,计算机 2401,发展对象,2024-09-01,2025-01-01,,,,计算机系本科生党支部", lines[1]),
                () -> assertEquals(2, lines.length),
                () -> assertFalse(out.csv.contains("身份证"), "严禁导出身份证号"),
                () -> assertFalse(out.csv.contains("手机"), "严禁导出联系方式"),
                () -> assertFalse(out.csv.contains("2024001234"), "只给学号后四位，不给完整学号"),
                () -> assertFalse(out.csv.startsWith("\uFEFF"), "服务层不带 BOM（BOM 由控制器加）"),
                () -> assertEquals("party_stages_" + LocalDate.now() + ".csv", out.filename),
                () -> assertEquals("PARTY_EXPORT", scanAudit("PARTY_EXPORT").action)
        );
    }

    @Test
    @DisplayName("G3 applications 导出：表头固定、状态中文化、只给学号后四位，超量提示按支部/阶段过滤")
    void exportCsvApplications() {
        PartyApplication row = application(APPLICATION_ID, WANGXM);
        row.setStudentNo("2024001");
        row.setApplyDate(LocalDate.of(2025, 9, 1));
        row.setTalkDate(LocalDate.of(2025, 9, 20));
        row.setTalkBy("李思");
        when(applicationMapper.list(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Arrays.asList(row));

        CommunityService.CsvExport out = service.exportCsv(COUNSELOR, "teacher", "applications", null, null);

        String[] lines = out.csv.split("\n");
        assertAll(
                () -> assertNull(out.errorCode),
                () -> assertEquals("学号后四位,姓名,班级,申请日期,谈话日期,谈话人,党支部,状态", lines[0]),
                () -> assertEquals("4001,王小明,计算机 2401,2025-09-01,2025-09-20,李思,计算机系本科生党支部,有效", lines[1]),
                () -> assertEquals("party_applications_" + LocalDate.now() + ".csv", out.filename),
                () -> assertFalse(out.csv.contains("身份证"))
        );
    }

    @Test
    @DisplayName("G4 导出超量保护：行数 > 100000 直接 400（防 OOM），不生成 csv")
    void exportCsvTooManyRows() {
        List<PartyStage> huge = new AbstractList<PartyStage>() {
            @Override
            public int size() {
                return MAX_EXPORT + 1;
            }

            @Override
            public PartyStage get(int index) {
                throw new IndexOutOfBoundsException();
            }
        };
        when(stageMapper.list(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(huge);

        CommunityService.CsvExport out = service.exportCsv(ADMIN, "admin", "stages", null, null);

        assertAll(
                () -> assertEquals(400, out.errorCode.intValue()),
                () -> assertEquals("数据量超过导出上限（" + MAX_EXPORT + " 行），请先按支部/阶段过滤", out.errorMessage),
                () -> assertNull(out.csv)
        );
    }

    @Test
    @DisplayName("G5 controller /export：首字节 UTF-8 BOM + text/csv + attachment 文件名 + Content-Length")
    void controllerExportAddsBom() {
        PartyStage row = stage(STAGE_ID, WANGXM, "full");
        row.setStudentNo("2024001");
        when(stageMapper.list(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Arrays.asList(row));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", ADMIN);
        session.setAttribute("role", "admin");

        ResponseEntity<byte[]> resp = controller.export(session, "stages", null, null);

        byte[] bytes = resp.getBody();
        assertNotNull(bytes);
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertAll(
                () -> assertEquals(200, resp.getStatusCode().value()),
                () -> assertEquals(0xEF, bytes[0] & 0xFF),
                () -> assertEquals(0xBB, bytes[1] & 0xFF),
                () -> assertEquals(0xBF, bytes[2] & 0xFF),
                () -> assertTrue(text.startsWith("\uFEFF学号后四位,姓名,班级,当前阶段,申请人日期,积极分子日期,发展对象日期,预备党员日期,正式党员日期,党支部")),
                () -> assertTrue(resp.getHeaders().getContentType().toString().contains("text/csv")),
                () -> assertEquals("attachment; filename=\"party_stages_" + LocalDate.now() + ".csv\"",
                        resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)),
                () -> assertEquals(bytes.length, resp.getHeaders().getContentLength())
        );
    }

    @Test
    @DisplayName("G6 controller /export 失败：按 errorCode 回状态码且无 body（非法 type 400 / 未登录 401 / 学生 403）")
    void controllerExportErrorStatus() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", ADMIN);
        session.setAttribute("role", "admin");

        ResponseEntity<byte[]> badType = controller.export(session, "materials", null, null);
        ResponseEntity<byte[]> anonymous = controller.export(new MockHttpSession(), "stages", null, null);
        MockHttpSession studentSession = new MockHttpSession();
        studentSession.setAttribute("userId", WANGXM);
        studentSession.setAttribute("role", "student");
        ResponseEntity<byte[]> student = controller.export(studentSession, "stages", null, null);

        assertAll(
                () -> assertEquals(400, badType.getStatusCode().value()),
                () -> assertNull(badType.getBody()),
                () -> assertEquals(401, anonymous.getStatusCode().value()),
                () -> assertNull(anonymous.getBody()),
                () -> assertEquals(403, student.getStatusCode().value()),
                () -> assertNull(student.getBody())
        );
    }

    @Test
    @DisplayName("G7 CSV 单元格转义：含逗号/引号的值按 RFC4180 加引号并双写引号（支部名带逗号也安全）")
    void exportCsvEscapesCells() {
        PartyStage row = stage(STAGE_ID, WANGXM, "applicant");
        row.setStudentNo("2024001");
        row.setUserName("王,小明");
        row.setBranchName("计算机系,本科生\"示范\"党支部");
        when(stageMapper.list(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Arrays.asList(row));

        CommunityService.CsvExport out = service.exportCsv(COUNSELOR, "teacher", "stages", null, null);

        assertAll(
                () -> assertTrue(out.csv.contains("\"王,小明\""), "含逗号的姓名必须加引号"),
                () -> assertTrue(out.csv.contains("\"计算机系,本科生\"\"示范\"\"党支部\""), "内部引号双写")
        );
    }

    // =========================================================================
    // H. 党校培训（PartyTrainingService）
    // =========================================================================

    @Test
    @DisplayName("H1 党校状态机常量与标签：班次/学员/结业结论中文，静态判定工具口径")
    void trainingLabelsAndStaticUtils() {
        assertAll(
                () -> assertEquals(Arrays.asList("draft", "signup", "ongoing", "finished"),
                        PartyTrainingService.TRAINING_FLOW),
                () -> assertEquals(2, PartyTrainingService.TRAINING_CANCELLABLE.size()),
                () -> assertTrue(PartyTrainingService.TRAINING_VISIBLE.contains("finished")),
                () -> assertFalse(PartyTrainingService.TRAINING_VISIBLE.contains("draft"), "草稿对学生不可见"),
                () -> assertFalse(PartyTrainingService.TRAINING_VISIBLE.contains("cancelled"), "已取消对学生不可见"),
                () -> assertTrue(PartyTrainingService.SIGNUP_OPEN.contains("signup")),
                () -> assertTrue(PartyTrainingService.SIGNUP_OPEN.contains("ongoing")),
                () -> assertFalse(PartyTrainingService.SIGNUP_OPEN.contains("draft")),
                () -> assertTrue(PartyTrainingService.MEMBER_SIGNED.contains("signed")),
                () -> assertTrue(PartyTrainingService.MEMBER_APPROVED.contains("approved")),
                () -> assertEquals("草稿", PartyTrainingService.statusText("draft")),
                () -> assertEquals("报名中", PartyTrainingService.statusText("signup")),
                () -> assertEquals("进行中", PartyTrainingService.statusText("ongoing")),
                () -> assertEquals("已结束", PartyTrainingService.statusText("finished")),
                () -> assertEquals("已取消", PartyTrainingService.statusText("cancelled")),
                () -> assertNull(PartyTrainingService.statusText(null)),
                () -> assertEquals("已报名", PartyTrainingService.memberStatusText("signed")),
                () -> assertEquals("审核通过", PartyTrainingService.memberStatusText("approved")),
                () -> assertEquals("已驳回", PartyTrainingService.memberStatusText("rejected")),
                // P16 复测缺陷 D3 的回归钉子：动作文案补「已」但绝不叠字（原实现输出「已已驳回」）。
                () -> assertEquals("已审核通过", PartyTrainingService.reviewedText("approved")),
                () -> assertEquals("已驳回", PartyTrainingService.reviewedText("rejected")),
                () -> assertEquals("已报名", PartyTrainingService.reviewedText("signed")),
                () -> assertEquals("已结业", PartyTrainingService.reviewedText("completed")),
                () -> assertNull(PartyTrainingService.reviewedText(null)),
                () -> assertFalse(PartyTrainingService.reviewedText("rejected").contains("已已")),
                () -> assertEquals("已结业", PartyTrainingService.memberStatusText("completed")),
                () -> assertEquals("合格", PartyTrainingService.completeResultText("pass")),
                () -> assertEquals("不合格", PartyTrainingService.completeResultText("fail")),
                () -> assertEquals("优秀", PartyTrainingService.completeResultText("优秀")),
                () -> assertTrue(PartyTrainingService.isCompleteResult("pass")),
                () -> assertTrue(PartyTrainingService.isCompleteResult("fail")),
                () -> assertFalse(PartyTrainingService.isCompleteResult("excellent"), "只收 pass|fail"),
                () -> assertFalse(PartyTrainingService.isCompleteResult("PASS"), "大小写敏感"),
                () -> assertTrue(PartyTrainingService.isCompleted(member(1L, TRAINING_ID, WANGXM, "completed"))),
                () -> assertFalse(PartyTrainingService.isCompleted(member(1L, TRAINING_ID, WANGXM, "approved"))),
                () -> assertTrue(PartyTrainingService.isReviewable(member(1L, TRAINING_ID, WANGXM, "signed"))),
                () -> assertFalse(PartyTrainingService.isReviewable(member(1L, TRAINING_ID, WANGXM, "approved"))),
                () -> assertTrue(PartyTrainingService.isVisibleToStudent(training(1L, "signup"))),
                () -> assertFalse(PartyTrainingService.isVisibleToStudent(training(1L, "draft"))),
                () -> assertTrue(PartyTrainingService.submittedFlag(1)),
                () -> assertFalse(PartyTrainingService.submittedFlag(0)),
                () -> assertTrue(PartyTrainingService.notBlank("x")),
                () -> assertFalse(PartyTrainingService.notBlank("  ")),
                () -> assertTrue(PartyTrainingService.sameUser(WANGXM, WANGXM)),
                () -> assertFalse(PartyTrainingService.sameUser(WANGXM, null)),
                () -> assertEquals(WANGXM, PartyTrainingService.bodyUserId(body("userId", WANGXM)).longValue())
        );
    }

    @Test
    @DisplayName("H2 createTraining：名称必填 / 结业早于开班 400 / 状态非法 400；默认草稿 + 配额 0 + 时长口径 + 审计")
    void createTrainingValidationAndDefaults() {
        Result<?> noName = trainingService.createTraining(COUNSELOR, "teacher", body());
        Result<?> badRange = trainingService.createTraining(COUNSELOR, "teacher",
                body("trainingName", "第一期", "startDate", "2026-03-10", "endDate", "2026-03-01"));
        Result<?> badStatus = trainingService.createTraining(COUNSELOR, "teacher",
                body("trainingName", "第一期", "status", "archived"));
        Result<?> student = trainingService.createTraining(WANGXM, "student", body("trainingName", "第一期"));

        Result<?> ok = trainingService.createTraining(COUNSELOR, "teacher", body("trainingName", "2026 年第一期积极分子培训班",
                "startDate", "2026-03-01", "endDate", "2026-03-07", "days", 7, "hours", 40, "quota", -5,
                "term", "2025-2026-2", "location", "主楼 301"));

        ArgumentCaptor<PartyTraining> captor = ArgumentCaptor.forClass(PartyTraining.class);
        verify(trainingMapper).insert(captor.capture());
        PartyTraining inserted = captor.getValue();

        assertAll(
                () -> assertMsg(noName, "班次名称不能为空"),
                () -> assertMsg(badRange, "结业日期不能早于开班日期"),
                () -> assertMsg(badStatus, "status 只能是 draft/signup/ongoing/finished/cancelled"),
                () -> assertCode(student, 403),
                () -> assertOk(ok),
                () -> assertEquals("班次已创建", ok.getMessage()),
                () -> assertEquals("draft", inserted.getStatus(), "默认草稿"),
                () -> assertEquals(0, inserted.getQuota().intValue(), "负数配额归 0（不限）"),
                () -> assertEquals(40, inserted.getHours().intValue()),
                () -> assertTrue(inserted.getContent().contains("培训时长两条口径并存"), "内容默认带双口径提示"),
                () -> assertHasSource(inserted.getContent(), "S24 第十七条", "S23"),
                () -> assertEquals("草稿", ((PartyTraining) ok.getData()).getStatusText()),
                () -> assertEquals(PartyService.TRAINING_HOURS_RULE, ((PartyTraining) ok.getData()).getHoursTip()),
                () -> assertEquals("TRAINING_CREATE", scanAudit("TRAINING_CREATE").action)
        );
    }

    @Test
    @DisplayName("H3 listTrainings：学生端 hideDraft=true（草稿/已取消不下发），staff=false 并回带报名人数")
    void listTrainingsHideDraftForStudent() {
        PartyTraining row = training(TRAINING_ID, "signup");
        when(trainingMapper.list(any(), any(), any(), anyBoolean(), anyInt(), anyInt())).thenReturn(Arrays.asList(row));
        when(trainingMapper.count(any(), any(), any(), anyBoolean())).thenReturn(1L);
        when(trainingMapper.countMembers(TRAINING_ID)).thenReturn(12L);

        Result<?> student = trainingService.listTrainings(WANGXM, "student", null, null, null, null, null);
        ArgumentCaptor<Boolean> studentHide = ArgumentCaptor.forClass(Boolean.class);
        verify(trainingMapper).list(isNull(), isNull(), isNull(), studentHide.capture(), anyInt(), anyInt());

        Result<?> staff = trainingService.listTrainings(COUNSELOR, "teacher", "draft", null, null, null, null);
        ArgumentCaptor<Boolean> staffHide = ArgumentCaptor.forClass(Boolean.class);
        verify(trainingMapper).list(eq("draft"), isNull(), isNull(), staffHide.capture(), anyInt(), anyInt());

        assertAll(
                () -> assertOk(student),
                () -> assertTrue(studentHide.getValue(), "学生端必须 hideDraft=true"),
                () -> assertEquals(12, ((PartyTraining) pageList(student).get(0)).getMemberCount().intValue()),
                () -> assertOk(staff),
                () -> assertFalse(staffHide.getValue(), "staff 端可见草稿"),
                () -> assertEquals(PartyService.TRAINING_HOURS_RULE, ((PartyTraining) pageList(student).get(0)).getHoursTip())
        );
    }

    @Test
    @DisplayName("H4 trainingDetail：学生看 draft / cancelled 一律 404；可报名班次返回 training + mySignup；staff 可见草稿")
    void trainingDetailVisibility() {
        when(trainingMapper.findById(TRAINING_ID)).thenReturn(training(TRAINING_ID, "draft"));
        Result<?> studentDraft = trainingService.trainingDetail(WANGXM, "student", TRAINING_ID);

        when(trainingMapper.findById(TRAINING_ID)).thenReturn(training(TRAINING_ID, "cancelled"));
        Result<?> studentCancelled = trainingService.trainingDetail(WANGXM, "student", TRAINING_ID);

        when(trainingMapper.findById(TRAINING_ID)).thenReturn(training(TRAINING_ID, "signup"));
        when(trainingMapper.countMembers(TRAINING_ID)).thenReturn(3L);
        when(trainingMemberMapper.findByTrainingAndUser(TRAINING_ID, WANGXM))
                .thenReturn(member(MEMBER_ID, TRAINING_ID, WANGXM, "signed"));
        Result<?> studentOpen = trainingService.trainingDetail(WANGXM, "student", TRAINING_ID);

        when(trainingMapper.findById(TRAINING_ID)).thenReturn(training(TRAINING_ID, "draft"));
        Result<?> staffDraft = trainingService.trainingDetail(COUNSELOR, "teacher", TRAINING_ID);

        Map<String, Object> data = dataMap(studentOpen);

        assertAll(
                () -> assertCode(studentDraft, 404),
                () -> assertMsg(studentDraft, "党校班次不存在"),
                () -> assertCode(studentCancelled, 404),
                () -> assertMsg(studentCancelled, "党校班次不存在"),
                () -> assertOk(studentOpen),
                () -> assertNotNull(data.get("training")),
                () -> assertEquals("已报名", ((PartyTrainingMember) data.get("mySignup")).getStatusText()),
                () -> assertOk(staffDraft),
                () -> assertEquals("草稿", ((PartyTraining) staffDraft.getData()).getStatusText())
        );
    }

    @Test
    @DisplayName("H5 updateTraining：已结束/已取消不可改；取消只允许草稿/报名中；CAS 带显式旧状态 + 审计")
    void updateTrainingStateMachine() {
        when(trainingMapper.findById(TRAINING_ID)).thenReturn(training(TRAINING_ID, "finished"));
        Result<?> finished = trainingService.updateTraining(COUNSELOR, "teacher", TRAINING_ID, body("trainingName", "改"));

        when(trainingMapper.findById(TRAINING_ID)).thenReturn(training(TRAINING_ID, "cancelled"));
        Result<?> cancelled = trainingService.updateTraining(COUNSELOR, "teacher", TRAINING_ID, body("trainingName", "改"));

        when(trainingMapper.findById(TRAINING_ID)).thenReturn(training(TRAINING_ID, "ongoing"));
        Result<?> cancelOngoing = trainingService.updateTraining(COUNSELOR, "teacher", TRAINING_ID, body("status", "cancelled"));

        when(trainingMapper.findById(TRAINING_ID)).thenReturn(training(TRAINING_ID, "signup"),
                training(TRAINING_ID, "cancelled"));
        when(trainingMapper.update(any(), any())).thenReturn(1);
        Result<?> cancelSignup = trainingService.updateTraining(COUNSELOR, "teacher", TRAINING_ID,
                body("status", "cancelled", "note", "人数不足暂缓"));

        ArgumentCaptor<String> fromCaptor = ArgumentCaptor.forClass(String.class);
        verify(trainingMapper).update(any(), fromCaptor.capture());

        assertAll(
                () -> assertCode(finished, 400),
                () -> assertMsg(finished, "「已结束」状态的班次不能修改"),
                () -> assertCode(cancelled, 400),
                () -> assertMsg(cancelled, "「已取消」状态的班次不能修改"),
                () -> assertCode(cancelOngoing, 400),
                () -> assertMsg(cancelOngoing, "只有草稿/报名中的班次可以取消，当前状态：进行中"),
                () -> assertOk(cancelSignup),
                () -> assertEquals("signup", fromCaptor.getValue(), "CAS 必须带显式旧状态"),
                () -> assertEquals("TRAINING_UPDATE", scanAudit("TRAINING_UPDATE").action)
        );
    }

    @Test
    @DisplayName("H6 学员名单范围：学生看他人 403；学生 = 本人 + 无辅导员过滤；辅导员 = 名下；admin = 全量")
    void listTrainingMembersScope() {
        Result<?> studentOther = trainingService.listTrainingMembers(WANGXM, "student", TRAINING_ID, ZHAOXH, null, null, null);
        verifyNoInteractions(trainingMemberMapper);

        when(trainingMemberMapper.list(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(new ArrayList<>());
        when(trainingMemberMapper.count(any(), any(), any(), any())).thenReturn(0L);

        trainingService.listTrainingMembers(WANGXM, "student", TRAINING_ID, null, null, null, null);
        verify(trainingMemberMapper).list(eq(TRAINING_ID), eq(WANGXM), isNull(), isNull(), anyInt(), anyInt());

        trainingService.listTrainingMembers(COUNSELOR, "teacher", TRAINING_ID, null, null, null, null);
        verify(trainingMemberMapper).list(eq(TRAINING_ID), isNull(), eq(COUNSELOR), isNull(), anyInt(), anyInt());

        trainingService.listTrainingMembers(ADMIN, "admin", TRAINING_ID, null, null, null, null);
        verify(trainingMemberMapper).list(eq(TRAINING_ID), isNull(), isNull(), isNull(), anyInt(), anyInt());

        assertAll(
                () -> assertCode(studentOther, 403),
                () -> assertMsg(studentOther, "无权查看他人党校报名记录")
        );
    }

    @Test
    @DisplayName("H7 signupTraining：班次不存在 404 / draft-finished-cancelled 不接受报名 / 重复报名拒绝 / 名额满拒绝")
    void signupPreconditions() {
        when(trainingMapper.findById(TRAINING_ID)).thenReturn(null);
        Result<?> notFound = trainingService.signupTraining(WANGXM, "student", TRAINING_ID, body());

        when(trainingMapper.findById(TRAINING_ID)).thenReturn(training(TRAINING_ID, "draft"));
        Result<?> draft = trainingService.signupTraining(WANGXM, "student", TRAINING_ID, body());

        when(trainingMapper.findById(TRAINING_ID)).thenReturn(training(TRAINING_ID, "finished"));
        Result<?> finished = trainingService.signupTraining(WANGXM, "student", TRAINING_ID, body());

        when(trainingMapper.findById(TRAINING_ID)).thenReturn(training(TRAINING_ID, "cancelled"));
        Result<?> cancelled = trainingService.signupTraining(WANGXM, "student", TRAINING_ID, body());

        when(trainingMapper.findById(TRAINING_ID)).thenReturn(training(TRAINING_ID, "signup"));
        when(trainingMemberMapper.findByTrainingAndUser(TRAINING_ID, WANGXM))
                .thenReturn(member(MEMBER_ID, TRAINING_ID, WANGXM, "signed"));
        Result<?> duplicate = trainingService.signupTraining(WANGXM, "student", TRAINING_ID, body());

        PartyTraining full = training(TRAINING_ID, "signup");
        full.setQuota(30);
        when(trainingMapper.findById(TRAINING_ID)).thenReturn(full);
        when(trainingMemberMapper.findByTrainingAndUser(TRAINING_ID, WANGXM)).thenReturn(null);
        when(trainingMapper.countMembers(TRAINING_ID)).thenReturn(30L);
        Result<?> quotaFull = trainingService.signupTraining(WANGXM, "student", TRAINING_ID, body());

        assertAll(
                () -> assertCode(notFound, 404),
                () -> assertMsg(notFound, "党校班次不存在"),
                () -> assertCode(draft, 400),
                () -> assertMsg(draft, "该班次当前状态为「草稿」，不接受报名"),
                () -> assertCode(finished, 400),
                () -> assertMsg(finished, "该班次当前状态为「已结束」，不接受报名"),
                () -> assertCode(cancelled, 400),
                () -> assertMsg(cancelled, "该班次当前状态为「已取消」，不接受报名"),
                () -> assertCode(duplicate, 400),
                () -> assertMsg(duplicate, "你已报名该班次"),
                () -> assertCode(quotaFull, 400),
                () -> assertMsg(quotaFull, "该班次名额已满（30 人）"),
                () -> verify(trainingMemberMapper, never()).insert(any())
        );
    }

    @Test
    @DisplayName("H8 signupTraining 成功：insert 状态 signed + 报名时间 + 审计 TRAINING_SIGNUP；quota=0 视为不限")
    void signupSuccess() {
        PartyTraining open = training(TRAINING_ID, "signup");
        open.setQuota(0);
        when(trainingMapper.findById(TRAINING_ID)).thenReturn(open);
        when(trainingMemberMapper.findByTrainingAndUser(TRAINING_ID, WANGXM)).thenReturn(null);

        Result<?> r = trainingService.signupTraining(WANGXM, "student", TRAINING_ID, body("note", "希望参加本期"));

        ArgumentCaptor<PartyTrainingMember> captor = ArgumentCaptor.forClass(PartyTrainingMember.class);
        verify(trainingMemberMapper).insert(captor.capture());
        PartyTrainingMember inserted = captor.getValue();

        assertAll(
                () -> assertOk(r),
                () -> assertEquals("报名已提交，等待辅导员审核", r.getMessage()),
                () -> assertEquals(TRAINING_ID, inserted.getTrainingId().longValue()),
                () -> assertEquals(WANGXM, inserted.getUserId().longValue(), "只允许本人报名"),
                () -> assertEquals("signed", inserted.getStatus()),
                () -> assertNotNull(inserted.getSignupAt()),
                () -> assertEquals("希望参加本期", inserted.getNote()),
                () -> assertEquals("已报名", ((PartyTrainingMember) r.getData()).getStatusText()),
                () -> assertEquals("TRAINING_SIGNUP", scanAudit("TRAINING_SIGNUP").action)
        );
    }

    @Test
    @DisplayName("H9 报名审核：只在 signed 前置态；驳回必填原因；通过/驳回都 CAS 传 fromStatus=\"signed\"")
    void reviewTrainingMemberStateMachine() {
        when(trainingMemberMapper.findById(MEMBER_ID)).thenReturn(member(MEMBER_ID, TRAINING_ID, WANGXM, "approved"));
        Result<?> alreadyApproved = trainingService.reviewTrainingMember(COUNSELOR, "teacher", MEMBER_ID, body());

        when(trainingMemberMapper.findById(MEMBER_ID)).thenReturn(member(MEMBER_ID, TRAINING_ID, WANGXM, "signed"));
        Result<?> rejectNoReason = trainingService.reviewTrainingMember(COUNSELOR, "teacher", MEMBER_ID,
                body("action", "reject"));

        when(trainingMemberMapper.findById(MEMBER_ID)).thenReturn(member(MEMBER_ID, TRAINING_ID, WANGXM, "signed"),
                member(MEMBER_ID, TRAINING_ID, WANGXM, "approved"));
        when(trainingMemberMapper.review(any(), any(), any(), any(), any(), any())).thenReturn(1);
        Result<?> approved = trainingService.reviewTrainingMember(COUNSELOR, "teacher", MEMBER_ID, body());

        ArgumentCaptor<String> fromCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(trainingMemberMapper).review(eq(MEMBER_ID), fromCaptor.capture(), toCaptor.capture(), eq(COUNSELOR),
                any(), reasonCaptor.capture());

        when(trainingMemberMapper.findById(MEMBER_ID)).thenReturn(member(MEMBER_ID, TRAINING_ID, WANGXM, "signed"),
                member(MEMBER_ID, TRAINING_ID, WANGXM, "rejected"));
        Result<?> rejected = trainingService.reviewTrainingMember(COUNSELOR, "teacher", MEMBER_ID,
                body("action", "reject", "rejectReason", "材料不齐"));

        assertAll(
                () -> assertCode(alreadyApproved, 400),
                () -> assertMsg(alreadyApproved, "只有「已报名」的记录可以审核，当前状态：审核通过"),
                () -> assertCode(rejectNoReason, 400),
                () -> assertMsg(rejectNoReason, "驳回必须填写原因"),
                () -> assertOk(approved),
                () -> assertEquals("已审核通过", approved.getMessage(),
                        "P16 文案修复：审核通过对外文案保持原样（reviewedText 给未带「已」的标签补前缀）"),
                () -> assertEquals("signed", fromCaptor.getValue(), "显式前置态 = signed"),
                () -> assertEquals("approved", toCaptor.getValue()),
                () -> assertNull(reasonCaptor.getValue(), "通过时无驳回原因"),
                () -> assertOk(rejected),
                () -> assertEquals("已驳回", rejected.getMessage(),
                        "P16 文案修复：原实现输出「已已驳回」（memberStatusText 已含「已」）"),
                () -> verifyNoInteractions(auditLogService)
        );
    }

    @Test
    @DisplayName("H10 结业登记：只在 approved 前置态；completeResult 只收 pass|fail；证书有效期默认结业日 + 1 年、可人工覆盖")
    void completeTrainingMember() {
        when(trainingMemberMapper.findById(MEMBER_ID)).thenReturn(member(MEMBER_ID, TRAINING_ID, WANGXM, "signed"));
        Result<?> notApproved = trainingService.completeTrainingMember(COUNSELOR, "teacher", MEMBER_ID, body());

        when(trainingMemberMapper.findById(MEMBER_ID)).thenReturn(member(MEMBER_ID, TRAINING_ID, WANGXM, "approved"));
        Result<?> missing = trainingService.completeTrainingMember(COUNSELOR, "teacher", MEMBER_ID, body());
        Result<?> illegalA = trainingService.completeTrainingMember(COUNSELOR, "teacher", MEMBER_ID,
                body("completeResult", "excellent"));
        Result<?> illegalB = trainingService.completeTrainingMember(COUNSELOR, "teacher", MEMBER_ID,
                body("completeResult", "合格"));

        when(trainingMemberMapper.findById(MEMBER_ID)).thenReturn(member(MEMBER_ID, TRAINING_ID, WANGXM, "approved"),
                member(MEMBER_ID, TRAINING_ID, WANGXM, "completed"));
        when(trainingMemberMapper.complete(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        Result<?> passed = trainingService.completeTrainingMember(COUNSELOR, "teacher", MEMBER_ID,
                body("completeResult", "pass", "completeDate", "2026-03-01", "certNo", "2026-0001"));

        ArgumentCaptor<String> fromCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDate> completeDateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<String> certNoCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDate> issueCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> validCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(trainingMemberMapper).complete(eq(MEMBER_ID), fromCaptor.capture(), resultCaptor.capture(),
                completeDateCaptor.capture(), certNoCaptor.capture(), issueCaptor.capture(), validCaptor.capture(), any());

        when(trainingMemberMapper.findById(MEMBER_ID)).thenReturn(member(MEMBER_ID, TRAINING_ID, WANGXM, "approved"),
                member(MEMBER_ID, TRAINING_ID, WANGXM, "completed"));
        trainingService.completeTrainingMember(COUNSELOR, "teacher", MEMBER_ID, body("completeResult", "fail",
                "completeDate", "2026-03-01", "certValidUntil", "2030-12-31"));

        ArgumentCaptor<LocalDate> overrideCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(trainingMemberMapper, times(2)).complete(any(), any(), any(), any(), any(), any(), overrideCaptor.capture(), any());

        ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, times(2)).record(auditCaptor.capture());

        assertAll(
                () -> assertCode(notApproved, 400),
                () -> assertMsg(notApproved, "只有「审核通过」的学员可以登记结业，当前状态：已报名"),
                () -> assertCode(missing, 400),
                () -> assertMsg(missing, "请填写结业结论：pass（合格）或 fail（不合格）"),
                () -> assertCode(illegalA, 400),
                () -> assertMsgStartsWith(illegalA, "结业结论只能是 pass（合格）或 fail（不合格）"),
                () -> assertTrue(illegalA.getMessage().contains("党校结业标准/及格线无公开来源")),
                () -> assertCode(illegalB, 400),
                () -> assertOk(passed),
                () -> assertEquals("结业登记已保存", passed.getMessage()),
                () -> assertEquals("approved", fromCaptor.getValue(), "显式前置态 = approved"),
                () -> assertEquals("pass", resultCaptor.getValue()),
                () -> assertEquals(LocalDate.of(2026, 3, 1), completeDateCaptor.getValue()),
                () -> assertEquals("2026-0001", certNoCaptor.getValue()),
                () -> assertEquals(LocalDate.of(2026, 3, 1), issueCaptor.getValue(), "未传颁发日 → 同结业日"),
                () -> assertEquals(LocalDate.of(2027, 3, 1), validCaptor.getValue(), "默认 = 颁发日 + 1 年"),
                () -> assertEquals(LocalDate.of(2030, 12, 31), overrideCaptor.getValue(), "人工覆盖值必须采信"),
                () -> assertEquals("TRAINING_COMPLETE", auditCaptor.getAllValues().get(0).getEventAction()),
                () -> assertEquals(AuditLogService.TYPE_PARTY, auditCaptor.getAllValues().get(0).getEventType()),
                () -> assertTrue(auditCaptor.getAllValues().get(1).getDetail().contains("结业登记"))
        );
    }

    @Test
    @DisplayName("H11 证书信息覆盖：只在 completed 前置态；缺有效期时按颁发日 + 365 天补齐")
    void updateTrainingCert() {
        when(trainingMemberMapper.findById(MEMBER_ID)).thenReturn(member(MEMBER_ID, TRAINING_ID, WANGXM, "approved"));
        Result<?> notCompleted = trainingService.updateTrainingCert(COUNSELOR, "teacher", MEMBER_ID, body());

        PartyTrainingMember completed = member(MEMBER_ID, TRAINING_ID, WANGXM, "completed");
        completed.setCertIssueDate(LocalDate.of(2026, 3, 1));
        when(trainingMemberMapper.findById(MEMBER_ID)).thenReturn(completed, completed);
        when(trainingMemberMapper.updateCert(any(), any(), any(), any())).thenReturn(1);
        Result<?> filled = trainingService.updateTrainingCert(COUNSELOR, "teacher", MEMBER_ID,
                body("certNo", "2026-0002"));

        ArgumentCaptor<String> certNoCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDate> validCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(trainingMemberMapper).updateCert(eq(MEMBER_ID), certNoCaptor.capture(), any(), validCaptor.capture());

        assertAll(
                () -> assertCode(notCompleted, 400),
                () -> assertMsg(notCompleted, "只有「已结业」的学员才有证书信息，当前状态：审核通过"),
                () -> assertOk(filled),
                () -> assertEquals("证书信息已更新", filled.getMessage()),
                () -> assertEquals("2026-0002", certNoCaptor.getValue()),
                () -> assertEquals(LocalDate.of(2027, 3, 1), validCaptor.getValue(), "缺有效期 → 颁发日 + 365 天"),
                () -> assertEquals("TRAINING_CERT_UPDATE", scanAudit("TRAINING_CERT_UPDATE").action)
        );
    }

    @Test
    @DisplayName("H12 myTrainings：只查本人报名 + 回带证书/时长口径与「不做自动判定」说明；未登录 401")
    void myTrainings() {
        when(trainingMemberMapper.list(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Arrays.asList(member(MEMBER_ID, TRAINING_ID, WANGXM, "completed")));
        when(trainingMemberMapper.count(any(), any(), any(), any())).thenReturn(1L);

        Result<?> r = trainingService.myTrainings(WANGXM, "student", null, null);
        Map<String, Object> data = dataMap(r);
        Result<?> anonymous = trainingService.myTrainings(null, "student", null, null);

        verify(trainingMemberMapper).list(isNull(), eq(WANGXM), isNull(), isNull(), anyInt(), anyInt());

        assertAll(
                () -> assertOk(r),
                () -> assertEquals(PartyService.TRAINING_CERT_RULE, data.get("certRule")),
                () -> assertEquals(PartyService.TRAINING_HOURS_RULE, data.get("hoursRule")),
                () -> assertTrue(String.valueOf(data.get("note")).contains("不做自动判定")),
                () -> assertEquals("已结业", ((PartyTrainingMember) pageList(r).get(0)).getStatusText()),
                () -> assertCode(anonymous, 401)
        );
    }

    @Test
    @DisplayName("H13 党校统计：班次状态 + 学员状态分组带中文，且回带时长/证书口径")
    void trainingStats() {
        when(trainingMapper.countGroupByStatus()).thenReturn(Arrays.asList(group("status", "draft", "total", 2)));
        when(trainingMemberMapper.countGroupByStatusAll()).thenReturn(Arrays.asList(group("status", "approved", "total", 9)));

        Result<?> r = trainingService.stats(COUNSELOR, "teacher");
        Map<String, Object> data = dataMap(r);

        assertAll(
                () -> assertOk(r),
                () -> assertEquals("草稿", asList(data.get("trainingStatus")).get(0).get("statusText")),
                () -> assertEquals(2, asList(data.get("trainingStatus")).get(0).get("total")),
                () -> assertEquals("审核通过", asList(data.get("memberStatus")).get(0).get("statusText")),
                () -> assertEquals(9, asList(data.get("memberStatus")).get(0).get("total")),
                () -> assertEquals(PartyService.TRAINING_HOURS_RULE, data.get("hoursRule")),
                () -> assertEquals(PartyService.TRAINING_CERT_RULE, data.get("certRule"))
        );
    }

    @Test
    @DisplayName("H14 学员状态装配：certValid 按有效期是否过期给 1/0，null 有效期给 null")
    void memberFillCertValid() {
        PartyTrainingMember alive = member(MEMBER_ID, TRAINING_ID, WANGXM, "completed");
        alive.setCertValidUntil(LocalDate.now().plusDays(1));
        PartyTrainingMember expired = member(2L, TRAINING_ID, WANGXM, "completed");
        expired.setCertValidUntil(LocalDate.now().minusDays(1));
        PartyTrainingMember noCert = member(3L, TRAINING_ID, WANGXM, "approved");
        alive.setCompleteResult("pass");
        expired.setCompleteResult("fail");

        when(trainingMemberMapper.list(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Arrays.asList(alive, expired, noCert));
        when(trainingMemberMapper.count(any(), any(), any(), any())).thenReturn(3L);

        Result<?> r = trainingService.myTrainings(WANGXM, "student", null, null);
        List<PartyTrainingMember> rows = pageList(r);

        assertAll(
                () -> assertEquals(1, rows.get(0).getCertValid().intValue(), "未过期"),
                () -> assertEquals(0, rows.get(1).getCertValid().intValue(), "已过期"),
                () -> assertNull(rows.get(2).getCertValid(), "无有效期不判定"),
                () -> assertEquals("合格", rows.get(0).getCompleteResultText()),
                () -> assertEquals("不合格", rows.get(1).getCompleteResultText()),
                () -> assertNull(rows.get(2).getCompleteResultText(), "未结业无结论文案")
        );
    }

    @Test
    @DisplayName("H15 PartyTrainingService.auditLogService 是普通实例字段（非 static），@InjectMocks 可注入、审计可验证")
    void trainingAuditFieldIsNotStatic() throws Exception {
        Field field = PartyTrainingService.class.getDeclaredField("auditLogService");
        assertFalse(Modifier.isStatic(field.getModifiers()),
                "PartyTrainingService 的审计字段不是 static —— 因此 @InjectMocks 能正常注入（与 PartyService 对比）");
    }

    @Test
    @DisplayName("H16【P16 修复回归】updateTraining 在班次 quota 为 null 且请求未带 quota 时不再 NPE，quota 兜底为 0")
    void updateTrainingWithNullQuotaNoLongerThrows() {
        PartyTraining legacy = training(TRAINING_ID, "signup");
        legacy.setQuota(null); // 历史数据 / 建表默认值缺失时的真实形态
        when(trainingMapper.findById(TRAINING_ID)).thenReturn(legacy);
        when(trainingMapper.update(any(), any())).thenReturn(1);
        when(trainingMapper.countMembers(TRAINING_ID)).thenReturn(0L);

        Result<?> r = trainingService.updateTraining(
                COUNSELOR, "teacher", TRAINING_ID, body("trainingName", "改名"));

        ArgumentCaptor<PartyTraining> captor = ArgumentCaptor.forClass(PartyTraining.class);
        assertAll(
                () -> assertOk(r),
                () -> verify(trainingMapper, times(1)).update(captor.capture(), any()),
                () -> assertEquals(0, captor.getValue().getQuota().intValue(),
                        "原写法（三元两分支 int/Integer 混合 ⇒ 条件表达式提升为 int、exist.getQuota() 隐式拆箱）"
                                + "在 quota=null 时抛 NPE；修复后兜底为 0")
        );
    }

    @Test
    @DisplayName("P1【回归钉子】PartyService.auditLogService 必须是实例字段（static ⇒ Spring 不注入、审计静默失效）")
    void auditLogServiceIsInstanceFieldNotStatic() throws Exception {
        Field field = PartyService.class.getDeclaredField("auditLogService");
        assertAll(
                () -> assertFalse(Modifier.isStatic(field.getModifiers()),
                        "static 字段注入不被 Spring 支持（启动日志实测告警：Autowired annotation is not "
                                + "supported on static fields）⇒ 生产环境该字段恒为 null、审计被 catch 静默吞掉；"
                                + "必须是实例字段"),
                () -> assertTrue(Modifier.isPrivate(field.getModifiers())),
                () -> assertNotNull(ReflectionTestUtils.getField(service, "auditLogService"),
                        "@InjectMocks 必须能把 @Mock AuditLogService 注入到实例字段（否则 12 条审计断言都是假绿）")
        );
    }

    // @@SECTION_ANCHOR_REMOVED@@
}
