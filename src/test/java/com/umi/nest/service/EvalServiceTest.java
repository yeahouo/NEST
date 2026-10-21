package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.EvalIndicator;
import com.umi.nest.entity.EvalScore;
import com.umi.nest.entity.EvalSummary;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.EvalIndicatorMapper;
import com.umi.nest.mapper.EvalScoreMapper;
import com.umi.nest.mapper.EvalSummaryMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 综合素质测评（EvalService）的契约测试。
 *
 * <p>盯三类错误：一、权限缝隙（辅导员碰到别人名下的学生、学生看到全校汇总）；
 * 二、写入契约（写入来源必须由服务端固定为 self / counselor，不能被请求体改写）；
 * 三、算法口径（同一指标多来源取优先级最高的一条、维度加权公式、等级线 90/80/60、
 * 同班同分并列）。这些错误都不抛异常，只会在学期末把分数算错、把排名搞乱，
 * 所以每一条都用 public 方法的可观察后果来钉住。</p>
 *
 * <p>注意：EvalService 的五个维度权重是 {@code @Value} 注入的字段，单测里没有
 * Spring 容器，必须用 {@link ReflectionTestUtils} 手工灌值；否则权重全是 0.0，
 * 所有总分都会算成 0 分，测试就成了自证空转。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EvalService - 综测权限、写入契约与算法口径")
class EvalServiceTest {

    private static final String TERM = "2026-2027-1";
    private static final Long ADMIN = 1L;
    private static final Long STUDENT = 2L;
    private static final Long COUNSELOR = 3L;
    private static final Long OTHER_COUNSELOR = 9L;

    @Mock
    private EvalIndicatorMapper indicatorMapper;

    @Mock
    private EvalScoreMapper scoreMapper;

    @Mock
    private EvalSummaryMapper summaryMapper;

    @Mock
    private StudentProfileMapper profileMapper;

    @InjectMocks
    private EvalService service;

    @BeforeEach
    void injectConfiguredWeights() {
        // 与 application 配置的默认口径一致：德 0.25 / 智 0.35 / 体 0.15 / 美 0.10 / 劳 0.15
        setWeights(0.25, 0.35, 0.15, 0.10, 0.15);
    }

    private void setWeights(double moral, double intellectual, double physical, double aesthetic, double labor) {
        ReflectionTestUtils.setField(service, "moralWeight", moral);
        ReflectionTestUtils.setField(service, "intellectualWeight", intellectual);
        ReflectionTestUtils.setField(service, "physicalWeight", physical);
        ReflectionTestUtils.setField(service, "aestheticWeight", aesthetic);
        ReflectionTestUtils.setField(service, "laborWeight", labor);
    }

    // ------------------------------------------------------------------ 返回体工具

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Result<?> r) {
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return (List<String>) value;
    }

    private static void assertDecimal(String expected, Object actual) {
        assertNotNull(actual, "期望 " + expected + "，实际是 null");
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(String.valueOf(actual))),
                "期望 " + expected + "，实际 " + actual);
    }

    private static Map<String, Object> dimensionOf(Result<?> r, String code) {
        for (Map<String, Object> dim : maps(data(r).get("dimensions"))) {
            if (code.equals(dim.get("code"))) {
                return dim;
            }
        }
        throw new AssertionError("返回体里没有维度 " + code);
    }

    private static Map<String, Object> itemOf(Map<String, Object> dimension, String indicatorCode) {
        for (Map<String, Object> item : maps(dimension.get("items"))) {
            if (indicatorCode.equals(item.get("code"))) {
                return item;
            }
        }
        throw new AssertionError("维度里没有指标 " + indicatorCode);
    }

    private static Map<String, Object> scoreMapOf(Result<?> r, String indicatorCode) {
        for (Map<String, Object> node : maps(data(r).get("scores"))) {
            if (indicatorCode.equals(node.get("indicatorCode"))) {
                return node;
            }
        }
        throw new AssertionError("返回体里没有得分项 " + indicatorCode);
    }

    /** 与 EvalService.currentTerm 同一条口径：9 月起 -1，2—7 月 -2，其余（1 月）上一年 -1 */
    private static String expectedCurrentTerm() {
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        int month = today.getMonthValue();
        if (month >= 9) {
            return year + "-" + (year + 1) + "-1";
        }
        if (month >= 2) {
            return (year - 1) + "-" + year + "-2";
        }
        return (year - 1) + "-" + year + "-1";
    }

    // ------------------------------------------------------------------ 测试装置

    private static EvalIndicator indicator(long id, String dimension, String code, String name,
                                           String maxScore, String weight) {
        EvalIndicator item = new EvalIndicator();
        item.setId(id);
        item.setDimension(dimension);
        item.setCode(code);
        item.setName(name);
        item.setDescription(name + "（测试口径）");
        item.setMaxScore(maxScore == null ? null : new BigDecimal(maxScore));
        item.setWeight(weight == null ? null : new BigDecimal(weight));
        item.setSortOrder((int) id * 10);
        item.setActive(1);
        return item;
    }

    /** 与 sql/nest_xuegong_seed.sql 一致的 17 项演示指标（德 4 / 智 4 / 体 3 / 美 3 / 劳 3） */
    private static List<EvalIndicator> demoIndicators() {
        List<EvalIndicator> all = new ArrayList<>();
        all.add(indicator(1L, "moral", "MORAL_BASIC", "思想政治表现", "100", "0.4000"));
        all.add(indicator(2L, "moral", "MORAL_ETHICS", "品德行为", "100", "0.3000"));
        all.add(indicator(3L, "moral", "MORAL_HONOR", "表彰与荣誉", "100", "0.2000"));
        all.add(indicator(4L, "moral", "MORAL_DISCIPLINE", "违纪扣分", "100", "0.1000"));
        all.add(indicator(5L, "intellectual", "INTEL_GPA", "学业成绩", "100", "0.6000"));
        all.add(indicator(6L, "intellectual", "INTEL_RANK", "学业排名", "100", "0.2000"));
        all.add(indicator(7L, "intellectual", "INTEL_COMPETITION", "学科竞赛", "100", "0.1500"));
        all.add(indicator(8L, "intellectual", "INTEL_RESEARCH", "科研与创新", "100", "0.0500"));
        all.add(indicator(9L, "physical", "PHYS_PE", "体育课程", "100", "0.4000"));
        all.add(indicator(10L, "physical", "PHYS_TEST", "体质健康测试", "100", "0.4000"));
        all.add(indicator(11L, "physical", "PHYS_ACTIVITY", "体育活动", "100", "0.2000"));
        all.add(indicator(12L, "aesthetic", "AEST_COURSE", "美育课程", "100", "0.4000"));
        all.add(indicator(13L, "aesthetic", "AEST_PRACTICE", "文艺实践", "100", "0.4000"));
        all.add(indicator(14L, "aesthetic", "AEST_AWARD", "文艺获奖", "100", "0.2000"));
        all.add(indicator(15L, "labor", "LABOR_PRACTICE", "劳动实践", "100", "0.4000"));
        all.add(indicator(16L, "labor", "LABOR_VOLUNTEER", "志愿服务", "100", "0.4000"));
        all.add(indicator(17L, "labor", "LABOR_DORM", "宿舍劳动", "100", "0.2000"));
        return all;
    }

    /** 每维一项、维度内权重 1.0、满分 100：维度得分 = 该项得分，便于验算加权公式 */
    private static List<EvalIndicator> onePerDimension() {
        List<EvalIndicator> all = new ArrayList<>();
        all.add(indicator(1L, "moral", "MORAL_BASIC", "思想政治表现", "100", "1.0000"));
        all.add(indicator(2L, "intellectual", "INTEL_GPA", "学业成绩", "100", "1.0000"));
        all.add(indicator(3L, "physical", "PHYS_PE", "体育课程", "100", "1.0000"));
        all.add(indicator(4L, "aesthetic", "AEST_COURSE", "美育课程", "100", "1.0000"));
        all.add(indicator(5L, "labor", "LABOR_PRACTICE", "劳动实践", "100", "1.0000"));
        return all;
    }

    /** 只有德一项（满分 100、维度内权重 1.0），把德维权重设成 1.0 后总分就等于该得分 */
    private static List<EvalIndicator> singleMoral() {
        return Collections.singletonList(indicator(1L, "moral", "MORAL_BASIC", "思想政治表现", "100", "1.0000"));
    }

    /** 德维四项（权重各 0.25），用来验 SOURCE_PRIORITY：辅导员 > 系统 > 班评 > 自评 */
    private static List<EvalIndicator> priorityIndicators() {
        List<EvalIndicator> all = new ArrayList<>();
        all.add(indicator(1L, "moral", "MORAL_BASIC", "思想政治表现", "100", "0.2500"));
        all.add(indicator(2L, "moral", "MORAL_ETHICS", "品德行为", "100", "0.2500"));
        all.add(indicator(3L, "moral", "MORAL_HONOR", "表彰与荣誉", "100", "0.2500"));
        all.add(indicator(4L, "moral", "MORAL_DISCIPLINE", "违纪扣分", "100", "0.2500"));
        return all;
    }

    private static EvalScore scoreRow(long studentId, long indicatorId, String source, String score) {
        EvalScore row = new EvalScore();
        row.setId(studentId * 100 + indicatorId);
        row.setStudentId(studentId);
        row.setIndicatorId(indicatorId);
        row.setAcademicTerm(TERM);
        row.setSource(source);
        row.setScore(score == null ? null : new BigDecimal(score));
        row.setScorerId("self".equals(source) ? studentId : COUNSELOR);
        return row;
    }

    private static StudentProfile profile(long userId, Long counselorId, String className) {
        StudentProfile p = new StudentProfile();
        p.setId(userId + 100);
        p.setUserId(userId);
        p.setName("学生" + userId);
        p.setStudentNo(String.format("2024%04d", userId));
        p.setClassName(className);
        p.setMajor("软件工程");
        p.setGrade("2024");
        p.setCounselorId(counselorId);
        p.setCounselorName(counselorId == null ? null : "辅导员" + counselorId);
        p.setStatus("active");
        return p;
    }

    private static Map<String, Object> scoreEntry(Object indicatorId, String code, Object score) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (indicatorId != null) {
            row.put("indicatorId", indicatorId);
        }
        if (code != null) {
            row.put("code", code);
        }
        if (score != null) {
            row.put("score", score);
        }
        return row;
    }

    private static Map<String, Object> submitBody(String term, Object scores) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (term != null) {
            body.put("term", term);
        }
        body.put("scores", scores);
        return body;
    }

    private static List<Map<String, Object>> oneScore(Object indicatorId, String code, Object score) {
        return Collections.singletonList(scoreEntry(indicatorId, code, score));
    }

    private void givenIndicators(List<EvalIndicator> indicators) {
        when(indicatorMapper.listActive()).thenReturn(indicators);
    }

    private void givenProfile(long userId, Long counselorId, String className) {
        when(profileMapper.findByUserId(userId)).thenReturn(profile(userId, counselorId, className));
    }

    private Map<Long, EvalSummary> capturedSummaries(int expected) {
        ArgumentCaptor<EvalSummary> captor = ArgumentCaptor.forClass(EvalSummary.class);
        verify(summaryMapper, times(expected)).upsert(captor.capture());
        Map<Long, EvalSummary> byStudent = new LinkedHashMap<>();
        for (EvalSummary one : captor.getAllValues()) {
            byStudent.put(one.getStudentId(), one);
        }
        return byStudent;
    }

    // ------------------------------------------------------------------ 未登录

    @Nested
    @DisplayName("未登录（userId=null）")
    class Unauthenticated {

        @Test
        @DisplayName("indicators：401 未登录，一次库都不查")
        void indicators() {
            Result<?> r = service.indicators(null);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(indicatorMapper, scoreMapper, summaryMapper, profileMapper);
        }

        @Test
        @DisplayName("mine：401 未登录")
        void mine() {
            Result<?> r = service.mine(null, TERM);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(indicatorMapper, scoreMapper, summaryMapper, profileMapper);
        }

        @Test
        @DisplayName("submit：401 先于角色判断（未登录不给 403 的误导）")
        void submit() {
            Result<?> r = service.submit(null, null, null);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(indicatorMapper, scoreMapper, summaryMapper, profileMapper);
        }

        @Test
        @DisplayName("score：401 未登录")
        void score() {
            Result<?> r = service.score(null, "teacher", STUDENT, submitBody(TERM, oneScore(1L, null, "80")));
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(indicatorMapper, scoreMapper, summaryMapper, profileMapper);
        }

        @Test
        @DisplayName("summary：401 未登录")
        void summary() {
            Result<?> r = service.summary(null, null, TERM, null, null, null, 1, 20);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(indicatorMapper, scoreMapper, summaryMapper, profileMapper);
        }

        @Test
        @DisplayName("detail：401 未登录")
        void detail() {
            Result<?> r = service.detail(null, null, STUDENT, TERM);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(indicatorMapper, scoreMapper, summaryMapper, profileMapper);
        }

        @Test
        @DisplayName("compute：401 未登录")
        void compute() {
            Result<?> r = service.compute(null, null, TERM, null);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(indicatorMapper, scoreMapper, summaryMapper, profileMapper);
        }
    }

    // ------------------------------------------------------------------ 指标表

    @Nested
    @DisplayName("indicators - 指标表与维度权重")
    class Indicators {

        @Test
        @DisplayName("五维固定顺序（德智体美劳），17 项演示指标各归其位")
        void fiveDimensions() {
            givenIndicators(demoIndicators());

            Result<?> r = service.indicators(STUDENT);

            assertEquals(200, r.getCode());
            assertEquals(17, data(r).get("indicatorCount"));
            List<Map<String, Object>> dimensions = maps(data(r).get("dimensions"));
            assertEquals(5, dimensions.size());
            assertEquals(Arrays.asList("moral", "intellectual", "physical", "aesthetic", "labor"),
                    Arrays.asList(dimensions.get(0).get("code"), dimensions.get(1).get("code"),
                            dimensions.get(2).get("code"), dimensions.get(3).get("code"),
                            dimensions.get(4).get("code")));
            assertEquals(Arrays.asList("德", "智", "体", "美", "劳"),
                    Arrays.asList(dimensions.get(0).get("name"), dimensions.get(1).get("name"),
                            dimensions.get(2).get("name"), dimensions.get(3).get("name"),
                            dimensions.get(4).get("name")));
            assertEquals(Arrays.asList(4, 4, 3, 3, 3),
                    Arrays.asList(dimensions.get(0).get("itemCount"), dimensions.get(1).get("itemCount"),
                            dimensions.get(2).get("itemCount"), dimensions.get(3).get("itemCount"),
                            dimensions.get(4).get("itemCount")));
            assertEquals(17, maps(data(r).get("dimensions")).stream()
                    .mapToInt(dim -> ((Number) dim.get("itemCount")).intValue()).sum());
        }

        @Test
        @DisplayName("学生也能读指标表（自评要照它填），不因角色被挡")
        void studentCanRead() {
            givenIndicators(demoIndicators());
            Result<?> r = service.indicators(STUDENT);
            assertEquals(200, r.getCode());
            assertEquals("success", r.getMessage());
        }

        @Test
        @DisplayName("指标项的字段快照：id/code/name/description/maxScore/weight/sortOrder 一个不少")
        void itemFields() {
            givenIndicators(demoIndicators());

            Map<String, Object> item = itemOf(dimensionOf(service.indicators(STUDENT), "moral"), "MORAL_BASIC");

            assertEquals(1L, item.get("id"));
            assertEquals("MORAL_BASIC", item.get("code"));
            assertEquals("思想政治表现", item.get("name"));
            assertEquals("思想政治表现（测试口径）", item.get("description"));
            assertEquals("moral", item.get("dimension"));
            assertDecimal("100", item.get("maxScore"));
            assertDecimal("0.4", item.get("weight"));
            assertEquals(10, item.get("sortOrder"));
        }

        @Test
        @DisplayName("维度权重与 weights 映射来自配置（改配置就改返回体）")
        void weightsComeFromConfig() {
            setWeights(0.30, 0.30, 0.20, 0.10, 0.10);
            givenIndicators(demoIndicators());

            Result<?> r = service.indicators(STUDENT);

            assertDecimal("0.3", dimensionOf(r, "moral").get("weight"));
            assertDecimal("0.2", dimensionOf(r, "physical").get("weight"));
            Map<String, Object> weights = mapOf(data(r).get("weights"));
            assertDecimal("0.3", weights.get("moral"));
            assertDecimal("0.3", weights.get("intellectual"));
            assertDecimal("0.1", weights.get("labor"));
            assertEquals(5, weights.size());
        }

        @Test
        @DisplayName("学期选项：当前学期 + 历史学期，倒序且去重；等级线与公式文案随体返回")
        void termOptionsAndTexts() {
            when(scoreMapper.listTerms()).thenReturn(Arrays.asList(expectedCurrentTerm(), "2025-2026-2", "2025-2026-1"));
            givenIndicators(demoIndicators());

            Result<?> r = service.indicators(STUDENT);

            assertEquals(expectedCurrentTerm(), data(r).get("currentTerm"));
            List<String> terms = strings(data(r).get("terms"));
            assertTrue(terms.contains(expectedCurrentTerm()), "学期选项里必须有当前学期");
            assertTrue(terms.contains("2025-2026-2"));
            assertEquals(3, terms.size(), "同一学期不能重复出现");
            for (int i = 1; i < terms.size(); i++) {
                assertTrue(terms.get(i - 1).compareTo(terms.get(i)) > 0, "学期应按倒序排列：" + terms);
            }
            assertEquals("优秀 ≥90 / 良好 ≥80 / 合格 ≥60 / 不合格 <60", data(r).get("gradeRule"));
            assertTrue(String.valueOf(data(r).get("formula")).contains("维度得分"));
        }

        @Test
        @DisplayName("指标表为空时五维骨架仍在（页面不会白屏），计数为 0")
        void emptyIndicatorTable() {
            when(indicatorMapper.listActive()).thenReturn(Collections.emptyList());

            Result<?> r = service.indicators(STUDENT);

            assertEquals(200, r.getCode());
            assertEquals(0, data(r).get("indicatorCount"));
            assertEquals(5, maps(data(r).get("dimensions")).size());
            assertEquals(0, dimensionOf(r, "moral").get("itemCount"));
        }
    }

    // ------------------------------------------------------------------ 我的测评

    @Nested
    @DisplayName("mine - 我的综测")
    class Mine {

        @Test
        @DisplayName("学期格式非法 → 400，不查库")
        void badTerm() {
            Result<?> r = service.mine(STUDENT, "2026-2027-3");
            assertEquals(400, r.getCode());
            assertEquals("学期格式不正确（应为 2026-2027-1）", r.getMessage());
            verifyNoInteractions(indicatorMapper, scoreMapper, summaryMapper);
        }

        @Test
        @DisplayName("不传学期（或空白）按当前学期查")
        void currentTermByDefault() {
            givenIndicators(demoIndicators());
            when(scoreMapper.listByStudent(STUDENT, expectedCurrentTerm())).thenReturn(Collections.emptyList());

            assertEquals(expectedCurrentTerm(), data(service.mine(STUDENT, null)).get("term"));
            assertEquals(expectedCurrentTerm(), data(service.mine(STUDENT, "   ")).get("term"));
            verify(scoreMapper, times(2)).listByStudent(STUDENT, expectedCurrentTerm());
        }

        @Test
        @DisplayName("一项都没填：exists=false、scoreCount=0，summary 不假装存在")
        void noScoresYet() {
            givenIndicators(demoIndicators());
            when(scoreMapper.listByStudent(STUDENT, TERM)).thenReturn(Collections.emptyList());
            when(summaryMapper.findByStudentTerm(STUDENT, TERM)).thenReturn(null);

            Result<?> r = service.mine(STUDENT, TERM);

            assertEquals(200, r.getCode());
            assertEquals(Boolean.FALSE, data(r).get("exists"));
            assertEquals(0, data(r).get("scoreCount"));
            assertEquals(17, data(r).get("indicatorCount"));
            assertTrue(maps(data(r).get("scores")).isEmpty());
            Map<String, Object> summary = mapOf(data(r).get("summary"));
            assertEquals(Boolean.FALSE, summary.get("exists"));
            assertEquals(TERM, summary.get("term"));
        }

        @Test
        @DisplayName("已有得分：逐项映射带来源中文文案，进度按五维统计")
        void withScores() {
            givenIndicators(demoIndicators());
            EvalScore row = scoreRow(STUDENT, 1L, "self", "88");
            row.setIndicatorCode("MORAL_BASIC");
            row.setIndicatorName("思想政治表现");
            row.setDimension("moral");
            row.setEvidence("主题班会全勤");
            row.setScorerName("学生2");
            when(scoreMapper.listByStudent(STUDENT, TERM)).thenReturn(Collections.singletonList(row));
            when(summaryMapper.findByStudentTerm(STUDENT, TERM)).thenReturn(null);

            Result<?> r = service.mine(STUDENT, TERM);

            assertEquals(Boolean.TRUE, data(r).get("exists"));
            assertEquals(1, data(r).get("scoreCount"));
            Map<String, Object> node = scoreMapOf(r, "MORAL_BASIC");
            assertEquals(STUDENT, node.get("studentId"));
            assertEquals(1L, node.get("indicatorId"));
            assertEquals("思想政治表现", node.get("indicatorName"));
            assertEquals("moral", node.get("dimension"));
            assertEquals(TERM, node.get("term"));
            assertDecimal("88.00", node.get("score"));
            assertEquals("self", node.get("source"));
            assertEquals("自评", node.get("sourceName"));
            assertEquals("主题班会全勤", node.get("evidence"));
            assertEquals("学生2", node.get("scorerName"));
            Map<String, Object> progress = mapOf(data(r).get("progress"));
            assertEquals(17, progress.get("indicatorCount"));
            assertEquals(1, progress.get("selfDone"));
            assertEquals(16, progress.get("selfRemain"));
            assertEquals(Boolean.FALSE, progress.get("selfComplete"));
        }

        @Test
        @DisplayName("自评覆盖满 17 项时 selfComplete=true（页面才允许提交）")
        void selfComplete() {
            givenIndicators(demoIndicators());
            List<EvalScore> rows = new ArrayList<>();
            for (EvalIndicator item : demoIndicators()) {
                rows.add(scoreRow(STUDENT, item.getId(), "self", "80"));
                rows.add(scoreRow(STUDENT, item.getId(), "counselor", "90"));
            }
            when(scoreMapper.listByStudent(STUDENT, TERM)).thenReturn(rows);
            when(summaryMapper.findByStudentTerm(STUDENT, TERM)).thenReturn(null);

            Map<String, Object> progress = mapOf(data(service.mine(STUDENT, TERM)).get("progress"));

            assertEquals(17, progress.get("selfDone"));
            assertEquals(0, progress.get("selfRemain"));
            assertEquals(17, progress.get("counselorDone"));
            assertEquals(Boolean.TRUE, progress.get("selfComplete"));
        }

        @Test
        @DisplayName("维度聚合：维度得分 = Σ(项得分 ÷ 满分 × 100 × 维度内权重)，weightedScore 再乘维度权重")
        void dimensionBreakdown() {
            setWeights(1.0, 0.0, 0.0, 0.0, 0.0);
            givenIndicators(priorityIndicators());
            List<EvalScore> rows = Arrays.asList(
                    scoreRow(STUDENT, 1L, "self", "40"),
                    scoreRow(STUDENT, 1L, "counselor", "90"),
                    scoreRow(STUDENT, 2L, "self", "50"),
                    scoreRow(STUDENT, 2L, "system", "80"),
                    scoreRow(STUDENT, 3L, "self", "60"),
                    scoreRow(STUDENT, 3L, "class", "70"),
                    scoreRow(STUDENT, 4L, "self", "55"));
            when(scoreMapper.listByStudent(STUDENT, TERM)).thenReturn(rows);
            when(summaryMapper.findByStudentTerm(STUDENT, TERM)).thenReturn(null);

            Map<String, Object> moral = dimensionOf(service.mine(STUDENT, TERM), "moral");

            // 0.25 × (90 + 80 + 70 + 55) = 73.75
            assertDecimal("73.75", moral.get("score"));
            assertDecimal("73.75", moral.get("weightedScore"));
            assertEquals(4, moral.get("scored"));
            assertEquals(4, moral.get("itemCount"));
        }

        @Test
        @DisplayName("同一指标多来源：明细里取优先级最高的一条（辅导员 > 系统 > 班评 > 自评），自评分仍单独回显")
        void sourcePriorityInBreakdown() {
            setWeights(1.0, 0.0, 0.0, 0.0, 0.0);
            givenIndicators(priorityIndicators());
            EvalScore counselorRow = scoreRow(STUDENT, 1L, "counselor", "90");
            counselorRow.setEvidence("辅导员核定");
            List<EvalScore> rows = Arrays.asList(
                    scoreRow(STUDENT, 1L, "self", "40"), counselorRow,
                    scoreRow(STUDENT, 2L, "self", "50"),
                    scoreRow(STUDENT, 2L, "system", "80"),
                    scoreRow(STUDENT, 3L, "self", "60"),
                    scoreRow(STUDENT, 3L, "class", "70"),
                    scoreRow(STUDENT, 4L, "self", "55"));
            when(scoreMapper.listByStudent(STUDENT, TERM)).thenReturn(rows);
            when(summaryMapper.findByStudentTerm(STUDENT, TERM)).thenReturn(null);

            Map<String, Object> moral = dimensionOf(service.mine(STUDENT, TERM), "moral");
            Map<String, Object> basic = itemOf(moral, "MORAL_BASIC");
            Map<String, Object> ethics = itemOf(moral, "MORAL_ETHICS");
            Map<String, Object> honor = itemOf(moral, "MORAL_HONOR");
            Map<String, Object> discipline = itemOf(moral, "MORAL_DISCIPLINE");

            assertDecimal("90", basic.get("score"));
            assertEquals("counselor", basic.get("source"));
            assertEquals("辅导员评分", basic.get("sourceName"));
            assertEquals("辅导员核定", basic.get("evidence"));
            assertDecimal("40", basic.get("selfScore"));
            assertDecimal("90", basic.get("counselorScore"));

            assertDecimal("80", ethics.get("score"));
            assertEquals("system", ethics.get("source"));
            assertEquals("系统", ethics.get("sourceName"));

            assertDecimal("70", honor.get("score"));
            assertEquals("class", honor.get("source"));
            assertEquals("班评", honor.get("sourceName"));

            assertDecimal("55", discipline.get("score"));
            assertEquals("self", discipline.get("source"));
            assertEquals("自评", discipline.get("sourceName"));
            assertNull(discipline.get("counselorScore"), "没录辅导员评分时应是 null 而不是 0");
        }

        @Test
        @DisplayName("已有汇总行：detail_json 解析回五维分数（与 compute 写出的快照同格式）")
        void summaryDetailParsed() {
            givenIndicators(demoIndicators());
            EvalSummary summary = new EvalSummary();
            summary.setId(66L);
            summary.setStudentId(STUDENT);
            summary.setAcademicTerm(TERM);
            summary.setTotalScore(new BigDecimal("50.50"));
            summary.setRankInClass(7);
            summary.setGradeLevel("合格");
            summary.setDetailJson("{\"moral\":36.00,\"intellectual\":48.00,\"physical\":0.00,\"aesthetic\":0.00,\"labor\":0.00}");
            when(scoreMapper.listByStudent(STUDENT, TERM)).thenReturn(Collections.emptyList());
            when(summaryMapper.findByStudentTerm(STUDENT, TERM)).thenReturn(summary);

            Map<String, Object> node = mapOf(data(service.mine(STUDENT, TERM)).get("summary"));

            assertEquals(Boolean.TRUE, node.get("exists"));
            assertEquals(66L, node.get("id"));
            assertDecimal("50.50", node.get("totalScore"));
            assertEquals(7, node.get("rankInClass"));
            assertEquals("合格", node.get("gradeLevel"));
            Map<String, Object> detail = mapOf(node.get("detail"));
            assertDecimal("36.00", detail.get("moral"));
            assertDecimal("48.00", detail.get("intellectual"));
        }
    }

    // ------------------------------------------------------------------ 学生自评

    @Nested
    @DisplayName("submit - 学生自评")
    class Submit {

        @Test
        @DisplayName("辅导员的角色不能走学生自评 → 403")
        void teacherForbidden() {
            Result<?> r = service.submit(COUNSELOR, "teacher", submitBody(TERM, oneScore(1L, null, "80")));
            assertEquals(403, r.getCode());
            assertEquals("只有学生可以提交综测自评", r.getMessage());
            verifyNoInteractions(scoreMapper);
        }

        @Test
        @DisplayName("管理员也不能走学生自评 → 403")
        void adminForbidden() {
            Result<?> r = service.submit(ADMIN, "admin", submitBody(TERM, oneScore(1L, null, "80")));
            assertEquals(403, r.getCode());
            assertEquals("只有学生可以提交综测自评", r.getMessage());
        }

        @Test
        @DisplayName("请求体为空 → 400 请求内容为空")
        void nullBody() {
            Result<?> r = service.submit(STUDENT, "student", null);
            assertEquals(400, r.getCode());
            assertEquals("请求内容为空", r.getMessage());
            verifyNoInteractions(scoreMapper);
        }

        @Test
        @DisplayName("没填 scores / scores 是空数组 / scores 不是数组 → 400 请至少填写一项得分")
        void emptyScores() {
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("term", TERM);
            assertEquals("请至少填写一项得分", service.submit(STUDENT, "student", missing).getMessage());
            assertEquals("请至少填写一项得分",
                    service.submit(STUDENT, "student", submitBody(TERM, Collections.emptyList())).getMessage());
            assertEquals("请至少填写一项得分",
                    service.submit(STUDENT, "student", submitBody(TERM, "我不是数组")).getMessage());
            verifyNeverUpsert();
        }

        @Test
        @DisplayName("一次提交超过 200 项 → 400（先于指标匹配，防刷）")
        void tooManyItems() {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < 201; i++) {
                rows.add(scoreEntry(1L, null, "80"));
            }
            Result<?> r = service.submit(STUDENT, "student", submitBody(TERM, rows));
            assertEquals(400, r.getCode());
            assertEquals("一次最多提交 200 项", r.getMessage());
            verifyNeverUpsert();
        }

        @Test
        @DisplayName("第 N 项不是对象 → 400 并指出行号")
        void itemNotMap() {
            Result<?> r = service.submit(STUDENT, "student", submitBody(TERM, Collections.singletonList("字符串")));
            assertEquals(400, r.getCode());
            assertEquals("第 1 项格式不正确", r.getMessage());
            verifyNeverUpsert();
        }

        @Test
        @DisplayName("indicatorId 不是数字 → 400 第 1 项的指标 id 不正确")
        void badIndicatorId() {
            givenIndicators(demoIndicators());
            Result<?> r = service.submit(STUDENT, "student", submitBody(TERM, oneScore("abc", null, "80")));
            assertEquals(400, r.getCode());
            assertEquals("第 1 项的指标 id 不正确", r.getMessage());
            verifyNeverUpsert();
        }

        @Test
        @DisplayName("指标不存在（id 与 code 都对不上）→ 400 第 1 项找不到对应指标")
        void unknownIndicator() {
            givenIndicators(demoIndicators());
            assertEquals("第 1 项找不到对应指标",
                    service.submit(STUDENT, "student", submitBody(TERM, oneScore(999L, "NOT_EXIST", "80"))).getMessage());
            verifyNeverUpsert();
        }

        @Test
        @DisplayName("没填 score → 400 报出是哪一项（带指标名）")
        void missingScore() {
            givenIndicators(demoIndicators());
            Result<?> r = service.submit(STUDENT, "student", submitBody(TERM, oneScore(1L, null, null)));
            assertEquals(400, r.getCode());
            assertEquals("第 1 项（思想政治表现）没有填得分", r.getMessage());
            verifyNeverUpsert();
        }

        @Test
        @DisplayName("score 不是数字 → 400 得分不是数字")
        void scoreNotNumber() {
            givenIndicators(demoIndicators());
            Result<?> r = service.submit(STUDENT, "student", submitBody(TERM, oneScore(1L, null, "优")));
            assertEquals(400, r.getCode());
            assertEquals("第 1 项（思想政治表现）得分不是数字", r.getMessage());
            verifyNeverUpsert();
        }

        @Test
        @DisplayName("得分超出满分 / 为负数 → 400「得分必须在 0 ~ 100 之间」")
        void scoreOutOfRange() {
            givenIndicators(demoIndicators());
            assertEquals("「思想政治表现」得分必须在 0 ~ 100 之间",
                    service.submit(STUDENT, "student", submitBody(TERM, oneScore(1L, null, "100.01"))).getMessage());
            assertEquals("「思想政治表现」得分必须在 0 ~ 100 之间",
                    service.submit(STUDENT, "student", submitBody(TERM, oneScore(1L, null, "-0.01"))).getMessage());
            verifyNeverUpsert();
        }

        @Test
        @DisplayName("学期格式非法 → 400，一项都不落库")
        void badTerm() {
            Result<?> r = service.submit(STUDENT, "student", submitBody("2026-2027-3", oneScore(1L, null, "80")));
            assertEquals(400, r.getCode());
            assertEquals("学期格式不正确（应为 2026-2027-1）", r.getMessage());
            verifyNeverUpsert();
        }

        @Test
        @DisplayName("成功：逐项 upsert，来源固定 self、评分人是学生本人，返回「已保存 N 项自评」")
        void successWritesSelfScores() {
            givenIndicators(demoIndicators());
            List<Map<String, Object>> rows = Arrays.asList(scoreEntry(1L, null, "88.5"), scoreEntry(null, "MORAL_ETHICS", "90"));

            Result<?> r = service.submit(STUDENT, "student", submitBody(TERM, rows));

            assertEquals(200, r.getCode());
            assertEquals("已保存 2 项自评", r.getMessage());
            assertEquals(2, data(r).get("saved"));
            assertEquals("self", data(r).get("source"));
            assertEquals("自评", data(r).get("sourceName"));
            assertEquals(TERM, data(r).get("term"));

            ArgumentCaptor<EvalScore> captor = ArgumentCaptor.forClass(EvalScore.class);
            verify(scoreMapper, times(2)).upsert(captor.capture());
            List<EvalScore> written = captor.getAllValues();
            assertEquals(STUDENT, written.get(0).getStudentId());
            assertEquals(1L, written.get(0).getIndicatorId());
            assertEquals("88.50", written.get(0).getScore().toPlainString());
            assertEquals("self", written.get(0).getSource());
            assertEquals(STUDENT, written.get(0).getScorerId());
            assertEquals(TERM, written.get(0).getAcademicTerm());
            assertEquals(2L, written.get(1).getIndicatorId(), "第二次按 code 兜底匹配到品德行为");
        }

        @Test
        @DisplayName("不传 term 时按当前学期写入")
        void termDefaultsToCurrent() {
            givenIndicators(demoIndicators());

            Result<?> r = service.submit(STUDENT, "student", submitBody(null, oneScore(1L, null, "80")));

            assertEquals(200, r.getCode());
            ArgumentCaptor<EvalScore> captor = ArgumentCaptor.forClass(EvalScore.class);
            verify(scoreMapper).upsert(captor.capture());
            assertEquals(expectedCurrentTerm(), captor.getValue().getAcademicTerm());
        }

        @Test
        @DisplayName("证据说明超 255 字被截断（防前端塞整篇文章）")
        void evidenceClipped() {
            givenIndicators(demoIndicators());
            String longEvidence = "x".repeat(300);
            Map<String, Object> entry = scoreEntry(1L, null, "80");
            entry.put("evidence", longEvidence);

            Result<?> r = service.submit(STUDENT, "student", submitBody(TERM, Collections.singletonList(entry)));

            assertEquals(200, r.getCode());
            ArgumentCaptor<EvalScore> captor = ArgumentCaptor.forClass(EvalScore.class);
            verify(scoreMapper).upsert(captor.capture());
            assertEquals(255, captor.getValue().getEvidence().length());
        }

        @Test
        @DisplayName("空串 indicatorId 会退回按 code 找指标")
        void blankIndicatorIdFallsBackToCode() {
            givenIndicators(demoIndicators());

            Result<?> r = service.submit(STUDENT, "student", submitBody(TERM, oneScore("", "MORAL_BASIC", "70")));

            assertEquals(200, r.getCode());
            ArgumentCaptor<EvalScore> captor = ArgumentCaptor.forClass(EvalScore.class);
            verify(scoreMapper).upsert(captor.capture());
            assertEquals(1L, captor.getValue().getIndicatorId());
        }

        private void verifyNeverUpsert() {
            verify(scoreMapper, never()).upsert(any(EvalScore.class));
        }
    }

    // ------------------------------------------------------------------ 辅导员评分

    @Nested
    @DisplayName("score - 辅导员评分")
    class Score {

        @Test
        @DisplayName("学生角色不能录入辅导员评分 → 403，且不查库")
        void studentForbidden() {
            Result<?> r = service.score(STUDENT, "student", STUDENT, submitBody(TERM, oneScore(1L, null, "80")));
            assertEquals(403, r.getCode());
            assertEquals("无权限录入综测评分", r.getMessage());
            verifyNoInteractions(scoreMapper, profileMapper);
        }

        @Test
        @DisplayName("缺少学生 id → 400，不查档案")
        void missingStudentId() {
            Result<?> r = service.score(COUNSELOR, "teacher", null, submitBody(TERM, oneScore(1L, null, "80")));
            assertEquals(400, r.getCode());
            assertEquals("缺少学生 id", r.getMessage());
            verify(profileMapper, never()).findByUserId(anyLong());
            verify(scoreMapper, never()).upsert(any(EvalScore.class));
        }

        @Test
        @DisplayName("学生档案不存在 → 404")
        void profileMissing() {
            when(profileMapper.findByUserId(888L)).thenReturn(null);

            Result<?> r = service.score(COUNSELOR, "teacher", 888L, submitBody(TERM, oneScore(1L, null, "80")));

            assertEquals(404, r.getCode());
            assertEquals("学生档案不存在", r.getMessage());
            verify(scoreMapper, never()).upsert(any(EvalScore.class));
        }

        @Test
        @DisplayName("学生不在自己名下 → 403，一次写都不发")
        void notMyStudent() {
            givenProfile(888L, OTHER_COUNSELOR, "软工2401");

            Result<?> r = service.score(COUNSELOR, "teacher", 888L, submitBody(TERM, oneScore(1L, null, "80")));

            assertEquals(403, r.getCode());
            assertEquals("该学生不在你的名下", r.getMessage());
            verify(scoreMapper, never()).upsert(any(EvalScore.class));
        }

        @Test
        @DisplayName("未分派学生（counselorId 为空）任何辅导员都能评分，来源固定 counselor")
        void unassignedStudentAllowed() {
            givenIndicators(demoIndicators());
            givenProfile(888L, null, "软工2401");

            Result<?> r = service.score(COUNSELOR, "teacher", 888L, submitBody(TERM, oneScore(1L, null, "86")));

            assertEquals(200, r.getCode());
            assertEquals("已保存 1 项辅导员评分", r.getMessage());
            assertEquals("counselor", data(r).get("source"));
            assertEquals("辅导员评分", data(r).get("sourceName"));
            ArgumentCaptor<EvalScore> captor = ArgumentCaptor.forClass(EvalScore.class);
            verify(scoreMapper).upsert(captor.capture());
            assertEquals(888L, captor.getValue().getStudentId());
            assertEquals("counselor", captor.getValue().getSource());
            assertEquals(COUNSELOR, captor.getValue().getScorerId(), "评分人必须是当前辅导员，不能是学生本人");
        }

        @Test
        @DisplayName("admin 能评任何学生（不受归属限制）")
        void adminAnyStudent() {
            givenIndicators(demoIndicators());
            givenProfile(888L, OTHER_COUNSELOR, "软工2401");

            Result<?> r = service.score(ADMIN, "admin", 888L, submitBody(TERM, oneScore(1L, null, "95")));

            assertEquals(200, r.getCode());
            ArgumentCaptor<EvalScore> captor = ArgumentCaptor.forClass(EvalScore.class);
            verify(scoreMapper).upsert(captor.capture());
            assertEquals("counselor", captor.getValue().getSource());
            assertEquals(ADMIN, captor.getValue().getScorerId());
        }

        @Test
        @DisplayName("评分同样受分数区间与指标存在性约束（复用自评的校验）")
        void reusesValidation() {
            givenIndicators(demoIndicators());
            givenProfile(888L, null, "软工2401");

            assertEquals("「思想政治表现」得分必须在 0 ~ 100 之间",
                    service.score(COUNSELOR, "teacher", 888L, submitBody(TERM, oneScore(1L, null, "120"))).getMessage());
            assertEquals(400, service.score(COUNSELOR, "teacher", 888L, submitBody(TERM, oneScore(999L, null, "80"))).getCode());
            verify(scoreMapper, never()).upsert(any(EvalScore.class));
        }
    }

    // ------------------------------------------------------------------ 汇总分页

    @Nested
    @DisplayName("summary - 学期汇总与分页")
    class Summary {

        @Test
        @DisplayName("学生看汇总 → 403（学生只能看自己的 mine）")
        void studentForbidden() {
            Result<?> r = service.summary(STUDENT, "student", TERM, null, null, null, 1, 20);
            assertEquals(403, r.getCode());
            assertEquals("无权限查看综测汇总", r.getMessage());
            verifyNoInteractions(summaryMapper, profileMapper);
        }

        @Test
        @DisplayName("学期格式非法 → 400")
        void badTerm() {
            Result<?> r = service.summary(ADMIN, "admin", "2026-13-1", null, null, null, 1, 20);
            assertEquals(400, r.getCode());
            assertEquals("学期格式不正确（应为 2026-2027-1）", r.getMessage());
            verifyNoInteractions(summaryMapper);
        }

        @Test
        @DisplayName("size=999 钳到 200、page=0 归一到第 1 页（offset 必须是 0）")
        void pagingClamped() {
            when(summaryMapper.listByTerm(eq(TERM), isNull(), isNull(), eq(0), eq(200)))
                    .thenReturn(Collections.emptyList());
            when(summaryMapper.countByTerm(eq(TERM), isNull(), isNull())).thenReturn(45);

            Result<?> r = service.summary(ADMIN, "admin", TERM, null, null, null, 0, 999);

            assertEquals(200, r.getCode());
            assertEquals(1, data(r).get("page"));
            assertEquals(200, data(r).get("size"));
            assertEquals(45, data(r).get("total"));
            assertEquals(1, data(r).get("pages"));
            verify(summaryMapper).listByTerm(eq(TERM), isNull(), isNull(), eq(0), eq(200));
        }

        @Test
        @DisplayName("第 2 页 offset=20，pages 按总数算出（45 条 / 每页 20 → 3 页）")
        void secondPageOffset() {
            when(summaryMapper.listByTerm(eq(TERM), isNull(), isNull(), eq(20), eq(20)))
                    .thenReturn(Collections.emptyList());
            when(summaryMapper.countByTerm(eq(TERM), isNull(), isNull())).thenReturn(45);

            Result<?> r = service.summary(ADMIN, "admin", TERM, null, null, null, 2, 20);

            assertEquals(2, data(r).get("page"));
            assertEquals(3, data(r).get("pages"));
            verify(summaryMapper).listByTerm(eq(TERM), isNull(), isNull(), eq(20), eq(20));
        }

        @Test
        @DisplayName("size=0 或负数钳到 1（不许把全表一次拉出来）")
        void sizeFloor() {
            when(summaryMapper.listByTerm(eq(TERM), isNull(), isNull(), eq(0), eq(1)))
                    .thenReturn(Collections.emptyList());

            assertEquals(1, data(service.summary(ADMIN, "admin", TERM, null, null, null, 1, 0)).get("size"));
            assertEquals(1, data(service.summary(ADMIN, "admin", TERM, null, null, null, 1, -5)).get("size"));
            verify(summaryMapper, times(2)).listByTerm(eq(TERM), isNull(), isNull(), eq(0), eq(1));
        }

        @Test
        @DisplayName("有 keyword 走 searchByTerm，空白 keyword 退回普通列表")
        void keywordRouting() {
            when(summaryMapper.searchByTerm(eq(TERM), isNull(), eq("张"), isNull(), eq(0), eq(20)))
                    .thenReturn(Collections.emptyList());
            when(summaryMapper.countSearch(eq(TERM), isNull(), eq("张"), isNull())).thenReturn(1);
            when(summaryMapper.listByTerm(eq(TERM), isNull(), isNull(), eq(0), eq(20)))
                    .thenReturn(Collections.emptyList());

            Result<?> search = service.summary(ADMIN, "admin", TERM, null, "张", null, 1, 20);
            assertEquals(1, data(search).get("total"));
            verify(summaryMapper).searchByTerm(eq(TERM), isNull(), eq("张"), isNull(), eq(0), eq(20));
            verify(summaryMapper).countSearch(eq(TERM), isNull(), eq("张"), isNull());

            service.summary(ADMIN, "admin", TERM, null, "   ", null, 1, 20);
            verify(summaryMapper, never()).searchByTerm(eq(TERM), isNull(), eq("   "), isNull(), eq(0), eq(20));
            verify(summaryMapper).listByTerm(eq(TERM), isNull(), isNull(), eq(0), eq(20));
        }

        @Test
        @DisplayName("有分派的辅导员 scope=mine：counselorId 一路传到列表、计数与班级下拉")
        void teacherScopedToOwn() {
            when(profileMapper.countByCounselor(COUNSELOR)).thenReturn(2);
            when(summaryMapper.listByTerm(eq(TERM), isNull(), eq(COUNSELOR), eq(0), eq(20)))
                    .thenReturn(Collections.emptyList());
            when(summaryMapper.countByTerm(eq(TERM), isNull(), eq(COUNSELOR))).thenReturn(0);
            when(profileMapper.listClassNames(COUNSELOR)).thenReturn(Arrays.asList("软工2401", "软工2402"));

            Result<?> r = service.summary(COUNSELOR, "teacher", TERM, null, null, null, 1, 20);

            assertEquals(200, r.getCode());
            assertEquals("mine", data(r).get("scope"));
            assertEquals(Arrays.asList("软工2401", "软工2402"), data(r).get("classes"));
            verify(summaryMapper).listByTerm(eq(TERM), isNull(), eq(COUNSELOR), eq(0), eq(20));
            verify(summaryMapper).countByTerm(eq(TERM), isNull(), eq(COUNSELOR));
            verify(profileMapper).listClassNames(COUNSELOR);
        }

        @Test
        @DisplayName("admin 看全校；scope=all 覆盖辅导员的默认范围，且不再查分派数")
        void adminAndScopeAll() {
            when(summaryMapper.listByTerm(eq(TERM), isNull(), isNull(), eq(0), eq(20)))
                    .thenReturn(Collections.emptyList());

            Result<?> admin = service.summary(ADMIN, "admin", TERM, null, null, null, 1, 20);
            assertEquals("all", data(admin).get("scope"));
            verify(summaryMapper).listByTerm(eq(TERM), isNull(), isNull(), eq(0), eq(20));
            verify(profileMapper).listClassNames(isNull());

            Result<?> override = service.summary(COUNSELOR, "teacher", TERM, null, null, "all", 1, 20);
            assertEquals("all", data(override).get("scope"));
            verify(profileMapper, never()).countByCounselor(anyLong());
            verify(summaryMapper, times(2)).listByTerm(eq(TERM), isNull(), isNull(), eq(0), eq(20));
            verify(profileMapper, times(2)).listClassNames(isNull());
        }

        @Test
        @DisplayName("班级筛选原样传给 mapper（列表、计数与统计三处一致）")
        void classFilterPassedThrough() {
            when(summaryMapper.listByTerm(eq(TERM), eq("软工2401"), isNull(), eq(0), eq(20)))
                    .thenReturn(Collections.emptyList());

            Result<?> r = service.summary(ADMIN, "admin", TERM, "软工2401", null, null, 1, 20);

            assertEquals(200, r.getCode());
            verify(summaryMapper).listByTerm(eq(TERM), eq("软工2401"), isNull(), eq(0), eq(20));
            verify(summaryMapper).countByTerm(eq(TERM), eq("软工2401"), isNull());
            verify(summaryMapper).statsByTerm(eq(TERM), eq("软工2401"), isNull());
        }

        @Test
        @DisplayName("stats：已评/未评/平均分/最高最低/等级分布/有分人数")
        void stats() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("graded", 4L);
            row.put("avg_score", new BigDecimal("83.456"));
            row.put("max_score", new BigDecimal("95.5"));
            row.put("min_score", new BigDecimal("61"));
            row.put("excellent", 1);
            row.put("good", 2);
            row.put("pass", 1);
            row.put("fail", 0);
            when(summaryMapper.statsByTerm(eq(TERM), isNull(), isNull())).thenReturn(Collections.singletonList(row));
            when(profileMapper.count(isNull(), isNull(), isNull(), isNull())).thenReturn(10);
            when(scoreMapper.countScoredStudents(eq(TERM), isNull())).thenReturn(4);

            Map<String, Object> stats = mapOf(data(service.summary(ADMIN, "admin", TERM, null, null, null, 1, 20)).get("stats"));

            assertEquals(10, stats.get("students"));
            assertEquals(4L, stats.get("graded"));
            assertEquals(6, stats.get("ungraded"));
            assertDecimal("83.46", stats.get("average"));
            assertDecimal("95.50", stats.get("highest"));
            assertDecimal("61.00", stats.get("lowest"));
            assertEquals(1L, stats.get("excellent"));
            assertEquals(2L, stats.get("good"));
            assertEquals(1L, stats.get("pass"));
            assertEquals(0L, stats.get("fail"));
            assertEquals(4, stats.get("scored"));
        }
    }

    // ------------------------------------------------------------------ 学生明细

    @Nested
    @DisplayName("detail - 学生综测明细")
    class Detail {

        @Test
        @DisplayName("非 teacher/admin → 403")
        void staffOnly() {
            Result<?> r = service.detail(STUDENT, "student", STUDENT, TERM);
            assertEquals(403, r.getCode());
            assertEquals("无权限查看学生综测", r.getMessage());
            verifyNoInteractions(profileMapper);
        }

        @Test
        @DisplayName("学期格式非法 → 400")
        void badTerm() {
            Result<?> r = service.detail(ADMIN, "admin", STUDENT, "2026-2027-4");
            assertEquals(400, r.getCode());
            assertEquals("学期格式不正确（应为 2026-2027-1）", r.getMessage());
        }

        @Test
        @DisplayName("缺少学生 id → 400，且不查档案")
        void missingStudentId() {
            Result<?> r = service.detail(ADMIN, "admin", null, TERM);
            assertEquals(400, r.getCode());
            assertEquals("缺少学生 id", r.getMessage());
            verify(profileMapper, never()).findByUserId(anyLong());
        }

        @Test
        @DisplayName("档案不存在 → 404")
        void profileMissing() {
            when(profileMapper.findByUserId(888L)).thenReturn(null);
            Result<?> r = service.detail(COUNSELOR, "teacher", 888L, TERM);
            assertEquals(404, r.getCode());
            assertEquals("学生档案不存在", r.getMessage());
            verifyNoInteractions(scoreMapper, summaryMapper);
        }

        @Test
        @DisplayName("别的辅导员名下的学生 → 403")
        void otherCounselor() {
            givenProfile(888L, OTHER_COUNSELOR, "软工2401");
            Result<?> r = service.detail(COUNSELOR, "teacher", 888L, TERM);
            assertEquals(403, r.getCode());
            assertEquals("该学生不在你的名下", r.getMessage());
            verifyNoInteractions(scoreMapper, summaryMapper);
        }

        @Test
        @DisplayName("未分派学生（counselorId 为空）任何辅导员都能看")
        void unassignedStudent() {
            givenIndicators(demoIndicators());
            givenProfile(888L, null, "软工2401");
            when(scoreMapper.listByStudent(888L, TERM)).thenReturn(Collections.emptyList());
            when(summaryMapper.findByStudentTerm(888L, TERM)).thenReturn(null);

            Result<?> r = service.detail(COUNSELOR, "teacher", 888L, TERM);

            assertEquals(200, r.getCode());
            Map<String, Object> student = mapOf(data(r).get("student"));
            assertEquals(888L, student.get("userId"));
            assertEquals("软工2401", student.get("className"));
            assertNull(student.get("counselorName"));
            assertEquals(Boolean.FALSE, data(r).get("exists"));
        }

        @Test
        @DisplayName("admin 能看任何人：返回学生资料 + 五维明细 + 汇总占位")
        void adminSeesAny() {
            givenIndicators(onePerDimension());
            givenProfile(888L, OTHER_COUNSELOR, "软工2402");
            when(scoreMapper.listByStudent(888L, TERM)).thenReturn(Collections.singletonList(
                    scoreRow(888L, 2L, "self", "77")));
            when(summaryMapper.findByStudentTerm(888L, TERM)).thenReturn(null);

            Result<?> r = service.detail(ADMIN, "admin", 888L, TERM);

            assertEquals(200, r.getCode());
            Map<String, Object> student = mapOf(data(r).get("student"));
            assertEquals("学生888", student.get("name"));
            assertEquals("20240888", student.get("studentNo"));
            assertEquals("软件工程", student.get("major"));
            assertEquals("2024", student.get("grade"));
            assertEquals("辅导员9", student.get("counselorName"));
            assertEquals(TERM, data(r).get("term"));
            assertEquals(Boolean.TRUE, data(r).get("exists"));
            assertEquals(5, maps(data(r).get("dimensions")).size());
            assertDecimal("77.00", dimensionOf(r, "intellectual").get("score"));
            assertEquals(Boolean.FALSE, mapOf(data(r).get("summary")).get("exists"));
        }
    }

    // ------------------------------------------------------------------ 重算

    @Nested
    @DisplayName("compute - 重算总分与排名")
    class Compute {

        @Test
        @DisplayName("学生角色不能重算 → 403")
        void studentForbidden() {
            Result<?> r = service.compute(STUDENT, "student", TERM, null);
            assertEquals(403, r.getCode());
            assertEquals("无权限重算综测汇总", r.getMessage());
            verifyNoInteractions(indicatorMapper, scoreMapper, summaryMapper, profileMapper);
        }

        @Test
        @DisplayName("学期格式非法 → 400")
        void badTerm() {
            Result<?> r = service.compute(ADMIN, "admin", "2026-2027", null);
            assertEquals(400, r.getCode());
            assertEquals("学期格式不正确（应为 2026-2027-1）", r.getMessage());
            verifyNoInteractions(scoreMapper);
        }

        @Test
        @DisplayName("还没有可用指标 → 400 并让运维去执行种子脚本")
        void noIndicators() {
            givenIndicators(Collections.emptyList());

            Result<?> r = service.compute(ADMIN, "admin", TERM, null);

            assertEquals(400, r.getCode());
            assertEquals("还没有可用的综测指标，请先执行 sql/nest_xuegen_seed.sql", r.getMessage());
            verifyNoInteractions(scoreMapper, summaryMapper);
        }

        @Test
        @DisplayName("该学期一条得分都没有 → 400，不写出空汇总")
        void noScores() {
            givenIndicators(singleMoral());
            when(scoreMapper.listByTerm(TERM, null)).thenReturn(Collections.emptyList());

            Result<?> r = service.compute(ADMIN, "admin", TERM, null);

            assertEquals(400, r.getCode());
            assertEquals("该学期还没有任何得分记录，无法重算", r.getMessage());
            verify(summaryMapper, never()).upsert(any(EvalSummary.class));
        }

        @Test
        @DisplayName("辅导员只算名下学生：scope 参数就是自己，scope=mine")
        void counselorOnlyOwnStudents() {
            setWeights(1.0, 0.0, 0.0, 0.0, 0.0);
            when(profileMapper.countByCounselor(COUNSELOR)).thenReturn(2);
            givenIndicators(singleMoral());
            when(scoreMapper.listByTerm(TERM, COUNSELOR))
                    .thenReturn(Collections.singletonList(scoreRow(2L, 1L, "self", "88")));
            givenProfile(2L, COUNSELOR, "软工2401");

            Result<?> r = service.compute(COUNSELOR, "teacher", TERM, null);

            assertEquals(200, r.getCode());
            assertEquals("mine", data(r).get("scope"));
            verify(scoreMapper).listByTerm(TERM, COUNSELOR);
        }

        @Test
        @DisplayName("未分派任何学生的辅导员按全校算（与 summary 的口径一致）")
        void unassignedCounselorSeesAll() {
            setWeights(1.0, 0.0, 0.0, 0.0, 0.0);
            when(profileMapper.countByCounselor(COUNSELOR)).thenReturn(0);
            givenIndicators(singleMoral());
            when(scoreMapper.listByTerm(TERM, null))
                    .thenReturn(Collections.singletonList(scoreRow(2L, 1L, "self", "88")));
            givenProfile(2L, OTHER_COUNSELOR, "软工2401");

            Result<?> r = service.compute(COUNSELOR, "teacher", TERM, null);

            assertEquals(200, r.getCode());
            assertEquals("all", data(r).get("scope"));
            verify(scoreMapper).listByTerm(eq(TERM), isNull());
        }

        @Test
        @DisplayName("admin 算全校；返回文案「已重算 N 名学生的综测总分与排名」")
        void adminComputesAll() {
            setWeights(1.0, 0.0, 0.0, 0.0, 0.0);
            givenIndicators(singleMoral());
            when(scoreMapper.listByTerm(TERM, null)).thenReturn(Arrays.asList(
                    scoreRow(2L, 1L, "self", "88"),
                    scoreRow(3L, 1L, "self", "70")));
            givenProfile(2L, COUNSELOR, "软工2401");
            givenProfile(3L, COUNSELOR, "软工2401");

            Result<?> r = service.compute(ADMIN, "admin", TERM, null);

            assertEquals(200, r.getCode());
            assertEquals("已重算 2 名学生的综测总分与排名", r.getMessage());
            assertEquals(2, data(r).get("students"));
            assertEquals("all", data(r).get("scope"));
            assertEquals(Collections.singletonList("软工2401"), data(r).get("classes"));
            Map<Long, EvalSummary> written = capturedSummaries(2);
            assertDecimal("88.00", written.get(2L).getTotalScore());
            assertEquals(TERM, written.get(2L).getAcademicTerm());
            assertEquals("合格", written.get(3L).getGradeLevel());
        }

        @Test
        @DisplayName("班级排名：总分降序，同分并列（1、1、3），同班才在一起排")
        void rankingWithTies() {
            setWeights(1.0, 0.0, 0.0, 0.0, 0.0);
            givenIndicators(singleMoral());
            when(scoreMapper.listByTerm(TERM, null)).thenReturn(Arrays.asList(
                    scoreRow(2L, 1L, "self", "88"),
                    scoreRow(3L, 1L, "self", "88"),
                    scoreRow(4L, 1L, "self", "70")));
            givenProfile(2L, COUNSELOR, "软工2401");
            givenProfile(3L, COUNSELOR, "软工2401");
            givenProfile(4L, COUNSELOR, "软工2401");

            service.compute(ADMIN, "admin", TERM, null);

            Map<Long, EvalSummary> written = capturedSummaries(3);
            assertEquals(1, written.get(2L).getRankInClass());
            assertEquals(1, written.get(3L).getRankInClass(), "同分必须并列");
            assertEquals(3, written.get(4L).getRankInClass(), "并列后名次跳到 3");
        }

        @Test
        @DisplayName("不同班级各自排名（软工2402 的 95 分是那一班第 1，不跟 2401 混排）")
        void rankPerClass() {
            setWeights(1.0, 0.0, 0.0, 0.0, 0.0);
            givenIndicators(singleMoral());
            when(scoreMapper.listByTerm(TERM, null)).thenReturn(Arrays.asList(
                    scoreRow(2L, 1L, "self", "70"),
                    scoreRow(3L, 1L, "self", "95")));
            givenProfile(2L, COUNSELOR, "软工2401");
            givenProfile(3L, COUNSELOR, "软工2402");

            service.compute(ADMIN, "admin", TERM, null);

            Map<Long, EvalSummary> written = capturedSummaries(2);
            assertEquals(1, written.get(2L).getRankInClass());
            assertEquals(1, written.get(3L).getRankInClass());
            assertEquals("软工2402", written.get(3L).getClassName());
        }

        @Test
        @DisplayName("等级线：90 优秀 / 80 良好 / 60 合格 / 59.99 不合格")
        void gradeLines() {
            setWeights(1.0, 0.0, 0.0, 0.0, 0.0);
            givenIndicators(singleMoral());
            when(scoreMapper.listByTerm(TERM, null)).thenReturn(Arrays.asList(
                    scoreRow(2L, 1L, "self", "90"),
                    scoreRow(3L, 1L, "self", "80"),
                    scoreRow(4L, 1L, "self", "60"),
                    scoreRow(5L, 1L, "self", "59.99")));
            for (long id = 2; id <= 5; id++) {
                givenProfile(id, COUNSELOR, "软工2401");
            }

            service.compute(ADMIN, "admin", TERM, null);

            Map<Long, EvalSummary> written = capturedSummaries(4);
            assertEquals("优秀", written.get(2L).getGradeLevel());
            assertEquals("良好", written.get(3L).getGradeLevel());
            assertEquals("合格", written.get(4L).getGradeLevel());
            assertEquals("不合格", written.get(5L).getGradeLevel());
        }

        @Test
        @DisplayName("维度加权与总分：维度得分 = Σ(得分÷满分×100×维度内权重)，总分 = Σ(维度得分×维度权重)")
        void weightedTotal() {
            givenIndicators(Arrays.asList(
                    indicator(1L, "moral", "MORAL_BASIC", "思想政治表现", "100", "0.5000"),
                    indicator(2L, "moral", "MORAL_ETHICS", "品德行为", "100", "0.5000"),
                    indicator(3L, "intellectual", "INTEL_GPA", "学业成绩", "100", "1.0000")));
            when(scoreMapper.listByTerm(TERM, null)).thenReturn(Arrays.asList(
                    scoreRow(2L, 1L, "self", "90"),
                    scoreRow(2L, 2L, "self", "70"),
                    scoreRow(2L, 3L, "self", "80")));
            givenProfile(2L, COUNSELOR, "软工2401");

            service.compute(ADMIN, "admin", TERM, null);

            // 德：90×0.5 + 70×0.5 = 80.00 → ×0.25 = 20.00；智：80.00 → ×0.35 = 28.00；合计 48.00
            EvalSummary written = capturedSummaries(1).get(2L);
            assertDecimal("48.00", written.getTotalScore());
            assertEquals("不合格", written.getGradeLevel());
            assertEquals("{\"moral\":80.00,\"intellectual\":80.00,\"physical\":0.00,\"aesthetic\":0.00,\"labor\":0.00}",
                    written.getDetailJson(), "维度快照按德智体美劳顺序、两位小数落库");
        }

        @Test
        @DisplayName("满分不是 100 的指标按比例折算（40 / 50 折成 80 分）")
        void nonHundredMaxScore() {
            setWeights(0.0, 0.0, 1.0, 0.0, 0.0);
            givenIndicators(Collections.singletonList(
                    indicator(3L, "physical", "PHYS_PE", "体育课程", "50", "1.0000")));
            when(scoreMapper.listByTerm(TERM, null))
                    .thenReturn(Collections.singletonList(scoreRow(2L, 3L, "self", "40")));
            givenProfile(2L, COUNSELOR, "软工2401");

            service.compute(ADMIN, "admin", TERM, null);

            EvalSummary written = capturedSummaries(1).get(2L);
            assertDecimal("80.00", written.getTotalScore());
            assertEquals("良好", written.getGradeLevel());
        }

        @Test
        @DisplayName("指标满分为空时按 100 分口径折算（防御性：DDL 是 NOT NULL，但重算不因此崩）")
        void nullMaxScoreTreatedAsHundred() {
            setWeights(1.0, 0.0, 0.0, 0.0, 0.0);
            givenIndicators(Collections.singletonList(
                    indicator(1L, "moral", "MORAL_BASIC", "思想政治表现", null, "1.0000")));
            when(scoreMapper.listByTerm(TERM, null))
                    .thenReturn(Collections.singletonList(scoreRow(2L, 1L, "self", "75")));
            givenProfile(2L, COUNSELOR, "软工2401");

            service.compute(ADMIN, "admin", TERM, null);

            assertDecimal("75.00", capturedSummaries(1).get(2L).getTotalScore());
        }

        @Test
        @DisplayName("多来源时只认优先级最高的一条算总分（辅导员 90 覆盖自评 40）")
        void priorityPickedForTotal() {
            setWeights(1.0, 0.0, 0.0, 0.0, 0.0);
            givenIndicators(priorityIndicators());
            when(scoreMapper.listByTerm(TERM, null)).thenReturn(Arrays.asList(
                    scoreRow(2L, 1L, "self", "40"), scoreRow(2L, 1L, "counselor", "90"),
                    scoreRow(2L, 2L, "self", "50"), scoreRow(2L, 2L, "system", "80"),
                    scoreRow(2L, 3L, "self", "60"), scoreRow(2L, 3L, "class", "70"),
                    scoreRow(2L, 4L, "self", "55")));
            givenProfile(2L, COUNSELOR, "软工2401");

            service.compute(ADMIN, "admin", TERM, null);

            // 0.25 × (90 + 80 + 70 + 55) = 73.75；若误用自评则是 0.25 × 205 = 51.25
            assertDecimal("73.75", capturedSummaries(1).get(2L).getTotalScore());
        }

        @Test
        @DisplayName("按班级重算：只写该班、classes 只回该班，其它班不动")
        void classFiltered() {
            setWeights(1.0, 0.0, 0.0, 0.0, 0.0);
            givenIndicators(singleMoral());
            when(scoreMapper.listByTerm(TERM, null)).thenReturn(Arrays.asList(
                    scoreRow(2L, 1L, "self", "70"),
                    scoreRow(3L, 1L, "self", "95")));
            givenProfile(2L, COUNSELOR, "软工2401");
            givenProfile(3L, COUNSELOR, "软工2402");

            Result<?> r = service.compute(ADMIN, "admin", TERM, "软工2402");

            assertEquals(200, r.getCode());
            assertEquals("已重算 1 名学生的综测总分与排名", r.getMessage());
            assertEquals(1, data(r).get("students"));
            assertEquals(Collections.singletonList("软工2402"), data(r).get("classes"));
            Map<Long, EvalSummary> written = capturedSummaries(1);
            assertEquals("软工2402", written.get(3L).getClassName());
        }

        @Test
        @DisplayName("班级筛选一条都对不上 → 400 并点名班级，不写库")
        void classFilterMatchesNothing() {
            givenIndicators(singleMoral());
            when(scoreMapper.listByTerm(TERM, null))
                    .thenReturn(Collections.singletonList(scoreRow(2L, 1L, "self", "70")));
            givenProfile(2L, COUNSELOR, "软工2401");

            Result<?> r = service.compute(ADMIN, "admin", TERM, "软工2403");

            assertEquals(400, r.getCode());
            assertEquals("该学期（软工2403）还没有得分记录，无法重算", r.getMessage());
            verify(summaryMapper, never()).upsert(any(EvalSummary.class));
        }

        @Test
        @DisplayName("学生没有档案时班级记为「未填班级」，仍然算分（不能因为没建档就丢分）")
        void missingProfileStillComputed() {
            setWeights(1.0, 0.0, 0.0, 0.0, 0.0);
            givenIndicators(singleMoral());
            when(scoreMapper.listByTerm(TERM, null))
                    .thenReturn(Collections.singletonList(scoreRow(2L, 1L, "self", "81")));
            when(profileMapper.findByUserId(2L)).thenReturn(null);

            Result<?> r = service.compute(ADMIN, "admin", TERM, null);

            assertEquals(200, r.getCode());
            EvalSummary written = capturedSummaries(1).get(2L);
            assertEquals("未填班级", written.getClassName());
            assertDecimal("81.00", written.getTotalScore());
            assertEquals("良好", written.getGradeLevel());
        }
    }
}
