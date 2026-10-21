package com.umi.nest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.umi.nest.common.Result;
import com.umi.nest.controller.AidReviewController;
import com.umi.nest.entity.AidApplication;
import com.umi.nest.entity.AidObjection;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.AidApplicationMapper;
import com.umi.nest.mapper.AidObjectionMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AidReviewService 单元测试（P11 家庭经济困难学生认定）。
 *
 * <p>风格与 AidServiceTest 一致：MockitoExtension + LENIENT + @Mock mapper + @InjectMocks + 中文 @DisplayName。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AidReviewServiceTest {

    @Mock
    private AidApplicationMapper mapper;
    @Mock
    private AidObjectionMapper objectionMapper;
    @Mock
    private StudentProfileMapper profileMapper;

    @InjectMocks
    private AidReviewService service;

    // =====================================================================
    // 工具
    // =====================================================================

    private static final String CY = "2025-2026";

    private AidApplication app(long id, long studentId, String stage, String level, String status) {
        AidApplication a = new AidApplication();
        a.setId(id);
        a.setStudentId(studentId);
        a.setAidType("need");
        a.setReviewStage(stage);
        a.setPovertyLevel(level);
        a.setStatus(status);
        a.setCertYear(CY);
        a.setAcademicYear(CY);
        a.setReason("家庭收入低于当地低保线");
        a.setStudentName("测试学生");
        a.setStudentNo("S2025001");
        a.setClassName("软件 1 班");
        return a;
    }

    private StudentProfile profile(Long counselorId) {
        StudentProfile p = new StudentProfile();
        p.setUserId(5L);
        p.setCounselorId(counselorId);
        return p;
    }

    private AidObjection objection(long id, long appId, long studentId, String status) {
        AidObjection o = new AidObjection();
        o.setId(id);
        o.setApplicationId(appId);
        o.setStudentId(studentId);
        o.setStatus(status);
        o.setContent("对级别有异议");
        o.setDeadline(LocalDateTime.now().plusDays(3));
        return o;
    }

    private static boolean isOk(Result<?> r) {
        return r != null && r.getCode() != null && r.getCode() == 200;
    }

    private static boolean isCode(Result<?> r, int code) {
        return r != null && r.getCode() != null && r.getCode() == code;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Result<?> r) {
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Result<?> r) {
        return (List<Map<String, Object>>) r.getData();
    }

    private static String json(Object o) throws Exception {
        return new ObjectMapper().writeValueAsString(o);
    }

    private static List<String> materials() {
        return Arrays.asList("低保证明", "家庭收入证明", "在读证明", "村委会困难证明");
    }

    /** 取一个与当前学期不同的时间点（用于复查「跨学期允许复查」用例）。 */
    private static LocalDateTime otherSemesterMoment() {
        LocalDate today = LocalDate.now();
        String key = AidReviewService.semesterKey(today);
        LocalDate other = key.endsWith("-1")
                ? LocalDate.of(today.getYear(), 5, 10)
                : LocalDate.of(today.getYear(), 10, 10);
        return other.atTime(10, 0);
    }

    // =====================================================================
    // 一、常量与阶段定义
    // =====================================================================

    @Test
    @DisplayName("01 级别只有两档：特困 / 贫困")
    void levelOnlyTwo() {
        assertEquals(2, AidReviewService.LEVELS.size());
        assertTrue(AidReviewService.LEVELS.contains("特困"));
        assertTrue(AidReviewService.LEVELS.contains("贫困"));
    }

    @Test
    @DisplayName("02 阶段枚举顺序固定且不含终态")
    void stageOrder() {
        assertEquals(Arrays.asList("apply", "committee", "workgroup", "publicity",
                "objection", "report", "center", "leader"), AidReviewService.STAGES);
        assertFalse(AidReviewService.STAGES.contains("approved"));
        assertFalse(AidReviewService.STAGES.contains("rejected"));
    }

    @Test
    @DisplayName("03 阶段定义 8 个元素、键为 stage/stageText/order")
    void stageDefinitionKeys() {
        List<Map<String, Object>> defs = AidReviewService.stageDefinitions();
        assertEquals(8, defs.size());
        for (Map<String, Object> d : defs) {
            assertEquals(3, d.size());
            assertNotNull(d.get("stage"));
            assertNotNull(d.get("stageText"));
            assertNotNull(d.get("order"));
            assertTrue(AidReviewService.STAGES.contains(d.get("stage")));
        }
        assertEquals("apply", defs.get(0).get("stage"));
        assertEquals("leader", defs.get(7).get("stage"));
    }

    @Test
    @DisplayName("04 七步：order 去重后恰好 1..7（center/leader 同属第七步）")
    void stageDefinitionSevenSteps() {
        List<Object> orders = new ArrayList<>();
        for (Map<String, Object> d : AidReviewService.stageDefinitions()) {
            if (!orders.contains(d.get("order"))) {
                orders.add(d.get("order"));
            }
        }
        assertEquals(7, orders.size());
        assertEquals(1, orders.get(0));
        assertEquals(7, orders.get(6));
    }

    @Test
    @DisplayName("05 每个阶段都有中文名")
    void stageTextAllPresent() {
        for (String s : AidReviewService.STAGES) {
            assertNotNull(AidReviewService.stageText(s));
            assertFalse(AidReviewService.stageText(s).isEmpty());
        }
        assertNotNull(AidReviewService.stageText("approved"));
        assertNotNull(AidReviewService.stageText("rejected"));
    }

    @Test
    @DisplayName("06 阶段定义接口返回 200 且 data 是数组")
    void stagesEndpoint() {
        Result<?> r = service.stages();
        assertTrue(isOk(r));
        assertEquals(8, asList(r).size());
    }

    @Test
    @DisplayName("07 十类不能认定恰好 10 条")
    void disqualifyReasonsExactlyTen() {
        assertEquals(10, AidReviewService.DISQUALIFY_REASONS.size());
    }

    @Test
    @DisplayName("08 十类不能认定的元素键为 code + text")
    void disqualifyReasonsKeys() {
        for (Map<String, String> r : AidReviewService.DISQUALIFY_REASONS) {
            assertEquals(2, r.size());
            assertNotNull(r.get("code"));
            assertNotNull(r.get("text"));
            assertFalse(r.get("code").isEmpty());
            assertFalse(r.get("text").isEmpty());
        }
    }

    @Test
    @DisplayName("09 十类不能认定接口返回恰好 10 条")
    void disqualifyReasonsEndpoint() {
        Result<?> r = service.disqualifyReasons();
        assertTrue(isOk(r));
        assertEquals(10, asList(r).size());
    }

    @Test
    @DisplayName("10 code 列表包含全部十类键名")
    void disqualifyReasonCodes() {
        List<String> codes = new ArrayList<>();
        for (Map<String, String> r : AidReviewService.DISQUALIFY_REASONS) {
            codes.add(r.get("code"));
        }
        assertEquals(Arrays.asList("HIGH_END_PHONE", "EXPENSIVE_COMPUTER", "LUXURY_GOODS",
                "FREQUENT_TRAVEL", "RENT_OR_HIGH_END_SPENDING", "HUGE_DEBT_BY_BUILDING_CAR_INVESTMENT",
                "FAMILY_IMPROVED", "SUSPENDED_SCHOOLING", "FALSE_REPORT", "OTHER_INELIGIBLE"), codes);
    }

    @Test
    @DisplayName("11 级别校验：特困/贫困通过")
    void validLevelPass() {
        assertTrue(AidReviewService.isValidLevel("特困"));
        assertTrue(AidReviewService.isValidLevel("贫困"));
    }

    @Test
    @DisplayName("12 级别校验：一般困难/特别困难/困难/英文码一律不通过")
    void invalidLevelRejected() {
        assertFalse(AidReviewService.isValidLevel("一般困难"));
        assertFalse(AidReviewService.isValidLevel("特别困难"));
        assertFalse(AidReviewService.isValidLevel("困难"));
        assertFalse(AidReviewService.isValidLevel("general"));
        assertFalse(AidReviewService.isValidLevel(null));
        assertFalse(AidReviewService.isValidLevel(""));
    }

    @Test
    @DisplayName("13 认定学年格式校验")
    void certYearFormat() {
        assertNull(AidReviewService.validateCertYear("2025-2026"));
        assertNotNull(AidReviewService.validateCertYear(null));
        assertNotNull(AidReviewService.validateCertYear("2025"));
        assertNotNull(AidReviewService.validateCertYear("2025/2026"));
        assertNotNull(AidReviewService.validateCertYear("2025-2027"));
    }

    @Test
    @DisplayName("14 学期标识：9 月-次年 1 月为第 1 学期，2-8 月为第 2 学期")
    void semesterKeyRule() {
        assertEquals("2025-1", AidReviewService.semesterKey(LocalDate.of(2025, 9, 1)));
        assertEquals("2025-1", AidReviewService.semesterKey(LocalDate.of(2025, 12, 31)));
        assertEquals("2025-1", AidReviewService.semesterKey(LocalDate.of(2026, 1, 15)));
        assertEquals("2026-2", AidReviewService.semesterKey(LocalDate.of(2026, 2, 1)));
        assertEquals("2026-2", AidReviewService.semesterKey(LocalDate.of(2026, 8, 31)));
    }

    @Test
    @DisplayName("15 性别展示：male/female/男/女")
    void genderTextRule() {
        assertEquals("男", AidReviewService.genderText("male"));
        assertEquals("男", AidReviewService.genderText("M"));
        assertEquals("男", AidReviewService.genderText("男"));
        assertEquals("女", AidReviewService.genderText("female"));
        assertEquals("女", AidReviewService.genderText("F"));
        assertEquals("女", AidReviewService.genderText("女"));
        assertEquals("", AidReviewService.genderText(null));
    }

    @Test
    @DisplayName("16 时间解析：yyyy-MM-dd 起点取 00:00:00、终点取 23:59:59")
    void parseDateTimeRule() {
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0, 0),
                AidReviewService.parseDateTime("2026-01-01", false));
        assertEquals(LocalDateTime.of(2026, 1, 1, 23, 59, 59),
                AidReviewService.parseDateTime("2026-01-01", true));
        assertEquals(LocalDateTime.of(2026, 1, 1, 8, 30, 0),
                AidReviewService.parseDateTime("2026-01-01 08:30:00", false));
        assertEquals(LocalDateTime.of(2026, 1, 1, 8, 30, 0),
                AidReviewService.parseDateTime("2026-01-01T08:30:00", false));
        assertEquals(LocalDateTime.of(2026, 1, 1, 8, 30, 0),
                AidReviewService.parseDateTime("2026-01-01 08:30", false));
        assertNull(AidReviewService.parseDateTime("2026/01/01", false));
        assertNull(AidReviewService.parseDateTime(null, false));
    }

    @Test
    @DisplayName("17 材料 JSON 解析：正常数组与回退按竖线切分")
    void parseMaterialsRule() {
        assertEquals(Arrays.asList("a", "b"), service.parseMaterials("[\"a\",\"b\"]"));
        assertEquals(Arrays.asList("a", "b"), service.parseMaterials("a|b"));
        assertTrue(service.parseMaterials(null).isEmpty());
        assertTrue(service.parseMaterials("  ").isEmpty());
    }

    // =====================================================================
    // 二、5 个工作日算法
    // =====================================================================

    @Test
    @DisplayName("18 工作日：周五 + 5 个工作日 = 下周五")
    void workingDaysFridayPlus5() {
        LocalDate friday = LocalDate.of(2026, 1, 2);
        assertEquals(DayOfWeek.FRIDAY, friday.getDayOfWeek());
        LocalDate end = AidReviewService.addWorkingDays(friday, 5);
        assertEquals(LocalDate.of(2026, 1, 9), end);
        assertEquals(DayOfWeek.FRIDAY, end.getDayOfWeek());
    }

    @Test
    @DisplayName("19 工作日：周一 + 5 个工作日 = 下周一")
    void workingDaysMondayPlus5() {
        LocalDate monday = LocalDate.of(2026, 1, 5);
        assertEquals(DayOfWeek.MONDAY, monday.getDayOfWeek());
        assertEquals(LocalDate.of(2026, 1, 12), AidReviewService.addWorkingDays(monday, 5));
    }

    @Test
    @DisplayName("20 工作日：周三 + 5 个工作日 = 下周三（跳过周末）")
    void workingDaysWednesdayPlus5() {
        LocalDate wednesday = LocalDate.of(2026, 1, 7);
        assertEquals(DayOfWeek.WEDNESDAY, wednesday.getDayOfWeek());
        assertEquals(LocalDate.of(2026, 1, 14), AidReviewService.addWorkingDays(wednesday, 5));
    }

    @Test
    @DisplayName("21 工作日：周六起步跳过周日")
    void workingDaysFromSaturday() {
        LocalDate saturday = LocalDate.of(2026, 1, 10);
        assertEquals(DayOfWeek.SATURDAY, saturday.getDayOfWeek());
        assertEquals(LocalDate.of(2026, 1, 16), AidReviewService.addWorkingDays(saturday, 5));
    }

    @Test
    @DisplayName("22 工作日：0 个工作日返回当天")
    void workingDaysZero() {
        LocalDate d = LocalDate.of(2026, 1, 7);
        assertEquals(d, AidReviewService.addWorkingDays(d, 0));
    }

    @Test
    @DisplayName("23 工作日：1 个工作日从周五跳到下周一")
    void workingDaysOneFromFriday() {
        assertEquals(LocalDate.of(2026, 1, 5),
                AidReviewService.addWorkingDays(LocalDate.of(2026, 1, 2), 1));
    }

    @Test
    @DisplayName("24 工作日（含时分秒）：保留时间部分")
    void workingDaysKeepsTime() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 2, 9, 30, 15);
        LocalDateTime end = AidReviewService.addWorkingDays(start, 5);
        assertEquals(LocalDateTime.of(2026, 1, 9, 9, 30, 15), end);
    }

    @Test
    @DisplayName("25 工作日（含时分秒）：起点为空时用当前时间")
    void workingDaysNullStart() {
        LocalDateTime end = AidReviewService.addWorkingDays((LocalDateTime) null, 5);
        assertNotNull(end);
        assertTrue(end.isAfter(LocalDateTime.now().plusDays(4)));
    }

    @Test
    @DisplayName("26 工作日：起点为空日期抛非法参数")
    void workingDaysNullDate() {
        boolean threw = false;
        try {
            AidReviewService.addWorkingDays((LocalDate) null, 5);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw);
    }

    @Test
    @DisplayName("27 工作日：跨两个周末（10 个工作日）")
    void workingDaysTen() {
        assertEquals(LocalDate.of(2026, 1, 16),
                AidReviewService.addWorkingDays(LocalDate.of(2026, 1, 2), 10));
    }

    // =====================================================================
    // 三、学生提交申请
    // =====================================================================

    @Test
    @DisplayName("28 学生提交申请成功：写入 apply 阶段 + 4 项材料")
    void applySuccess() {
        when(mapper.countCertApplications(5L, CY)).thenReturn(0);
        Result<?> r = service.apply(5L, "student", CY, "家庭收入低于当地低保线",
                materials(), "家庭 4 口人年收入 1.2 万元");
        assertTrue(isOk(r));
        assertEquals("apply", asMap(r).get("reviewStage"));
        assertEquals("学生提交申请", asMap(r).get("stageText"));
        assertEquals(4, ((List<?>) asMap(r).get("materials")).size());
        verify(mapper, times(1)).insertReview(any(AidApplication.class));
    }

    @Test
    @DisplayName("29 非学生提交申请被拒 403")
    void applyNonStudentForbidden() {
        Result<?> r = service.apply(2L, "teacher", CY, "家庭收入低于当地低保线", materials(), null);
        assertTrue(isCode(r, 403));
        verify(mapper, times(0)).insertReview(any(AidApplication.class));
    }

    @Test
    @DisplayName("30 未登录（studentId 为空）提交申请 401")
    void applyNoLogin() {
        Result<?> r = service.apply(null, "student", CY, "家庭收入低于当地低保线", materials(), null);
        assertTrue(isCode(r, 401));
    }

    @Test
    @DisplayName("31 同一学年重复提交 400")
    void applyDuplicateCertYear() {
        when(mapper.countCertApplications(5L, CY)).thenReturn(1);
        Result<?> r = service.apply(5L, "student", CY, "家庭收入低于当地低保线", materials(), null);
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("重复提交"));
    }

    @Test
    @DisplayName("32 认定学年格式非法 400")
    void applyBadCertYear() {
        Result<?> r = service.apply(5L, "student", "2025", "家庭收入低于当地低保线", materials(), null);
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("认定学年"));
    }

    @Test
    @DisplayName("33 认定学年为空 400")
    void applyNullCertYear() {
        Result<?> r = service.apply(5L, "student", null, "家庭收入低于当地低保线", materials(), null);
        assertTrue(isCode(r, 400));
    }

    @Test
    @DisplayName("34 申请理由为空 400")
    void applyNullReason() {
        Result<?> r = service.apply(5L, "student", CY, "  ", materials(), null);
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("申请理由不能为空"));
    }

    @Test
    @DisplayName("35 申请理由过短 400")
    void applyReasonTooShort() {
        Result<?> r = service.apply(5L, "student", CY, "困难", materials(), null);
        assertTrue(isCode(r, 400));
    }

    @Test
    @DisplayName("36 申请理由过长 400")
    void applyReasonTooLong() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            sb.append('困');
        }
        Result<?> r = service.apply(5L, "student", CY, sb.toString(), materials(), null);
        assertTrue(isCode(r, 400));
    }

    @Test
    @DisplayName("37 材料不是 4 项 400")
    void applyMaterialsCount() {
        Result<?> r = service.apply(5L, "student", CY, "家庭收入低于当地低保线",
                Arrays.asList("低保证明", "收入证明", "在读证明"), null);
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("4 项"));
    }

    @Test
    @DisplayName("38 材料为空 400")
    void applyMaterialsNull() {
        Result<?> r = service.apply(5L, "student", CY, "家庭收入低于当地低保线", null, null);
        assertTrue(isCode(r, 400));
    }

    @Test
    @DisplayName("39 材料含空白项 400")
    void applyMaterialsBlank() {
        Result<?> r = service.apply(5L, "student", CY, "家庭收入低于当地低保线",
                Arrays.asList("低保证明", " ", "在读证明", "村委证明"), null);
        assertTrue(isCode(r, 400));
    }

    @Test
    @DisplayName("40 单项材料过长 400")
    void applyMaterialTooLong() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 121; i++) {
            sb.append('证');
        }
        Result<?> r = service.apply(5L, "student", CY, "家庭收入低于当地低保线",
                Arrays.asList(sb.toString(), "b", "c", "d"), null);
        assertTrue(isCode(r, 400));
    }

    @Test
    @DisplayName("41 家庭情况说明过长 400")
    void applyFamilyNoteTooLong() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1001; i++) {
            sb.append('家');
        }
        Result<?> r = service.apply(5L, "student", CY, "家庭收入低于当地低保线", materials(), sb.toString());
        assertTrue(isCode(r, 400));
    }

    @Test
    @DisplayName("42 提交时写入的字段口径（aid_type=need / status=pending / academic_year）")
    void applyWritesFields() {
        when(mapper.countCertApplications(5L, CY)).thenReturn(0);
        service.apply(5L, "student", CY, "家庭收入低于当地低保线", materials(), null);
        org.mockito.ArgumentCaptor<AidApplication> captor =
                org.mockito.ArgumentCaptor.forClass(AidApplication.class);
        verify(mapper).insertReview(captor.capture());
        AidApplication saved = captor.getValue();
        assertEquals("need", saved.getAidType());
        assertEquals("pending", saved.getStatus());
        assertEquals("apply", saved.getReviewStage());
        assertEquals(CY, saved.getCertYear());
        assertEquals(CY, saved.getAcademicYear());
        assertEquals(5L, saved.getStudentId());
        assertTrue(saved.getMaterials().startsWith("["));
        assertTrue(saved.getEvidence().length() <= 250);
    }

    // =====================================================================
    // 四、本人申请与阶段定义
    // =====================================================================

    @Test
    @DisplayName("43 非学生查看本人申请 403")
    void mineNonStudentForbidden() {
        Result<?> r = service.mine(2L, "teacher");
        assertTrue(isCode(r, 403));
    }

    @Test
    @DisplayName("44 没有申请时 mine 返回空结构")
    void mineEmpty() {
        when(mapper.listMineReview(5L)).thenReturn(Collections.<AidApplication>emptyList());
        Result<?> r = service.mine(5L, "student");
        assertTrue(isOk(r));
        assertNull(asMap(r).get("application"));
        assertNull(asMap(r).get("stage"));
        assertEquals(0, ((List<?>) asMap(r).get("applications")).size());
        assertEquals(0, ((List<?>) asMap(r).get("objections")).size());
    }

    @Test
    @DisplayName("45 mine 顶层键与最新一条申请")
    void mineLatest() {
        AidApplication a = app(1L, 5L, "publicity", "贫困", "pending");
        a.setPublicityStart(LocalDateTime.now().minusDays(1));
        a.setPublicityEnd(LocalDateTime.now().plusDays(4));
        a.setObjectionDeadline(LocalDateTime.now().plusDays(4));
        when(mapper.listMineReview(5L)).thenReturn(Arrays.asList(a));
        when(objectionMapper.listByApplication(1L)).thenReturn(Collections.<AidObjection>emptyList());
        Result<?> r = service.mine(5L, "student");
        assertTrue(isOk(r));
        Map<String, Object> d = asMap(r);
        assertNotNull(d.get("application"));
        assertEquals("publicity", d.get("stage"));
        assertEquals("公示中", d.get("stageText"));
        assertEquals("贫困", d.get("level"));
        assertNotNull(d.get("publicityStart"));
        assertNotNull(d.get("publicityEnd"));
        assertNotNull(d.get("objectionDeadline"));
        assertNotNull(d.get("objections"));
    }

    @Test
    @DisplayName("46 mine 带出本人复议记录")
    void mineWithObjections() {
        AidApplication a = app(1L, 5L, "objection", "贫困", "pending");
        when(mapper.listMineReview(5L)).thenReturn(Arrays.asList(a));
        when(objectionMapper.listByApplication(1L)).thenReturn(Arrays.asList(objection(9L, 1L, 5L, "pending")));
        Result<?> r = service.mine(5L, "student");
        assertEquals(1, ((List<?>) asMap(r).get("objections")).size());
    }

    @Test
    @DisplayName("47 mine 按学年倒序列出全部申请")
    void mineApplicationsList() {
        when(mapper.listMineReview(5L)).thenReturn(Arrays.asList(
                app(1L, 5L, "committee", null, "pending"),
                app(2L, 5L, "approved", "特困", "approved")));
        when(objectionMapper.listByApplication(1L)).thenReturn(Collections.<AidObjection>emptyList());
        Result<?> r = service.mine(5L, "student");
        assertEquals(2, ((List<?>) asMap(r).get("applications")).size());
        assertEquals("committee", asMap(r).get("stage"));
    }

    // =====================================================================
    // 五、公示隐私
    // =====================================================================

    @Test
    @DisplayName("48 公示列表只有 name/gender/level 三个键")
    void publicityOnlyThreeKeys() {
        AidApplication a = app(1L, 5L, "publicity", "贫困", "pending");
        a.setGender("female");
        when(mapper.listPublicity(CY)).thenReturn(Arrays.asList(a));
        Result<?> r = service.publicity(CY);
        assertTrue(isOk(r));
        Map<String, Object> item = asList(r).get(0);
        assertEquals(3, item.size());
        assertEquals("测试学生", item.get("name"));
        assertEquals("女", item.get("gender"));
        assertEquals("贫困", item.get("level"));
    }

    @Test
    @DisplayName("49 公示序列化文本不含 idCard/family/income/studentNo 等隐私键")
    void publicityNoPrivacyKeys() throws Exception {
        AidApplication a = app(1L, 5L, "publicity", "贫困", "pending");
        a.setGender("male");
        when(mapper.listPublicity(null)).thenReturn(Arrays.asList(a));
        String text = json(service.publicity(null).getData());
        assertFalse(text.contains("idCard"));
        assertFalse(text.contains("id_card"));
        assertFalse(text.contains("family"));
        assertFalse(text.contains("income"));
        assertFalse(text.contains("studentNo"));
        assertFalse(text.contains("studentId"));
        assertFalse(text.contains("S2025001"));
        assertFalse(text.contains("软件 1 班"));
        assertTrue(text.contains("贫困"));
    }

    @Test
    @DisplayName("50 公示列表为空时返回空数组")
    void publicityEmpty() {
        when(mapper.listPublicity(null)).thenReturn(null);
        Result<?> r = service.publicity(null);
        assertTrue(isOk(r));
        assertEquals(0, asList(r).size());
    }

    @Test
    @DisplayName("51 公示只列已发布公示的申请（透传 certYear 给 mapper）")
    void publicityCertYear() {
        when(mapper.listPublicity(CY)).thenReturn(Collections.<AidApplication>emptyList());
        service.publicity(CY);
        verify(mapper).listPublicity(CY);
    }

    // =====================================================================
    // 六、管理端列表与详情
    // =====================================================================

    @Test
    @DisplayName("52 非辅导员/管理员看列表 403")
    void listNonStaffForbidden() {
        Result<?> r = service.list(5L, "student", null, null, null, null, 1, 10);
        assertTrue(isCode(r, 403));
    }

    @Test
    @DisplayName("53 admin 看全校（scope 传 null）")
    void listAdminAll() {
        when(mapper.listReview(null, null, null, null, 0, 10)).thenReturn(new ArrayList<AidApplication>());
        Result<?> r = service.list(1L, "admin", null, null, null, null, 1, 10);
        assertTrue(isOk(r));
        assertEquals(0, asMap(r).get("total"));
        verify(mapper).listReview(null, null, null, null, 0, 10);
    }

    @Test
    @DisplayName("54 辅导员看自己名下学生（scope=actorId）")
    void listTeacherScope() {
        when(profileMapper.countByCounselor(3L)).thenReturn(2);
        when(mapper.listReview(null, null, null, 3L, 0, 10)).thenReturn(new ArrayList<AidApplication>());
        Result<?> r = service.list(3L, "teacher", null, null, null, null, 1, 10);
        assertTrue(isOk(r));
        verify(mapper).listReview(null, null, null, 3L, 0, 10);
    }

    @Test
    @DisplayName("55 非辅导员身份的 teacher 不限制范围（沿用 AidService 口径）")
    void listTeacherNotCounselor() {
        when(profileMapper.countByCounselor(9L)).thenReturn(0);
        when(mapper.listReview(null, null, null, null, 0, 10)).thenReturn(new ArrayList<AidApplication>());
        service.list(9L, "teacher", null, null, null, null, 1, 10);
        verify(mapper).listReview(null, null, null, null, 0, 10);
    }

    @Test
    @DisplayName("56 scope=all 时不限制范围")
    void listScopeAll() {
        when(mapper.listReview(null, null, null, null, 0, 10)).thenReturn(new ArrayList<AidApplication>());
        service.list(3L, "teacher", "all", null, null, null, 1, 10);
        verify(mapper).listReview(null, null, null, null, 0, 10);
    }

    @Test
    @DisplayName("57 列表 stage 非法 400")
    void listBadStage() {
        Result<?> r = service.list(1L, "admin", null, "unknown", null, null, 1, 10);
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("未知的认定阶段"));
    }

    @Test
    @DisplayName("58 列表 stage 允许终态 approved / rejected")
    void listTerminalStageAllowed() {
        when(mapper.listReview("approved", null, null, null, 0, 10)).thenReturn(new ArrayList<AidApplication>());
        assertTrue(isOk(service.list(1L, "admin", null, "approved", null, null, 1, 10)));
        when(mapper.listReview("rejected", null, null, null, 0, 10)).thenReturn(new ArrayList<AidApplication>());
        assertTrue(isOk(service.list(1L, "admin", null, "rejected", null, null, 1, 10)));
    }

    @Test
    @DisplayName("59 列表分页：page<1 归 1、size>100 截到 100")
    void listPagingClamp() {
        when(mapper.listReview(null, null, null, null, 0, 100)).thenReturn(new ArrayList<AidApplication>());
        Result<?> r = service.list(1L, "admin", null, null, null, null, 0, 1000);
        assertEquals(1, asMap(r).get("page"));
        assertEquals(100, asMap(r).get("size"));
        verify(mapper).listReview(null, null, null, null, 0, 100);
    }

    @Test
    @DisplayName("60 列表第 3 页 offset 计算正确")
    void listOffset() {
        when(mapper.listReview(null, null, null, null, 40, 20)).thenReturn(new ArrayList<AidApplication>());
        service.list(1L, "admin", null, null, null, null, 3, 20);
        verify(mapper).listReview(null, null, null, null, 40, 20);
    }

    @Test
    @DisplayName("61 列表返回 list/total/page/size 且含 stageText")
    void listShape() {
        AidApplication a = app(1L, 5L, "committee", null, "pending");
        when(mapper.listReview(null, null, null, null, 0, 10)).thenReturn(Arrays.asList(a));
        when(mapper.countReview(null, null, null, null)).thenReturn(1);
        Result<?> r = service.list(1L, "admin", null, null, null, null, 1, 10);
        assertEquals(1, asMap(r).get("total"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) asMap(r).get("list");
        assertEquals(1, rows.size());
        assertEquals("民主评议", rows.get(0).get("stageText"));
        assertEquals("S2025001", rows.get(0).get("studentNo"));
    }

    @Test
    @DisplayName("62 非辅导员/管理员看详情 403")
    void detailNonStaffForbidden() {
        Result<?> r = service.detail(5L, "student", null, 1L);
        assertTrue(isCode(r, 403));
    }

    @Test
    @DisplayName("63 详情 id 为空 400")
    void detailNullId() {
        Result<?> r = service.detail(1L, "admin", null, null);
        assertTrue(isCode(r, 400));
    }

    @Test
    @DisplayName("64 详情申请不存在 400")
    void detailNotFound() {
        when(mapper.findById(99L)).thenReturn(null);
        Result<?> r = service.detail(1L, "admin", null, 99L);
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("不存在"));
    }

    @Test
    @DisplayName("65 详情非认定类型申请 400")
    void detailNotReviewType() {
        AidApplication a = app(1L, 5L, "apply", null, "pending");
        a.setAidType("grant");
        when(mapper.findById(1L)).thenReturn(a);
        Result<?> r = service.detail(1L, "admin", null, 1L);
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("不是家庭经济困难认定"));
    }

    @Test
    @DisplayName("66 详情中文码 aid_type=困难认定 也认")
    void detailChineseAidType() {
        AidApplication a = app(1L, 5L, "apply", null, "pending");
        a.setAidType("困难认定");
        when(mapper.findById(1L)).thenReturn(a);
        when(objectionMapper.listByApplication(1L)).thenReturn(Collections.<AidObjection>emptyList());
        assertTrue(isOk(service.detail(1L, "admin", null, 1L)));
    }

    @Test
    @DisplayName("67 辅导员越权看别人学生详情 403")
    void detailOutOfScope() {
        AidApplication a = app(1L, 5L, "apply", null, "pending");
        when(mapper.findById(1L)).thenReturn(a);
        when(profileMapper.findByUserId(5L)).thenReturn(profile(999L));
        Result<?> r = service.detail(3L, "teacher", null, 1L);
        assertTrue(isCode(r, 403));
    }

    @Test
    @DisplayName("68 辅导员看自己名下学生详情 200")
    void detailInScope() {
        AidApplication a = app(1L, 5L, "apply", null, "pending");
        when(mapper.findById(1L)).thenReturn(a);
        when(profileMapper.findByUserId(5L)).thenReturn(profile(3L));
        when(objectionMapper.listByApplication(1L)).thenReturn(Collections.<AidObjection>emptyList());
        assertTrue(isOk(service.detail(3L, "teacher", null, 1L)));
    }

    @Test
    @DisplayName("69 admin 看任意学生详情 200，且带证据链与十类清单")
    void detailAdmin() {
        AidApplication a = app(1L, 5L, "committee", null, "pending");
        a.setMaterials("[\"a\",\"b\",\"c\",\"d\"]");
        when(mapper.findById(1L)).thenReturn(a);
        when(objectionMapper.listByApplication(1L)).thenReturn(Arrays.asList(objection(9L, 1L, 5L, "pending")));
        Result<?> r = service.detail(1L, "admin", null, 1L);
        Map<String, Object> d = asMap(r);
        assertEquals(4, ((List<?>) ((Map<?, ?>) d.get("application")).get("materials")).size());
        assertEquals(1, ((List<?>) d.get("objections")).size());
        assertEquals(10, ((List<?>) d.get("disqualifyReasons")).size());
        assertEquals(2, ((List<?>) d.get("levels")).size());
        Map<String, Object> appView = (Map<String, Object>) d.get("application");
        assertEquals("民主评议", appView.get("stageText"));
    }

    @Test
    @DisplayName("70 详情带出学生提交的材料明细")
    void detailMaterials() {
        AidApplication a = app(1L, 5L, "apply", null, "pending");
        when(mapper.findById(1L)).thenReturn(a);
        when(objectionMapper.listByApplication(1L)).thenReturn(Collections.<AidObjection>emptyList());
        Result<?> r = service.detail(1L, "admin", null, 1L);
        Map<?, ?> appView = (Map<?, ?>) asMap(r).get("application");
        assertNotNull(appView.get("reason"));
        assertNotNull(appView.get("materials"));
        assertNotNull(appView.get("certYear"));
    }

    // =====================================================================
    // 七、民主评议
    // =====================================================================

    @Test
    @DisplayName("71 民主评议通过 → workgroup")
    void committeePass() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "apply", null, "pending"));
        when(mapper.committeePass(1L, "apply", "workgroup", "全票通过")).thenReturn(1);
        Result<?> r = service.committee(3L, "teacher", null, 1L, "全票通过", "pass");
        assertTrue(isOk(r));
        assertEquals("workgroup", asMap(r).get("reviewStage"));
        assertEquals("院系认定工作组审核定级", asMap(r).get("stageText"));
    }

    @Test
    @DisplayName("72 民主评议不通过 → rejected 终态")
    void committeeReject() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "apply", null, "pending"));
        when(mapper.rejectApplication(eq(1L), eq("apply"), any())).thenReturn(1);
        Result<?> r = service.committee(3L, "teacher", null, 1L, "材料不实", "reject");
        assertTrue(isOk(r));
        assertEquals("rejected", asMap(r).get("reviewStage"));
        assertEquals("rejected", asMap(r).get("status"));
    }

    @Test
    @DisplayName("73 非 apply 阶段做民主评议 400")
    void committeeWrongStage() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "workgroup", "贫困", "pending"));
        Result<?> r = service.committee(3L, "teacher", null, 1L, "全票通过", "pass");
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("不是待民主评议"));
    }

    @Test
    @DisplayName("74 民主评议结论非法 400")
    void committeeBadConclusion() {
        Result<?> r = service.committee(3L, "teacher", null, 1L, "全票通过", "agree");
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("pass 或 reject"));
    }

    @Test
    @DisplayName("75 民主评议意见为空 400")
    void committeeEmptyNote() {
        Result<?> r = service.committee(3L, "teacher", null, 1L, "  ", "pass");
        assertTrue(isCode(r, 400));
    }

    @Test
    @DisplayName("76 民主评议 CAS 失败（状态已被改）400")
    void committeeCasFail() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "apply", null, "pending"));
        when(mapper.committeePass(anyLong(), any(), any(), any())).thenReturn(0);
        Result<?> r = service.committee(3L, "teacher", null, 1L, "全票通过", "pass");
        assertTrue(isCode(r, 400));
    }

    @Test
    @DisplayName("77 学生不能做民主评议 403")
    void committeeStudentForbidden() {
        Result<?> r = service.committee(5L, "student", null, 1L, "全票通过", "pass");
        assertTrue(isCode(r, 403));
    }

    @Test
    @DisplayName("78 辅导员对范围外学生做民主评议 403")
    void committeeOutOfScope() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "apply", null, "pending"));
        when(profileMapper.findByUserId(5L)).thenReturn(profile(999L));
        Result<?> r = service.committee(3L, "teacher", null, 1L, "全票通过", "pass");
        assertTrue(isCode(r, 403));
    }

    // =====================================================================
    // 八、审核定级 + 十类不能认定
    // =====================================================================

    @Test
    @DisplayName("79 定级 特困 → publicity")
    void levelExtreme() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "workgroup", null, "pending"));
        when(mapper.setLevel(1L, "workgroup", "publicity", "特困", "情况属实")).thenReturn(1);
        Result<?> r = service.level(3L, "teacher", null, 1L, "特困", null, "情况属实");
        assertTrue(isOk(r));
        assertEquals("特困", asMap(r).get("povertyLevel"));
        assertEquals("publicity", asMap(r).get("reviewStage"));
    }

    @Test
    @DisplayName("80 定级 贫困 → publicity")
    void levelPoor() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "workgroup", null, "pending"));
        when(mapper.setLevel(1L, "workgroup", "publicity", "贫困", null)).thenReturn(1);
        Result<?> r = service.level(3L, "teacher", null, 1L, "贫困", null, null);
        assertTrue(isOk(r));
        assertEquals("贫困", asMap(r).get("povertyLevel"));
    }

    @Test
    @DisplayName("81 定级「一般困难」400")
    void levelGeneralRejected() {
        Result<?> r = service.level(3L, "teacher", null, 1L, "一般困难", null, null);
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("特困"));
    }

    @Test
    @DisplayName("82 定级「特别困难」400（库里旧值不是新口径）")
    void levelSpecialRejected() {
        assertTrue(isCode(service.level(3L, "teacher", null, 1L, "特别困难", null, null), 400));
    }

    @Test
    @DisplayName("83 定级为空 400")
    void levelNullRejected() {
        assertTrue(isCode(service.level(3L, "teacher", null, 1L, null, null, null), 400));
    }

    @Test
    @DisplayName("84 勾选任一条不能认定情形 → rejected 并记录原因")
    void levelDisqualifyByText() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "workgroup", null, "pending"));
        org.mockito.ArgumentCaptor<String> note = org.mockito.ArgumentCaptor.forClass(String.class);
        when(mapper.rejectApplication(eq(1L), eq("workgroup"), note.capture())).thenReturn(1);
        Result<?> r = service.level(3L, "teacher", null, 1L, "贫困",
                Arrays.asList("拥有或使用高档通讯工具"), null);
        assertTrue(isOk(r));
        assertEquals("rejected", asMap(r).get("reviewStage"));
        assertTrue(note.getValue().contains("高档通讯工具"));
        assertEquals("存在不能认定的情形：拥有或使用高档通讯工具", note.getValue());
    }

    @Test
    @DisplayName("85 十类不能认定按 code 勾选也认")
    void levelDisqualifyByCode() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "workgroup", null, "pending"));
        when(mapper.rejectApplication(eq(1L), eq("workgroup"), any())).thenReturn(1);
        Result<?> r = service.level(3L, "teacher", null, 1L, "贫困",
                Arrays.asList("high_end_phone"), "已核实");
        assertTrue(isOk(r));
        assertEquals("rejected", asMap(r).get("reviewStage"));
    }

    @Test
    @DisplayName("86 多条不能认定情形合并成原因")
    void levelDisqualifyMultiple() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "workgroup", null, "pending"));
        org.mockito.ArgumentCaptor<String> note = org.mockito.ArgumentCaptor.forClass(String.class);
        when(mapper.rejectApplication(eq(1L), eq("workgroup"), note.capture())).thenReturn(1);
        service.level(3L, "teacher", null, 1L, "贫困",
                Arrays.asList("LUXURY_GOODS", "FREQUENT_TRAVEL"), "照片为证");
        assertTrue(note.getValue().contains("购买或使用奢侈品、经常旅游"));
        assertTrue(note.getValue().endsWith("照片为证"));
    }

    @Test
    @DisplayName("87 未知的不能认定情形 400")
    void levelDisqualifyUnknown() {
        Result<?> r = service.level(3L, "teacher", null, 1L, "贫困", Arrays.asList("随便什么"), null);
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("未知的不能认定情形"));
    }

    @Test
    @DisplayName("88 非 workgroup 阶段定级 400")
    void levelWrongStage() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "apply", null, "pending"));
        Result<?> r = service.level(3L, "teacher", null, 1L, "贫困", null, null);
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("院系认定工作组"));
    }

    @Test
    @DisplayName("89 定级 CAS 失败 400")
    void levelCasFail() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "workgroup", null, "pending"));
        when(mapper.setLevel(anyLong(), any(), any(), any(), any())).thenReturn(0);
        assertTrue(isCode(service.level(3L, "teacher", null, 1L, "贫困", null, null), 400));
    }

    @Test
    @DisplayName("90 审核意见过长 400")
    void levelNoteTooLong() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1001; i++) {
            sb.append('审');
        }
        assertTrue(isCode(service.level(3L, "teacher", null, 1L, "贫困", null, sb.toString()), 400));
    }

    @Test
    @DisplayName("91 辅导员对范围外学生定级 403")
    void levelOutOfScope() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "workgroup", null, "pending"));
        when(profileMapper.findByUserId(5L)).thenReturn(profile(999L));
        assertTrue(isCode(service.level(9L, "teacher", null, 1L, "贫困", null, null), 403));
    }

    // =====================================================================
    // 九、公示发布
    // =====================================================================

    @Test
    @DisplayName("92 发布公示：publicity_end = 开始 + 5 个工作日，objection_deadline = publicity_end")
    void publishDefaultFiveWorkingDays() {
        AidApplication a = app(1L, 5L, "publicity", "贫困", "pending");
        when(mapper.findById(1L)).thenReturn(a);
        when(mapper.startPublicity(eq(1L), eq("publicity"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);
        Result<?> r = service.publishPublicity(3L, "teacher", null, 1L, null, null);
        assertTrue(isOk(r));
        Map<String, Object> d = asMap(r);
        LocalDateTime start = (LocalDateTime) d.get("publicityStart");
        LocalDateTime end = (LocalDateTime) d.get("publicityEnd");
        assertEquals(LocalDate.now(), start.toLocalDate());
        assertEquals(AidReviewService.addWorkingDays(LocalDate.now(), 5), end.toLocalDate());
        assertEquals(end, d.get("objectionDeadline"));
        assertEquals(5, d.get("workingDays"));
        assertEquals(Boolean.FALSE, d.get("customWindow"));
    }

    @Test
    @DisplayName("93 发布公示：写入的时间区间与 objection_deadline 一致")
    void publishWritesWindow() {
        AidApplication a = app(1L, 5L, "publicity", "贫困", "pending");
        when(mapper.findById(1L)).thenReturn(a);
        org.mockito.ArgumentCaptor<LocalDateTime> s = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.ArgumentCaptor<LocalDateTime> e = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        when(mapper.startPublicity(eq(1L), eq("publicity"), s.capture(), e.capture())).thenReturn(1);
        service.publishPublicity(3L, "teacher", null, 1L, null, null);
        assertNotNull(s.getValue());
        assertNotNull(e.getValue());
        assertTrue(e.getValue().isAfter(s.getValue()));
        assertEquals(AidReviewService.addWorkingDays(s.getValue(), 5), e.getValue());
    }

    @Test
    @DisplayName("94 非公示阶段发布公示 400")
    void publishWrongStage() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "workgroup", null, "pending"));
        Result<?> r = service.publishPublicity(3L, "teacher", null, 1L, null, null);
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("不是公示阶段"));
    }

    @Test
    @DisplayName("95 公示重复发布 400")
    void publishTwice() {
        AidApplication a = app(1L, 5L, "publicity", "贫困", "pending");
        a.setPublicityStart(LocalDateTime.now().minusDays(1));
        when(mapper.findById(1L)).thenReturn(a);
        Result<?> r = service.publishPublicity(3L, "teacher", null, 1L, null, null);
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("重复发布"));
    }

    @Test
    @DisplayName("96 公示 CAS 失败 400")
    void publishCasFail() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "publicity", "贫困", "pending"));
        when(mapper.startPublicity(anyLong(), any(), any(), any())).thenReturn(0);
        assertTrue(isCode(service.publishPublicity(3L, "teacher", null, 1L, null, null), 400));
    }

    @Test
    @DisplayName("97 admin 补录公示区间（回溯）成功")
    void publishCustomWindowByAdmin() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "publicity", "贫困", "pending"));
        when(mapper.startPublicity(eq(1L), eq("publicity"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);
        Result<?> r = service.publishPublicity(1L, "admin", null, 1L, "2026-01-05", "2026-01-09");
        assertTrue(isOk(r));
        Map<String, Object> d = asMap(r);
        assertEquals(LocalDateTime.of(2026, 1, 5, 0, 0, 0), d.get("publicityStart"));
        assertEquals(LocalDateTime.of(2026, 1, 9, 23, 59, 59), d.get("publicityEnd"));
        assertEquals(d.get("publicityEnd"), d.get("objectionDeadline"));
        assertEquals(Boolean.TRUE, d.get("customWindow"));
    }

    @Test
    @DisplayName("98 teacher 传 publicityStart/publicityEnd 被拒 403")
    void publishCustomWindowByTeacherForbidden() {
        Result<?> r = service.publishPublicity(3L, "teacher", null, 1L, "2026-01-05", "2026-01-09");
        assertTrue(isCode(r, 403));
        verify(mapper, times(0)).startPublicity(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("99 补录只给一个时间 400")
    void publishCustomMissingOne() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "publicity", "贫困", "pending"));
        Result<?> r = service.publishPublicity(1L, "admin", null, 1L, "2026-01-05", null);
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("需同时提供"));
    }

    @Test
    @DisplayName("100 补录时间格式非法 400")
    void publishCustomBadFormat() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "publicity", "贫困", "pending"));
        Result<?> r = service.publishPublicity(1L, "admin", null, 1L, "2026/01/05", "2026/01/09");
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("格式"));
    }

    @Test
    @DisplayName("101 补录结束早于开始 400")
    void publishCustomEndBeforeStart() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "publicity", "贫困", "pending"));
        Result<?> r = service.publishPublicity(1L, "admin", null, 1L, "2026-01-09", "2026-01-05");
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("晚于开始"));
    }

    @Test
    @DisplayName("102 补录支持 yyyy-MM-dd HH:mm:ss")
    void publishCustomDateTime() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "publicity", "贫困", "pending"));
        when(mapper.startPublicity(eq(1L), eq("publicity"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);
        Result<?> r = service.publishPublicity(1L, "admin", null, 1L,
                "2026-01-05 08:00:00", "2026-01-09 18:00:00");
        assertEquals(LocalDateTime.of(2026, 1, 9, 18, 0, 0), asMap(r).get("publicityEnd"));
    }

    @Test
    @DisplayName("103 学生不能发布公示 403")
    void publishStudentForbidden() {
        assertTrue(isCode(service.publishPublicity(5L, "student", null, 1L, null, null), 403));
    }

    // =====================================================================
    // 十、复议提交
    // =====================================================================

    @Test
    @DisplayName("104 公示期内提交复议成功：进入 objection 阶段、答复期限 5 个工作日")
    void objectionSubmitSuccess() {
        AidApplication a = app(1L, 5L, "publicity", "贫困", "pending");
        a.setObjectionDeadline(LocalDateTime.now().plusDays(3));
        when(mapper.findById(1L)).thenReturn(a);
        when(mapper.advanceStage(1L, "publicity", "objection")).thenReturn(1);
        Result<?> r = service.submitObjection(5L, "student", 1L, "对级别有异议，申请调整为特困", "病历");
        assertTrue(isOk(r));
        Map<String, Object> d = asMap(r);
        assertEquals("objection", d.get("reviewStage"));
        assertEquals("pending", d.get("status"));
        LocalDateTime deadline = (LocalDateTime) d.get("deadline");
        assertEquals(AidReviewService.addWorkingDays(LocalDate.now(), 5), deadline.toLocalDate());
        verify(objectionMapper, times(1)).insert(any(AidObjection.class));
    }

    @Test
    @DisplayName("105 复议只在公示期内：已过期 400")
    void objectionExpired() {
        AidApplication a = app(1L, 5L, "publicity", "贫困", "pending");
        a.setObjectionDeadline(LocalDateTime.now().minusDays(1));
        when(mapper.findById(1L)).thenReturn(a);
        Result<?> r = service.submitObjection(5L, "student", 1L, "对级别有异议", null);
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("公示期已结束"));
        verify(objectionMapper, times(0)).insert(any(AidObjection.class));
    }

    @Test
    @DisplayName("106 objection 阶段（公示期已过）提交复议 400")
    void objectionExpiredAtObjectionStage() {
        AidApplication a = app(1L, 5L, "objection", "贫困", "pending");
        a.setObjectionDeadline(LocalDateTime.now().minusDays(1));
        when(mapper.findById(1L)).thenReturn(a);
        assertTrue(isCode(service.submitObjection(5L, "student", 1L, "再提一条", null), 400));
    }

    @Test
    @DisplayName("107 公示尚未发布（deadline 为空）提交复议 400")
    void objectionNoPublicity() {
        AidApplication a = app(1L, 5L, "publicity", "贫困", "pending");
        when(mapper.findById(1L)).thenReturn(a);
        Result<?> r = service.submitObjection(5L, "student", 1L, "对级别有异议", null);
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("尚未发布"));
    }

    @Test
    @DisplayName("108 非学生提交复议 403")
    void objectionNonStudent() {
        assertTrue(isCode(service.submitObjection(3L, "teacher", 1L, "内容", null), 403));
    }

    @Test
    @DisplayName("109 复议 applicationId 为空 400")
    void objectionNullId() {
        assertTrue(isCode(service.submitObjection(5L, "student", null, "内容", null), 400));
    }

    @Test
    @DisplayName("110 复议目标申请不存在 400")
    void objectionAppNotFound() {
        when(mapper.findById(99L)).thenReturn(null);
        assertTrue(isCode(service.submitObjection(5L, "student", 99L, "内容", null), 400));
    }

    @Test
    @DisplayName("111 非认定类型申请不能提复议 400")
    void objectionNotReviewType() {
        AidApplication a = app(1L, 5L, "publicity", "贫困", "pending");
        a.setAidType("temporary");
        a.setObjectionDeadline(LocalDateTime.now().plusDays(1));
        when(mapper.findById(1L)).thenReturn(a);
        assertTrue(isCode(service.submitObjection(5L, "student", 1L, "内容", null), 400));
    }

    @Test
    @DisplayName("112 不能对别人的申请提复议 403")
    void objectionOtherStudent() {
        AidApplication a = app(1L, 6L, "publicity", "贫困", "pending");
        a.setObjectionDeadline(LocalDateTime.now().plusDays(1));
        when(mapper.findById(1L)).thenReturn(a);
        Result<?> r = service.submitObjection(5L, "student", 1L, "内容", null);
        assertTrue(isCode(r, 403));
    }

    @Test
    @DisplayName("113 非公示期阶段提复议 400")
    void objectionWrongStage() {
        AidApplication a = app(1L, 5L, "committee", null, "pending");
        a.setObjectionDeadline(LocalDateTime.now().plusDays(1));
        when(mapper.findById(1L)).thenReturn(a);
        Result<?> r = service.submitObjection(5L, "student", 1L, "内容", null);
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("不在公示期"));
    }

    @Test
    @DisplayName("114 复议内容为空 400")
    void objectionEmptyContent() {
        AidApplication a = app(1L, 5L, "publicity", "贫困", "pending");
        a.setObjectionDeadline(LocalDateTime.now().plusDays(1));
        when(mapper.findById(1L)).thenReturn(a);
        assertTrue(isCode(service.submitObjection(5L, "student", 1L, "   ", null), 400));
    }

    @Test
    @DisplayName("115 复议内容过长 400")
    void objectionContentTooLong() {
        AidApplication a = app(1L, 5L, "publicity", "贫困", "pending");
        a.setObjectionDeadline(LocalDateTime.now().plusDays(1));
        when(mapper.findById(1L)).thenReturn(a);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1001; i++) {
            sb.append('议');
        }
        assertTrue(isCode(service.submitObjection(5L, "student", 1L, sb.toString(), null), 400));
    }

    @Test
    @DisplayName("116 复议推进 CAS 失败 400")
    void objectionCasFail() {
        AidApplication a = app(1L, 5L, "publicity", "贫困", "pending");
        a.setObjectionDeadline(LocalDateTime.now().plusDays(1));
        when(mapper.findById(1L)).thenReturn(a);
        when(mapper.advanceStage(anyLong(), any(), any())).thenReturn(0);
        assertTrue(isCode(service.submitObjection(5L, "student", 1L, "内容", null), 400));
    }

    @Test
    @DisplayName("117 已在 objection 阶段再提复议不重复推进状态机")
    void objectionAtObjectionStage() {
        AidApplication a = app(1L, 5L, "objection", "贫困", "pending");
        a.setObjectionDeadline(LocalDateTime.now().plusDays(2));
        when(mapper.findById(1L)).thenReturn(a);
        Result<?> r = service.submitObjection(5L, "student", 1L, "补充异议", null);
        assertTrue(isOk(r));
        verify(mapper, times(0)).advanceStage(anyLong(), any(), any());
    }

    @Test
    @DisplayName("118 本人复议列表：非学生 403")
    void myObjectionsForbidden() {
        assertTrue(isCode(service.myObjections(3L, "teacher"), 403));
    }

    @Test
    @DisplayName("119 本人复议列表返回数据")
    void myObjectionsList() {
        when(objectionMapper.listMine(5L)).thenReturn(Arrays.asList(objection(9L, 1L, 5L, "pending")));
        Result<?> r = service.myObjections(5L, "student");
        assertTrue(isOk(r));
        assertEquals(1, asList(r).size());
        assertEquals("pending", asList(r).get(0).get("status"));
    }

    // =====================================================================
    // 十一、复议列表与答复
    // =====================================================================

    @Test
    @DisplayName("120 复议列表：非管理者 403")
    void objectionsForbidden() {
        assertTrue(isCode(service.objections(5L, "student", null, null), 403));
    }

    @Test
    @DisplayName("121 复议列表：状态非法 400")
    void objectionsBadStatus() {
        assertTrue(isCode(service.objections(1L, "admin", null, "unknown"), 400));
    }

    @Test
    @DisplayName("122 复议列表：admin 全校")
    void objectionsAdmin() {
        when(objectionMapper.listByStatus(null, null))
                .thenReturn(Arrays.asList(objection(9L, 1L, 5L, "pending")));
        Result<?> r = service.objections(1L, "admin", null, null);
        assertEquals(1, asList(r).size());
    }

    @Test
    @DisplayName("123 复议列表：辅导员限范围")
    void objectionsTeacherScope() {
        when(profileMapper.countByCounselor(3L)).thenReturn(1);
        when(objectionMapper.listByStatus("pending", 3L)).thenReturn(new ArrayList<AidObjection>());
        assertTrue(isOk(service.objections(3L, "teacher", null, "pending")));
        verify(objectionMapper).listByStatus("pending", 3L);
    }

    @Test
    @DisplayName("124 答复复议：采纳并调整级别")
    void answerAcceptedAdjustLevel() {
        AidObjection o = objection(9L, 1L, 5L, "pending");
        when(objectionMapper.findById(9L)).thenReturn(o);
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "objection", "贫困", "pending"));
        when(objectionMapper.answer(9L, "情况属实", "accepted", 3L)).thenReturn(1);
        when(objectionMapper.countPendingByApplication(1L)).thenReturn(1);
        Result<?> r = service.answerObjection(3L, "teacher", null, 9L, "情况属实", "accepted", "特困");
        assertTrue(isOk(r));
        verify(mapper).updatePovertyLevel(1L, "特困");
        assertEquals("objection", asMap(r).get("reviewStage"));
        assertEquals("accepted", asMap(r).get("objectionStatus"));
    }

    @Test
    @DisplayName("125 答复复议：采纳但不给新级别 → 取消认定资格")
    void answerAcceptedCancel() {
        AidObjection o = objection(9L, 1L, 5L, "pending");
        when(objectionMapper.findById(9L)).thenReturn(o);
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "objection", "贫困", "pending"));
        when(objectionMapper.answer(anyLong(), any(), any(), anyLong())).thenReturn(1);
        Result<?> r = service.answerObjection(3L, "teacher", null, 9L, "已核实不符合条件", "accepted", null);
        assertTrue(isOk(r));
        verify(mapper).rejectApplication(eq(1L), eq("objection"), any());
        assertEquals("rejected", asMap(r).get("reviewStage"));
        assertEquals("rejected", asMap(r).get("status"));
    }

    @Test
    @DisplayName("126 答复复议：不采纳维持原级别")
    void answerRejectedKeepLevel() {
        AidObjection o = objection(9L, 1L, 5L, "pending");
        when(objectionMapper.findById(9L)).thenReturn(o);
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "objection", "贫困", "pending"));
        when(objectionMapper.answer(anyLong(), any(), any(), anyLong())).thenReturn(1);
        when(objectionMapper.countPendingByApplication(anyLong())).thenReturn(1);
        Result<?> r = service.answerObjection(3L, "teacher", null, 9L, "维持原结论", "rejected", null);
        assertTrue(isOk(r));
        verify(mapper, times(0)).updatePovertyLevel(anyLong(), any());
        assertEquals("贫困", asMap(r).get("povertyLevel"));
    }

    @Test
    @DisplayName("127 答复复议：最后一条答复完回到 publicity")
    void answerReturnsToPublicity() {
        AidObjection o = objection(9L, 1L, 5L, "pending");
        when(objectionMapper.findById(9L)).thenReturn(o);
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "objection", "贫困", "pending"));
        when(objectionMapper.answer(anyLong(), any(), any(), anyLong())).thenReturn(1);
        when(objectionMapper.countPendingByApplication(1L)).thenReturn(0);
        Result<?> r = service.answerObjection(3L, "teacher", null, 9L, "维持原结论", "rejected", null);
        assertEquals("publicity", asMap(r).get("reviewStage"));
        verify(mapper).advanceStage(1L, "objection", "publicity");
    }

    @Test
    @DisplayName("128 答复复议：不存在 400")
    void answerNotFound() {
        when(objectionMapper.findById(99L)).thenReturn(null);
        assertTrue(isCode(service.answerObjection(3L, "teacher", null, 99L, "内容", "accepted", null), 400));
    }

    @Test
    @DisplayName("129 答复复议：已答复过 400")
    void answerAlreadyDone() {
        when(objectionMapper.findById(9L)).thenReturn(objection(9L, 1L, 5L, "accepted"));
        Result<?> r = service.answerObjection(3L, "teacher", null, 9L, "内容", "accepted", null);
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("重复答复"));
    }

    @Test
    @DisplayName("130 答复复议：结论非法 400")
    void answerBadVerdict() {
        when(objectionMapper.findById(9L)).thenReturn(objection(9L, 1L, 5L, "pending"));
        assertTrue(isCode(service.answerObjection(3L, "teacher", null, 9L, "内容", "ok", null), 400));
    }

    @Test
    @DisplayName("131 答复复议：答复内容为空 400")
    void answerEmpty() {
        when(objectionMapper.findById(9L)).thenReturn(objection(9L, 1L, 5L, "pending"));
        assertTrue(isCode(service.answerObjection(3L, "teacher", null, 9L, "  ", "accepted", null), 400));
    }

    @Test
    @DisplayName("132 答复复议：新级别非法 400")
    void answerBadLevel() {
        assertTrue(isCode(service.answerObjection(3L, "teacher", null, 9L, "内容", "accepted", "一般困难"), 400));
    }

    @Test
    @DisplayName("133 答复复议：不采纳却传新级别 400")
    void answerRejectedWithLevel() {
        assertTrue(isCode(service.answerObjection(3L, "teacher", null, 9L, "内容", "rejected", "特困"), 400));
    }

    @Test
    @DisplayName("134 答复复议：越权（别的辅导员的学生）403")
    void answerOutOfScope() {
        when(objectionMapper.findById(9L)).thenReturn(objection(9L, 1L, 5L, "pending"));
        when(profileMapper.findByUserId(5L)).thenReturn(profile(999L));
        assertTrue(isCode(service.answerObjection(3L, "teacher", null, 9L, "内容", "accepted", "特困"), 403));
    }

    @Test
    @DisplayName("135 答复复议：CAS 失败 400")
    void answerCasFail() {
        when(objectionMapper.findById(9L)).thenReturn(objection(9L, 1L, 5L, "pending"));
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "objection", "贫困", "pending"));
        when(objectionMapper.answer(anyLong(), any(), any(), anyLong())).thenReturn(0);
        assertTrue(isCode(service.answerObjection(3L, "teacher", null, 9L, "内容", "rejected", null), 400));
    }

    @Test
    @DisplayName("136 答复复议：关联申请不存在 400")
    void answerAppMissing() {
        when(objectionMapper.findById(9L)).thenReturn(objection(9L, 1L, 5L, "pending"));
        when(mapper.findById(1L)).thenReturn(null);
        assertTrue(isCode(service.answerObjection(3L, "teacher", null, 9L, "内容", "rejected", null), 400));
    }

    @Test
    @DisplayName("137 答复复议：非管理者 403")
    void answerForbidden() {
        assertTrue(isCode(service.answerObjection(5L, "student", null, 9L, "内容", "rejected", null), 403));
    }

    // =====================================================================
    // 十二、院系评审报告
    // =====================================================================

    @Test
    @DisplayName("138 提交评审报告 → center")
    void reportSuccess() {
        AidApplication a = app(1L, 5L, "publicity", "贫困", "pending");
        a.setPublicityStart(LocalDateTime.now().minusDays(6));
        when(mapper.findById(1L)).thenReturn(a);
        when(objectionMapper.countPendingByApplication(1L)).thenReturn(0);
        when(mapper.toReport(1L, "publicity", "center", "公示无异议")).thenReturn(1);
        Result<?> r = service.report(3L, "teacher", null, 1L, "公示无异议");
        assertTrue(isOk(r));
        assertEquals("center", asMap(r).get("reviewStage"));
    }

    @Test
    @DisplayName("139 非公示阶段提交报告 400")
    void reportWrongStage() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "workgroup", null, "pending"));
        Result<?> r = service.report(3L, "teacher", null, 1L, "报告");
        assertTrue(isCode(r, 400));
    }

    @Test
    @DisplayName("140 公示未发布就交报告 400")
    void reportNoPublicity() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "publicity", "贫困", "pending"));
        Result<?> r = service.report(3L, "teacher", null, 1L, "报告");
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("公示尚未发布"));
    }

    @Test
    @DisplayName("141 还有未答复复议就交报告 400")
    void reportPendingObjection() {
        AidApplication a = app(1L, 5L, "publicity", "贫困", "pending");
        a.setPublicityStart(LocalDateTime.now().minusDays(6));
        when(mapper.findById(1L)).thenReturn(a);
        when(objectionMapper.countPendingByApplication(1L)).thenReturn(1);
        Result<?> r = service.report(3L, "teacher", null, 1L, "报告");
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("未答复的复议"));
    }

    @Test
    @DisplayName("142 报告内容为空 400")
    void reportEmpty() {
        assertTrue(isCode(service.report(3L, "teacher", null, 1L, "  "), 400));
    }

    @Test
    @DisplayName("143 报告 CAS 失败 400")
    void reportCasFail() {
        AidApplication a = app(1L, 5L, "publicity", "贫困", "pending");
        a.setPublicityStart(LocalDateTime.now().minusDays(6));
        when(mapper.findById(1L)).thenReturn(a);
        when(objectionMapper.countPendingByApplication(1L)).thenReturn(0);
        when(mapper.toReport(anyLong(), any(), any(), any())).thenReturn(0);
        assertTrue(isCode(service.report(3L, "teacher", null, 1L, "报告"), 400));
    }

    // =====================================================================
    // 十三、审核审批
    // =====================================================================

    @Test
    @DisplayName("144 资助中心审核通过 → leader")
    void approveCenterPass() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "center", "贫困", "pending"));
        when(mapper.approveStage(1L, "center", "leader", 1L, "资助管理中心审核通过")).thenReturn(1);
        Result<?> r = service.approve(1L, "admin", null, 1L, "center", true, null, null);
        assertTrue(isOk(r));
        assertEquals("leader", asMap(r).get("reviewStage"));
    }

    @Test
    @DisplayName("145 资助中心审核不通过 → rejected")
    void approveCenterReject() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "center", "贫困", "pending"));
        when(mapper.rejectApplication(eq(1L), eq("center"), any())).thenReturn(1);
        Result<?> r = service.approve(1L, "admin", null, 1L, "center", false, "材料存疑", null);
        assertTrue(isOk(r));
        assertEquals("rejected", asMap(r).get("reviewStage"));
        assertEquals("rejected", asMap(r).get("status"));
    }

    @Test
    @DisplayName("146 领导小组审批通过 → approved 终态且保留级别")
    void approveLeaderPass() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "leader", "特困", "pending"));
        when(mapper.approveFinal(1L, "leader", 1L, "特困", "领导小组审批通过")).thenReturn(1);
        Result<?> r = service.approve(1L, "admin", null, 1L, "leader", true, null, null);
        assertTrue(isOk(r));
        assertEquals("approved", asMap(r).get("reviewStage"));
        assertEquals("approved", asMap(r).get("status"));
        assertEquals("特困", asMap(r).get("povertyLevel"));
    }

    @Test
    @DisplayName("147 领导小组审批不通过 → rejected")
    void approveLeaderReject() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "leader", "特困", "pending"));
        when(mapper.rejectApplication(eq(1L), eq("leader"), any())).thenReturn(1);
        Result<?> r = service.approve(1L, "admin", null, 1L, "leader", false, null, null);
        assertTrue(isOk(r));
        assertEquals("rejected", asMap(r).get("reviewStage"));
    }

    @Test
    @DisplayName("148 领导小组审批时尚未定级 400")
    void approveLeaderNoLevel() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "leader", null, "pending"));
        Result<?> r = service.approve(1L, "admin", null, 1L, "leader", true, null, null);
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("尚未定级"));
    }

    @Test
    @DisplayName("149 审批角色非法 400")
    void approveBadRole() {
        assertTrue(isCode(service.approve(1L, "admin", null, 1L, "boss", true, null, null), 400));
    }

    @Test
    @DisplayName("150 approved 为空 400")
    void approveNullApproved() {
        assertTrue(isCode(service.approve(1L, "admin", null, 1L, "center", null, null, null), 400));
    }

    @Test
    @DisplayName("151 阶段与审核角色不匹配 400")
    void approveWrongStage() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "workgroup", null, "pending"));
        Result<?> r = service.approve(1L, "admin", null, 1L, "center", true, null, null);
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("资助管理中心"));
    }

    @Test
    @DisplayName("152 审批时勾选不能认定情形 → rejected")
    void approveDisqualify() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "leader", "特困", "pending"));
        when(mapper.rejectApplication(eq(1L), eq("leader"), any())).thenReturn(1);
        Result<?> r = service.approve(1L, "admin", null, 1L, "leader", true, null,
                Arrays.asList("FAMILY_IMPROVED"));
        assertTrue(isOk(r));
        assertEquals("rejected", asMap(r).get("reviewStage"));
    }

    @Test
    @DisplayName("153 审批勾选未知情形 400")
    void approveDisqualifyUnknown() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "leader", "特困", "pending"));
        assertTrue(isCode(service.approve(1L, "admin", null, 1L, "leader", true, null,
                Arrays.asList("没有这一项")), 400));
    }

    @Test
    @DisplayName("154 审批 CAS 失败 400")
    void approveCasFail() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "center", "贫困", "pending"));
        when(mapper.approveStage(anyLong(), any(), any(), anyLong(), any())).thenReturn(0);
        assertTrue(isCode(service.approve(1L, "admin", null, 1L, "center", true, null, null), 400));
    }

    @Test
    @DisplayName("155 审批：申请不存在 400")
    void approveNotFound() {
        when(mapper.findById(99L)).thenReturn(null);
        assertTrue(isCode(service.approve(1L, "admin", null, 99L, "center", true, null, null), 400));
    }

    @Test
    @DisplayName("156 审批：学生无权 403")
    void approveStudentForbidden() {
        assertTrue(isCode(service.approve(5L, "student", null, 1L, "center", true, null, null), 403));
    }

    // =====================================================================
    // 十四、学期复查
    // =====================================================================

    @Test
    @DisplayName("157 复查名单：非管理者 403")
    void recheckListForbidden() {
        assertTrue(isCode(service.recheckList(5L, "student", null, CY), 403));
    }

    @Test
    @DisplayName("158 复查名单：admin 全校")
    void recheckListAdmin() {
        AidApplication a = app(1L, 5L, "approved", "特困", "approved");
        when(mapper.listRecheck(CY, null)).thenReturn(Arrays.asList(a));
        Result<?> r = service.recheckList(1L, "admin", null, CY);
        assertTrue(isOk(r));
        assertEquals(1, asList(r).size());
        assertTrue(asList(r).get(0).containsKey("recheckedAt"));
    }

    @Test
    @DisplayName("159 复查名单：辅导员限范围")
    void recheckListTeacherScope() {
        when(profileMapper.countByCounselor(3L)).thenReturn(1);
        when(mapper.listRecheck(CY, 3L)).thenReturn(new ArrayList<AidApplication>());
        service.recheckList(3L, "teacher", null, CY);
        verify(mapper).listRecheck(CY, 3L);
    }

    @Test
    @DisplayName("160 复查：写结论，不调整级别")
    void recheckKeepLevel() {
        AidApplication a = app(1L, 5L, "approved", "特困", "approved");
        a.setRecheckedAt(otherSemesterMoment());
        AidApplication after = app(1L, 5L, "approved", "特困", "approved");
        after.setRecheckedAt(LocalDateTime.now());
        when(mapper.findById(1L)).thenReturn(a, a, after);
        when(mapper.updateRecheck(eq(1L), eq(CY), eq("家庭情况未变"), eq(null), eq("维持特困")))
                .thenReturn(1);
        Result<?> r = service.recheck(3L, "teacher", null, 1L, "家庭情况未变", false, null, "维持特困");
        assertTrue(isOk(r));
        Map<String, Object> d = asMap(r);
        assertEquals("特困", d.get("povertyLevel"));
        assertEquals("家庭情况未变", d.get("recheckResult"));
        assertEquals(Boolean.FALSE, d.get("adjusted"));
        assertNotNull(d.get("recheckedAt"));
    }

    @Test
    @DisplayName("161 复查：调整级别")
    void recheckAdjustLevel() {
        AidApplication a = app(1L, 5L, "approved", "贫困", "approved");
        a.setRecheckedAt(otherSemesterMoment());
        AidApplication after = app(1L, 5L, "approved", "特困", "approved");
        after.setRecheckedAt(LocalDateTime.now());
        when(mapper.findById(1L)).thenReturn(a, a, after);
        when(mapper.updateRecheck(anyLong(), any(), any(), eq("特困"), any())).thenReturn(1);
        Result<?> r = service.recheck(3L, "teacher", null, 1L, "父亲重病已核实", true, "特困", "调整");
        assertTrue(isOk(r));
        assertEquals("特困", asMap(r).get("povertyLevel"));
        assertEquals(Boolean.TRUE, asMap(r).get("adjusted"));
    }

    @Test
    @DisplayName("162 复查：非 approved 状态 400")
    void recheckNotApproved() {
        when(mapper.findById(1L)).thenReturn(app(1L, 5L, "publicity", "贫困", "pending"));
        Result<?> r = service.recheck(3L, "teacher", null, 1L, "结论", false, null, null);
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("已批准"));
    }

    @Test
    @DisplayName("163 复查：同一学期重复复查 400")
    void recheckSameSemester() {
        AidApplication a = app(1L, 5L, "approved", "特困", "approved");
        a.setRecheckedAt(LocalDateTime.now());
        when(mapper.findById(1L)).thenReturn(a);
        Result<?> r = service.recheck(3L, "teacher", null, 1L, "复核结论", false, null, null);
        assertTrue(isCode(r, 400));
        assertTrue(r.getMessage().contains("本学期"));
    }

    @Test
    @DisplayName("164 复查：adjusted=true 未给新级别 400")
    void recheckAdjustWithoutLevel() {
        AidApplication a = app(1L, 5L, "approved", "特困", "approved");
        a.setRecheckedAt(otherSemesterMoment());
        when(mapper.findById(1L)).thenReturn(a);
        assertTrue(isCode(service.recheck(3L, "teacher", null, 1L, "结论", true, null, null), 400));
    }

    @Test
    @DisplayName("165 复查：未调整却传新级别 400")
    void recheckNoAdjustWithLevel() {
        AidApplication a = app(1L, 5L, "approved", "特困", "approved");
        a.setRecheckedAt(otherSemesterMoment());
        when(mapper.findById(1L)).thenReturn(a);
        assertTrue(isCode(service.recheck(3L, "teacher", null, 1L, "结论", false, "贫困", null), 400));
    }

    @Test
    @DisplayName("166 复查：新级别非法 400")
    void recheckBadLevel() {
        assertTrue(isCode(service.recheck(3L, "teacher", null, 1L, "结论", true, "一般困难", null), 400));
    }

    @Test
    @DisplayName("167 复查：结论为空 400")
    void recheckEmptyResult() {
        assertTrue(isCode(service.recheck(3L, "teacher", null, 1L, "  ", false, null, null), 400));
    }

    @Test
    @DisplayName("168 复查 CAS 失败 400")
    void recheckCasFail() {
        AidApplication a = app(1L, 5L, "approved", "特困", "approved");
        a.setRecheckedAt(otherSemesterMoment());
        when(mapper.findById(1L)).thenReturn(a);
        when(mapper.updateRecheck(anyLong(), any(), any(), any(), any())).thenReturn(0);
        assertTrue(isCode(service.recheck(3L, "teacher", null, 1L, "结论", false, null, null), 400));
    }

    @Test
    @DisplayName("169 复查：非管理者 403")
    void recheckForbidden() {
        assertTrue(isCode(service.recheck(5L, "student", null, 1L, "结论", false, null, null), 403));
    }

    @Test
    @DisplayName("170 复查：结论过长 400")
    void recheckResultTooLong() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            sb.append('查');
        }
        assertTrue(isCode(service.recheck(3L, "teacher", null, 1L, sb.toString(), false, null, null), 400));
    }

    @Test
    @DisplayName("171 复查：跨学期允许复查（学期不同）")
    void recheckOtherSemesterAllowed() {
        AidApplication a = app(1L, 5L, "approved", "特困", "approved");
        a.setRecheckedAt(otherSemesterMoment());
        AidApplication after = app(1L, 5L, "approved", "特困", "approved");
        after.setRecheckedAt(LocalDateTime.now());
        when(mapper.findById(1L)).thenReturn(a, a, after);
        when(mapper.updateRecheck(anyLong(), any(), any(), any(), any())).thenReturn(1);
        assertTrue(isOk(service.recheck(3L, "teacher", null, 1L, "新学期复查", false, null, null)));
    }

    // =====================================================================
    // 十五、七步正向流转（端到端串起来，mock 层验证）
    // =====================================================================

    @Test
    @DisplayName("172 七步正向流转：apply → committee → workgroup → publicity → objection → report → center → leader → approved")
    void sevenStepHappyPath() {
        long id = 1L;
        long stu = 5L;
        // 1 apply
        when(mapper.countCertApplications(stu, CY)).thenReturn(0);
        assertTrue(isOk(service.apply(stu, "student", CY, "家庭收入低于当地低保线", materials(), null)));
        // 2 committee
        when(mapper.findById(id)).thenReturn(app(id, stu, "apply", null, "pending"));
        when(mapper.committeePass(id, "apply", "workgroup", "全票通过")).thenReturn(1);
        assertTrue(isOk(service.committee(1L, "admin", null, id, "全票通过", "pass")));
        // 3 workgroup 定级
        when(mapper.findById(id)).thenReturn(app(id, stu, "workgroup", null, "pending"));
        when(mapper.setLevel(id, "workgroup", "publicity", "贫困", null)).thenReturn(1);
        assertTrue(isOk(service.level(1L, "admin", null, id, "贫困", null, null)));
        // 4 publicity
        when(mapper.findById(id)).thenReturn(app(id, stu, "publicity", "贫困", "pending"));
        when(mapper.startPublicity(eq(id), eq("publicity"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);
        assertTrue(isOk(service.publishPublicity(1L, "admin", null, id, null, null)));
        // 5 objection
        AidApplication pub = app(id, stu, "publicity", "贫困", "pending");
        pub.setObjectionDeadline(LocalDateTime.now().plusDays(2));
        when(mapper.findById(id)).thenReturn(pub);
        when(mapper.advanceStage(id, "publicity", "objection")).thenReturn(1);
        Result<?> obj = service.submitObjection(stu, "student", id, "申请复核级别", null);
        assertTrue(isOk(obj));
        assertEquals("objection", asMap(obj).get("reviewStage"));
        // 5b 答复（不采纳，维持原级别）→ 回到 publicity
        when(objectionMapper.findById(9L)).thenReturn(objection(9L, id, stu, "pending"));
        when(mapper.findById(id)).thenReturn(app(id, stu, "objection", "贫困", "pending"));
        when(objectionMapper.answer(anyLong(), any(), any(), anyLong())).thenReturn(1);
        when(objectionMapper.countPendingByApplication(id)).thenReturn(0);
        assertTrue(isOk(service.answerObjection(1L, "admin", null, 9L, "维持原结论", "rejected", null)));
        // 6 report
        AidApplication reported = app(id, stu, "publicity", "贫困", "pending");
        reported.setPublicityStart(LocalDateTime.now().minusDays(6));
        when(mapper.findById(id)).thenReturn(reported);
        when(mapper.toReport(id, "publicity", "center", "公示无异议")).thenReturn(1);
        assertTrue(isOk(service.report(1L, "admin", null, id, "公示无异议")));
        // 7 center → leader
        when(mapper.findById(id)).thenReturn(app(id, stu, "center", "贫困", "pending"));
        when(mapper.approveStage(id, "center", "leader", 1L, "资助管理中心审核通过")).thenReturn(1);
        assertTrue(isOk(service.approve(1L, "admin", null, id, "center", true, null, null)));
        // 8 leader → approved
        when(mapper.findById(id)).thenReturn(app(id, stu, "leader", "贫困", "pending"));
        when(mapper.approveFinal(id, "leader", 1L, "贫困", "领导小组审批通过")).thenReturn(1);
        Result<?> fin = service.approve(1L, "admin", null, id, "leader", true, null, null);
        assertTrue(isOk(fin));
        assertEquals("approved", asMap(fin).get("reviewStage"));
        assertEquals("approved", asMap(fin).get("status"));
    }

    @Test
    @DisplayName("173 七步各阶段非法跳级一律 400")
    void sevenStepIllegalJumps() {
        long id = 1L;
        long stu = 5L;
        when(mapper.findById(id)).thenReturn(app(id, stu, "apply", null, "pending"));
        assertTrue(isCode(service.level(1L, "admin", null, id, "贫困", null, null), 400));
        assertTrue(isCode(service.publishPublicity(1L, "admin", null, id, null, null), 400));
        assertTrue(isCode(service.report(1L, "admin", null, id, "报告"), 400));
        assertTrue(isCode(service.approve(1L, "admin", null, id, "center", true, null, null), 400));
        assertTrue(isCode(service.approve(1L, "admin", null, id, "leader", true, null, null), 400));
    }

    @Test
    @DisplayName("174 未登录/未知角色访问管理端一律拒绝")
    void unknownRoleRejected() {
        assertTrue(isCode(service.list(null, null, null, null, null, null, 1, 10), 403));
        assertTrue(isCode(service.detail(1L, "null", null, 1L), 403));
        assertTrue(isCode(service.objections(1L, null, null, null), 403));
        assertTrue(isCode(service.committee(1L, null, null, 1L, "评议", "pass"), 403));
    }

    @Test
    @DisplayName("175 审计归属：/api/aid 前缀已由 AuditLogService 归入 TYPE_AID（常量存在）")
    void auditTypeConstant() throws Exception {
        java.lang.reflect.Field f = AuditLogService.class.getField("TYPE_AID");
        assertEquals("AID", f.get(null));
    }

    // =====================================================================
    // 十六、管理端 6 格统计（GET /api/aid/review/stats）
    // =====================================================================

    private static Map<String, Object> stageRow(String stage, int cnt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("review_stage", stage);
        row.put("cnt", cnt);
        return row;
    }

    @Test
    @DisplayName("176 统计：data 恰好 6 个键，按阶段映射，applied 为合计、publicity 含复议期内")
    void statsMapping() {
        when(mapper.countByStage(null, CY, null, null)).thenReturn(Arrays.asList(
                stageRow("apply", 1), stageRow("committee", 2), stageRow("workgroup", 3),
                stageRow("publicity", 4), stageRow("objection", 5), stageRow("approved", 6),
                stageRow("rejected", 7)));
        Result<?> r = service.stats(1L, "admin", null, CY);
        assertTrue(isOk(r));
        Map<String, Object> d = asMap(r);
        assertEquals(6, d.size());
        assertEquals(28, d.get("applied"));
        assertEquals(2, d.get("committee"));
        assertEquals(3, d.get("level"));
        assertEquals(9, d.get("publicity"));
        assertEquals(5, d.get("objection"));
        assertEquals(6, d.get("final"));
    }

    @Test
    @DisplayName("177 统计：空数据 6 个键全为 0（不是 null）")
    void statsEmptyAllZero() {
        when(mapper.countByStage(null, null, null, null)).thenReturn(null);
        Result<?> r = service.stats(1L, "admin", null, null);
        assertTrue(isOk(r));
        Map<String, Object> d = asMap(r);
        assertEquals(6, d.size());
        for (String key : Arrays.asList("applied", "committee", "level", "publicity", "objection", "final")) {
            assertEquals(0, d.get(key), key + " 应为 0");
        }
    }

    @Test
    @DisplayName("178 统计：teacher 只见管辖范围（scope 传 counselorId）")
    void statsTeacherScope() {
        when(profileMapper.countByCounselor(3L)).thenReturn(2);
        when(mapper.countByStage(null, CY, null, 3L)).thenReturn(Arrays.asList(stageRow("committee", 2)));
        Result<?> r = service.stats(3L, "teacher", null, CY);
        assertTrue(isOk(r));
        assertEquals(2, asMap(r).get("applied"));
        assertEquals(2, asMap(r).get("committee"));
        verify(mapper).countByStage(null, CY, null, 3L);
    }

    @Test
    @DisplayName("179 统计：非管理者（学生）403")
    void statsForbidden() {
        assertTrue(isCode(service.stats(5L, "student", null, CY), 403));
    }

    @Test
    @DisplayName("180 统计：未登录 401（controller 层）")
    void statsNoLogin401() {
        AidReviewController controller = new AidReviewController(service);
        jakarta.servlet.http.HttpSession session = org.mockito.Mockito.mock(jakarta.servlet.http.HttpSession.class);
        Result<?> r = controller.stats(CY, session);
        assertTrue(isCode(r, 401));
    }

    @Test
    @DisplayName("181 未登录访问管理端列表 401（controller 层）")
    void listNoLogin401() {
        AidReviewController controller = new AidReviewController(service);
        jakarta.servlet.http.HttpSession session = org.mockito.Mockito.mock(jakarta.servlet.http.HttpSession.class);
        Result<?> r = controller.list(null, null, null, 1, 10, session);
        assertTrue(isCode(r, 401));
    }

    @Test
    @DisplayName("182 统计计数转换：Number/String/null 都安全")
    void statsToInt() {
        assertEquals(7, AidReviewService.toInt(7L));
        assertEquals(7, AidReviewService.toInt(7));
        assertEquals(7, AidReviewService.toInt("7"));
        assertEquals(0, AidReviewService.toInt(null));
        assertEquals(0, AidReviewService.toInt("abc"));
    }
}
