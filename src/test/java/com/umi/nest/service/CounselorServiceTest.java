package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.AidApplication;
import com.umi.nest.entity.AwardPunishment;
import com.umi.nest.entity.EvalSummary;
import com.umi.nest.entity.LeaveRequest;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.mapper.AidApplicationMapper;
import com.umi.nest.mapper.AwardPunishmentMapper;
import com.umi.nest.mapper.EvalSummaryMapper;
import com.umi.nest.mapper.LeaveRequestMapper;
import com.umi.nest.mapper.StudentProfileMapper;
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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
 * 辅导员工作台（学工 P7）的契约测试。
 *
 * <p>盯四类缺陷：一、鉴权次序（未登录必须先 401，不能先 403）；二、范围口径
 * （teacher 已分派只看名下、未分派看全校、admin 看全校，未分派学生任何辅导员可看）；
 * 三、聚合与筛选项（待办类型 / 预警级别的白名单、分页钳制、六类预警的构造条件与级别）；
 * 四、格式化文案（date / dateTime / days / 各种中文映射，尤其 {@code str(Object)}
 * 必须同时吃 {@code java.sql.Timestamp} 与 {@code LocalDateTime}）。</p>
 *
 * <p>这些错误都不抛异常，只会在工作台上悄悄看错人、把别人的学生算进自己的待办，
 * 所以每一条都用公开方法的可观察结果钉住。本类不触碰生产代码，也不连库。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CounselorService - 辅导员工作台权限、聚合与文案")
class CounselorServiceTest {

    private static final Long ADMIN = 1L;
    /** 已分派学生的辅导员 */
    private static final Long TEACHER = 3L;
    /** 没有分派学生的辅导员（也是「别人」） */
    private static final Long OTHER_TEACHER = 9L;
    /** 学生 users.id */
    private static final Long STUDENT_USER = 7L;
    private static final String TERM = "2026-2027-1";
    private static final int SCAN = 60;

    @Mock
    private StudentProfileMapper profileMapper;

    @Mock
    private LeaveRequestMapper leaveMapper;

    @Mock
    private AwardPunishmentMapper awardMapper;

    @Mock
    private AidApplicationMapper aidMapper;

    @Mock
    private EvalSummaryMapper summaryMapper;

    @InjectMocks
    private CounselorService service;

    // ------------------------------------------------------------------ 夹具

    private StudentProfile profile(Long userId, Long counselorId) {
        StudentProfile p = new StudentProfile();
        p.setId(userId + 100);
        p.setUserId(userId);
        p.setName("张三");
        p.setUsername("zhangsan");
        p.setStudentNo("2024" + userId);
        p.setClassName("软工2401");
        p.setCounselorId(counselorId);
        p.setStatus("active");
        return p;
    }

    private LeaveRequest leave(Long id, Long studentId) {
        LeaveRequest lr = new LeaveRequest();
        lr.setId(id);
        lr.setStudentId(studentId);
        lr.setStudentName("张三");
        lr.setStudentNo("2024001");
        lr.setClassName("软工2401");
        lr.setType("personal");
        lr.setStatus("pending");
        lr.setDays(new BigDecimal("2.50"));
        lr.setReason("回家办事");
        lr.setStartAt(LocalDateTime.of(2026, 1, 10, 9, 0));
        lr.setEndAt(LocalDateTime.of(2026, 1, 12, 18, 0));
        lr.setCreatedAt(LocalDateTime.of(2026, 1, 5, 9, 30));
        return lr;
    }

    private AidApplication aid(Long id, Long studentId) {
        AidApplication a = new AidApplication();
        a.setId(id);
        a.setStudentId(studentId);
        a.setStudentName("张三");
        a.setStudentNo("2024001");
        a.setClassName("软工2401");
        a.setAidType("need");
        a.setAcademicYear("2025-2026");
        a.setStatus("pending");
        a.setAmount(new BigDecimal("1000.00"));
        a.setReason("家庭困难");
        a.setCreatedAt(LocalDateTime.of(2026, 1, 6, 10, 0));
        return a;
    }

    private AwardPunishment award(Long id, String kind, Long studentId) {
        AwardPunishment ap = new AwardPunishment();
        ap.setId(id);
        ap.setStudentId(studentId);
        ap.setKind(kind);
        ap.setLevel("school");
        ap.setTitle("三好学生");
        ap.setAmount(new BigDecimal("500.00"));
        ap.setGrantedOn(LocalDate.of(2025, 12, 31));
        return ap;
    }

    private EvalSummary summary(Long studentId, String term, String score, Integer rank, String grade) {
        EvalSummary s = new EvalSummary();
        s.setId(1L);
        s.setStudentId(studentId);
        s.setAcademicTerm(term);
        s.setTotalScore(score == null ? null : new BigDecimal(score));
        s.setRankInClass(rank);
        s.setGradeLevel(grade);
        return s;
    }

    private Map<String, Object> row(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    private Map<String, Object> countRow(String key, String value, int cnt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        m.put("cnt", cnt);
        return m;
    }

    // ------------------------------------------------------------------ 断言工具

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Result<?> r) {
        return (Map<String, Object>) r.getData();
    }

    /** 断言 200 并把 data 取出来 */
    private Map<String, Object> ok(Result<?> r) {
        assertEquals(200, r.getCode(), "期望 200，实际 " + r.getCode() + " / " + r.getMessage());
        return data(r);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("items");
    }

    /** 取 data 里某个列表字段（classCounts / recentLeaves / recentAwards / recentAids） */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rowsOf(Map<String, Object> data, String key) {
        return (List<Map<String, Object>>) data.get(key);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    /** 与 CounselorService.currentTerm() 同口径（9 月起当年-次年-1；2—7 月去年-今年-2） */
    private static String currentTerm() {
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

    /** 与 CounselorService.recentTerms() 同口径：从 from 往回 count 个学期（含 from） */
    private static List<String> recentTerms(String from, int count) {
        List<String> out = new ArrayList<>();
        String cursor = from;
        for (int i = 0; i < count; i++) {
            out.add(cursor);
            int start = Integer.parseInt(cursor.substring(0, 4));
            int part = Integer.parseInt(cursor.substring(10));
            if (part == 2) {
                cursor = start + "-" + (start + 1) + "-1";
            } else {
                cursor = (start - 1) + "-" + start + "-2";
            }
        }
        return out;
    }

    /** 六类预警各造一条：high 2 / medium 3 / low 1 */
    private void stubFullAlertSet(String role) {
        when(profileMapper.list(any(), any(), any(), eq("suspended"), eq(0), eq(SCAN)))
                .thenReturn(Collections.singletonList(profile(11L, TEACHER)));
        when(leaveMapper.listOverdue(any(), any(), eq(0), eq(SCAN)))
                .thenReturn(Collections.singletonList(leave(100L, 11L)));
        Map<String, Object> frequent = new LinkedHashMap<>();
        frequent.put("student_id", 12);
        frequent.put("student_name", "李四");
        frequent.put("student_no", "2024002");
        frequent.put("class_name", "软工2402");
        frequent.put("cnt", 4);
        frequent.put("last_created", Timestamp.valueOf(LocalDateTime.of(2025, 12, 20, 9, 15)));
        when(leaveMapper.listFrequentStudents(any(), any(), eq(3))).thenReturn(Collections.singletonList(frequent));
        when(profileMapper.listMissingEval(any(), any(), eq(0), eq(SCAN)))
                .thenReturn(Collections.singletonList(profile(13L, TEACHER)));
        AidApplication stale = aid(200L, 13L);
        stale.setCreatedAt(LocalDateTime.now().minusDays(10));
        when(aidMapper.listInbox(eq("pending"), any(), any(), any(), eq(0), eq(SCAN)))
                .thenReturn(Collections.singletonList(stale));
        when(profileMapper.listMissingProfile(eq(0), eq(SCAN)))
                .thenReturn(Collections.singletonList(profile(14L, TEACHER)));
    }

    // ================================================================== 鉴权

    @Nested
    @DisplayName("鉴权次序：未登录先 401，再判角色 403")
    class Auth {

        @Test
        @DisplayName("overview：actorId 为 null 时返 401 未登录")
        void overviewUnauthenticated() {
            Result<?> r = service.overview(null, null, null);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
        }

        @Test
        @DisplayName("overview：未登录先于角色判断（actorId null + 学生角色仍是 401）")
        void overviewUnauthenticatedBeforeRole() {
            Result<?> r = service.overview(null, "student", null);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
        }

        @Test
        @DisplayName("overview：学生角色返 403 无权限查看辅导员工作台")
        void overviewStudentForbidden() {
            Result<?> r = service.overview(STUDENT_USER, "student", null);
            assertEquals(403, r.getCode());
            assertEquals("无权限查看辅导员工作台", r.getMessage());
        }

        @Test
        @DisplayName("overview：角色为 null 也返 403")
        void overviewNullRoleForbidden() {
            Result<?> r = service.overview(ADMIN, null, null);
            assertEquals(403, r.getCode());
            assertEquals("无权限查看辅导员工作台", r.getMessage());
        }

        @Test
        @DisplayName("overview：401/403 时一次库都不查")
        void overviewNoMapperCallWhenDenied() {
            service.overview(null, null, null);
            service.overview(STUDENT_USER, "student", null);
            verifyNoInteractions(profileMapper, leaveMapper, awardMapper, aidMapper, summaryMapper);
        }

        @Test
        @DisplayName("todo：actorId 为 null 时返 401")
        void todoUnauthenticated() {
            Result<?> r = service.todo(null, null, null, null, 1, 20);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
        }

        @Test
        @DisplayName("todo：学生角色返 403")
        void todoStudentForbidden() {
            Result<?> r = service.todo(STUDENT_USER, "student", "all", null, 1, 20);
            assertEquals(403, r.getCode());
            assertEquals("无权限查看辅导员工作台", r.getMessage());
        }

        @Test
        @DisplayName("alerts：actorId 为 null 时返 401")
        void alertsUnauthenticated() {
            Result<?> r = service.alerts(null, null, null, null, 1, 20);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
        }

        @Test
        @DisplayName("alerts：学生角色返 403")
        void alertsStudentForbidden() {
            Result<?> r = service.alerts(STUDENT_USER, "student", null, null, 1, 20);
            assertEquals(403, r.getCode());
            assertEquals("无权限查看辅导员工作台", r.getMessage());
        }

        @Test
        @DisplayName("portrait：actorId 为 null 时返 401")
        void portraitUnauthenticated() {
            Result<?> r = service.portrait(null, null, STUDENT_USER);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
        }

        @Test
        @DisplayName("portrait：学生角色返 403 无权限查看学生画像")
        void portraitStudentForbidden() {
            Result<?> r = service.portrait(STUDENT_USER, "student", STUDENT_USER);
            assertEquals(403, r.getCode());
            assertEquals("无权限查看学生画像", r.getMessage());
        }

        @Test
        @DisplayName("portrait：角色先于 studentId 判空（学生角色 + studentId null 仍是 403）")
        void portraitRoleBeforeStudentId() {
            Result<?> r = service.portrait(STUDENT_USER, "student", null);
            assertEquals(403, r.getCode());
            assertEquals("无权限查看学生画像", r.getMessage());
        }
    }

    // ================================================================== 概览

    @Nested
    @DisplayName("overview：范围口径、学期回退与聚合字段")
    class Overview {

        @Test
        @DisplayName("admin 看全校：scope=all，给 Mapper 传 null")
        void adminScopeAll() {
            Map<String, Object> d = ok(service.overview(ADMIN, "admin", TERM));
            assertEquals("all", d.get("scope"));
            verify(profileMapper).count(isNull(), isNull(), isNull(), eq("active"));
            verify(profileMapper, never()).countByCounselor(anyLong());
        }

        @Test
        @DisplayName("teacher 已分派（countByCounselor>0）看名下：scope=mine 且传本人 id")
        void teacherAssignedScopeMine() {
            when(profileMapper.countByCounselor(TEACHER)).thenReturn(2);
            Map<String, Object> d = ok(service.overview(TEACHER, "teacher", TERM));
            assertEquals("mine", d.get("scope"));
            verify(profileMapper).count(eq(TEACHER), isNull(), isNull(), eq("active"));
        }

        @Test
        @DisplayName("teacher 未分派（countByCounselor=0）降级看全校：scope=all 且传 null")
        void teacherUnassignedScopeAll() {
            when(profileMapper.countByCounselor(OTHER_TEACHER)).thenReturn(0);
            Map<String, Object> d = ok(service.overview(OTHER_TEACHER, "teacher", TERM));
            assertEquals("all", d.get("scope"));
            verify(profileMapper).count(isNull(), isNull(), isNull(), eq("active"));
        }

        @Test
        @DisplayName("term 为空时回落到当前学期")
        void termBlankFallsBack() {
            Map<String, Object> d = ok(service.overview(ADMIN, "admin", "  "));
            assertEquals(currentTerm(), d.get("term"));
            verify(summaryMapper).countByTerm(eq(currentTerm()), isNull(), isNull());
        }

        @Test
        @DisplayName("term 为 null 时回落到当前学期")
        void termNullFallsBack() {
            Map<String, Object> d = ok(service.overview(ADMIN, "admin", null));
            assertEquals(currentTerm(), d.get("term"));
        }

        @Test
        @DisplayName("term 非法（不符合 yyyy-yyyy-N）时回落到当前学期")
        void termIllegalFallsBack() {
            Map<String, Object> d = ok(service.overview(ADMIN, "admin", "bogus"));
            assertEquals(currentTerm(), d.get("term"));
        }

        @Test
        @DisplayName("term 形似但序号为 3 时也回落到当前学期")
        void termBadPartFallsBack() {
            Map<String, Object> d = ok(service.overview(ADMIN, "admin", "2025-2026-3"));
            assertEquals(currentTerm(), d.get("term"));
        }

        @Test
        @DisplayName("term 合法时原样使用，并 trim 两侧空格")
        void termLegalKept() {
            Map<String, Object> d = ok(service.overview(ADMIN, "admin", " 2025-2026-1 "));
            assertEquals("2025-2026-1", d.get("term"));
            verify(summaryMapper).countByTerm(eq("2025-2026-1"), isNull(), isNull());
        }

        @Test
        @DisplayName("学籍状态计数：预置四种状态、按 cnt 汇总并给出 total")
        void statusCounts() {
            when(profileMapper.countGroupByStatus(isNull())).thenReturn(Arrays.asList(
                    countRow("status", "active", 120),
                    countRow("status", "suspended", 3),
                    countRow("status", "quit", 1)));
            Map<String, Object> sc = map(ok(service.overview(ADMIN, "admin", TERM)).get("statusCounts"));
            assertEquals(120, sc.get("active"));
            assertEquals(3, sc.get("suspended"));
            assertEquals(1, sc.get("quit"));
            assertEquals(0, sc.get("graduated"));
            assertEquals(124, sc.get("total"));
        }

        @Test
        @DisplayName("statusCounts 不吞白名单之外的状态行")
        void statusCountsUnknownKey() {
            when(profileMapper.countGroupByStatus(isNull()))
                    .thenReturn(Collections.singletonList(countRow("status", "banana", 5)));
            Map<String, Object> sc = map(ok(service.overview(ADMIN, "admin", TERM)).get("statusCounts"));
            assertEquals(5, sc.get("banana"));
            assertEquals(5, sc.get("total"));
        }

        @Test
        @DisplayName("班级分布：classCounts 取 class_name/cnt，classes 是班级下拉")
        void classCounts() {
            when(profileMapper.countGroupByClass(isNull()))
                    .thenReturn(Collections.singletonList(countRow("class_name", "软工2401", 40)));
            when(profileMapper.listClassNames(isNull())).thenReturn(Arrays.asList("软工2401", "软工2402"));
            Map<String, Object> d = ok(service.overview(ADMIN, "admin", TERM));
            List<Map<String, Object>> rows = rowsOf(d, "classCounts");
            assertEquals(1, rows.size());
            assertEquals("软工2401", rows.get(0).get("className"));
            assertEquals(40, rows.get(0).get("count"));
            assertEquals(Arrays.asList("软工2401", "软工2402"), d.get("classes"));
        }

        @Test
        @DisplayName("总数与分布字段：students/pendingLeaves/pendingAids/evalScored/missingEval 都进 data")
        void overviewNumbers() {
            when(profileMapper.count(any(), any(), any(), any())).thenReturn(120);
            when(leaveMapper.countInbox(any(), eq("pending"), any())).thenReturn(4);
            when(aidMapper.countInbox(eq("pending"), any(), any(), any())).thenReturn(2);
            when(summaryMapper.countByTerm(any(), any(), any())).thenReturn(100);
            when(profileMapper.countMissingEval(any(), any())).thenReturn(20);
            when(profileMapper.countMissingProfile()).thenReturn(6);
            when(leaveMapper.countOverdue(any(), any())).thenReturn(3);
            Map<String, Object> d = ok(service.overview(ADMIN, "admin", TERM));
            assertEquals(120, d.get("students"));
            assertEquals(4, d.get("pendingLeaves"));
            assertEquals(2, d.get("pendingAids"));
            assertEquals(100, d.get("evalScored"));
            assertEquals(20, d.get("missingEval"));
            assertEquals(6, d.get("missingProfiles"));
            assertEquals(3, d.get("overdueLeaves"));
        }

        @Test
        @DisplayName("todoTotal = 待审假条 + 待审资助 + 综测未出分")
        void todoTotal() {
            when(leaveMapper.countInbox(any(), eq("pending"), any())).thenReturn(4);
            when(aidMapper.countInbox(eq("pending"), any(), any(), any())).thenReturn(2);
            when(profileMapper.countMissingEval(any(), any())).thenReturn(20);
            Map<String, Object> d = ok(service.overview(ADMIN, "admin", TERM));
            assertEquals(26, d.get("todoTotal"));
        }

        @Test
        @DisplayName("审批状态分布：假条五态、资助四态各自补齐并给出 total")
        void statusStats() {
            when(leaveMapper.countGroupByStatus(any())).thenReturn(Arrays.asList(
                    countRow("status", "pending", 4), countRow("status", "approved", 10)));
            when(aidMapper.countGroupByStatus(any(), any())).thenReturn(Arrays.asList(
                    countRow("status", "pending", 2), countRow("status", "rejected", 1)));
            Map<String, Object> d = ok(service.overview(ADMIN, "admin", TERM));
            Map<String, Object> leave = map(d.get("leaveStats"));
            assertEquals(4, leave.get("pending"));
            assertEquals(10, leave.get("approved"));
            assertEquals(0, leave.get("closed"));
            assertEquals(14, leave.get("total"));
            Map<String, Object> aid = map(d.get("aidStats"));
            assertEquals(2, aid.get("pending"));
            assertEquals(1, aid.get("rejected"));
            assertEquals(0, aid.get("cancelled"));
            assertEquals(3, aid.get("total"));
        }

        @Test
        @DisplayName("预警合计：alertTotal 与 alertCounts 的三档 + total")
        void alertCounts() {
            when(profileMapper.list(any(), any(), any(), eq("suspended"), eq(0), eq(SCAN)))
                    .thenReturn(Collections.singletonList(profile(11L, TEACHER)));
            Map<String, Object> d = ok(service.overview(ADMIN, "admin", TERM));
            assertEquals(1, d.get("alertTotal"));
            Map<String, Object> counts = map(d.get("alertCounts"));
            assertEquals(1, counts.get("high"));
            assertEquals(0, counts.get("medium"));
            assertEquals(0, counts.get("low"));
            assertEquals(1, counts.get("total"));
        }

        @Test
        @DisplayName("generatedAt 是 yyyy-MM-dd HH:mm")
        void generatedAtFormat() {
            Map<String, Object> d = ok(service.overview(ADMIN, "admin", TERM));
            String at = String.valueOf(d.get("generatedAt"));
            assertTrue(at.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"), "实际：" + at);
        }

        @Test
        @DisplayName("空库（Mapper 全空）时不抛异常且计数为 0")
        void emptyDatabase() {
            Map<String, Object> d = ok(service.overview(ADMIN, "admin", TERM));
            assertEquals(0, d.get("students"));
            assertEquals(0, d.get("todoTotal"));
            assertEquals(0, d.get("alertTotal"));
            assertEquals(0, map(d.get("alertCounts")).get("total"));
        }

        @Test
        @DisplayName("分组统计返回 null 时不抛异常")
        void nullGroupRows() {
            when(profileMapper.countGroupByStatus(any())).thenReturn(null);
            when(profileMapper.countGroupByClass(any())).thenReturn(null);
            when(leaveMapper.countGroupByStatus(any())).thenReturn(null);
            when(aidMapper.countGroupByStatus(any(), any())).thenReturn(null);
            Map<String, Object> d = ok(service.overview(ADMIN, "admin", TERM));
            assertEquals(0, map(d.get("statusCounts")).get("total"));
            assertEquals(0, rowsOf(d, "classCounts").size());
            assertEquals(0, map(d.get("leaveStats")).get("total"));
            assertEquals(0, map(d.get("aidStats")).get("total"));
        }
    }

    // ================================================================== 待办

    @Nested
    @DisplayName("todo：类型白名单、分页钳制与合并排序")
    class Todo {

        @Test
        @DisplayName("type=bogus 返 400 待办类型不正确")
        void badType() {
            Result<?> r = service.todo(ADMIN, "admin", "bogus", TERM, 1, 20);
            assertEquals(400, r.getCode());
            assertEquals("待办类型不正确（可选：all / leave / aid / eval）", r.getMessage());
        }

        @Test
        @DisplayName("type 大小写敏感：LEAVE 返 400")
        void typeCaseSensitive() {
            Result<?> r = service.todo(ADMIN, "admin", "LEAVE", TERM, 1, 20);
            assertEquals(400, r.getCode());
            assertEquals("待办类型不正确（可选：all / leave / aid / eval）", r.getMessage());
        }

        @Test
        @DisplayName("type=ALl 之类混合大小写也返 400")
        void typeMixedCase() {
            assertEquals(400, service.todo(ADMIN, "admin", "ALl", TERM, 1, 20).getCode());
        }

        @Test
        @DisplayName("type 为空串视为 all")
        void typeBlankIsAll() {
            Map<String, Object> d = ok(service.todo(ADMIN, "admin", "  ", TERM, 1, 20));
            assertEquals("all", d.get("type"));
        }

        @Test
        @DisplayName("type 为 null 视为 all")
        void typeNullIsAll() {
            Map<String, Object> d = ok(service.todo(ADMIN, "admin", null, TERM, 1, 20));
            assertEquals("all", d.get("type"));
        }

        @Test
        @DisplayName("type 两侧空格被 trim 后合法")
        void typeTrimmed() {
            Map<String, Object> d = ok(service.todo(ADMIN, "admin", " leave ", TERM, 1, 20));
            assertEquals("leave", d.get("type"));
        }

        @Test
        @DisplayName("type 非法时不查库")
        void badTypeNoQuery() {
            service.todo(ADMIN, "admin", "bogus", TERM, 1, 20);
            verifyNoInteractions(profileMapper, leaveMapper, awardMapper, aidMapper, summaryMapper);
        }

        @Test
        @DisplayName("分页：page<1 钳到 1")
        void pageClampedLow() {
            Map<String, Object> d = ok(service.todo(ADMIN, "admin", "leave", TERM, 0, 10));
            assertEquals(1, d.get("page"));
            verify(leaveMapper).listInbox(isNull(), eq("pending"), isNull(), eq(0), eq(10));
        }

        @Test
        @DisplayName("分页：page 为负也钳到 1")
        void pageClampedNegative() {
            Map<String, Object> d = ok(service.todo(ADMIN, "admin", "leave", TERM, -5, 10));
            assertEquals(1, d.get("page"));
        }

        @Test
        @DisplayName("分页：size<1 钳到 1")
        void sizeClampedLow() {
            Map<String, Object> d = ok(service.todo(ADMIN, "admin", "leave", TERM, 1, 0));
            assertEquals(1, d.get("size"));
            verify(leaveMapper).listInbox(isNull(), eq("pending"), isNull(), eq(0), eq(1));
        }

        @Test
        @DisplayName("分页：size>100 钳到 100")
        void sizeClampedHigh() {
            Map<String, Object> d = ok(service.todo(ADMIN, "admin", "leave", TERM, 1, 101));
            assertEquals(100, d.get("size"));
            verify(leaveMapper).listInbox(isNull(), eq("pending"), isNull(), eq(0), eq(100));
        }

        @Test
        @DisplayName("分页：size=100 保持 100（上界包含）")
        void sizeAtBound() {
            Map<String, Object> d = ok(service.todo(ADMIN, "admin", "leave", TERM, 1, 100));
            assertEquals(100, d.get("size"));
        }

        @Test
        @DisplayName("分页：offset = (page-1)*size")
        void offsetComputed() {
            ok(service.todo(ADMIN, "admin", "leave", TERM, 3, 10));
            verify(leaveMapper).listInbox(isNull(), eq("pending"), isNull(), eq(20), eq(10));
        }

        @Test
        @DisplayName("pages 按 total/size 向上取整")
        void pagesComputed() {
            when(leaveMapper.countInbox(any(), eq("pending"), any())).thenReturn(21);
            Map<String, Object> d = ok(service.todo(ADMIN, "admin", "leave", TERM, 1, 10));
            assertEquals(21, d.get("total"));
            assertEquals(3, d.get("pages"));
        }

        @Test
        @DisplayName("type=leave 走假条收件箱分页查询，total 是假条待审数")
        void typeLeave() {
            when(leaveMapper.countInbox(any(), eq("pending"), any())).thenReturn(5);
            when(leaveMapper.listInbox(any(), eq("pending"), any(), eq(0), eq(20)))
                    .thenReturn(Collections.singletonList(leave(100L, STUDENT_USER)));
            Map<String, Object> d = ok(service.todo(ADMIN, "admin", "leave", TERM, 1, 20));
            assertEquals(5, d.get("total"));
            assertEquals(1, items(d).size());
            assertEquals("leave", items(d).get(0).get("type"));
        }

        @Test
        @DisplayName("type=aid 走资助收件箱分页查询，total 是资助待审数")
        void typeAid() {
            when(aidMapper.countInbox(eq("pending"), any(), any(), any())).thenReturn(3);
            when(aidMapper.listInbox(eq("pending"), any(), any(), any(), eq(0), eq(20)))
                    .thenReturn(Collections.singletonList(aid(200L, STUDENT_USER)));
            Map<String, Object> d = ok(service.todo(ADMIN, "admin", "aid", TERM, 1, 20));
            assertEquals(3, d.get("total"));
            assertEquals(1, items(d).size());
            assertEquals("aid", items(d).get(0).get("type"));
        }

        @Test
        @DisplayName("type=eval 走综测未出分分页查询，total 是未出分数")
        void typeEval() {
            when(profileMapper.countMissingEval(any(), any())).thenReturn(8);
            when(profileMapper.listMissingEval(any(), any(), eq(0), eq(20)))
                    .thenReturn(Collections.singletonList(profile(13L, TEACHER)));
            Map<String, Object> d = ok(service.todo(ADMIN, "admin", "eval", TERM, 1, 20));
            assertEquals(8, d.get("total"));
            assertEquals(1, items(d).size());
            assertEquals("eval", items(d).get(0).get("type"));
        }

        @Test
        @DisplayName("type=all 总数是三类待办之和")
        void allTotal() {
            when(leaveMapper.countInbox(any(), eq("pending"), any())).thenReturn(4);
            when(aidMapper.countInbox(eq("pending"), any(), any(), any())).thenReturn(2);
            when(profileMapper.countMissingEval(any(), any())).thenReturn(20);
            Map<String, Object> d = ok(service.todo(ADMIN, "admin", "all", TERM, 1, 20));
            assertEquals(26, d.get("total"));
            Map<String, Object> counts = map(d.get("counts"));
            assertEquals(4, counts.get("leave"));
            assertEquals(2, counts.get("aid"));
            assertEquals(20, counts.get("eval"));
            assertEquals(26, counts.get("total"));
        }

        @Test
        @DisplayName("type=all 三类各取第一页（offset 恒为 0，不受 page 影响）")
        void allTakesFirstPageOfEach() {
            ok(service.todo(ADMIN, "admin", "all", TERM, 3, 10));
            verify(leaveMapper).listInbox(isNull(), eq("pending"), isNull(), eq(0), eq(10));
            verify(aidMapper).listInbox(eq("pending"), isNull(), isNull(), isNull(), eq(0), eq(10));
            verify(profileMapper).listMissingEval(eq(TERM), isNull(), eq(0), eq(10));
        }

        @Test
        @DisplayName("type=all 合并后按 at 倒序")
        void allSortedByAtDesc() {
            LeaveRequest lr = leave(100L, STUDENT_USER);
            lr.setCreatedAt(LocalDateTime.of(2026, 1, 10, 9, 0));
            AidApplication a = aid(200L, STUDENT_USER);
            a.setCreatedAt(LocalDateTime.of(2026, 1, 12, 8, 0));
            when(leaveMapper.listInbox(any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(lr));
            when(aidMapper.listInbox(any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(a));
            when(profileMapper.listMissingEval(any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(profile(13L, TEACHER)));
            Map<String, Object> d = ok(service.todo(ADMIN, "admin", "all", TERM, 1, 20));
            List<Map<String, Object>> list = items(d);
            assertEquals(3, list.size());
            assertEquals("aid", list.get(0).get("type"));
            assertEquals("leave", list.get(1).get("type"));
        }

        @Test
        @DisplayName("type=all 中 at 为空串的综测条目排在最后")
        void allBlankAtLast() {
            LeaveRequest lr = leave(100L, STUDENT_USER);
            lr.setCreatedAt(LocalDateTime.of(2026, 1, 10, 9, 0));
            when(leaveMapper.listInbox(any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(lr));
            when(profileMapper.listMissingEval(any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(profile(13L, TEACHER)));
            Map<String, Object> d = ok(service.todo(ADMIN, "admin", "all", TERM, 1, 20));
            List<Map<String, Object>> list = items(d);
            assertEquals("", list.get(list.size() - 1).get("at"));
            assertEquals("eval", list.get(list.size() - 1).get("type"));
        }

        @Test
        @DisplayName("type=all 合并后条目数超过 size 时截断到 size")
        void allTruncatedToSize() {
            when(leaveMapper.listInbox(any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(leave(100L, STUDENT_USER)));
            when(aidMapper.listInbox(any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(aid(200L, STUDENT_USER)));
            when(profileMapper.listMissingEval(any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(profile(13L, TEACHER)));
            Map<String, Object> d = ok(service.todo(ADMIN, "admin", "all", TERM, 1, 2));
            assertEquals(2, items(d).size());
        }

        @Test
        @DisplayName("假条待办条目：字段齐全，含 typeText=请假 与审核链接")
        void leaveItemFields() {
            when(leaveMapper.listInbox(any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(leave(100L, STUDENT_USER)));
            Map<String, Object> item = items(ok(service.todo(ADMIN, "admin", "leave", TERM, 1, 20))).get(0);
            assertEquals("leave", item.get("type"));
            assertEquals("请假", item.get("typeText"));
            assertEquals(100L, item.get("id"));
            assertEquals(STUDENT_USER, item.get("studentId"));
            assertEquals("张三", item.get("studentName"));
            assertEquals("2024001", item.get("studentNo"));
            assertEquals("软工2401", item.get("className"));
            assertEquals("事假 · 2.5 天", item.get("title"));
            assertTrue(String.valueOf(item.get("detail")).startsWith("2026-01-10 至 2026-01-12"));
            assertEquals("2026-01-05 09:30", item.get("at"));
            assertEquals("leave-review.html?keyword=2024001", item.get("url"));
        }

        @Test
        @DisplayName("资助待办条目：字段齐全，含 typeText=资助 与中文项目名")
        void aidItemFields() {
            when(aidMapper.listInbox(any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(aid(200L, STUDENT_USER)));
            Map<String, Object> item = items(ok(service.todo(ADMIN, "admin", "aid", TERM, 1, 20))).get(0);
            assertEquals("aid", item.get("type"));
            assertEquals("资助", item.get("typeText"));
            assertEquals(200L, item.get("id"));
            assertEquals(STUDENT_USER, item.get("studentId"));
            assertEquals("困难认定 · 2025-2026", item.get("title"));
            assertEquals("家庭困难", item.get("detail"));
            assertEquals("2026-01-06 10:00", item.get("at"));
            assertEquals("aid-review.html?keyword=2024001", item.get("url"));
        }

        @Test
        @DisplayName("资助待办：未知项目类型回落为「资助项目」")
        void aidItemUnknownType() {
            AidApplication a = aid(200L, STUDENT_USER);
            a.setAidType("banana");
            when(aidMapper.listInbox(any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(a));
            Map<String, Object> item = items(ok(service.todo(ADMIN, "admin", "aid", TERM, 1, 20))).get(0);
            assertEquals("资助项目 · 2025-2026", item.get("title"));
        }

        @Test
        @DisplayName("综测待办条目：at 为空串、typeText=综测、id 用 userId、链接带学期")
        void evalItemFields() {
            StudentProfile sp = profile(13L, TEACHER);
            sp.setName(null);
            when(profileMapper.listMissingEval(any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(sp));
            Map<String, Object> item = items(ok(service.todo(ADMIN, "admin", "eval", TERM, 1, 20))).get(0);
            assertEquals("eval", item.get("type"));
            assertEquals("综测", item.get("typeText"));
            assertEquals(13L, item.get("id"));
            assertEquals(13L, item.get("studentId"));
            assertEquals("zhangsan", item.get("studentName"));
            assertEquals("2026-2027-1 综测未出分", item.get("title"));
            assertEquals("", item.get("at"));
            assertEquals("eval-review.html?term=2026-2027-1", item.get("url"));
        }

        @Test
        @DisplayName("data 里带 term/type/scope")
        void metaFields() {
            when(profileMapper.countByCounselor(TEACHER)).thenReturn(1);
            Map<String, Object> d = ok(service.todo(TEACHER, "teacher", "leave", "2025-2026-1", 1, 20));
            assertEquals("2025-2026-1", d.get("term"));
            assertEquals("leave", d.get("type"));
            assertEquals("mine", d.get("scope"));
        }

        @Test
        @DisplayName("空数据时 items 为空、total 为 0、pages 为 0")
        void emptyResult() {
            Map<String, Object> d = ok(service.todo(ADMIN, "admin", "all", TERM, 1, 20));
            assertEquals(0, items(d).size());
            assertEquals(0, d.get("total"));
            assertEquals(0, d.get("pages"));
        }
    }

    // ================================================================== 预警

    @Nested
    @DisplayName("alerts：级别白名单、六类预警构造与计数")
    class Alerts {

        @Test
        @DisplayName("level 为空 = 全部，data.level 回落为 all")
        void levelBlankIsAll() {
            Map<String, Object> d = ok(service.alerts(ADMIN, "admin", " ", TERM, 1, 20));
            assertEquals("all", d.get("level"));
        }

        @Test
        @DisplayName("level 为 null = 全部")
        void levelNullIsAll() {
            Map<String, Object> d = ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20));
            assertEquals("all", d.get("level"));
        }

        @Test
        @DisplayName("level=high 只留 high")
        void levelHigh() {
            stubFullAlertSet("admin");
            Map<String, Object> d = ok(service.alerts(ADMIN, "admin", "high", TERM, 1, 20));
            assertEquals("high", d.get("level"));
            assertEquals(2, d.get("total"));
            for (Map<String, Object> a : items(d)) {
                assertEquals("high", a.get("level"));
            }
        }

        @Test
        @DisplayName("level=medium 只留 medium")
        void levelMedium() {
            stubFullAlertSet("admin");
            Map<String, Object> d = ok(service.alerts(ADMIN, "admin", "medium", TERM, 1, 20));
            assertEquals(3, d.get("total"));
            for (Map<String, Object> a : items(d)) {
                assertEquals("medium", a.get("level"));
            }
        }

        @Test
        @DisplayName("level=low 只留 low")
        void levelLow() {
            stubFullAlertSet("admin");
            Map<String, Object> d = ok(service.alerts(ADMIN, "admin", "low", TERM, 1, 20));
            assertEquals(1, d.get("total"));
            assertEquals("profile_missing", items(d).get(0).get("type"));
        }

        @Test
        @DisplayName("level=all 不是合法级别，返 400")
        void levelAllRejected() {
            Result<?> r = service.alerts(ADMIN, "admin", "all", TERM, 1, 20);
            assertEquals(400, r.getCode());
            assertEquals("预警级别不正确（可选：high / medium / low）", r.getMessage());
        }

        @Test
        @DisplayName("level=bogus 返 400")
        void levelBogusRejected() {
            Result<?> r = service.alerts(ADMIN, "admin", "bogus", TERM, 1, 20);
            assertEquals(400, r.getCode());
            assertEquals("预警级别不正确（可选：high / medium / low）", r.getMessage());
        }

        @Test
        @DisplayName("level 大小写敏感：HIGH 返 400")
        void levelCaseSensitive() {
            assertEquals(400, service.alerts(ADMIN, "admin", "HIGH", TERM, 1, 20).getCode());
        }

        @Test
        @DisplayName("level 非法时不查库")
        void badLevelNoQuery() {
            service.alerts(ADMIN, "admin", "bogus", TERM, 1, 20);
            verifyNoInteractions(profileMapper, leaveMapper, awardMapper, aidMapper, summaryMapper);
        }

        @Test
        @DisplayName("① 学籍异常：休学 → high/status/学籍异常")
        void alertStatusSuspended() {
            when(profileMapper.list(any(), any(), any(), eq("suspended"), eq(0), eq(SCAN)))
                    .thenReturn(Collections.singletonList(profile(11L, TEACHER)));
            Map<String, Object> a = items(ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20))).get(0);
            assertEquals("high", a.get("level"));
            assertEquals("高", a.get("levelText"));
            assertEquals("status", a.get("type"));
            assertEquals("学籍异常", a.get("typeText"));
            assertEquals(11L, a.get("studentId"));
            assertEquals("学籍状态：休学", a.get("title"));
            assertTrue(String.valueOf(a.get("detail")).contains("该生档案状态是「休学」"));
            assertEquals("counselor-students.html?keyword=202411", a.get("url"));
        }

        @Test
        @DisplayName("① 学籍异常：退学也进预警，文案为「退学」")
        void alertStatusQuit() {
            when(profileMapper.list(any(), any(), any(), eq("quit"), eq(0), eq(SCAN)))
                    .thenReturn(Collections.singletonList(profile(12L, TEACHER)));
            Map<String, Object> a = items(ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20))).get(0);
            assertEquals("学籍状态：退学", a.get("title"));
        }

        @Test
        @DisplayName("② 逾期未销假：high/leave_overdue，detail 含已过天数")
        void alertLeaveOverdue() {
            LeaveRequest lr = leave(100L, STUDENT_USER);
            lr.setEndAt(LocalDateTime.now().minusDays(3));
            when(leaveMapper.listOverdue(any(), any(), eq(0), eq(SCAN)))
                    .thenReturn(Collections.singletonList(lr));
            Map<String, Object> a = items(ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20))).get(0);
            assertEquals("high", a.get("level"));
            assertEquals("leave_overdue", a.get("type"));
            assertEquals("逾期未销假", a.get("typeText"));
            assertEquals("请假逾期未销假", a.get("title"));
            assertTrue(String.valueOf(a.get("detail")).contains("已过 3 天仍未销假"), "实际：" + a.get("detail"));
            assertEquals("leave-review.html?keyword=2024001", a.get("url"));
        }

        @Test
        @DisplayName("③ 请假频繁：medium/leave_frequent，标题带窗口天数与次数")
        void alertLeaveFrequent() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("student_id", 12);
            row.put("student_name", "李四");
            row.put("student_no", "2024002");
            row.put("class_name", "软工2402");
            row.put("cnt", 4);
            row.put("last_created", Timestamp.valueOf(LocalDateTime.of(2025, 12, 20, 9, 15)));
            when(leaveMapper.listFrequentStudents(any(), any(), eq(3))).thenReturn(Collections.singletonList(row));
            Map<String, Object> a = items(ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20))).get(0);
            assertEquals("medium", a.get("level"));
            assertEquals("leave_frequent", a.get("type"));
            assertEquals("请假频繁", a.get("typeText"));
            assertEquals("近 30 天请假 4 次", a.get("title"));
            assertTrue(String.valueOf(a.get("detail")).contains("最近一次 2025-12-20 09:15"), "实际：" + a.get("detail"));
            assertEquals("leave-review.html?keyword=2024002", a.get("url"));
        }

        @Test
        @DisplayName("③ 请假频繁：studentId 从 Map 的 student_id 取出")
        void alertLeaveFrequentStudentId() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("student_id", 42L);
            row.put("cnt", "5");
            when(leaveMapper.listFrequentStudents(any(), any(), eq(3))).thenReturn(Collections.singletonList(row));
            Map<String, Object> a = items(ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20))).get(0);
            assertEquals(42L, a.get("studentId"));
            assertEquals("近 30 天请假 5 次", a.get("title"));
        }

        @Test
        @DisplayName("③ 请假频繁：since 参数 = now - 30 天，minCount = 3")
        void alertLeaveFrequentWindow() {
            service.alerts(ADMIN, "admin", null, TERM, 1, 20);
            ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(leaveMapper).listFrequentStudents(isNull(), since.capture(), eq(3));
            assertTrue(since.getValue().isBefore(LocalDateTime.now().minusDays(29)),
                    "since 应约为 30 天前，实际 " + since.getValue());
        }

        @Test
        @DisplayName("④ 综测未出分：medium/eval_missing，链接带学期")
        void alertEvalMissing() {
            when(profileMapper.listMissingEval(any(), any(), eq(0), eq(SCAN)))
                    .thenReturn(Collections.singletonList(profile(13L, TEACHER)));
            Map<String, Object> a = items(ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20))).get(0);
            assertEquals("medium", a.get("level"));
            assertEquals("eval_missing", a.get("type"));
            assertEquals("综测未出分", a.get("typeText"));
            assertEquals("2026-2027-1 综测未出分", a.get("title"));
            assertEquals("eval-review.html?term=2026-2027-1", a.get("url"));
        }

        @Test
        @DisplayName("⑤ 资助积压：待审超过 7 天 → medium/aid_stale")
        void alertAidStale() {
            AidApplication a1 = aid(200L, STUDENT_USER);
            a1.setCreatedAt(LocalDateTime.now().minusDays(10));
            when(aidMapper.listInbox(eq("pending"), any(), any(), any(), eq(0), eq(SCAN)))
                    .thenReturn(Collections.singletonList(a1));
            Map<String, Object> a = items(ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20))).get(0);
            assertEquals("medium", a.get("level"));
            assertEquals("aid_stale", a.get("type"));
            assertEquals("资助积压", a.get("typeText"));
            assertEquals("资助申请已等待 10 天", a.get("title"));
            assertTrue(String.valueOf(a.get("detail")).contains("困难认定（2025-2026）待审批 10 天未处理"),
                    "实际：" + a.get("detail"));
            assertEquals("aid-review.html?keyword=2024001", a.get("url"));
        }

        @Test
        @DisplayName("⑤ 资助积压：未超 7 天的待审不进预警")
        void alertAidNotStale() {
            AidApplication a1 = aid(200L, STUDENT_USER);
            a1.setCreatedAt(LocalDateTime.now().minusDays(2));
            when(aidMapper.listInbox(eq("pending"), any(), any(), any(), eq(0), eq(SCAN)))
                    .thenReturn(Collections.singletonList(a1));
            assertEquals(0, ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20)).get("total"));
        }

        @Test
        @DisplayName("⑤ 资助积压：createdAt 为 null 的待审被跳过")
        void alertAidNullCreatedAt() {
            AidApplication a1 = aid(200L, STUDENT_USER);
            a1.setCreatedAt(null);
            when(aidMapper.listInbox(eq("pending"), any(), any(), any(), eq(0), eq(SCAN)))
                    .thenReturn(Collections.singletonList(a1));
            assertEquals(0, ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20)).get("total"));
        }

        @Test
        @DisplayName("⑥ 档案未建：admin 可见 low/profile_missing")
        void alertProfileMissingForAdmin() {
            when(profileMapper.listMissingProfile(eq(0), eq(SCAN)))
                    .thenReturn(Collections.singletonList(profile(14L, TEACHER)));
            Map<String, Object> a = items(ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20))).get(0);
            assertEquals("low", a.get("level"));
            assertEquals("低", a.get("levelText"));
            assertEquals("profile_missing", a.get("type"));
            assertEquals("档案未建", a.get("typeText"));
            assertEquals("学生档案未建立", a.get("title"));
        }

        @Test
        @DisplayName("⑥ 档案未建：teacher 看不到，且根本不查未建档名单")
        void alertProfileMissingHiddenForTeacher() {
            when(profileMapper.countByCounselor(TEACHER)).thenReturn(1);
            ok(service.alerts(TEACHER, "teacher", null, TERM, 1, 20));
            verify(profileMapper, never()).listMissingProfile(anyInt(), anyInt());
        }

        @Test
        @DisplayName("六类预警的收集顺序固定：学籍异常 → 逾期 → 频繁 → 未出分 → 资助积压 → 档案未建")
        void alertOrder() {
            stubFullAlertSet("admin");
            List<Map<String, Object>> list = items(ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20)));
            assertEquals(6, list.size());
            assertEquals(Arrays.asList("status", "leave_overdue", "leave_frequent", "eval_missing",
                    "aid_stale", "profile_missing"),
                    Arrays.asList(list.get(0).get("type"), list.get(1).get("type"), list.get(2).get("type"),
                            list.get(3).get("type"), list.get(4).get("type"), list.get(5).get("type")));
        }

        @Test
        @DisplayName("counts 是全部预警的分级计数（high 2 / medium 3 / low 1 / total 6）")
        void countsByLevel() {
            stubFullAlertSet("admin");
            Map<String, Object> counts = map(ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20)).get("counts"));
            assertEquals(2, counts.get("high"));
            assertEquals(3, counts.get("medium"));
            assertEquals(1, counts.get("low"));
            assertEquals(6, counts.get("total"));
        }

        @Test
        @DisplayName("按级别过滤时 counts 仍是全量（不受 level 影响）")
        void countsStayFullWhenFiltered() {
            stubFullAlertSet("admin");
            Map<String, Object> counts = map(ok(service.alerts(ADMIN, "admin", "low", TERM, 1, 20)).get("counts"));
            assertEquals(2, counts.get("high"));
            assertEquals(6, counts.get("total"));
        }

        @Test
        @DisplayName("空预警时 counts 三档全 0")
        void countsEmpty() {
            Map<String, Object> counts = map(ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20)).get("counts"));
            assertEquals(0, counts.get("high"));
            assertEquals(0, counts.get("medium"));
            assertEquals(0, counts.get("low"));
            assertEquals(0, counts.get("total"));
        }

        @Test
        @DisplayName("分页：按 size 切片，pages 向上取整")
        void pagination() {
            stubFullAlertSet("admin");
            Map<String, Object> d = ok(service.alerts(ADMIN, "admin", null, TERM, 1, 2));
            assertEquals(2, items(d).size());
            assertEquals(6, d.get("total"));
            assertEquals(1, d.get("page"));
            assertEquals(2, d.get("size"));
            assertEquals(3, d.get("pages"));
        }

        @Test
        @DisplayName("分页：offset 超出总数时 items 为空但 total 保留")
        void paginationOutOfRange() {
            stubFullAlertSet("admin");
            Map<String, Object> d = ok(service.alerts(ADMIN, "admin", null, TERM, 2, 10));
            assertEquals(0, items(d).size());
            assertEquals(6, d.get("total"));
            assertEquals(2, d.get("page"));
        }

        @Test
        @DisplayName("链接关键字清洗：非字母数字字符被剔除")
        void urlSanitized() {
            StudentProfile sp = profile(11L, TEACHER);
            sp.setStudentNo("2024#001");
            when(profileMapper.list(any(), any(), any(), eq("suspended"), eq(0), eq(SCAN)))
                    .thenReturn(Collections.singletonList(sp));
            Map<String, Object> a = items(ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20))).get(0);
            assertEquals("counselor-students.html?keyword=2024001", a.get("url"));
        }

        @Test
        @DisplayName("teacher 已分派时预警按本人范围查")
        void teacherAlertsScoped() {
            when(profileMapper.countByCounselor(TEACHER)).thenReturn(1);
            Map<String, Object> d = ok(service.alerts(TEACHER, "teacher", null, TERM, 1, 20));
            assertEquals("mine", d.get("scope"));
            verify(profileMapper).list(eq(TEACHER), any(), any(), eq("suspended"), eq(0), eq(SCAN));
        }
    }

    // ================================================================== 画像

    @Nested
    @DisplayName("portrait：学生标识、越权与画像数据")
    class Portrait {

        @Test
        @DisplayName("studentId 为 null 返 400 学生标识不正确")
        void studentIdNull() {
            Result<?> r = service.portrait(ADMIN, "admin", null);
            assertEquals(400, r.getCode());
            assertEquals("学生标识不正确", r.getMessage());
        }

        @Test
        @DisplayName("studentId 为 null 时不查档案")
        void studentIdNullNoQuery() {
            service.portrait(ADMIN, "admin", null);
            verifyNoInteractions(profileMapper);
        }

        @Test
        @DisplayName("档案不存在返 404 学生档案不存在")
        void profileNotFound() {
            Result<?> r = service.portrait(ADMIN, "admin", STUDENT_USER);
            assertEquals(404, r.getCode());
            assertEquals("学生档案不存在", r.getMessage());
        }

        @Test
        @DisplayName("teacher 看名下之外的学生返 403 该学生不在你的名下")
        void teacherForbidden() {
            when(profileMapper.countByCounselor(TEACHER)).thenReturn(1);
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, OTHER_TEACHER));
            Result<?> r = service.portrait(TEACHER, "teacher", STUDENT_USER);
            assertEquals(403, r.getCode());
            assertEquals("该学生不在你的名下", r.getMessage());
        }

        @Test
        @DisplayName("teacher 已分派且学生是本人的：放行")
        void teacherOwnStudent() {
            when(profileMapper.countByCounselor(TEACHER)).thenReturn(1);
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            assertEquals(200, service.portrait(TEACHER, "teacher", STUDENT_USER).getCode());
        }

        @Test
        @DisplayName("teacher 未分派（看全校）时可看任意学生")
        void teacherUnassignedSeesAll() {
            when(profileMapper.countByCounselor(OTHER_TEACHER)).thenReturn(0);
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            assertEquals(200, service.portrait(OTHER_TEACHER, "teacher", STUDENT_USER).getCode());
        }

        @Test
        @DisplayName("未分派学生（counselorId 为空）任何辅导员都能看")
        void unassignedStudentVisibleToTeacher() {
            when(profileMapper.countByCounselor(TEACHER)).thenReturn(1);
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, null));
            assertEquals(200, service.portrait(TEACHER, "teacher", STUDENT_USER).getCode());
        }

        @Test
        @DisplayName("admin 可看任意学生")
        void adminSeesAny() {
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, OTHER_TEACHER));
            assertEquals(200, service.portrait(ADMIN, "admin", STUDENT_USER).getCode());
        }

        @Test
        @DisplayName("data 含 profile/term/leaves/awards/aids/eval/三类 recent 列表")
        void dataKeys() {
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            Map<String, Object> d = ok(service.portrait(ADMIN, "admin", STUDENT_USER));
            assertNotNull(d.get("profile"));
            assertEquals(currentTerm(), d.get("term"));
            assertNotNull(d.get("leaves"));
            assertNotNull(d.get("awards"));
            assertNotNull(d.get("aids"));
            assertNotNull(d.get("eval"));
            assertNotNull(d.get("recentLeaves"));
            assertNotNull(d.get("recentAwards"));
            assertNotNull(d.get("recentAids"));
        }

        @Test
        @DisplayName("profile 原样返回")
        void profileReturned() {
            StudentProfile p = profile(STUDENT_USER, TEACHER);
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(p);
            assertEquals(p, ok(service.portrait(ADMIN, "admin", STUDENT_USER)).get("profile"));
        }

        @Test
        @DisplayName("leaves：逐状态 count 并按五态汇总 total")
        void leavesCount() {
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            when(leaveMapper.countByStudent(STUDENT_USER, "pending")).thenReturn(1);
            when(leaveMapper.countByStudent(STUDENT_USER, "approved")).thenReturn(2);
            when(leaveMapper.countByStudent(STUDENT_USER, "rejected")).thenReturn(3);
            when(leaveMapper.countByStudent(STUDENT_USER, "closed")).thenReturn(4);
            when(leaveMapper.countByStudent(STUDENT_USER, "cancelled")).thenReturn(5);
            Map<String, Object> leaves = map(ok(service.portrait(ADMIN, "admin", STUDENT_USER)).get("leaves"));
            assertEquals(1, leaves.get("pending"));
            assertEquals(2, leaves.get("approved"));
            assertEquals(3, leaves.get("rejected"));
            assertEquals(4, leaves.get("closed"));
            assertEquals(5, leaves.get("cancelled"));
            assertEquals(15, leaves.get("total"));
        }

        @Test
        @DisplayName("awards：按 kind 分组填奖励/处分并汇总 total")
        void awardsCount() {
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            when(awardMapper.countGroupByKind(STUDENT_USER, null)).thenReturn(Arrays.asList(
                    countRow("kind", "award", 2), countRow("kind", "punishment", 1)));
            Map<String, Object> awards = map(ok(service.portrait(ADMIN, "admin", STUDENT_USER)).get("awards"));
            assertEquals(2, awards.get("award"));
            assertEquals(1, awards.get("punishment"));
            assertEquals(3, awards.get("total"));
        }

        @Test
        @DisplayName("awards：countGroupByKind 返回 null 时不抛异常且全 0")
        void awardsNullRows() {
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            when(awardMapper.countGroupByKind(STUDENT_USER, null)).thenReturn(null);
            Map<String, Object> awards = map(ok(service.portrait(ADMIN, "admin", STUDENT_USER)).get("awards"));
            assertEquals(0, awards.get("award"));
            assertEquals(0, awards.get("punishment"));
            assertEquals(0, awards.get("total"));
        }

        @Test
        @DisplayName("eval：当前学期有汇总时 current 指向它，history 含各学期")
        void evalCurrentAndHistory() {
            String t = currentTerm();
            List<String> terms = recentTerms(t, 4);
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            when(summaryMapper.findByStudentTerm(STUDENT_USER, terms.get(0)))
                    .thenReturn(summary(STUDENT_USER, terms.get(0), "88.50", 3, "good"));
            when(summaryMapper.findByStudentTerm(STUDENT_USER, terms.get(1)))
                    .thenReturn(summary(STUDENT_USER, terms.get(1), "91.00", 1, "excellent"));
            Map<String, Object> eval = map(ok(service.portrait(ADMIN, "admin", STUDENT_USER)).get("eval"));
            assertEquals(t, eval.get("currentTerm"));
            Map<String, Object> current = map(eval.get("current"));
            assertNotNull(current);
            assertEquals(t, current.get("term"));
            assertEquals("88.50", current.get("totalScore"));
            assertEquals(3, current.get("rankInClass"));
            assertEquals("good", current.get("gradeLevel"));
            assertEquals("良好", current.get("gradeText"));
            assertEquals(2, ((List<?>) eval.get("history")).size());
        }

        @Test
        @DisplayName("eval：查不到汇总的学期被跳过，history 只留有分的")
        void evalHistorySkipsMissing() {
            String t = currentTerm();
            List<String> terms = recentTerms(t, 4);
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            when(summaryMapper.findByStudentTerm(STUDENT_USER, terms.get(2)))
                    .thenReturn(summary(STUDENT_USER, terms.get(2), "70.00", 20, "pass"));
            Map<String, Object> eval = map(ok(service.portrait(ADMIN, "admin", STUDENT_USER)).get("eval"));
            assertEquals(1, ((List<?>) eval.get("history")).size());
            assertNull(eval.get("current"));
        }

        @Test
        @DisplayName("eval：history 按最近 4 个学期（含当前）依次查询")
        void evalTermWindow() {
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            ok(service.portrait(ADMIN, "admin", STUDENT_USER));
            for (String term : recentTerms(currentTerm(), 4)) {
                verify(summaryMapper).findByStudentTerm(STUDENT_USER, term);
            }
            verify(summaryMapper, times(4)).findByStudentTerm(eq(STUDENT_USER), any());
        }

        @Test
        @DisplayName("recentLeaves：字段与中文状态齐全")
        void recentLeaves() {
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            when(leaveMapper.listByStudent(STUDENT_USER, null, 0, 5))
                    .thenReturn(Collections.singletonList(leave(100L, STUDENT_USER)));
            List<Map<String, Object>> list = rowsOf(ok(service.portrait(ADMIN, "admin", STUDENT_USER)), "recentLeaves");
            assertEquals(1, list.size());
            Map<String, Object> item = list.get(0);
            assertEquals(100L, item.get("id"));
            assertEquals("personal", item.get("type"));
            assertEquals("事假", item.get("typeText"));
            assertEquals("2026-01-10", item.get("startAt"));
            assertEquals("2026-01-12", item.get("endAt"));
            assertEquals("2.5", item.get("days"));
            assertEquals("pending", item.get("status"));
            assertEquals("待审批", item.get("statusText"));
        }

        @Test
        @DisplayName("recentAwards：金额、日期格式与奖励/处分中文")
        void recentAwards() {
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            when(awardMapper.list(STUDENT_USER, null, null, null, null, 0, 5))
                    .thenReturn(Arrays.asList(award(1L, "award", STUDENT_USER), award(2L, "punishment", STUDENT_USER)));
            List<Map<String, Object>> list = rowsOf(ok(service.portrait(ADMIN, "admin", STUDENT_USER)), "recentAwards");
            assertEquals(2, list.size());
            assertEquals("奖励", list.get(0).get("kindText"));
            assertEquals("500.00", list.get(0).get("amount"));
            assertEquals("2025-12-31", list.get(0).get("grantedOn"));
            assertEquals("", list.get(0).get("revokedOn"));
            assertEquals("处分", list.get(1).get("kindText"));
        }

        @Test
        @DisplayName("recentAwards：金额为 null 时输出 null")
        void recentAwardsNullAmount() {
            AwardPunishment ap = award(1L, "award", STUDENT_USER);
            ap.setAmount(null);
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            when(awardMapper.list(STUDENT_USER, null, null, null, null, 0, 5))
                    .thenReturn(Collections.singletonList(ap));
            List<Map<String, Object>> list = rowsOf(ok(service.portrait(ADMIN, "admin", STUDENT_USER)), "recentAwards");
            assertNull(list.get(0).get("amount"));
        }

        @Test
        @DisplayName("recentAids：项目中文、金额与状态中文")
        void recentAids() {
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            when(aidMapper.listMine(STUDENT_USER, null, 0, 5))
                    .thenReturn(Collections.singletonList(aid(200L, STUDENT_USER)));
            List<Map<String, Object>> list = rowsOf(ok(service.portrait(ADMIN, "admin", STUDENT_USER)), "recentAids");
            assertEquals(1, list.size());
            Map<String, Object> item = list.get(0);
            assertEquals(200L, item.get("id"));
            assertEquals("need", item.get("aidType"));
            assertEquals("困难认定", item.get("aidTypeText"));
            assertEquals("2025-2026", item.get("academicYear"));
            assertEquals("1000.00", item.get("amount"));
            assertEquals("待审批", item.get("statusText"));
        }

        @Test
        @DisplayName("aids：按学生维度的状态统计（四态 + total）")
        void aidsStat() {
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            when(aidMapper.countGroupByStatus(STUDENT_USER, null)).thenReturn(Arrays.asList(
                    countRow("status", "approved", 2), countRow("status", "pending", 1)));
            Map<String, Object> aids = map(ok(service.portrait(ADMIN, "admin", STUDENT_USER)).get("aids"));
            assertEquals(2, aids.get("approved"));
            assertEquals(1, aids.get("pending"));
            assertEquals(0, aids.get("rejected"));
            assertEquals(0, aids.get("cancelled"));
            assertEquals(3, aids.get("total"));
        }

        @Test
        @DisplayName("查询学生维度数据时一律用 studentId 精确定位")
        void studentScopedQueries() {
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            ok(service.portrait(ADMIN, "admin", STUDENT_USER));
            verify(leaveMapper).countByStudent(STUDENT_USER, "approved");
            verify(awardMapper).countGroupByKind(STUDENT_USER, null);
            verify(leaveMapper).listByStudent(STUDENT_USER, null, 0, 5);
            verify(awardMapper).list(eq(STUDENT_USER), isNull(), isNull(), isNull(), isNull(), eq(0), eq(5));
            verify(aidMapper).listMine(STUDENT_USER, null, 0, 5);
            verify(aidMapper).countGroupByStatus(STUDENT_USER, null);
        }

        @Test
        @DisplayName("空数据的画像不抛异常：空列表 + 全 0 统计")
        void emptyPortrait() {
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            Map<String, Object> d = ok(service.portrait(ADMIN, "admin", STUDENT_USER));
            assertEquals(0, map(d.get("leaves")).get("total"));
            assertEquals(0, map(d.get("awards")).get("total"));
            assertEquals(0, map(d.get("aids")).get("total"));
            assertEquals(0, rowsOf(d, "recentLeaves").size());
        }
    }

    // ================================================================== 文案

    @Nested
    @DisplayName("文案与格式化（经公开方法间接断言）")
    class Text {

        @Test
        @DisplayName("date 输出 yyyy-MM-dd（不带时间）")
        void dateFormat() {
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            when(leaveMapper.listByStudent(STUDENT_USER, null, 0, 5))
                    .thenReturn(Collections.singletonList(leave(100L, STUDENT_USER)));
            Map<String, Object> item = rowsOf(ok(service.portrait(ADMIN, "admin", STUDENT_USER)), "recentLeaves").get(0);
            assertEquals("2026-01-10", item.get("startAt"));
        }

        @Test
        @DisplayName("dateTime 输出 yyyy-MM-dd HH:mm")
        void dateTimeFormat() {
            when(leaveMapper.listInbox(any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(leave(100L, STUDENT_USER)));
            Map<String, Object> item = items(ok(service.todo(ADMIN, "admin", "leave", TERM, 1, 20))).get(0);
            assertEquals("2026-01-05 09:30", item.get("at"));
        }

        @Test
        @DisplayName("str 能格式化 java.sql.Timestamp（不出现 ISO 的 T）")
        void strTimestamp() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("last_created", Timestamp.valueOf(LocalDateTime.of(2025, 12, 20, 9, 15)));
            when(leaveMapper.listFrequentStudents(any(), any(), eq(3))).thenReturn(Collections.singletonList(row));
            Map<String, Object> a = items(ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20))).get(0);
            assertTrue(String.valueOf(a.get("detail")).contains("2025-12-20 09:15"));
            assertTrue(!String.valueOf(a.get("detail")).contains("T09:15"));
        }

        @Test
        @DisplayName("str 能格式化 LocalDateTime（Map 结果集里的 DATETIME）")
        void strLocalDateTime() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("last_created", LocalDateTime.of(2025, 12, 20, 9, 15));
            when(leaveMapper.listFrequentStudents(any(), any(), eq(3))).thenReturn(Collections.singletonList(row));
            Map<String, Object> a = items(ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20))).get(0);
            assertTrue(String.valueOf(a.get("detail")).contains("2025-12-20 09:15"));
        }

        @Test
        @DisplayName("days 去掉多余的小数 0：2.50 → 2.5")
        void daysStripTrailingZeros() {
            LeaveRequest lr = leave(100L, STUDENT_USER);
            lr.setDays(new BigDecimal("2.50"));
            when(leaveMapper.listInbox(any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(lr));
            Map<String, Object> item = items(ok(service.todo(ADMIN, "admin", "leave", TERM, 1, 20))).get(0);
            assertEquals("事假 · 2.5 天", item.get("title"));
        }

        @Test
        @DisplayName("days 整数不显示小数点：3.00 → 3")
        void daysInteger() {
            LeaveRequest lr = leave(100L, STUDENT_USER);
            lr.setDays(new BigDecimal("3.00"));
            when(leaveMapper.listInbox(any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(lr));
            Map<String, Object> item = items(ok(service.todo(ADMIN, "admin", "leave", TERM, 1, 20))).get(0);
            assertEquals("事假 · 3 天", item.get("title"));
        }

        @Test
        @DisplayName("days 为 null 时输出 0")
        void daysNull() {
            LeaveRequest lr = leave(100L, STUDENT_USER);
            lr.setDays(null);
            when(leaveMapper.listInbox(any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(lr));
            Map<String, Object> item = items(ok(service.todo(ADMIN, "admin", "leave", TERM, 1, 20))).get(0);
            assertEquals("事假 · 0 天", item.get("title"));
        }

        @Test
        @DisplayName("levelText：高 / 中 / 低")
        void levelTextAll() {
            stubFullAlertSet("admin");
            Map<String, Object> d = ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20));
            List<Map<String, Object>> list = items(d);
            assertEquals("高", list.get(0).get("levelText"));
            assertEquals("中", list.get(2).get("levelText"));
            assertEquals("低", list.get(5).get("levelText"));
        }

        @Test
        @DisplayName("leaveStatusText：待审批/已批准/已驳回/已销假/已撤回/未知")
        void leaveStatusTextAll() {
            String[] statuses = {"pending", "approved", "rejected", "closed", "cancelled", "banana"};
            String[] expects = {"待审批", "已批准", "已驳回", "已销假", "已撤回", "未知"};
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            List<LeaveRequest> list = new ArrayList<>();
            for (int i = 0; i < statuses.length; i++) {
                LeaveRequest lr = leave((long) (100 + i), STUDENT_USER);
                lr.setStatus(statuses[i]);
                list.add(lr);
            }
            when(leaveMapper.listByStudent(STUDENT_USER, null, 0, 5)).thenReturn(list);
            List<Map<String, Object>> rows = rowsOf(ok(service.portrait(ADMIN, "admin", STUDENT_USER)), "recentLeaves");
            for (int i = 0; i < expects.length; i++) {
                assertEquals(expects[i], rows.get(i).get("statusText"), "状态 " + statuses[i]);
            }
        }

        @Test
        @DisplayName("aidStatusText：待审批/已批准/已驳回/已撤回/未知")
        void aidStatusTextAll() {
            String[] statuses = {"pending", "approved", "rejected", "cancelled", "banana"};
            String[] expects = {"待审批", "已批准", "已驳回", "已撤回", "未知"};
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            List<AidApplication> list = new ArrayList<>();
            for (int i = 0; i < statuses.length; i++) {
                AidApplication a = aid((long) (200 + i), STUDENT_USER);
                a.setStatus(statuses[i]);
                list.add(a);
            }
            when(aidMapper.listMine(STUDENT_USER, null, 0, 5)).thenReturn(list);
            List<Map<String, Object>> rows = rowsOf(ok(service.portrait(ADMIN, "admin", STUDENT_USER)), "recentAids");
            for (int i = 0; i < expects.length; i++) {
                assertEquals(expects[i], rows.get(i).get("statusText"), "状态 " + statuses[i]);
            }
        }

        @Test
        @DisplayName("gradeText：优秀/良好/合格/不合格（含 null 与未知）")
        void gradeTextAll() {
            String[] grades = {"excellent", "good", "pass", null};
            String[] expects = {"优秀", "良好", "合格", "不合格"};
            String t = currentTerm();
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            List<String> terms = recentTerms(t, 4);
            for (int i = 0; i < grades.length; i++) {
                when(summaryMapper.findByStudentTerm(STUDENT_USER, terms.get(i)))
                        .thenReturn(summary(STUDENT_USER, terms.get(i), "80.00", i + 1, grades[i]));
            }
            Map<String, Object> eval = map(ok(service.portrait(ADMIN, "admin", STUDENT_USER)).get("eval"));
            List<?> history = (List<?>) eval.get("history");
            for (int i = 0; i < expects.length; i++) {
                assertEquals(expects[i], map(history.get(i)).get("gradeText"), "等级 " + grades[i]);
            }
        }

        @Test
        @DisplayName("alertTypeText：六类预警的中文名")
        void alertTypeTextAll() {
            stubFullAlertSet("admin");
            List<Map<String, Object>> list = items(ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20)));
            assertEquals("学籍异常", list.get(0).get("typeText"));
            assertEquals("逾期未销假", list.get(1).get("typeText"));
            assertEquals("请假频繁", list.get(2).get("typeText"));
            assertEquals("综测未出分", list.get(3).get("typeText"));
            assertEquals("资助积压", list.get(4).get("typeText"));
            assertEquals("档案未建", list.get(5).get("typeText"));
        }

        @Test
        @DisplayName("todoTypeText：请假 / 资助 / 综测")
        void todoTypeTextAll() {
            when(leaveMapper.listInbox(any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(leave(100L, STUDENT_USER)));
            when(aidMapper.listInbox(any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(aid(200L, STUDENT_USER)));
            when(profileMapper.listMissingEval(any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(profile(13L, TEACHER)));
            List<Map<String, Object>> list = items(ok(service.todo(ADMIN, "admin", "all", TERM, 1, 20)));
            List<String> texts = new ArrayList<>();
            for (Map<String, Object> item : list) {
                texts.add(String.valueOf(item.get("typeText")));
            }
            assertEquals(3, texts.size(), "实际：" + texts);
            assertTrue(texts.contains("请假"), "实际：" + texts);
            assertTrue(texts.contains("资助"), "实际：" + texts);
            assertTrue(texts.contains("综测"), "实际：" + texts);
        }

        @Test
        @DisplayName("gradeText：未知等级（banana）按不合格处理")
        void gradeTextUnknown() {
            String t = currentTerm();
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            String first = recentTerms(t, 4).get(0);
            when(summaryMapper.findByStudentTerm(STUDENT_USER, first))
                    .thenReturn(summary(STUDENT_USER, first, "80.00", 1, "banana"));
            Map<String, Object> eval = map(ok(service.portrait(ADMIN, "admin", STUDENT_USER)).get("eval"));
            List<?> history = (List<?>) eval.get("history");
            assertEquals("不合格", map(history.get(0)).get("gradeText"));
        }

        @Test
        @DisplayName("statusText：休学 / 退学 的中文写进预警标题与详情")
        void statusTextAll() {
            when(profileMapper.list(any(), any(), any(), eq("suspended"), eq(0), eq(SCAN)))
                    .thenReturn(Collections.singletonList(profile(11L, TEACHER)));
            when(profileMapper.list(any(), any(), any(), eq("quit"), eq(0), eq(SCAN)))
                    .thenReturn(Collections.singletonList(profile(12L, TEACHER)));
            List<Map<String, Object>> list = items(ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20)));
            assertEquals("学籍状态：休学", list.get(0).get("title"));
            assertEquals("学籍状态：退学", list.get(1).get("title"));
        }

        @Test
        @DisplayName("长文本按 60 字截断并追加省略号")
        void clipLongReason() {
            LeaveRequest lr = leave(100L, STUDENT_USER);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 70; i++) {
                sb.append("甲");
            }
            lr.setReason(sb.toString());
            when(leaveMapper.listInbox(any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(lr));
            Map<String, Object> item = items(ok(service.todo(ADMIN, "admin", "leave", TERM, 1, 20))).get(0);
            String detail = String.valueOf(item.get("detail"));
            assertTrue(detail.endsWith("…"), "实际结尾：" + detail.substring(detail.length() - 5));
            assertEquals(60 + 1, detail.length() - detail.indexOf("　") - 1);
        }

        @Test
        @DisplayName("studentName 为空时回退到 username")
        void nameFallback() {
            StudentProfile sp = profile(11L, TEACHER);
            sp.setName("");
            sp.setUsername("lisi");
            when(profileMapper.list(any(), any(), any(), eq("suspended"), eq(0), eq(SCAN)))
                    .thenReturn(Collections.singletonList(sp));
            Map<String, Object> a = items(ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20))).get(0);
            assertEquals("lisi", a.get("studentName"));
        }

        @Test
        @DisplayName("全空格姓名回退到账号（name() 做 trim）")
        void nameWhitespaceTrimmed() {
            StudentProfile sp = profile(11L, TEACHER);
            sp.setName("  ");
            sp.setUsername("lisi");
            when(profileMapper.list(any(), any(), any(), eq("suspended"), eq(0), eq(SCAN)))
                    .thenReturn(Collections.singletonList(sp));
            Map<String, Object> a = items(ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20))).get(0);
            assertEquals("lisi", a.get("studentName"));
        }

        @Test
        @DisplayName("学号为空时 url 关键字为空串")
        void urlEmptyStudentNo() {
            StudentProfile sp = profile(11L, TEACHER);
            sp.setStudentNo(null);
            when(profileMapper.list(any(), any(), any(), eq("suspended"), eq(0), eq(SCAN)))
                    .thenReturn(Collections.singletonList(sp));
            Map<String, Object> a = items(ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20))).get(0);
            assertEquals("counselor-students.html?keyword=", a.get("url"));
        }
    }

    // ================================================================== 健壮性

    @Nested
    @DisplayName("健壮性：空列表、null 结果与边界输入")
    class Robustness {

        @Test
        @DisplayName("overview：Mapper 全返回空列表时正常出数")
        void overviewEmptyLists() {
            when(profileMapper.countGroupByStatus(any())).thenReturn(Collections.emptyList());
            when(profileMapper.countGroupByClass(any())).thenReturn(Collections.emptyList());
            when(profileMapper.listClassNames(any())).thenReturn(Collections.emptyList());
            when(leaveMapper.countGroupByStatus(any())).thenReturn(Collections.emptyList());
            when(aidMapper.countGroupByStatus(any(), any())).thenReturn(Collections.emptyList());
            when(profileMapper.list(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Collections.emptyList());
            when(leaveMapper.listOverdue(any(), any(), anyInt(), anyInt())).thenReturn(Collections.emptyList());
            when(leaveMapper.listFrequentStudents(any(), any(), anyInt())).thenReturn(Collections.emptyList());
            when(profileMapper.listMissingEval(any(), any(), anyInt(), anyInt())).thenReturn(Collections.emptyList());
            when(aidMapper.listInbox(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Collections.emptyList());
            when(profileMapper.listMissingProfile(anyInt(), anyInt())).thenReturn(Collections.emptyList());
            assertEquals(200, service.overview(ADMIN, "admin", TERM).getCode());
        }

        @Test
        @DisplayName("todo：各 Mapper 返回空列表时 items 为空且不抛异常")
        void todoEmptyLists() {
            when(leaveMapper.listInbox(any(), any(), any(), anyInt(), anyInt())).thenReturn(Collections.emptyList());
            when(aidMapper.listInbox(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Collections.emptyList());
            when(profileMapper.listMissingEval(any(), any(), anyInt(), anyInt())).thenReturn(Collections.emptyList());
            Map<String, Object> d = ok(service.todo(ADMIN, "admin", "all", TERM, 1, 20));
            assertEquals(0, items(d).size());
        }

        @Test
        @DisplayName("alerts：各 Mapper 返回空列表时 counts 全 0")
        void alertsEmptyLists() {
            when(leaveMapper.listFrequentStudents(any(), any(), anyInt())).thenReturn(Collections.emptyList());
            when(aidMapper.listInbox(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Collections.emptyList());
            when(profileMapper.listMissingProfile(anyInt(), anyInt())).thenReturn(Collections.emptyList());
            Map<String, Object> d = ok(service.alerts(ADMIN, "admin", null, TERM, 1, 20));
            assertEquals(0, d.get("total"));
            assertEquals(0, map(d.get("counts")).get("total"));
        }

        @Test
        @DisplayName("portrait：假条/奖惩/资助列表返回空时三类 recent 为空数组")
        void portraitEmptyLists() {
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            when(leaveMapper.listByStudent(any(), any(), anyInt(), anyInt())).thenReturn(Collections.emptyList());
            when(awardMapper.list(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Collections.emptyList());
            when(aidMapper.listMine(any(), any(), anyInt(), anyInt())).thenReturn(Collections.emptyList());
            Map<String, Object> d = ok(service.portrait(ADMIN, "admin", STUDENT_USER));
            assertEquals(0, rowsOf(d, "recentLeaves").size());
        }

        @Test
        @DisplayName("portrait：每学期都查不到综测时不抛异常")
        void portraitNoEval() {
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, TEACHER));
            Map<String, Object> eval = map(ok(service.portrait(ADMIN, "admin", STUDENT_USER)).get("eval"));
            assertNull(eval.get("current"));
            assertEquals(0, ((List<?>) eval.get("history")).size());
            assertEquals(currentTerm(), eval.get("currentTerm"));
        }

        @Test
        @DisplayName("countByCounselor 为 0（未分派）时 scope 回落全校且不抛异常")
        void countByCounselorZero() {
            when(profileMapper.countByCounselor(TEACHER)).thenReturn(0);
            Map<String, Object> d = ok(service.overview(TEACHER, "teacher", TERM));
            assertEquals("all", d.get("scope"));
        }

        @Test
        @DisplayName("todo：page 与 size 极大时 offset 不溢出为负（钳到 Integer.MAX_VALUE）")
        void hugePaging() {
            Map<String, Object> d = ok(service.todo(ADMIN, "admin", "leave", TERM, Integer.MAX_VALUE, Integer.MAX_VALUE));
            assertEquals(100, d.get("size"));
            assertEquals(Integer.MAX_VALUE, d.get("page"));
            verify(leaveMapper).listInbox(any(), eq("pending"), any(), eq(Integer.MAX_VALUE), eq(100));
        }
    }
}
