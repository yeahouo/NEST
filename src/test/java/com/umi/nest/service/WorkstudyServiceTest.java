package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.entity.WorkstudyApplication;
import com.umi.nest.entity.WorkstudyPayroll;
import com.umi.nest.entity.WorkstudyPost;
import com.umi.nest.entity.WorkstudyTimesheet;
import com.umi.nest.mapper.StudentProfileMapper;
import com.umi.nest.mapper.WorkstudyApplicationMapper;
import com.umi.nest.mapper.WorkstudyPayrollMapper;
import com.umi.nest.mapper.WorkstudyPostMapper;
import com.umi.nest.mapper.WorkstudyTimesheetMapper;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
 * WorkstudyService 单元测试（学工 P10 勤工助学）。
 *
 * <p>风格照 CounselorServiceTest：MockitoExtension + LENIENT + 全部 Mapper @Mock + @InjectMocks。
 * 覆盖：鉴权次序 / 岗位 / 申请 / 面试录用 / 工时 / 酬金 / 概览 / 视图装配 / 健壮性。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkstudyService - 勤工助学（P10）")
class WorkstudyServiceTest {

    @Mock
    private WorkstudyPostMapper postMapper;

    @Mock
    private WorkstudyApplicationMapper applicationMapper;

    @Mock
    private WorkstudyTimesheetMapper timesheetMapper;

    @Mock
    private WorkstudyPayrollMapper payrollMapper;

    @Mock
    private StudentProfileMapper profileMapper;

    @InjectMocks
    private WorkstudyService service;

    private static final Long STUDENT = 6L;
    private static final Long OTHER_STUDENT = 7L;
    private static final Long TEACHER = 2L;
    private static final Long OTHER_TEACHER = 3L;
    private static final Long ADMIN = 1L;

    /** offsetOf 对超大页码的钳制值 */
    private static final int MAX_SAFE_OFFSET = Integer.MAX_VALUE;

    // =====================================================================
    // 夹具
    // =====================================================================

    private WorkstudyPost post(long id, String status, String postType, Long unitUserId) {
        WorkstudyPost p = new WorkstudyPost();
        p.setId(id);
        p.setPostType(postType);
        p.setUnit("图书馆");
        p.setUnitUserId(unitUserId);
        p.setName("图书整理与借阅服务");
        p.setContent("图书上架、读者引导");
        p.setSkill("细心、有责任心");
        p.setHeadcount(3);
        p.setHiredCount(0);
        p.setHourlyWage(new BigDecimal("15.00"));
        p.setHoursPerWeek(new BigDecimal("8.0"));
        p.setHoursPerMonth(new BigDecimal("40.0"));
        p.setAcademicTerm("2025-2026-1");
        p.setStatus(status);
        return p;
    }

    private WorkstudyPost recruitingPost() {
        return post(9001L, "recruiting", "fixed", TEACHER);
    }

    private WorkstudyApplication app(long id, long postId, long studentId, String status) {
        WorkstudyApplication a = new WorkstudyApplication();
        a.setId(id);
        a.setPostId(postId);
        a.setStudentId(studentId);
        a.setAcademicTerm("2025-2026-1");
        a.setReason("家庭经济困难，希望通过勤工助学自立");
        a.setStatus(status);
        a.setPostName("图书整理与借阅服务");
        a.setUnit("图书馆");
        a.setPostType("fixed");
        a.setHourlyWage(new BigDecimal("15.00"));
        a.setStudentName("陈大伟");
        a.setStudentNo("2024003");
        a.setClassName("软工2402");
        a.setPoverty(1);
        return a;
    }

    private WorkstudyTimesheet sheet(long id, long studentId, String month, String date, String hours, String status) {
        WorkstudyTimesheet s = new WorkstudyTimesheet();
        s.setId(id);
        s.setApplicationId(9001L);
        s.setPostId(9001L);
        s.setStudentId(studentId);
        s.setWorkMonth(month);
        s.setWorkDate(LocalDate.parse(date));
        s.setHours(new BigDecimal(hours));
        s.setContent("图书上架与整理");
        s.setStatus(status);
        s.setRecorderId(TEACHER);
        s.setPostName("图书整理与借阅服务");
        return s;
    }

    private WorkstudyPayroll payroll(long id, long studentId, String month, String hours, String wage, String amount) {
        WorkstudyPayroll p = new WorkstudyPayroll();
        p.setId(id);
        p.setStudentId(studentId);
        p.setPostId(9001L);
        p.setApplicationId(9001L);
        p.setWorkMonth(month);
        p.setTotalHours(new BigDecimal(hours));
        p.setHourlyWage(new BigDecimal(wage));
        p.setAmount(new BigDecimal(amount));
        p.setStatus("pending");
        p.setStudentName("陈大伟");
        p.setStudentNo("2024003");
        p.setClassName("软工2402");
        p.setPostName("图书整理与借阅服务");
        p.setUnit("图书馆");
        return p;
    }

    private static Map<String, String> body(String... pairs) {
        Map<String, String> map = new HashMap<String, String>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static boolean truthy(Object value) {
        return value != null && Boolean.TRUE.equals(value);
    }

    private static Integer counter(Object value) {
        return value == null ? Integer.valueOf(0) : Integer.valueOf(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Result<?> r) {
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ok(Result<?> r) {
        assertEquals(200, r.getCode(), "期望 200，实际 " + r.getCode() + " / " + r.getMessage());
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("items");
    }

    private void assertMessage(Result<?> r, String message) {
        assertNotNull(r.getMessage());
        assertTrue(r.getMessage().contains(message),
                "期望消息包含「" + message + "」，实际「" + r.getMessage() + "」");
    }

    /** warnings 数组中是否存在包含指定片段的条目 */
    private static boolean warningsHits(List<?> warnings, String fragment) {
        if (warnings == null) {
            return false;
        }
        for (Object entry : warnings) {
            if (entry != null && String.valueOf(entry).contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================
    // A. 鉴权
    // =====================================================================

    @Nested
    @DisplayName("鉴权次序：未登录先 401，再判角色 403")
    class Auth {

        @Test
        @DisplayName("posts：userId 为 null 时返 401 未登录")
        void postsUnauthenticated() {
            Result<?> r = service.posts(null, "student", 1, 20, null, null, null);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(postMapper);
        }

        @Test
        @DisplayName("postDetail：userId 为 null 时返 401 未登录")
        void postDetailUnauthenticated() {
            Result<?> r = service.postDetail(null, "student", 9001L);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(postMapper);
        }

        @Test
        @DisplayName("apply：userId 为 null 时返 401 未登录")
        void applyUnauthenticated() {
            Result<?> r = service.apply(null, "student", 9001L, body("reason", "想参加"));
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(applicationMapper);
        }

        @Test
        @DisplayName("mine：userId 为 null 时返 401 未登录")
        void mineUnauthenticated() {
            Result<?> r = service.mine(null, "student");
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(applicationMapper);
        }

        @Test
        @DisplayName("myTimesheets：userId 为 null 时返 401 未登录")
        void myTimesheetsUnauthenticated() {
            Result<?> r = service.myTimesheets(null, "student", "2026-10");
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(timesheetMapper);
        }

        @Test
        @DisplayName("resign：userId 为 null 时返 401 未登录")
        void resignUnauthenticated() {
            Result<?> r = service.resign(null, "student", 9001L, body("reason", "课表冲突"));
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(applicationMapper);
        }

        @Test
        @DisplayName("managePosts：userId 为 null 时返 401 未登录")
        void managePostsUnauthenticated() {
            Result<?> r = service.managePosts(null, "teacher", 1, 20, null, null);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(postMapper);
        }

        @Test
        @DisplayName("createPost：userId 为 null 时返 401 未登录")
        void createPostUnauthenticated() {
            Result<?> r = service.createPost(null, "teacher", body("postType", "fixed"));
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(postMapper);
        }

        @Test
        @DisplayName("updatePost：userId 为 null 时返 401 未登录")
        void updatePostUnauthenticated() {
            Result<?> r = service.updatePost(null, "teacher", 9001L, body("name", "新岗位"));
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(postMapper);
        }

        @Test
        @DisplayName("submitPost：userId 为 null 时返 401 未登录")
        void submitPostUnauthenticated() {
            Result<?> r = service.submitPost(null, "teacher", 9001L);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(postMapper);
        }

        @Test
        @DisplayName("reviewPost：userId 为 null 时返 401 未登录")
        void reviewPostUnauthenticated() {
            Result<?> r = service.reviewPost(null, "admin", 9001L, body("approve", "true"));
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(postMapper);
        }

        @Test
        @DisplayName("closePost：userId 为 null 时返 401 未登录")
        void closePostUnauthenticated() {
            Result<?> r = service.closePost(null, "teacher", 9001L, body("note", "岗位撤销"));
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(postMapper);
        }

        @Test
        @DisplayName("manageApplications：userId 为 null 时返 401 未登录")
        void manageApplicationsUnauthenticated() {
            Result<?> r = service.manageApplications(null, "teacher", 9001L, null, 1, 20);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(applicationMapper);
        }

        @Test
        @DisplayName("interview：userId 为 null 时返 401 未登录")
        void interviewUnauthenticated() {
            Result<?> r = service.interview(null, "teacher", 9001L, body("result", "pass"));
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(applicationMapper);
        }

        @Test
        @DisplayName("hire：userId 为 null 时返 401 未登录")
        void hireUnauthenticated() {
            Result<?> r = service.hire(null, "teacher", 9001L, body("note", "同意录用"));
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(applicationMapper);
        }

        @Test
        @DisplayName("reject：userId 为 null 时返 401 未登录")
        void rejectUnauthenticated() {
            Result<?> r = service.reject(null, "teacher", 9001L, null);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(applicationMapper);
        }

        @Test
        @DisplayName("generatePayroll：userId 为 null 时返 401 未登录")
        void generatePayrollUnauthenticated() {
            Result<?> r = service.generatePayroll(null, "admin", body("workMonth", "2026-10"));
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(timesheetMapper);
        }

        @Test
        @DisplayName("listPayroll：userId 为 null 时返 401 未登录")
        void listPayrollUnauthenticated() {
            Result<?> r = service.listPayroll(null, "admin", "2026-10", null, 1, 20);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(payrollMapper);
        }

        @Test
        @DisplayName("payPayroll：userId 为 null 时返 401 未登录")
        void payPayrollUnauthenticated() {
            Result<?> r = service.payPayroll(null, "admin", 9001L);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(payrollMapper);
        }

        @Test
        @DisplayName("overview：userId 为 null 时返 401 未登录")
        void overviewUnauthenticated() {
            Result<?> r = service.overview(null, "teacher");
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(postMapper);
        }

        @Test
        @DisplayName("createTimesheet：userId 为 null 时返 401 未登录")
        void createTimesheetUnauthenticated() {
            Result<?> r = service.createTimesheet(null, "teacher", body("hours", "2"));
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(timesheetMapper);
        }

        @Test
        @DisplayName("confirmTimesheet：userId 为 null 时返 401 未登录")
        void confirmTimesheetUnauthenticated() {
            Result<?> r = service.confirmTimesheet(null, "teacher", 9001L);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
            verifyNoInteractions(timesheetMapper);
        }

        @Test
        @DisplayName("apply：teacher 申请岗位返 403 只有学生可以申请")
        void applyAsTeacher() {
            Result<?> r = service.apply(TEACHER, "teacher", 9001L, body("reason", "想参加"));
            assertEquals(403, r.getCode());
            assertMessage(r, "只有学生可以申请");
            verifyNoInteractions(applicationMapper);
        }

        @Test
        @DisplayName("managePosts：student 返 403 无权限管理岗位")
        void managePostsAsStudent() {
            Result<?> r = service.managePosts(STUDENT, "student", 1, 20, null, null);
            assertEquals(403, r.getCode());
            assertEquals("无权限管理岗位", r.getMessage());
            verifyNoInteractions(postMapper);
        }

        @Test
        @DisplayName("createPost：student 返 403 无权限设岗")
        void createPostAsStudent() {
            Result<?> r = service.createPost(STUDENT, "student", body("postType", "fixed"));
            assertEquals(403, r.getCode());
            assertEquals("无权限设岗", r.getMessage());
            verifyNoInteractions(postMapper);
        }

        @Test
        @DisplayName("reviewPost：teacher 返 403 无权限审批岗位")
        void reviewPostAsTeacher() {
            Result<?> r = service.reviewPost(TEACHER, "teacher", 9001L, body("approve", "true"));
            assertEquals(403, r.getCode());
            assertEquals("无权限审批岗位", r.getMessage());
            verifyNoInteractions(postMapper);
        }

        @Test
        @DisplayName("generatePayroll：teacher 返 403 无权限汇总酬金")
        void generatePayrollAsTeacher() {
            Result<?> r = service.generatePayroll(TEACHER, "teacher", body("workMonth", "2026-10"));
            assertEquals(403, r.getCode());
            assertEquals("无权限汇总酬金", r.getMessage());
            verifyNoInteractions(timesheetMapper);
        }

        @Test
        @DisplayName("payPayroll：teacher 返 403 无权限发放酬金")
        void payPayrollAsTeacher() {
            Result<?> r = service.payPayroll(TEACHER, "teacher", 9001L);
            assertEquals(403, r.getCode());
            assertEquals("无权限发放酬金", r.getMessage());
            verifyNoInteractions(payrollMapper);
        }

        @Test
        @DisplayName("overview：student 返 403 无权限查看概览")
        void overviewAsStudent() {
            Result<?> r = service.overview(STUDENT, "student");
            assertEquals(403, r.getCode());
            assertEquals("无权限查看勤工助学概览", r.getMessage());
            verifyNoInteractions(postMapper);
        }

        @Test
        @DisplayName("manageTimesheets：student 返 403 无权限查看工时")
        void manageTimesheetsAsStudent() {
            Result<?> r = service.manageTimesheets(STUDENT, "student", null, null, null, 1, 20);
            assertEquals(403, r.getCode());
            assertEquals("无权限查看工时", r.getMessage());
            verifyNoInteractions(timesheetMapper);
        }

        @Test
        @DisplayName("createTimesheet：student 返 403 无权限记录工时")
        void createTimesheetAsStudent() {
            Result<?> r = service.createTimesheet(STUDENT, "student", body("hours", "2"));
            assertEquals(403, r.getCode());
            assertEquals("无权限记录工时", r.getMessage());
            verifyNoInteractions(timesheetMapper);
        }

        @Test
        @DisplayName("confirmTimesheet：student 返 403 无权限确认工时")
        void confirmTimesheetAsStudent() {
            Result<?> r = service.confirmTimesheet(STUDENT, "student", 9001L);
            assertEquals(403, r.getCode());
            assertEquals("无权限确认工时", r.getMessage());
            verifyNoInteractions(timesheetMapper);
        }

        @Test
        @DisplayName("listPayroll：student 返 403 无权限查看酬金")
        void listPayrollAsStudent() {
            Result<?> r = service.listPayroll(STUDENT, "student", "2026-10", null, 1, 20);
            assertEquals(403, r.getCode());
            assertEquals("无权限查看酬金", r.getMessage());
            verifyNoInteractions(payrollMapper);
        }
    }

    // =====================================================================
    // B. 岗位
    // =====================================================================

    @Nested
    @DisplayName("岗位：公告列表 / 详情 / 列表筛查")
    class Posts {

        @Test
        @DisplayName("posts：学生只看到 recruiting，且携带自己的申请状态")
        void postsForStudent() {
            WorkstudyPost p = recruitingPost();
            when(postMapper.count(isNull(), isNull(), isNull(), eq("recruiting"), isNull())).thenReturn(1);
            when(postMapper.list(isNull(), isNull(), isNull(), eq("recruiting"), isNull(), eq(20), eq(0)))
                    .thenReturn(Collections.singletonList(p));
            when(applicationMapper.listByStudent(STUDENT, 200, 0))
                    .thenReturn(Collections.singletonList(app(9001L, 9001L, STUDENT, "pending")));

            Map<String, Object> data = ok(service.posts(STUDENT, "student", 1, 20, null, null, null));

            assertEquals(1, counter(data.get("total")));
            assertEquals(WorkstudyService.currentTerm(), data.get("term"));
            Map<String, Object> item = items(data).get(0);
            assertEquals(9001L, item.get("id"));
            assertEquals("固定岗", item.get("postTypeText"));
            assertEquals("招募中", item.get("statusText"));
            assertTrue(truthy(item.get("applied")));
            assertEquals("pending", item.get("applicationStatus"));
            assertEquals("待审核", item.get("applicationStatusText"));
        }

        @Test
        @DisplayName("posts：teacher/admin 不加 recruiting 限制（status 传 null）")
        void postsForTeacher() {
            when(postMapper.count(eq("fixed"), isNull(), eq("图书"), isNull(), isNull())).thenReturn(1);
            when(postMapper.list(eq("fixed"), isNull(), eq("图书"), isNull(), isNull(), eq(20), eq(0)))
                    .thenReturn(Collections.singletonList(post(9003L, "pending", "fixed", OTHER_TEACHER)));

            Map<String, Object> data = ok(service.posts(TEACHER, "teacher", 1, 20, "fixed", "  ", " 图书 "));

            assertEquals(1, counter(data.get("total")));
            Map<String, Object> item = items(data).get(0);
            assertFalse(truthy(item.get("applied")));
            assertNull(item.get("applicationStatus"));
            assertNull(item.get("applicationStatusText"));
        }

        @Test
        @DisplayName("posts：type 非法返 400")
        void postsBadType() {
            Result<?> r = service.posts(STUDENT, "student", 1, 20, "intern", null, null);
            assertEquals(400, r.getCode());
            assertEquals("岗位类型只支持 fixed / temporary", r.getMessage());
        }

        @Test
        @DisplayName("posts：学期格式非法返 400")
        void postsBadTerm() {
            Result<?> r = service.posts(STUDENT, "student", 1, 20, null, "2025-1", null);
            assertEquals(400, r.getCode());
            assertEquals("学期格式应为 2025-2026-1", r.getMessage());
        }

        @Test
        @DisplayName("posts：term 传空时回落到当前学期")
        void postsDefaultTerm() {
            when(postMapper.count(anyString(), anyString(), anyString(), anyString(), anyLong())).thenReturn(0);
            Map<String, Object> data = ok(service.posts(STUDENT, "student", 1, 20, null, "  ", null));
            assertEquals(WorkstudyService.currentTerm(), data.get("term"));
            assertEquals(0, counter(data.get("total")));
            verify(postMapper, never()).list(any(), any(), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("posts：分页元数据（pages 向上取整）")
        void postsPaging() {
            when(postMapper.count(isNull(), isNull(), isNull(), eq("recruiting"), isNull())).thenReturn(25);
            when(postMapper.list(isNull(), isNull(), isNull(), eq("recruiting"), isNull(), eq(10), eq(10)))
                    .thenReturn(Collections.singletonList(recruitingPost()));

            Map<String, Object> data = ok(service.posts(STUDENT, "student", 2, 10, null, null, null));

            assertEquals(25, counter(data.get("total")));
            assertEquals(2, counter(data.get("page")));
            assertEquals(10, counter(data.get("size")));
            assertEquals(3, counter(data.get("pages")));
        }

        @Test
        @DisplayName("posts：size 超上限被钳到 100")
        void postsClampSize() {
            when(postMapper.count(isNull(), isNull(), isNull(), eq("recruiting"), isNull())).thenReturn(1);
            when(postMapper.list(isNull(), isNull(), isNull(), eq("recruiting"), isNull(), eq(100), eq(0)))
                    .thenReturn(Collections.singletonList(recruitingPost()));

            Map<String, Object> data = ok(service.posts(STUDENT, "student", 1, 999, null, null, null));
            assertEquals(100, counter(data.get("size")));
        }

        @Test
        @DisplayName("postDetail：岗位不存在返 404")
        void postDetailNotFound() {
            when(postMapper.findById(404L)).thenReturn(null);
            Result<?> r = service.postDetail(STUDENT, "student", 404L);
            assertEquals(404, r.getCode());
            assertEquals("岗位不存在", r.getMessage());
        }

        @Test
        @DisplayName("postDetail：postId 为 null 返 400 岗位不存在")
        void postDetailNullId() {
            Result<?> r = service.postDetail(STUDENT, "student", null);
            assertEquals(400, r.getCode());
            assertEquals("岗位不存在", r.getMessage());
            verifyNoInteractions(postMapper);
        }

        @Test
        @DisplayName("postDetail：学生看招募中岗位，my 为本人申请")
        void postDetailForStudent() {
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            when(applicationMapper.listByStudent(STUDENT, 200, 0))
                    .thenReturn(Collections.singletonList(app(9001L, 9001L, STUDENT, "interview_passed")));

            Map<String, Object> data = ok(service.postDetail(STUDENT, "student", 9001L));

            assertNotNull(data.get("post"));
            Map<String, Object> post = map(data.get("post"));
            assertEquals(TEACHER, post.get("unitUserId"));
            assertEquals("固定岗", post.get("postTypeText"));
            Map<String, Object> my = map(data.get("my"));
            assertEquals("interview_passed", my.get("status"));
            assertEquals("面试通过", my.get("statusText"));
            assertEquals(new BigDecimal("15.00"), my.get("hourlyWage"));
        }

        @Test
        @DisplayName("postDetail：学生看非招募中岗位返 400 该岗位当前不接受申请")
        void postDetailNotRecruiting() {
            when(postMapper.findById(9003L)).thenReturn(post(9003L, "pending", "fixed", OTHER_TEACHER));
            Result<?> r = service.postDetail(STUDENT, "student", 9003L);
            assertEquals(400, r.getCode());
            assertEquals("该岗位当前不接受申请", r.getMessage());
        }

        @Test
        @DisplayName("postDetail：teacher 可看非招募中岗位（用工单位视角）")
        void postDetailForStaff() {
            when(postMapper.findById(9003L)).thenReturn(post(9003L, "pending", "fixed", TEACHER));
            Result<?> r = service.postDetail(TEACHER, "teacher", 9003L);
            assertEquals(200, r.getCode());
            assertNull(data(r).get("my"));
        }

        @Test
        @DisplayName("postDetail：teacher 属 STAFF 角色，可看非本单位非招募中岗位")
        void postDetailOtherUnit() {
            when(postMapper.findById(9003L)).thenReturn(post(9003L, "pending", "fixed", OTHER_TEACHER));
            Result<?> r = service.postDetail(TEACHER, "teacher", 9003L);
            assertEquals(200, r.getCode());
            assertNotNull(data(r).get("post"));
        }

        @Test
        @DisplayName("postDetail：装配审批信息（reviewNote/reviewerId/reviewedAt/createdAt）")
        void postDetailAssemblesReview() {
            WorkstudyPost p = post(9001L, "recruiting", "fixed", TEACHER);
            p.setReviewNote("同意设岗");
            p.setReviewerId(ADMIN);
            p.setReviewedAt(LocalDateTime.of(2026, 10, 8, 9, 30));
            p.setCreatedAt(LocalDateTime.of(2026, 10, 1, 9, 0));
            p.setUnitUserName("张三");
            when(postMapper.findById(9001L)).thenReturn(p);

            Map<String, Object> post = map(ok(service.postDetail(TEACHER, "teacher", 9001L)).get("post"));

            assertEquals("同意设岗", post.get("reviewNote"));
            assertEquals(ADMIN, post.get("reviewerId"));
            assertEquals("2026-10-08T09:30", post.get("reviewedAt"));
            assertEquals("2026-10-01T09:00", post.get("createdAt"));
            assertEquals("张三", post.get("unitUserName"));
        }

        @Test
        @DisplayName("managePosts：teacher 只看自己（unitUserId = 自己）")
        void managePostsScopeTeacher() {
            when(postMapper.count(isNull(), isNull(), isNull(), isNull(), eq(TEACHER))).thenReturn(1);
            when(postMapper.list(isNull(), isNull(), isNull(), isNull(), eq(TEACHER), eq(20), eq(0)))
                    .thenReturn(Collections.singletonList(recruitingPost()));

            Map<String, Object> data = ok(service.managePosts(TEACHER, "teacher", 1, 20, null, null));

            assertEquals(1, counter(data.get("total")));
            assertEquals("2025-2026-1", ((Map<String, Object>) items(data).get(0)).get("academicTerm"));
        }

        @Test
        @DisplayName("managePosts：admin 且 mine=0 时看全部（unitUserId = null）")
        void managePostsAdminAll() {
            when(postMapper.count(anyString(), anyString(), anyString(), anyString(), anyLong())).thenReturn(0);
            Map<String, Object> data = ok(service.managePosts(ADMIN, "admin", 1, 20, null, 0));
            assertEquals(0, counter(data.get("total")));
            verify(postMapper, never()).list(any(), any(), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("managePosts：admin 且 mine=1 时只看自己")
        void managePostsAdminMine() {
            when(postMapper.count(isNull(), isNull(), isNull(), isNull(), eq(ADMIN))).thenReturn(0);
            ok(service.managePosts(ADMIN, "admin", 1, 20, null, 1));
            verify(postMapper).count(isNull(), isNull(), isNull(), isNull(), eq(ADMIN));
        }

        @Test
        @DisplayName("managePosts：status 非法返 400")
        void managePostsBadStatus() {
            Result<?> r = service.managePosts(TEACHER, "teacher", 1, 20, "unknown", null);
            assertEquals(400, r.getCode());
            assertEquals("岗位状态不正确", r.getMessage());
        }

        @Test
        @DisplayName("managePosts：teacher 分页 offset 按页数下推")
        void managePostsPaging() {
            when(postMapper.count(isNull(), isNull(), isNull(), isNull(), eq(TEACHER))).thenReturn(30);
            when(postMapper.list(isNull(), isNull(), isNull(), isNull(), eq(TEACHER), eq(10), eq(20)))
                    .thenReturn(Collections.singletonList(recruitingPost()));

            Map<String, Object> data = ok(service.managePosts(TEACHER, "teacher", 3, 10, null, null));
            assertEquals(3, counter(data.get("pages")));
            verify(postMapper).list(isNull(), isNull(), isNull(), isNull(), eq(TEACHER), eq(10), eq(20));
        }

        @Test
        @DisplayName("managePosts：省略学期时不过滤（跨学期岗位可见）")
        void managePostsNoTermFilter() {
            when(postMapper.count(isNull(), isNull(), isNull(), isNull(), eq(TEACHER))).thenReturn(1);
            when(postMapper.list(isNull(), isNull(), isNull(), isNull(), eq(TEACHER), eq(20), eq(0)))
                    .thenReturn(Collections.singletonList(recruitingPost()));

            Map<String, Object> data = ok(service.managePosts(TEACHER, "teacher", 1, 20, null, null, null));

            assertEquals(1, counter(data.get("total")));
            verify(postMapper).count(isNull(), isNull(), isNull(), isNull(), eq(TEACHER));
        }

        @Test
        @DisplayName("managePosts：显式传学期时按学期过滤")
        void managePostsExplicitTerm() {
            when(postMapper.count(isNull(), eq("2035-2036-1"), isNull(), isNull(), eq(TEACHER))).thenReturn(0);

            ok(service.managePosts(TEACHER, "teacher", 1, 20, null, null, "2035-2036-1"));

            verify(postMapper).count(isNull(), eq("2035-2036-1"), isNull(), isNull(), eq(TEACHER));
        }

        @Test
        @DisplayName("managePosts：学期格式非法返 400")
        void managePostsBadTerm() {
            Result<?> r = service.managePosts(TEACHER, "teacher", 1, 20, null, null, "2035");

            assertEquals(400, r.getCode());
            assertEquals("学期格式应为 2025-2026-1", r.getMessage());
        }
    }

    // =====================================================================
    // C. 设岗与审批
    // =====================================================================

    @Nested
    @DisplayName("设岗：申报校验 / warnings / 修改 / 提交 / 审批 / 关闭")
    class PostManagement {

        @Test
        @DisplayName("createPost：body 为空返 400")
        void createPostEmptyBody() {
            Result<?> r = service.createPost(TEACHER, "teacher", null);
            assertEquals(400, r.getCode());
            assertEquals("没有要提交的内容", r.getMessage());
        }

        @Test
        @DisplayName("createPost：岗位类型非法返 400")
        void createPostBadType() {
            Result<?> r = service.createPost(TEACHER, "teacher", body("postType", "intern"));
            assertEquals(400, r.getCode());
            assertEquals("岗位类型只支持 fixed / temporary", r.getMessage());
        }

        @Test
        @DisplayName("createPost：岗位名称为空返 400")
        void createPostBlankName() {
            Result<?> r = service.createPost(TEACHER, "teacher",
                    body("postType", "fixed", "name", "   ", "unit", "图书馆"));
            assertEquals(400, r.getCode());
            assertEquals("岗位名称不能为空", r.getMessage());
        }

        @Test
        @DisplayName("createPost：用工单位为空白返 400")
        void createPostBlankUnit() {
            Result<?> r = service.createPost(TEACHER, "teacher",
                    body("postType", "fixed", "name", "图书整理", "unit", ""));
            assertEquals(400, r.getCode());
            assertEquals("用工单位不能为空", r.getMessage());
        }

        @Test
        @DisplayName("createPost：学期格式非法返 400")
        void createPostBadTerm() {
            Result<?> r = service.createPost(TEACHER, "teacher",
                    body("postType", "fixed", "name", "图书整理", "unit", "图书馆", "academicTerm", "2025-2026"));
            assertEquals(400, r.getCode());
            assertEquals("学期格式应为 2025-2026-1", r.getMessage());
        }

        @Test
        @DisplayName("createPost：招聘人数非数字返 400")
        void createPostBadHeadcount() {
            Result<?> r = service.createPost(TEACHER, "teacher", body("postType", "fixed", "name", "图书整理",
                    "unit", "图书馆", "academicTerm", "2025-2026-1", "headcount", "abc"));
            assertEquals(400, r.getCode());
            assertEquals("招聘人数不正确", r.getMessage());
        }

        @Test
        @DisplayName("createPost：招聘人数 < 1 返 400")
        void createPostHeadcountZero() {
            Result<?> r = service.createPost(TEACHER, "teacher", body("postType", "fixed", "name", "图书整理",
                    "unit", "图书馆", "academicTerm", "2025-2026-1", "headcount", "0"));
            assertEquals(400, r.getCode());
            assertEquals("招聘人数至少为 1", r.getMessage());
        }

        @Test
        @DisplayName("createPost：时薪为 0 返 400")
        void createPostWageZero() {
            Result<?> r = service.createPost(TEACHER, "teacher", body("postType", "fixed", "name", "图书整理",
                    "unit", "图书馆", "academicTerm", "2025-2026-1", "headcount", "2", "hourlyWage", "0"));
            assertEquals(400, r.getCode());
            assertEquals("时薪必须大于 0", r.getMessage());
        }

        @Test
        @DisplayName("createPost：每周工时为负返 400")
        void createPostWeekHoursNegative() {
            Result<?> r = service.createPost(TEACHER, "teacher", body("postType", "fixed", "name", "图书整理",
                    "unit", "图书馆", "academicTerm", "2025-2026-1", "headcount", "2",
                    "hourlyWage", "15", "hoursPerWeek", "-1"));
            assertEquals(400, r.getCode());
            assertEquals("每周工时必须大于 0", r.getMessage());
        }

        @Test
        @DisplayName("createPost：每月工时缺省返 400")
        void createPostMonthHoursMissing() {
            Result<?> r = service.createPost(TEACHER, "teacher", body("postType", "fixed", "name", "图书整理",
                    "unit", "图书馆", "academicTerm", "2025-2026-1", "headcount", "2",
                    "hourlyWage", "15", "hoursPerWeek", "8"));
            assertEquals(400, r.getCode());
            assertEquals("每月工时不正确", r.getMessage());
        }

        @Test
        @DisplayName("createPost：合规岗位落 draft，warnings 为空，unitUserId 记为本人")
        void createPostSuccess() {
            when(postMapper.insert(any(WorkstudyPost.class))).thenAnswer(invocation -> {
                invocation.getArgument(0, WorkstudyPost.class).setId(9001L);
                return 1;
            });

            Map<String, Object> data = ok(service.createPost(TEACHER, "teacher", body(
                    "postType", "fixed", "name", " 图书整理 ", "unit", "图书馆", "content", "上架",
                    "skill", "细心", "headcount", "3", "hourlyWage", "15", "hoursPerWeek", "8",
                    "hoursPerMonth", "40", "academicTerm", "2025-2026-1",
                    "applyDeadline", "2026-10-20 18:00:00", "interviewAt", "2026-10-22 14:00:00")));

            assertEquals(9001L, data.get("id"));
            assertEquals("draft", data.get("status"));
            assertEquals("草稿", data.get("statusText"));
            assertTrue(((List<?>) data.get("warnings")).isEmpty());

            ArgumentCaptor<WorkstudyPost> captor = ArgumentCaptor.forClass(WorkstudyPost.class);
            verify(postMapper).insert(captor.capture());
            WorkstudyPost inserted = captor.getValue();
            assertEquals(TEACHER, inserted.getUnitUserId());
            assertEquals("图书整理", inserted.getName());
            assertEquals("draft", inserted.getStatus());
            assertEquals(Integer.valueOf(0), inserted.getHiredCount());
            assertEquals(LocalDateTime.of(2026, 10, 20, 18, 0), inserted.getApplyDeadline());
            assertEquals(LocalDateTime.of(2026, 10, 22, 14, 0), inserted.getInterviewAt());
        }

        @Test
        @DisplayName("createPost：临时岗时薪 < 15 只给 warnings 不阻断")
        void createPostTemporaryWageWarning() {
            when(postMapper.insert(any(WorkstudyPost.class))).thenReturn(1);

            Map<String, Object> data = ok(service.createPost(TEACHER, "teacher", body(
                    "postType", "temporary", "name", "资料录入", "unit", "教务处", "headcount", "2",
                    "hourlyWage", "12", "hoursPerWeek", "4", "hoursPerMonth", "16",
                    "academicTerm", "2025-2026-1")));

            List<?> warnings = (List<?>) data.get("warnings");
            assertEquals(1, warnings.size());
            assertTrue(warningsHits(warnings, "临时岗位时薪原则上不低于 15 元/小时"), "实际 " + warnings);
            assertEquals("draft", data.get("status"));
        }

        @Test
        @DisplayName("createPost：周/月工时超上限给两条 warnings")
        void createPostHoursWarnings() {
            when(postMapper.insert(any(WorkstudyPost.class))).thenReturn(1);

            Map<String, Object> data = ok(service.createPost(TEACHER, "teacher", body(
                    "postType", "fixed", "name", "值班", "unit", "图书馆", "headcount", "1",
                    "hourlyWage", "18", "hoursPerWeek", "10", "hoursPerMonth", "50",
                    "academicTerm", "2025-2026-1")));

            assertEquals(2, ((List<?>) data.get("warnings")).size());
        }

        @Test
        @DisplayName("updatePost：岗位不存在返 404")
        void updatePostNotFound() {
            when(postMapper.findById(404L)).thenReturn(null);
            Result<?> r = service.updatePost(TEACHER, "teacher", 404L, body("name", "新岗位"));
            assertEquals(404, r.getCode());
            assertEquals("岗位不存在", r.getMessage());
        }

        @Test
        @DisplayName("updatePost：非本单位 teacher 改别人岗位返 403")
        void updatePostOtherUnit() {
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "draft", "fixed", OTHER_TEACHER));
            Result<?> r = service.updatePost(TEACHER, "teacher", 9001L, body("name", "新岗位"));
            assertEquals(403, r.getCode());
            assertEquals("无权限修改该岗位", r.getMessage());
        }

        @Test
        @DisplayName("updatePost：admin 可改别人岗位")
        void updatePostAdminAllowed() {
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "draft", "fixed", OTHER_TEACHER));
            when(postMapper.update(any(WorkstudyPost.class))).thenReturn(1);
            Result<?> r = service.updatePost(ADMIN, "admin", 9001L, body("name", "新岗位"));
            assertEquals(200, r.getCode());
        }

        @Test
        @DisplayName("updatePost：岗位已关闭返 400")
        void updatePostClosed() {
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "closed", "fixed", TEACHER));
            Result<?> r = service.updatePost(TEACHER, "teacher", 9001L, body("name", "新岗位"));
            assertEquals(400, r.getCode());
            assertEquals("岗位已关闭，不能再修改", r.getMessage());
        }

        @Test
        @DisplayName("updatePost：空 body 返 400")
        void updatePostEmptyBody() {
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "draft", "fixed", TEACHER));
            Result<?> r = service.updatePost(TEACHER, "teacher", 9001L, new HashMap<String, String>());
            assertEquals(400, r.getCode());
            assertEquals("没有要提交的内容", r.getMessage());
        }

        @Test
        @DisplayName("updatePost：已招募且有录用时不许改时薪 → 400")
        void updatePostWageLocked() {
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "recruiting", "fixed", TEACHER));
            when(applicationMapper.countByPostAndStatus(eq(9001L), eq("hired"))).thenReturn(1);

            Result<?> r = service.updatePost(TEACHER, "teacher", 9001L, body("hourlyWage", "20"));
            assertEquals(400, r.getCode());
            assertEquals("岗位已招募且有录用学生，不能修改时薪", r.getMessage());
            verify(postMapper, never()).update(any(WorkstudyPost.class));
        }

        @Test
        @DisplayName("updatePost：已招募且有录用时不许改招聘人数 → 400")
        void updatePostHeadcountLocked() {
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "recruiting", "fixed", TEACHER));
            when(applicationMapper.countByPostAndStatus(eq(9001L), eq("hired"))).thenReturn(1);

            Result<?> r = service.updatePost(TEACHER, "teacher", 9001L, body("headcount", "5"));
            assertEquals(400, r.getCode());
            assertEquals("岗位已招募且有录用学生，不能修改招聘人数", r.getMessage());
        }

        @Test
        @DisplayName("updatePost：招聘人数小于已录用人数返 400")
        void updatePostHeadcountBelowHired() {
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "pending", "fixed", TEACHER));
            when(applicationMapper.countByPostAndStatus(eq(9001L), eq("hired"))).thenReturn(2);

            Result<?> r = service.updatePost(TEACHER, "teacher", 9001L, body("headcount", "1"));
            assertEquals(400, r.getCode());
            assertEquals("招聘人数不能小于已录用人数", r.getMessage());
        }

        @Test
        @DisplayName("updatePost：内容修改成功并透传 warnings")
        void updatePostSuccess() {
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "draft", "fixed", TEACHER));
            when(applicationMapper.countByPostAndStatus(eq(9001L), eq("hired"))).thenReturn(0);
            when(postMapper.update(any(WorkstudyPost.class))).thenReturn(1);

            Map<String, Object> data = ok(service.updatePost(TEACHER, "teacher", 9001L,
                    body("content", "新内容", "hourlyWage", "10", "postType", "temporary")));

            assertEquals(9001L, data.get("id"));
            assertEquals(1, ((List<?>) data.get("warnings")).size());

            ArgumentCaptor<WorkstudyPost> captor = ArgumentCaptor.forClass(WorkstudyPost.class);
            verify(postMapper).update(captor.capture());
            assertEquals("新内容", captor.getValue().getContent());
            assertEquals(new BigDecimal("10"), captor.getValue().getHourlyWage());
        }

        @Test
        @DisplayName("submitPost：draft → pending")
        void submitPostSuccess() {
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "draft", "fixed", TEACHER));
            when(postMapper.updateStatus(9001L, "draft", "pending", null, null, null)).thenReturn(1);

            Result<?> r = service.submitPost(TEACHER, "teacher", 9001L);

            assertEquals(200, r.getCode());
            assertMessage(r, "已提交资助中心审批");
            verify(postMapper).updateStatus(9001L, "draft", "pending", null, null, null);
        }

        @Test
        @DisplayName("submitPost：岗位不存在返 404")
        void submitPostNotFound() {
            when(postMapper.findById(404L)).thenReturn(null);
            Result<?> r = service.submitPost(TEACHER, "teacher", 404L);
            assertEquals(404, r.getCode());
            assertEquals("岗位不存在", r.getMessage());
        }

        @Test
        @DisplayName("submitPost：非本单位 teacher 返 403")
        void submitPostOtherUnit() {
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "draft", "fixed", OTHER_TEACHER));
            Result<?> r = service.submitPost(TEACHER, "teacher", 9001L);
            assertEquals(403, r.getCode());
            assertEquals("无权限操作该岗位", r.getMessage());
        }

        @Test
        @DisplayName("submitPost：非 draft 状态返 400 并回显当前中文状态")
        void submitPostWrongStatus() {
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "recruiting", "fixed", TEACHER));
            Result<?> r = service.submitPost(TEACHER, "teacher", 9001L);
            assertEquals(400, r.getCode());
            assertMessage(r, "该岗位当前是「招募中」，不能提交审批");
        }

        @Test
        @DisplayName("submitPost：并发冲突（更新行数 0）返 400")
        void submitPostConflict() {
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "draft", "fixed", TEACHER));
            when(postMapper.updateStatus(9001L, "draft", "pending", null, null, null)).thenReturn(0);
            Result<?> r = service.submitPost(TEACHER, "teacher", 9001L);
            assertEquals(400, r.getCode());
            assertEquals("该岗位当前状态已变化，请刷新后重试", r.getMessage());
        }

        @Test
        @DisplayName("reviewPost：admin 通过 → recruiting，记录审批人")
        void reviewPostApprove() {
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "pending", "fixed", TEACHER));
            when(postMapper.updateStatus(eq(9001L), eq("pending"), eq("recruiting"), eq(ADMIN), eq("同意设岗"), any(LocalDateTime.class)))
                    .thenReturn(1);

            Result<?> r = service.reviewPost(ADMIN, "admin", 9001L, body("approve", "true", "note", "同意设岗"));

            assertEquals(200, r.getCode());
            assertMessage(r, "岗位已通过，进入招募");
            verify(postMapper).updateStatus(eq(9001L), eq("pending"), eq("recruiting"), eq(ADMIN), eq("同意设岗"), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("reviewPost：admin 不通过 → rejected")
        void reviewPostReject() {
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "pending", "fixed", TEACHER));
            when(postMapper.updateStatus(eq(9001L), eq("pending"), eq("rejected"), eq(ADMIN), isNull(), any(LocalDateTime.class)))
                    .thenReturn(1);

            Result<?> r = service.reviewPost(ADMIN, "admin", 9001L, body("approve", "false"));

            assertEquals(200, r.getCode());
            assertMessage(r, "岗位未通过");
            verify(postMapper).updateStatus(eq(9001L), eq("pending"), eq("rejected"), eq(ADMIN), isNull(), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("reviewPost：非 pending 状态返 400")
        void reviewPostWrongStatus() {
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "draft", "fixed", TEACHER));
            Result<?> r = service.reviewPost(ADMIN, "admin", 9001L, body("approve", "true"));
            assertEquals(400, r.getCode());
            assertMessage(r, "该岗位当前是「草稿」，不能审批");
        }

        @Test
        @DisplayName("reviewPost：岗位不存在返 404")
        void reviewPostNotFound() {
            when(postMapper.findById(404L)).thenReturn(null);
            Result<?> r = service.reviewPost(ADMIN, "admin", 404L, body("approve", "true"));
            assertEquals(404, r.getCode());
            assertEquals("岗位不存在", r.getMessage());
        }

        @Test
        @DisplayName("reviewPost：并发冲突（行数 0）返 400")
        void reviewPostConflict() {
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "pending", "fixed", TEACHER));
            when(postMapper.updateStatus(eq(9001L), eq("pending"), eq("recruiting"), eq(ADMIN), isNull(), any(LocalDateTime.class)))
                    .thenReturn(0);
            Result<?> r = service.reviewPost(ADMIN, "admin", 9001L, body("approve", "true"));
            assertEquals(400, r.getCode());
            assertEquals("该岗位当前状态已变化，请刷新后重试", r.getMessage());
        }

        @Test
        @DisplayName("closePost：关闭并写入终止情形说明")
        void closePostSuccess() {
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "recruiting", "fixed", TEACHER));
            when(postMapper.updateStatus(eq(9001L), isNull(), eq("closed"), eq(TEACHER), eq("岗位撤销"), any(LocalDateTime.class)))
                    .thenReturn(1);

            Result<?> r = service.closePost(TEACHER, "teacher", 9001L, body("note", "岗位撤销"));

            assertEquals(200, r.getCode());
            assertMessage(r, "岗位已关闭");
            verify(postMapper).updateStatus(eq(9001L), isNull(), eq("closed"), eq(TEACHER), eq("岗位撤销"), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("closePost：note 缺省时使用默认终止情形说明")
        void closePostDefaultNote() {
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "recruiting", "fixed", TEACHER));
            when(postMapper.updateStatus(eq(9001L), isNull(), eq("closed"), eq(TEACHER), anyString(), any(LocalDateTime.class)))
                    .thenReturn(1);

            Result<?> r = service.closePost(TEACHER, "teacher", 9001L, null);

            assertEquals(200, r.getCode(), "期望 200，实际 " + r.getCode() + " / " + r.getMessage());
            assertMessage(r, "岗位已关闭");
            ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
            verify(postMapper).updateStatus(eq(9001L), isNull(), eq("closed"), eq(TEACHER), note.capture(), any(LocalDateTime.class));
            assertNotNull(note.getValue());
            assertTrue(note.getValue().contains("岗位撤销"), "实际 " + note.getValue());
        }

        @Test
        @DisplayName("closePost：已关闭岗位返 400")
        void closePostAlreadyClosed() {
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "closed", "fixed", TEACHER));
            Result<?> r = service.closePost(TEACHER, "teacher", 9001L, null);
            assertEquals(400, r.getCode());
            assertEquals("该岗位已关闭", r.getMessage());
        }

        @Test
        @DisplayName("closePost：非本单位 teacher 返 403")
        void closePostOtherUnit() {
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "recruiting", "fixed", OTHER_TEACHER));
            Result<?> r = service.closePost(TEACHER, "teacher", 9001L, null);
            assertEquals(403, r.getCode());
            assertEquals("无权限操作该岗位", r.getMessage());
        }
    }

    // =====================================================================
    // D. 学生申请
    // =====================================================================

    @Nested
    @DisplayName("申请：提交 / 我的 / 工时 / 辞职")
    class Applications {

        @Test
        @DisplayName("apply：理由为空返 400")
        void applyBlankReason() {
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            Result<?> r = service.apply(STUDENT, "student", 9001L, body("reason", "   "));
            assertEquals(400, r.getCode());
            assertEquals("请填写申请理由", r.getMessage());
            verify(applicationMapper, never()).insert(any(WorkstudyApplication.class));
        }

        @Test
        @DisplayName("apply：理由超过 500 字返 400")
        void applyReasonTooLong() {
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            StringBuilder long0 = new StringBuilder();
            for (int i = 0; i < 501; i++) {
                long0.append('困');
            }
            Result<?> r = service.apply(STUDENT, "student", 9001L, body("reason", long0.toString()));
            assertEquals(400, r.getCode());
            assertEquals("申请理由不能超过 500 字", r.getMessage());
        }

        @Test
        @DisplayName("apply：岗位不存在返 404")
        void applyPostNotFound() {
            when(postMapper.findById(404L)).thenReturn(null);
            Result<?> r = service.apply(STUDENT, "student", 404L, body("reason", "想参加"));
            assertEquals(404, r.getCode());
            assertEquals("岗位不存在", r.getMessage());
        }

        @Test
        @DisplayName("apply：postId 为 null 返 404 岗位不存在")
        void applyNullPostId() {
            Result<?> r = service.apply(STUDENT, "student", null, body("reason", "想参加"));
            assertEquals(404, r.getCode());
            assertEquals("岗位不存在", r.getMessage());
        }

        @Test
        @DisplayName("apply：非 recruiting 岗位返 400 该岗位当前不接受申请")
        void applyNotRecruiting() {
            when(postMapper.findById(9003L)).thenReturn(post(9003L, "pending", "fixed", TEACHER));
            Result<?> r = service.apply(STUDENT, "student", 9003L, body("reason", "想参加"));
            assertEquals(400, r.getCode());
            assertEquals("该岗位当前不接受申请", r.getMessage());
        }

        @Test
        @DisplayName("apply：申请已截止返 400")
        void applyDeadlinePassed() {
            WorkstudyPost p = recruitingPost();
            p.setApplyDeadline(LocalDateTime.now().minusDays(1));
            when(postMapper.findById(9001L)).thenReturn(p);
            Result<?> r = service.apply(STUDENT, "student", 9001L, body("reason", "想参加"));
            assertEquals(400, r.getCode());
            assertEquals("该岗位申请已截止", r.getMessage());
        }

        @Test
        @DisplayName("apply：重复申请返 400 你已经申请过该岗位")
        void applyDuplicate() {
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            when(applicationMapper.countByPostStudent(9001L, STUDENT)).thenReturn(1);
            Result<?> r = service.apply(STUDENT, "student", 9001L, body("reason", "想参加"));
            assertEquals(400, r.getCode());
            assertEquals("你已经申请过该岗位", r.getMessage());
        }

        @Test
        @DisplayName("apply：名额已满返 400 该岗位已招满")
        void applyFull() {
            WorkstudyPost p = recruitingPost();
            p.setHeadcount(1);
            when(postMapper.findById(9001L)).thenReturn(p);
            when(applicationMapper.countByPostAndStatus(eq(9001L), eq("hired"))).thenReturn(1);
            Result<?> r = service.apply(STUDENT, "student", 9001L, body("reason", "想参加"));
            assertEquals(400, r.getCode());
            assertEquals("该岗位已招满", r.getMessage());
        }

        @Test
        @DisplayName("apply：同学期已有固定岗在岗返 400")
        void applyFixedConflict() {
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            when(applicationMapper.countByPostStudent(9001L, STUDENT)).thenReturn(0);
            when(applicationMapper.countByPostAndStatus(eq(9001L), eq("hired"))).thenReturn(0);
            when(applicationMapper.listHiredByStudent(STUDENT, "fixed", "2025-2026-1"))
                    .thenReturn(Collections.singletonList(app(8000L, 8001L, STUDENT, "hired")));

            Result<?> r = service.apply(STUDENT, "student", 9001L, body("reason", "想参加"));
            assertEquals(400, r.getCode());
            assertEquals("同一学期只能在一个固定岗位在岗", r.getMessage());
        }

        @Test
        @DisplayName("apply：临时岗不受固定岗在岗限制")
        void applyTemporaryNoFixedLimit() {
            WorkstudyPost p = post(9002L, "recruiting", "temporary", TEACHER);
            when(postMapper.findById(9002L)).thenReturn(p);
            when(applicationMapper.countByPostStudent(9002L, STUDENT)).thenReturn(0);
            when(applicationMapper.countByPostAndStatus(9002L, "hired")).thenReturn(0);
            when(applicationMapper.insert(any(WorkstudyApplication.class))).thenReturn(1);

            Result<?> r = service.apply(STUDENT, "student", 9002L, body("reason", "想参加"));

            assertEquals(200, r.getCode());
            verify(applicationMapper, never()).listHiredByStudent(anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("apply：成功落 pending 并回显 id / status / statusText")
        void applySuccess() {
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            when(applicationMapper.countByPostStudent(9001L, STUDENT)).thenReturn(0);
            when(applicationMapper.countByPostAndStatus(eq(9001L), eq("hired"))).thenReturn(0);
            when(applicationMapper.listHiredByStudent(STUDENT, "fixed", "2025-2026-1")).thenReturn(new ArrayList<WorkstudyApplication>());
            when(applicationMapper.insert(any(WorkstudyApplication.class))).thenAnswer(invocation -> {
                invocation.getArgument(0, WorkstudyApplication.class).setId(9100L);
                return 1;
            });

            Result<?> r = service.apply(STUDENT, "student", 9001L, body("reason", " 家庭经济困难 ", "evidence", "低保证明"));

            Map<String, Object> data = ok(r);
            assertEquals(9100L, data.get("id"));
            assertEquals("pending", data.get("status"));
            assertEquals("待审核", data.get("statusText"));
            assertMessage(r, "已提交申请");

            ArgumentCaptor<WorkstudyApplication> captor = ArgumentCaptor.forClass(WorkstudyApplication.class);
            verify(applicationMapper).insert(captor.capture());
            assertEquals("家庭经济困难", captor.getValue().getReason());
            assertEquals("低保证明", captor.getValue().getEvidence());
            assertEquals("2025-2026-1", captor.getValue().getAcademicTerm());
        }

        @Test
        @DisplayName("mine：学生返申请 / 在岗 / 汇总")
        void mineForStudent() {
            when(applicationMapper.listByStudent(STUDENT, 100, 0))
                    .thenReturn(Arrays.asList(app(9001L, 9001L, STUDENT, "hired"), app(9003L, 9002L, STUDENT, "pending")));
            when(applicationMapper.listHiredByStudent(STUDENT, null, null))
                    .thenReturn(Collections.singletonList(app(9001L, 9001L, STUDENT, "hired")));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            when(timesheetMapper.sumHours(STUDENT, null, null)).thenReturn(new BigDecimal("7.5"));
            when(payrollMapper.sumAmount(anyString(), isNull())).thenReturn(new BigDecimal("112.50"));

            Map<String, Object> data = ok(service.mine(STUDENT, "student"));

            assertEquals(2, ((List<?>) data.get("applications")).size());
            List<?> jobs = (List<?>) data.get("jobs");
            assertEquals(1, jobs.size());
            Map<String, Object> job = map(jobs.get(0));
            assertEquals(9001L, job.get("postId"));
            assertEquals("固定岗", job.get("postTypeText"));
            assertEquals("已录用", job.get("statusText"));
            assertEquals(0, new BigDecimal("7.5").compareTo((BigDecimal) job.get("totalHours")));

            Map<String, Object> summary = map(data.get("summary"));
            assertEquals(2, counter(summary.get("applied")));
            assertEquals(1, counter(summary.get("hired")));
            assertEquals(0, new BigDecimal("7.5").compareTo((BigDecimal) summary.get("totalHours")));
            assertEquals(0, new BigDecimal("112.50").compareTo((BigDecimal) summary.get("totalAmount")));
            assertEquals(WorkstudyService.currentTerm(), summary.get("term"));
        }

        @Test
        @DisplayName("mine：无已发放酬金时按工时 × 时薪估算")
        void mineEstimatesAmount() {
            when(applicationMapper.listByStudent(STUDENT, 100, 0))
                    .thenReturn(Collections.singletonList(app(9001L, 9001L, STUDENT, "hired")));
            when(applicationMapper.listHiredByStudent(STUDENT, null, null))
                    .thenReturn(Collections.singletonList(app(9001L, 9001L, STUDENT, "hired")));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            when(timesheetMapper.sumHours(STUDENT, null, null)).thenReturn(new BigDecimal("4"));
            when(payrollMapper.sumAmount(anyString(), isNull())).thenReturn(BigDecimal.ZERO);

            Map<String, Object> summary = map(ok(service.mine(STUDENT, "student")).get("summary"));
            assertEquals(0, new BigDecimal("60").compareTo((BigDecimal) summary.get("totalAmount")));
        }

        @Test
        @DisplayName("mine：teacher 可查自己的（STAFF 允许）")
        void mineForStaff() {
            when(applicationMapper.listByStudent(TEACHER, 100, 0)).thenReturn(new ArrayList<WorkstudyApplication>());
            when(applicationMapper.listHiredByStudent(TEACHER, null, null)).thenReturn(new ArrayList<WorkstudyApplication>());
            when(payrollMapper.sumAmount(anyString(), isNull())).thenReturn(null);

            Map<String, Object> data = ok(service.mine(TEACHER, "teacher"));
            assertEquals(0, ((List<?>) data.get("applications")).size());
            assertEquals(0, counter(map(data.get("summary")).get("hired")));
        }

        @Test
        @DisplayName("myTimesheets：月份格式非法返 400")
        void myTimesheetsBadMonth() {
            Result<?> r = service.myTimesheets(STUDENT, "student", "2026-13");
            assertEquals(400, r.getCode());
            assertEquals("月份格式应为 2026-10", r.getMessage());
        }

        @Test
        @DisplayName("myTimesheets：正常月未超限")
        void myTimesheetsWithinLimit() {
            when(timesheetMapper.countMine(STUDENT, "2026-10")).thenReturn(2);
            when(timesheetMapper.listMine(STUDENT, "2026-10", 200, 0)).thenReturn(Arrays.asList(
                    sheet(9001L, STUDENT, "2026-10", "2026-10-08", "3.0", "confirmed"),
                    sheet(9002L, STUDENT, "2026-10", "2026-10-12", "4.5", "submitted")));
            when(timesheetMapper.sumHours(STUDENT, "2026-10", null)).thenReturn(new BigDecimal("7.5"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());

            Map<String, Object> data = ok(service.myTimesheets(STUDENT, "student", "2026-10"));

            assertEquals(2, ((List<?>) data.get("items")).size());
            assertEquals(0, new BigDecimal("7.5").compareTo((BigDecimal) data.get("totalHours")));
            assertEquals(0, new BigDecimal("112.50").compareTo((BigDecimal) data.get("totalAmount")));
            assertFalse(truthy(data.get("overLimit")));
            assertEquals("2026-10", data.get("month"));
            Map<String, Object> first = items(data).get(0);
            assertEquals("已确认", first.get("statusText"));
            assertEquals(TEACHER, first.get("recordedBy"));
            assertFalse(truthy(first.get("overLimit")));
        }

        @Test
        @DisplayName("myTimesheets：月累计 > 40 小时置 overLimit=true（只提示不阻拦）")
        void myTimesheetsOverMonthLimit() {
            when(timesheetMapper.countMine(STUDENT, "2026-10")).thenReturn(3);
            when(timesheetMapper.listMine(STUDENT, "2026-10", 200, 0)).thenReturn(Arrays.asList(
                    sheet(9001L, STUDENT, "2026-10", "2026-10-08", "8.0", "submitted"),
                    sheet(9002L, STUDENT, "2026-10", "2026-10-20", "2.0", "submitted"),
                    sheet(9003L, STUDENT, "2026-10", "2026-10-22", "32.0", "submitted")));
            when(timesheetMapper.sumHours(STUDENT, "2026-10", null)).thenReturn(new BigDecimal("42.0"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());

            Map<String, Object> data = ok(service.myTimesheets(STUDENT, "student", "2026-10"));

            assertTrue(truthy(data.get("overLimit")));
            assertEquals(0, new BigDecimal("42.0").compareTo((BigDecimal) data.get("totalHours")));
        }

        @Test
        @DisplayName("myTimesheets：某周 > 8 小时置 overLimit=true")
        void myTimesheetsOverWeekLimit() {
            when(timesheetMapper.countMine(STUDENT, "2026-10")).thenReturn(2);
            when(timesheetMapper.listMine(STUDENT, "2026-10", 200, 0)).thenReturn(Arrays.asList(
                    sheet(9001L, STUDENT, "2026-10", "2026-10-08", "5.0", "submitted"),
                    sheet(9002L, STUDENT, "2026-10", "2026-10-09", "5.0", "submitted")));
            when(timesheetMapper.sumHours(STUDENT, "2026-10", null)).thenReturn(new BigDecimal("10.0"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());

            Map<String, Object> data = ok(service.myTimesheets(STUDENT, "student", "2026-10"));
            assertTrue(truthy(data.get("overLimit")));
        }

        @Test
        @DisplayName("myTimesheets：month 缺省时回落到当前月份")
        void myTimesheetsDefaultMonth() {
            when(timesheetMapper.countMine(STUDENT, null)).thenReturn(0);
            when(timesheetMapper.sumHours(STUDENT, null, null)).thenReturn(null);

            Map<String, Object> data = ok(service.myTimesheets(STUDENT, "student", "  "));

            assertEquals(LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")), data.get("month"));
            assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) data.get("totalHours")));
        }

        @Test
        @DisplayName("resign：理由为空返 400")
        void resignBlankReason() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "hired"));
            Result<?> r = service.resign(STUDENT, "student", 9001L, body("reason", ""));
            assertEquals(400, r.getCode());
            assertEquals("请填写辞职理由", r.getMessage());
            verify(applicationMapper, never()).updateStatus(any(Map.class));
        }

        @Test
        @DisplayName("resign：申请不存在返 404")
        void resignNotFound() {
            when(applicationMapper.findById(404L)).thenReturn(null);
            Result<?> r = service.resign(STUDENT, "student", 404L, body("reason", "课表冲突"));
            assertEquals(404, r.getCode());
            assertEquals("申请不存在", r.getMessage());
        }

        @Test
        @DisplayName("resign：非本人申请返 403")
        void resignNotOwner() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, OTHER_STUDENT, "hired"));
            Result<?> r = service.resign(STUDENT, "student", 9001L, body("reason", "课表冲突"));
            assertEquals(403, r.getCode());
            assertEquals("无权限操作该申请", r.getMessage());
        }

        @Test
        @DisplayName("resign：非在岗状态返 400 并回显中文状态")
        void resignWrongStatus() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "pending"));
            Result<?> r = service.resign(STUDENT, "student", 9001L, body("reason", "课表冲突"));
            assertEquals(400, r.getCode());
            assertMessage(r, "该申请当前是「待审核」，不能申请辞职");
        }

        @Test
        @DisplayName("resign：hired → resigning 成功")
        void resignSuccess() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "hired"));
            when(applicationMapper.updateStatus(any(Map.class))).thenReturn(1);

            Result<?> r = service.resign(STUDENT, "student", 9001L, body("reason", "课程调整"));

            assertEquals(200, r.getCode());
            assertMessage(r, "辞职申请已提交");

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(applicationMapper).updateStatus(captor.capture());
            assertEquals("resigning", captor.getValue().get("toStatus"));
            assertEquals("hired", captor.getValue().get("fromStatus"));
            assertEquals("课程调整", captor.getValue().get("resignReason"));
        }

        @Test
        @DisplayName("resign：并发冲突（行数 0）返 400")
        void resignConflict() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "hired"));
            when(applicationMapper.updateStatus(any(Map.class))).thenReturn(0);
            Result<?> r = service.resign(STUDENT, "student", 9001L, body("reason", "课程调整"));
            assertEquals(400, r.getCode());
            assertEquals("该申请当前状态已变化，请刷新后重试", r.getMessage());
        }
    }

    // =====================================================================
    // E. 申请列表 / 面试 / 录用
    // =====================================================================

    @Nested
    @DisplayName("面试录用：列表 / 面试 / 录用 / 未录用 / 确认离岗")
    class InterviewHire {

        @Test
        @DisplayName("manageApplications：teacher 限定本单位岗位")
        void manageApplicationsForTeacher() {
            when(applicationMapper.countByPost(9001L, "pending", TEACHER, null)).thenReturn(1);
            when(applicationMapper.listByPost(9001L, "pending", TEACHER, null, 20, 0))
                    .thenReturn(Collections.singletonList(app(9001L, 9001L, STUDENT, "pending")));

            Map<String, Object> data = ok(service.manageApplications(TEACHER, "teacher", 9001L, "pending", 1, 20));

            assertEquals(1, counter(data.get("total")));
            Map<String, Object> row = items(data).get(0);
            assertEquals("陈大伟", row.get("studentName"));
            assertEquals("2024003", row.get("studentNo"));
            assertEquals("软工2402", row.get("className"));
            assertEquals(1, counter(row.get("poverty")));
            assertEquals("待审核", row.get("statusText"));
            assertEquals("固定岗", row.get("postTypeText"));
        }

        @Test
        @DisplayName("manageApplications：admin 不加单位限定")
        void manageApplicationsForAdmin() {
            when(applicationMapper.countByPost(isNull(), isNull(), isNull(), isNull())).thenReturn(0);
            Map<String, Object> data = ok(service.manageApplications(ADMIN, "admin", null, null, 1, 20));
            assertEquals(0, counter(data.get("total")));
            verify(applicationMapper).countByPost(isNull(), isNull(), isNull(), isNull());
        }

        @Test
        @DisplayName("manageApplications：状态非法返 400")
        void manageApplicationsBadStatus() {
            Result<?> r = service.manageApplications(TEACHER, "teacher", 9001L, "unknown", 1, 20);
            assertEquals(400, r.getCode());
            assertEquals("申请状态不正确", r.getMessage());
        }

        @Test
        @DisplayName("interview：结果为非法取值返 400")
        void interviewBadResult() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "pending"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());

            Result<?> r = service.interview(TEACHER, "teacher", 9001L, body("result", "maybe"));
            assertEquals(400, r.getCode());
            assertEquals("面试结果只支持 pass / fail", r.getMessage());
        }

        @Test
        @DisplayName("interview：面试通过 → interview_passed")
        void interviewPass() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "pending"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            when(applicationMapper.updateStatus(any(Map.class))).thenReturn(1);

            Result<?> r = service.interview(TEACHER, "teacher", 9001L, body("result", "pass", "note", "表现不错"));

            assertEquals(200, r.getCode());
            assertMessage(r, "面试通过，可办理录用");

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(applicationMapper).updateStatus(captor.capture());
            assertEquals("interview_passed", captor.getValue().get("toStatus"));
            assertEquals("pass", captor.getValue().get("interviewResult"));
            assertEquals("表现不错", captor.getValue().get("interviewNote"));
        }

        @Test
        @DisplayName("interview：面试未通过 → interview_failed")
        void interviewFail() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "pending"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            when(applicationMapper.updateStatus(any(Map.class))).thenReturn(1);

            Result<?> r = service.interview(TEACHER, "teacher", 9001L, body("result", "fail"));

            assertEquals(200, r.getCode());
            assertMessage(r, "面试未通过");
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(applicationMapper).updateStatus(captor.capture());
            assertEquals("interview_failed", captor.getValue().get("toStatus"));
        }

        @Test
        @DisplayName("interview：非 pending 状态返 400")
        void interviewWrongStatus() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "hired"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());

            Result<?> r = service.interview(TEACHER, "teacher", 9001L, body("result", "pass"));
            assertEquals(400, r.getCode());
            assertMessage(r, "该申请当前是「已录用」，不能面试");
        }

        @Test
        @DisplayName("interview：非本单位 teacher 返 403")
        void interviewOtherUnit() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "pending"));
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "recruiting", "fixed", OTHER_TEACHER));

            Result<?> r = service.interview(TEACHER, "teacher", 9001L, body("result", "pass"));
            assertEquals(403, r.getCode());
            assertEquals("无权限面试该岗位的申请", r.getMessage());
        }

        @Test
        @DisplayName("interview：申请不存在返 404")
        void interviewNotFound() {
            when(applicationMapper.findById(404L)).thenReturn(null);
            Result<?> r = service.interview(TEACHER, "teacher", 404L, body("result", "pass"));
            assertEquals(404, r.getCode());
            assertEquals("申请不存在", r.getMessage());
        }

        @Test
        @DisplayName("interview：admin 可面试任意岗位")
        void interviewAsAdmin() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "pending"));
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "recruiting", "fixed", OTHER_TEACHER));
            when(applicationMapper.updateStatus(any(Map.class))).thenReturn(1);

            Result<?> r = service.interview(ADMIN, "admin", 9001L, body("result", "pass"));
            assertEquals(200, r.getCode());
        }

        @Test
        @DisplayName("hire：面试通过 → hired，hired_count +1")
        void hireSuccess() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "interview_passed"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            when(applicationMapper.countByPostAndStatus(eq(9001L), eq("hired"))).thenReturn(0);
            when(applicationMapper.listHiredByStudent(STUDENT, "fixed", "2025-2026-1")).thenReturn(new ArrayList<WorkstudyApplication>());
            when(applicationMapper.updateStatus(any(Map.class))).thenReturn(1);
            when(postMapper.updateHiredCount(9001L, 1)).thenReturn(1);

            Result<?> r = service.hire(TEACHER, "teacher", 9001L, body("note", "同意录用"));

            assertEquals(200, r.getCode());
            assertMessage(r, "已录用");
            verify(postMapper).updateHiredCount(9001L, 1);
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(applicationMapper).updateStatus(captor.capture());
            assertEquals("hired", captor.getValue().get("toStatus"));
            assertNotNull(captor.getValue().get("hiredAt"));
        }

        @Test
        @DisplayName("hire：非 interview_passed 状态返 400")
        void hireWrongStatus() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "pending"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());

            Result<?> r = service.hire(TEACHER, "teacher", 9001L, null);
            assertEquals(400, r.getCode());
            assertMessage(r, "该申请当前是「待审核」，不能录用");
            verify(postMapper, never()).updateHiredCount(anyLong(), anyInt());
        }

        @Test
        @DisplayName("hire：名额已满返 400")
        void hireFull() {
            WorkstudyPost p = recruitingPost();
            p.setHeadcount(1);
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "interview_passed"));
            when(postMapper.findById(9001L)).thenReturn(p);
            when(applicationMapper.countByPostAndStatus(eq(9001L), eq("hired"))).thenReturn(1);

            Result<?> r = service.hire(TEACHER, "teacher", 9001L, null);
            assertEquals(400, r.getCode());
            assertEquals("该岗位已招满", r.getMessage());
        }

        @Test
        @DisplayName("hire：同学期已有固定岗在岗返 400（再次校验第二十一条）")
        void hireFixedConflict() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "interview_passed"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            when(applicationMapper.countByPostAndStatus(eq(9001L), eq("hired"))).thenReturn(0);
            when(applicationMapper.listHiredByStudent(STUDENT, "fixed", "2025-2026-1"))
                    .thenReturn(Collections.singletonList(app(8000L, 8001L, STUDENT, "hired")));

            Result<?> r = service.hire(TEACHER, "teacher", 9001L, null);
            assertEquals(400, r.getCode());
            assertEquals("同一学期只能在一个固定岗位在岗", r.getMessage());
        }

        @Test
        @DisplayName("hire：岗位名额同步失败抛 IllegalStateException（回滚）")
        void hireHiredCountSyncFails() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "interview_passed"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            when(applicationMapper.countByPostAndStatus(eq(9001L), eq("hired"))).thenReturn(0);
            when(applicationMapper.listHiredByStudent(STUDENT, "fixed", "2025-2026-1")).thenReturn(new ArrayList<WorkstudyApplication>());
            when(applicationMapper.updateStatus(any(Map.class))).thenReturn(1);
            when(postMapper.updateHiredCount(9001L, 1)).thenReturn(0);

            assertThrows(IllegalStateException.class, () -> service.hire(TEACHER, "teacher", 9001L, null));
        }

        @Test
        @DisplayName("hire：非本单位 teacher 返 403")
        void hireOtherUnit() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "interview_passed"));
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "recruiting", "fixed", OTHER_TEACHER));

            Result<?> r = service.hire(TEACHER, "teacher", 9001L, null);
            assertEquals(403, r.getCode());
            assertEquals("无权限录用该岗位的申请", r.getMessage());
        }

        @Test
        @DisplayName("reject：interview_failed → rejected")
        void rejectFromInterviewFailed() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "interview_failed"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            when(applicationMapper.updateStatus(any(Map.class))).thenReturn(1);

            Result<?> r = service.reject(TEACHER, "teacher", 9001L, body("note", "名额有限"));

            assertEquals(200, r.getCode());
            assertMessage(r, "已标记未录用");
        }

        @Test
        @DisplayName("reject：已录用状态返 400")
        void rejectHired() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "hired"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());

            Result<?> r = service.reject(TEACHER, "teacher", 9001L, null);
            assertEquals(400, r.getCode());
            assertMessage(r, "该申请当前是「已录用」，不能标记未录用");
        }

        @Test
        @DisplayName("reject：非本单位 teacher 返 403")
        void rejectOtherUnit() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "pending"));
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "recruiting", "fixed", OTHER_TEACHER));

            Result<?> r = service.reject(TEACHER, "teacher", 9001L, null);
            assertEquals(403, r.getCode());
            assertEquals("无权限处理该岗位的申请", r.getMessage());
        }

        @Test
        @DisplayName("reject：申请不存在返 404")
        void rejectNotFound() {
            when(applicationMapper.findById(404L)).thenReturn(null);
            Result<?> r = service.reject(TEACHER, "teacher", 404L, null);
            assertEquals(404, r.getCode());
            assertEquals("申请不存在", r.getMessage());
        }

        @Test
        @DisplayName("confirmResign：resigning → resigned，hired_count -1")
        void confirmResignSuccess() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "resigning"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            when(applicationMapper.updateStatus(any(Map.class))).thenReturn(1);
            when(postMapper.updateHiredCount(9001L, -1)).thenReturn(1);

            Result<?> r = service.confirmResign(TEACHER, "teacher", 9001L, body("note", "同意离岗"));

            assertEquals(200, r.getCode());
            assertMessage(r, "已确认离岗");
            verify(postMapper).updateHiredCount(9001L, -1);
        }

        @Test
        @DisplayName("confirmResign：非 resigning 状态返 400")
        void confirmResignWrongStatus() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "hired"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());

            Result<?> r = service.confirmResign(TEACHER, "teacher", 9001L, null);
            assertEquals(400, r.getCode());
            assertMessage(r, "该申请不在辞职流程中");
        }

        @Test
        @DisplayName("confirmResign：学生无权确认返 403")
        void confirmResignStudentForbidden() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "resigning"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());

            Result<?> r = service.confirmResign(STUDENT, "student", 9001L, null);
            assertEquals(403, r.getCode());
            assertEquals("无权限处理该岗位的申请", r.getMessage());
        }

        @Test
        @DisplayName("confirmResign：并发下 updateStatus 影响 0 行返 400")
        void confirmResignRaceLost() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "resigning"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            when(applicationMapper.updateStatus(any(Map.class))).thenReturn(0);

            Result<?> r = service.confirmResign(TEACHER, "teacher", 9001L, body("note", "同意"));
            assertEquals(400, r.getCode());
            assertMessage(r, "状态已变化");
            verify(postMapper, never()).updateHiredCount(anyLong(), anyInt());
        }
    }

    // =====================================================================
    // E2. 前端契约宽容度（字符串数值 / 三种时间格式 / 未来学期）
    // =====================================================================

    @Nested
    @DisplayName("契约宽容度：字符串数值 / 时间格式 / 学期通配")
    class FrontendTolerance {

        private Map<String, String> postBody() {
            Map<String, String> body = new LinkedHashMap<String, String>();
            body.put("postType", "fixed");
            body.put("unit", "图书馆");
            body.put("name", "图书整理员");
            body.put("content", "整理书架");
            body.put("skill", "细心");
            body.put("headcount", "3");
            body.put("hourlyWage", "15.5");
            body.put("hoursPerWeek", "6.5");
            body.put("hoursPerMonth", "30.5");
            body.put("academicTerm", "2035-2036-1");
            return body;
        }

        @Test
        @DisplayName("createPost：字符串数值可强转，未来学期 2035-2036-1 通过")
        void createPostStringNumbers() {
            when(postMapper.insert(any(WorkstudyPost.class))).thenReturn(1);

            Result<?> r = service.createPost(TEACHER, "teacher", postBody());

            assertEquals(200, r.getCode(), "期望 200，实际 " + r.getCode() + " / " + r.getMessage());
            assertEquals("草稿", map(r.getData()).get("statusText"));
            ArgumentCaptor<WorkstudyPost> captor = ArgumentCaptor.forClass(WorkstudyPost.class);
            verify(postMapper).insert(captor.capture());
            assertEquals(Integer.valueOf(3), captor.getValue().getHeadcount());
            assertEquals(0, new BigDecimal("15.5").compareTo(captor.getValue().getHourlyWage()));
            assertEquals("2035-2036-1", captor.getValue().getAcademicTerm());
        }

        @Test
        @DisplayName("createPost：字符串数值前后空白也能解析")
        void createPostPaddedNumbers() {
            Map<String, String> body = postBody();
            body.put("headcount", "  3 ");
            body.put("hourlyWage", " 15.5 ");
            when(postMapper.insert(any(WorkstudyPost.class))).thenReturn(1);

            Result<?> r = service.createPost(TEACHER, "teacher", body);
            assertEquals(200, r.getCode(), "期望 200，实际 " + r.getCode() + " / " + r.getMessage());
        }

        @Test
        @DisplayName("createPost：小数形式的整数字符串 \"3.0\" 也能解析为人数")
        void createPostDecimalInteger() {
            Map<String, String> body = postBody();
            body.put("headcount", "3.0");
            when(postMapper.insert(any(WorkstudyPost.class))).thenReturn(1);

            Result<?> r = service.createPost(TEACHER, "teacher", body);
            assertEquals(200, r.getCode(), "期望 200，实际 " + r.getCode() + " / " + r.getMessage());
        }

        @Test
        @DisplayName("createPost：非数字人数仍返 400")
        void createPostNonNumeric() {
            Map<String, String> body = postBody();
            body.put("headcount", "很多");
            Result<?> r = service.createPost(TEACHER, "teacher", body);
            assertEquals(400, r.getCode());
            assertEquals("招聘人数不正确", r.getMessage());
        }

        @Test
        @DisplayName("createPost：合法期内 2025-2026-1 仍通过")
        void createPostStandardTerm() {
            Map<String, String> body = postBody();
            body.put("academicTerm", "2025-2026-1");
            when(postMapper.insert(any(WorkstudyPost.class))).thenReturn(1);

            Result<?> r = service.createPost(TEACHER, "teacher", body);
            assertEquals(200, r.getCode(), "期望 200，实际 " + r.getCode() + " / " + r.getMessage());
        }

        @Test
        @DisplayName("createPost：学期缺少学期序号返 400")
        void createPostBadTerm() {
            Map<String, String> body = postBody();
            body.put("academicTerm", "2035-2036");
            Result<?> r = service.createPost(TEACHER, "teacher", body);
            assertEquals(400, r.getCode());
        }

        @Test
        @DisplayName("createPost：applyDeadline 支持 yyyy-MM-ddTHH:mm:ss")
        void createPostDeadlineWithSeconds() {
            Map<String, String> body = postBody();
            body.put("applyDeadline", "2035-09-01T09:00:00");
            body.put("interviewAt", "2035-09-02T14:30:00");
            when(postMapper.insert(any(WorkstudyPost.class))).thenReturn(1);

            service.createPost(TEACHER, "teacher", body);

            ArgumentCaptor<WorkstudyPost> captor = ArgumentCaptor.forClass(WorkstudyPost.class);
            verify(postMapper).insert(captor.capture());
            assertEquals("2035-09-01T09:00", captor.getValue().getApplyDeadline().toString());
            assertEquals("2035-09-02T14:30", captor.getValue().getInterviewAt().toString());
        }

        @Test
        @DisplayName("createPost：applyDeadline 支持 yyyy-MM-ddTHH:mm")
        void createPostDeadlineNoSeconds() {
            Map<String, String> body = postBody();
            body.put("applyDeadline", "2035-09-01T09:00");
            when(postMapper.insert(any(WorkstudyPost.class))).thenReturn(1);

            service.createPost(TEACHER, "teacher", body);

            ArgumentCaptor<WorkstudyPost> captor = ArgumentCaptor.forClass(WorkstudyPost.class);
            verify(postMapper).insert(captor.capture());
            assertEquals("2035-09-01T09:00", captor.getValue().getApplyDeadline().toString());
        }

        @Test
        @DisplayName("createPost：applyDeadline 支持纯日期 yyyy-MM-dd")
        void createPostDeadlineDateOnly() {
            Map<String, String> body = postBody();
            body.put("applyDeadline", "2035-09-01");
            when(postMapper.insert(any(WorkstudyPost.class))).thenReturn(1);

            service.createPost(TEACHER, "teacher", body);

            ArgumentCaptor<WorkstudyPost> captor = ArgumentCaptor.forClass(WorkstudyPost.class);
            verify(postMapper).insert(captor.capture());
            assertEquals("2035-09-01T00:00", captor.getValue().getApplyDeadline().toString());
        }

        @Test
        @DisplayName("createPost：时间格式非法时不抛异常，按 null 保存")
        void createPostBadDeadline() {
            Map<String, String> body = postBody();
            body.put("applyDeadline", "2035/09/01 09:00");
            when(postMapper.insert(any(WorkstudyPost.class))).thenReturn(1);

            Result<?> r = service.createPost(TEACHER, "teacher", body);

            assertEquals(200, r.getCode(), "期望 200，实际 " + r.getCode() + " / " + r.getMessage());
            ArgumentCaptor<WorkstudyPost> captor = ArgumentCaptor.forClass(WorkstudyPost.class);
            verify(postMapper).insert(captor.capture());
            assertNull(captor.getValue().getApplyDeadline());
        }

        @Test
        @DisplayName("createPost：字符串工时可解析，超限只给 warnings")
        void createPostStringHoursOverLimit() {
            Map<String, String> body = postBody();
            body.put("hoursPerWeek", "9.5");
            body.put("hoursPerMonth", "45");
            body.put("hourlyWage", "10");
            when(postMapper.insert(any(WorkstudyPost.class))).thenReturn(1);

            Result<?> r = service.createPost(TEACHER, "teacher", body);

            assertEquals(200, r.getCode(), "期望 200，实际 " + r.getCode() + " / " + r.getMessage());
            assertFalse(((List<?>) map(r.getData()).get("warnings")).isEmpty());
        }

        @Test
        @DisplayName("createTimesheet：字符串工时 / 三种 workDate 格式可解析")
        void createTimesheetStringValues() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "hired"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            when(timesheetMapper.insert(any(WorkstudyTimesheet.class))).thenReturn(1);

            Map<String, String> body = new LinkedHashMap<String, String>();
            body.put("applicationId", "9001");
            body.put("workMonth", "2035-10");
            body.put("workDate", "2035-10-05T09:00:00");
            body.put("hours", "3.5");
            body.put("content", "整理书架");

            Result<?> r = service.createTimesheet(TEACHER, "teacher", body);

            assertEquals(200, r.getCode(), "期望 200，实际 " + r.getCode() + " / " + r.getMessage());
            ArgumentCaptor<WorkstudyTimesheet> captor = ArgumentCaptor.forClass(WorkstudyTimesheet.class);
            verify(timesheetMapper).insert(captor.capture());
            assertEquals(0, new BigDecimal("3.5").compareTo(captor.getValue().getHours()));
        }

        @Test
        @DisplayName("createTimesheet：workDate 纯日期形式可解析")
        void createTimesheetDateOnly() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "hired"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            when(timesheetMapper.insert(any(WorkstudyTimesheet.class))).thenReturn(1);

            Map<String, String> body = new LinkedHashMap<String, String>();
            body.put("applicationId", "9001");
            body.put("workMonth", "2035-10");
            body.put("workDate", "2035-10-05");
            body.put("hours", "2");
            body.put("content", "值班");

            Result<?> r = service.createTimesheet(TEACHER, "teacher", body);
            assertEquals(200, r.getCode(), "期望 200，实际 " + r.getCode() + " / " + r.getMessage());
        }

        @Test
        @DisplayName("generatePayroll：无已确认工时时仍按月份正常返回")
        void generatePayrollStringMonth() {
            when(timesheetMapper.listConfirmedGroups(anyString())).thenReturn(null);
            Map<String, String> body = new LinkedHashMap<String, String>();
            body.put("workMonth", "2035-10");

            Result<?> r = service.generatePayroll(ADMIN, "admin", body);
            assertEquals(200, r.getCode(), "期望 200，实际 " + r.getCode() + " / " + r.getMessage());
            assertEquals("2035-10", map(r.getData()).get("month"));
        }
    }

    // =====================================================================
    // F. 工时
    // =====================================================================

    @Nested
    @DisplayName("工时：记录 / 确认 / 查询")
    class Timesheets {

        private Map<String, String> timesheetBody(String hours) {
            return body("applicationId", "9001", "workMonth", "2026-10", "workDate", "2026-10-08",
                    "hours", hours, "content", "图书上架");
        }

        @Test
        @DisplayName("createTimesheet：body 为空返 400")
        void createTimesheetEmptyBody() {
            Result<?> r = service.createTimesheet(TEACHER, "teacher", null);
            assertEquals(400, r.getCode());
            assertEquals("没有要提交的内容", r.getMessage());
        }

        @Test
        @DisplayName("createTimesheet：applicationId 缺失返 400 申请不存在")
        void createTimesheetNoApplication() {
            Result<?> r = service.createTimesheet(TEACHER, "teacher", body("workMonth", "2026-10"));
            assertEquals(400, r.getCode());
            assertEquals("申请不存在", r.getMessage());
        }

        @Test
        @DisplayName("createTimesheet：申请不存在返 404")
        void createTimesheetApplicationNotFound() {
            when(applicationMapper.findById(9001L)).thenReturn(null);
            Result<?> r = service.createTimesheet(TEACHER, "teacher", timesheetBody("3"));
            assertEquals(404, r.getCode());
            assertEquals("申请不存在", r.getMessage());
        }

        @Test
        @DisplayName("createTimesheet：学生不在岗返 400")
        void createTimesheetNotHired() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "pending"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());

            Result<?> r = service.createTimesheet(TEACHER, "teacher", timesheetBody("3"));
            assertEquals(400, r.getCode());
            assertEquals("该学生当前不是在岗状态，不能记录工时", r.getMessage());
        }

        @Test
        @DisplayName("createTimesheet：非本单位 teacher 返 403")
        void createTimesheetOtherUnit() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "hired"));
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "recruiting", "fixed", OTHER_TEACHER));

            Result<?> r = service.createTimesheet(TEACHER, "teacher", timesheetBody("3"));
            assertEquals(403, r.getCode());
            assertEquals("无权限为该岗位记录工时", r.getMessage());
        }

        @Test
        @DisplayName("createTimesheet：月份格式非法返 400")
        void createTimesheetBadMonth() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "hired"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());

            Result<?> r = service.createTimesheet(TEACHER, "teacher",
                    body("applicationId", "9001", "workMonth", "2026/10", "workDate", "2026-10-08", "hours", "3"));
            assertEquals(400, r.getCode());
            assertEquals("用工月份格式应为 2026-10", r.getMessage());
        }

        @Test
        @DisplayName("createTimesheet：日期格式非法返 400")
        void createTimesheetBadDate() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "hired"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());

            Result<?> r = service.createTimesheet(TEACHER, "teacher",
                    body("applicationId", "9001", "workMonth", "2026-10", "workDate", "10月8日", "hours", "3"));
            assertEquals(400, r.getCode());
            assertEquals("用工日期格式应为 2026-10-08", r.getMessage());
        }

        @Test
        @DisplayName("createTimesheet：用工日期与月份不一致返 400")
        void createTimesheetDateMonthMismatch() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "hired"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());

            Result<?> r = service.createTimesheet(TEACHER, "teacher",
                    body("applicationId", "9001", "workMonth", "2026-10", "workDate", "2026-11-08", "hours", "3"));
            assertEquals(400, r.getCode());
            assertEquals("用工日期与用工月份不一致", r.getMessage());
        }

        @Test
        @DisplayName("createTimesheet：工时为 0 返 400")
        void createTimesheetZeroHours() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "hired"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());

            Result<?> r = service.createTimesheet(TEACHER, "teacher", timesheetBody("0"));
            assertEquals(400, r.getCode());
            assertEquals("工时数必须大于 0", r.getMessage());
        }

        @Test
        @DisplayName("createTimesheet：工时为负返 400")
        void createTimesheetNegativeHours() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "hired"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());

            Result<?> r = service.createTimesheet(TEACHER, "teacher", timesheetBody("-2"));
            assertEquals(400, r.getCode());
            assertEquals("工时数必须大于 0", r.getMessage());
        }

        @Test
        @DisplayName("createTimesheet：单次 > 24 小时返 400")
        void createTimesheetTooManyHours() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "hired"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());

            Result<?> r = service.createTimesheet(TEACHER, "teacher", timesheetBody("25"));
            assertEquals(400, r.getCode());
            assertEquals("单次工时不能超过 24 小时", r.getMessage());
        }

        @Test
        @DisplayName("createTimesheet：正常记录 submitted，overLimit=false")
        void createTimesheetWithinLimit() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "hired"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            when(timesheetMapper.sumHours(STUDENT, "2026-10", null)).thenReturn(new BigDecimal("3.0"));
            when(timesheetMapper.sumHoursByDateRange(eq(STUDENT), eq(9001L), any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(new BigDecimal("3.0"));
            when(timesheetMapper.insert(any(WorkstudyTimesheet.class))).thenAnswer(invocation -> {
                invocation.getArgument(0, WorkstudyTimesheet.class).setId(9500L);
                return 1;
            });

            Result<?> r = service.createTimesheet(TEACHER, "teacher", timesheetBody("4.5"));

            Map<String, Object> data = ok(r);
            assertEquals(9500L, data.get("id"));
            assertEquals("submitted", data.get("status"));
            assertEquals("待确认", data.get("statusText"));
            assertFalse(truthy(data.get("overLimit")));
            assertNull(data.get("overLimitWarning"));

            ArgumentCaptor<WorkstudyTimesheet> captor = ArgumentCaptor.forClass(WorkstudyTimesheet.class);
            verify(timesheetMapper).insert(captor.capture());
            assertEquals(STUDENT, captor.getValue().getStudentId());
            assertEquals(TEACHER, captor.getValue().getRecorderId());
            assertEquals("图书上架", captor.getValue().getContent());
        }

        @Test
        @DisplayName("createTimesheet：单周累计 > 8 小时只写 overLimit 提示，不阻断")
        void createTimesheetWeekOverLimit() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "hired"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            when(timesheetMapper.sumHours(STUDENT, "2026-10", null)).thenReturn(new BigDecimal("4.0"));
            when(timesheetMapper.sumHoursByDateRange(eq(STUDENT), eq(9001L), any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(new BigDecimal("7.0"));
            when(timesheetMapper.insert(any(WorkstudyTimesheet.class))).thenReturn(1);

            Map<String, Object> data = ok(service.createTimesheet(TEACHER, "teacher", timesheetBody("3.0")));

            assertTrue(truthy(data.get("overLimit")));
            assertNotNull(data.get("overLimitWarning"));
            assertTrue(String.valueOf(data.get("overLimitWarning")).contains("8 小时"),
                    "实际 " + data.get("overLimitWarning"));
        }

        @Test
        @DisplayName("createTimesheet：单月累计 > 40 小时只写 overLimit 提示，不阻断")
        void createTimesheetMonthOverLimit() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "hired"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            when(timesheetMapper.sumHours(STUDENT, "2026-10", null)).thenReturn(new BigDecimal("39.0"));
            when(timesheetMapper.insert(any(WorkstudyTimesheet.class))).thenReturn(1);

            Map<String, Object> data = ok(service.createTimesheet(TEACHER, "teacher", timesheetBody("2.0")));

            assertTrue(truthy(data.get("overLimit")));
            verify(timesheetMapper).insert(any(WorkstudyTimesheet.class));
        }

        @Test
        @DisplayName("createTimesheet：admin 可为任意岗位记录")
        void createTimesheetAsAdmin() {
            when(applicationMapper.findById(9001L)).thenReturn(app(9001L, 9001L, STUDENT, "hired"));
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "recruiting", "fixed", OTHER_TEACHER));
            when(timesheetMapper.sumHours(STUDENT, "2026-10", null)).thenReturn(BigDecimal.ZERO);
            when(timesheetMapper.sumHoursByDateRange(eq(STUDENT), eq(9001L), any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(BigDecimal.ZERO);
            when(timesheetMapper.insert(any(WorkstudyTimesheet.class))).thenReturn(1);

            Result<?> r = service.createTimesheet(ADMIN, "admin", timesheetBody("2"));
            assertEquals(200, r.getCode());
        }

        @Test
        @DisplayName("confirmTimesheet：submitted → confirmed")
        void confirmTimesheetSuccess() {
            when(timesheetMapper.findById(9500L)).thenReturn(sheet(9500L, STUDENT, "2026-10", "2026-10-08", "3.0", "submitted"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            when(timesheetMapper.updateStatus(9500L, "submitted", "confirmed")).thenReturn(1);

            Result<?> r = service.confirmTimesheet(TEACHER, "teacher", 9500L);

            assertEquals(200, r.getCode());
            assertMessage(r, "工时已确认");
            verify(timesheetMapper).updateStatus(9500L, "submitted", "confirmed");
        }

        @Test
        @DisplayName("confirmTimesheet：已确认返 400")
        void confirmTimesheetAlreadyConfirmed() {
            when(timesheetMapper.findById(9500L)).thenReturn(sheet(9500L, STUDENT, "2026-10", "2026-10-08", "3.0", "confirmed"));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());

            Result<?> r = service.confirmTimesheet(TEACHER, "teacher", 9500L);
            assertEquals(400, r.getCode());
            assertMessage(r, "该工时当前是「已确认」，不能确认");
        }

        @Test
        @DisplayName("confirmTimesheet：工时记录不存在返 404")
        void confirmTimesheetNotFound() {
            when(timesheetMapper.findById(404L)).thenReturn(null);
            Result<?> r = service.confirmTimesheet(TEACHER, "teacher", 404L);
            assertEquals(404, r.getCode());
            assertEquals("工时记录不存在", r.getMessage());
        }

        @Test
        @DisplayName("confirmTimesheet：timesheetId 为 null 返 404")
        void confirmTimesheetNullId() {
            Result<?> r = service.confirmTimesheet(TEACHER, "teacher", null);
            assertEquals(404, r.getCode());
            assertEquals("工时记录不存在", r.getMessage());
        }

        @Test
        @DisplayName("confirmTimesheet：非本单位 teacher 返 403")
        void confirmTimesheetOtherUnit() {
            when(timesheetMapper.findById(9500L)).thenReturn(sheet(9500L, STUDENT, "2026-10", "2026-10-08", "3.0", "submitted"));
            when(postMapper.findById(9001L)).thenReturn(post(9001L, "recruiting", "fixed", OTHER_TEACHER));

            Result<?> r = service.confirmTimesheet(TEACHER, "teacher", 9500L);
            assertEquals(403, r.getCode());
            assertEquals("无权限确认该工时", r.getMessage());
        }

        @Test
        @DisplayName("manageTimesheets：teacher 限定本单位")
        void manageTimesheetsForTeacher() {
            when(timesheetMapper.countManage("2026-10", 9001L, STUDENT, TEACHER, null)).thenReturn(1);
            when(timesheetMapper.listManage("2026-10", 9001L, STUDENT, TEACHER, null, 20, 0))
                    .thenReturn(Collections.singletonList(sheet(9500L, STUDENT, "2026-10", "2026-10-08", "3.0", "submitted")));

            Map<String, Object> data = ok(service.manageTimesheets(TEACHER, "teacher", "2026-10", 9001L, STUDENT, 1, 20));

            assertEquals(1, counter(data.get("total")));
            Map<String, Object> row = items(data).get(0);
            assertEquals("2026-10-08", row.get("workDate"));
            assertEquals("待确认", row.get("statusText"));
            assertEquals("图书整理与借阅服务", row.get("postName"));
        }

        @Test
        @DisplayName("manageTimesheets：月份格式非法返 400")
        void manageTimesheetsBadMonth() {
            Result<?> r = service.manageTimesheets(TEACHER, "teacher", "2026-1", null, null, 1, 20);
            assertEquals(400, r.getCode());
            assertEquals("月份格式应为 2026-10", r.getMessage());
        }

        @Test
        @DisplayName("manageTimesheets：admin 不加单位限定")
        void manageTimesheetsForAdmin() {
            when(timesheetMapper.countManage(isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(0);
            Map<String, Object> data = ok(service.manageTimesheets(ADMIN, "admin", null, null, null, 1, 20));
            assertEquals(0, counter(data.get("total")));
            verify(timesheetMapper).countManage(isNull(), isNull(), isNull(), isNull(), isNull());
        }
    }

    // =====================================================================
    // G. 酬金
    // =====================================================================

    @Nested
    @DisplayName("酬金：汇总 / 发放表 / 确认发放")
    class Payroll {

        @Test
        @DisplayName("generatePayroll：发放月份格式非法返 400")
        void generatePayrollBadMonth() {
            Result<?> r = service.generatePayroll(ADMIN, "admin", body("workMonth", "2026-10-01"));
            assertEquals(400, r.getCode());
            assertEquals("发放月份格式应为 2026-10", r.getMessage());
        }

        @Test
        @DisplayName("generatePayroll：只汇总 confirmed 工时，amount = 工时 × 时薪")
        void generatePayrollSuccess() {
            Map<String, Object> group = new LinkedHashMap<String, Object>();
            group.put("student_id", 6L);
            group.put("post_id", 9001L);
            group.put("application_id", 9001L);
            group.put("hours", new BigDecimal("7.5"));
            when(timesheetMapper.listConfirmedGroups("2026-10")).thenReturn(Collections.singletonList(group));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());

            Map<String, Object> data = ok(service.generatePayroll(ADMIN, "admin", body("workMonth", "2026-10")));

            assertEquals("2026-10", data.get("month"));
            assertEquals(1, counter(data.get("people")));
            assertEquals(1, counter(data.get("items")));
            assertEquals(0, new BigDecimal("112.50").compareTo((BigDecimal) data.get("amount")));

            ArgumentCaptor<WorkstudyPayroll> captor = ArgumentCaptor.forClass(WorkstudyPayroll.class);
            verify(payrollMapper).upsert(captor.capture());
            WorkstudyPayroll saved = captor.getValue();
            assertEquals(STUDENT, saved.getStudentId());
            assertEquals(9001L, saved.getPostId());
            assertEquals(0, new BigDecimal("112.50").compareTo(saved.getAmount()));
            assertEquals("pending", saved.getStatus());
        }

        @Test
        @DisplayName("generatePayroll：(学生,岗位,月) 幂等 —— 走 upsert 不新增重复行")
        void generatePayrollIdempotent() {
            Map<String, Object> group = new LinkedHashMap<String, Object>();
            group.put("student_id", 6L);
            group.put("post_id", 9001L);
            group.put("application_id", 9001L);
            group.put("hours", new BigDecimal("7.5"));
            when(timesheetMapper.listConfirmedGroups("2026-10")).thenReturn(Collections.singletonList(group));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());

            ok(service.generatePayroll(ADMIN, "admin", body("workMonth", "2026-10")));
            ok(service.generatePayroll(ADMIN, "admin", body("workMonth", "2026-10")));

            verify(payrollMapper, times(2)).upsert(any(WorkstudyPayroll.class));
        }

        @Test
        @DisplayName("generatePayroll：多人多岗位汇总")
        void generatePayrollMultiGroup() {
            Map<String, Object> g1 = new LinkedHashMap<String, Object>();
            g1.put("student_id", 6L);
            g1.put("post_id", 9001L);
            g1.put("application_id", 9001L);
            g1.put("hours", new BigDecimal("7.5"));
            Map<String, Object> g2 = new LinkedHashMap<String, Object>();
            g2.put("student_id", 7L);
            g2.put("post_id", 9002L);
            g2.put("application_id", 9002L);
            g2.put("hours", new BigDecimal("3.0"));
            when(timesheetMapper.listConfirmedGroups("2026-10")).thenReturn(Arrays.asList(g1, g2));
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());
            when(postMapper.findById(9002L)).thenReturn(post(9002L, "recruiting", "temporary", OTHER_TEACHER));

            Map<String, Object> data = ok(service.generatePayroll(ADMIN, "admin", body("workMonth", "2026-10")));

            assertEquals(2, counter(data.get("people")));
            assertEquals(2, counter(data.get("items")));
            assertEquals(0, new BigDecimal("157.50").compareTo((BigDecimal) data.get("amount")));
        }

        @Test
        @DisplayName("generatePayroll：跳过工时非正 / 岗位缺失的分组")
        void generatePayrollSkipsInvalid() {
            Map<String, Object> zero = new LinkedHashMap<String, Object>();
            zero.put("student_id", 6L);
            zero.put("post_id", 9001L);
            zero.put("application_id", 9001L);
            zero.put("hours", BigDecimal.ZERO);
            Map<String, Object> noPost = new LinkedHashMap<String, Object>();
            noPost.put("student_id", 7L);
            noPost.put("post_id", 9002L);
            noPost.put("application_id", 9002L);
            noPost.put("hours", new BigDecimal("3"));
            when(timesheetMapper.listConfirmedGroups("2026-10")).thenReturn(Arrays.asList(zero, noPost));
            when(postMapper.findById(9002L)).thenReturn(null);

            Map<String, Object> data = ok(service.generatePayroll(ADMIN, "admin", body("workMonth", "2026-10")));

            assertEquals(0, counter(data.get("items")));
            assertEquals(0, counter(data.get("people")));
            verify(payrollMapper, never()).upsert(any(WorkstudyPayroll.class));
        }

        @Test
        @DisplayName("listPayroll：返回发放表 + 汇总（人数/工时/金额/未发放）")
        void listPayrollSuccess() {
            when(payrollMapper.countList("2026-10", null)).thenReturn(2);
            when(payrollMapper.listByMonth("2026-10", null, 20, 0))
                    .thenReturn(Arrays.asList(payroll(9001L, STUDENT, "2026-10", "7.5", "15.00", "112.50"),
                            payroll(9002L, OTHER_STUDENT, "2026-10", "3.0", "15.00", "45.00")));
            when(payrollMapper.sumAmount("2026-10", "pending")).thenReturn(new BigDecimal("45.00"));

            Map<String, Object> data = ok(service.listPayroll(ADMIN, "admin", "2026-10", null, 1, 20));

            assertEquals(2, counter(data.get("total")));
            assertEquals("2026-10", data.get("workMonth"));
            Map<String, Object> summary = map(data.get("summary"));
            assertEquals(2, counter(summary.get("people")));
            assertEquals(0, new BigDecimal("10.5").compareTo((BigDecimal) summary.get("hours")));
            assertEquals(0, new BigDecimal("157.50").compareTo((BigDecimal) summary.get("amount")));
            assertEquals(0, new BigDecimal("45.00").compareTo((BigDecimal) summary.get("unpaid")));

            Map<String, Object> row = items(data).get(0);
            assertEquals("陈大伟", row.get("studentName"));
            assertEquals("软工2402", row.get("className"));
            assertEquals("待发放", row.get("statusText"));
        }

        @Test
        @DisplayName("listPayroll：月份格式非法返 400")
        void listPayrollBadMonth() {
            Result<?> r = service.listPayroll(ADMIN, "admin", "2026/10", null, 1, 20);
            assertEquals(400, r.getCode());
            assertEquals("发放月份格式应为 2026-10", r.getMessage());
        }

        @Test
        @DisplayName("listPayroll：酬金状态非法返 400")
        void listPayrollBadStatus() {
            Result<?> r = service.listPayroll(ADMIN, "admin", "2026-10", "unknown", 1, 20);
            assertEquals(400, r.getCode());
            assertEquals("酬金状态不正确", r.getMessage());
        }

        @Test
        @DisplayName("payPayroll：pending → paid，记发放人")
        void payPayrollSuccess() {
            when(payrollMapper.findById(9001L)).thenReturn(payroll(9001L, STUDENT, "2026-10", "7.5", "15.00", "112.50"));
            when(payrollMapper.updatePay(eq(9001L), eq("pending"), eq(ADMIN), any(LocalDateTime.class))).thenReturn(1);

            Result<?> r = service.payPayroll(ADMIN, "admin", 9001L);

            assertEquals(200, r.getCode());
            assertMessage(r, "已标记发放");
            verify(payrollMapper).updatePay(eq(9001L), eq("pending"), eq(ADMIN), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("payPayroll：已发放返 400")
        void payPayrollAlreadyPaid() {
            WorkstudyPayroll p = payroll(9001L, STUDENT, "2026-10", "7.5", "15.00", "112.50");
            p.setStatus("paid");
            when(payrollMapper.findById(9001L)).thenReturn(p);

            Result<?> r = service.payPayroll(ADMIN, "admin", 9001L);
            assertEquals(400, r.getCode());
            assertEquals("该笔酬金已发放", r.getMessage());
            verify(payrollMapper, never()).updatePay(anyLong(), anyString(), anyLong(), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("payPayroll：酬金记录不存在返 404")
        void payPayrollNotFound() {
            when(payrollMapper.findById(404L)).thenReturn(null);
            Result<?> r = service.payPayroll(ADMIN, "admin", 404L);
            assertEquals(404, r.getCode());
            assertEquals("酬金记录不存在", r.getMessage());
        }

        @Test
        @DisplayName("payPayroll：payrollId 为 null 返 404")
        void payPayrollNullId() {
            Result<?> r = service.payPayroll(ADMIN, "admin", null);
            assertEquals(404, r.getCode());
            assertEquals("酬金记录不存在", r.getMessage());
        }

        @Test
        @DisplayName("payPayroll：并发冲突（行数 0）返 400")
        void payPayrollConflict() {
            when(payrollMapper.findById(9001L)).thenReturn(payroll(9001L, STUDENT, "2026-10", "7.5", "15.00", "112.50"));
            when(payrollMapper.updatePay(eq(9001L), eq("pending"), eq(ADMIN), any(LocalDateTime.class))).thenReturn(0);
            Result<?> r = service.payPayroll(ADMIN, "admin", 9001L);
            assertEquals(400, r.getCode());
            assertEquals("该笔酬金当前状态已变化，请刷新后重试", r.getMessage());
        }
    }

    // =====================================================================
    // H. 概览
    // =====================================================================

    @Nested
    @DisplayName("概览：岗位 / 申请 / 工时 / 酬金四组统计")
    class Overview {

        private Map<String, Object> countRow(String key, Object value, int cnt) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put(key, value);
            row.put("cnt", cnt);
            return row;
        }

        private Map<String, Object> hoursRow(String status, String hours) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("status", status);
            row.put("hours", new BigDecimal(hours));
            return row;
        }

        private void stubHappyPathAdmin() {
            when(postMapper.countGroupByStatus(isNull(), isNull())).thenReturn(Arrays.asList(
                    countRow("status", "pending", 2),
                    countRow("status", "recruiting", 3),
                    countRow("status", "closed", 1),
                    countRow("status", "draft", 4)));
            when(applicationMapper.countGroupByStatus(isNull(), isNull())).thenReturn(Arrays.asList(
                    countRow("status", "pending", 5),
                    countRow("status", "interview_passed", 2),
                    countRow("status", "interview_failed", 1),
                    countRow("status", "hired", 3),
                    countRow("status", "resigning", 1),
                    countRow("status", "rejected", 2)));
            when(timesheetMapper.sumGroupByStatus(anyString(), isNull())).thenReturn(Arrays.asList(
                    hoursRow("confirmed", "7.5"),
                    hoursRow("submitted", "32.0")));
            when(payrollMapper.sumAmount(anyString(), isNull())).thenReturn(new BigDecimal("112.50"));
            when(payrollMapper.sumAmount(anyString(), eq("pending"))).thenReturn(new BigDecimal("45.00"));
            when(payrollMapper.countByMonth(anyString(), isNull())).thenReturn(2);
        }

        private void stubHappyPathUnit() {
            when(postMapper.countGroupByStatus(isNull(), eq(TEACHER))).thenReturn(Arrays.asList(
                    countRow("status", "pending", 2),
                    countRow("status", "recruiting", 3),
                    countRow("status", "closed", 1),
                    countRow("status", "draft", 4)));
            when(applicationMapper.countGroupByStatus(eq(TEACHER), isNull())).thenReturn(Arrays.asList(
                    countRow("status", "pending", 5),
                    countRow("status", "interview_passed", 2),
                    countRow("status", "interview_failed", 1),
                    countRow("status", "hired", 3),
                    countRow("status", "resigning", 1),
                    countRow("status", "rejected", 2)));
            when(timesheetMapper.sumGroupByStatus(anyString(), eq(TEACHER))).thenReturn(Arrays.asList(
                    hoursRow("confirmed", "7.5"),
                    hoursRow("submitted", "32.0")));
            when(payrollMapper.sumAmount(anyString(), isNull())).thenReturn(new BigDecimal("112.50"));
            when(payrollMapper.sumAmount(anyString(), eq("pending"))).thenReturn(new BigDecimal("45.00"));
            when(payrollMapper.countByMonth(anyString(), isNull())).thenReturn(2);
        }

        @Test
        @DisplayName("overview：admin 全量统计")
        void overviewForAdmin() {
            stubHappyPathAdmin();

            Map<String, Object> data = ok(service.overview(ADMIN, "admin"));

            assertNull(data.get("term"), "概览不按学期过滤，term 回显为 null（P10 遗留 X4）");

            Map<String, Object> posts = map(data.get("posts"));
            assertEquals(10, counter(posts.get("total")));
            assertEquals(2, counter(posts.get("pending")));
            assertEquals(3, counter(posts.get("recruiting")));
            assertEquals(1, counter(posts.get("closed")));

            Map<String, Object> applications = map(data.get("applications"));
            assertEquals(5, counter(applications.get("pending")));
            assertEquals(3, counter(applications.get("interviewed")));
            assertEquals(4, counter(applications.get("hired")));
            assertEquals(2, counter(applications.get("rejected")));

            Map<String, Object> timesheets = map(data.get("timesheets"));
            assertEquals(LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")), timesheets.get("month"));
            assertEquals(0, new BigDecimal("7.5").compareTo((BigDecimal) timesheets.get("confirmedHours")));
            assertEquals(0, new BigDecimal("32.0").compareTo((BigDecimal) timesheets.get("submittedHours")));
            assertFalse(truthy(timesheets.get("overLimit")));

            Map<String, Object> payroll = map(data.get("payroll"));
            assertEquals(0, new BigDecimal("112.50").compareTo((BigDecimal) payroll.get("amount")));
            assertEquals(0, new BigDecimal("45.00").compareTo((BigDecimal) payroll.get("unpaid")));
            assertEquals(2, counter(payroll.get("people")));
        }

        @Test
        @DisplayName("overview：teacher 只统计本单位岗位")
        void overviewForTeacher() {
            stubHappyPathUnit();
            ok(service.overview(TEACHER, "teacher"));
            verify(postMapper).countGroupByStatus(isNull(), eq(TEACHER));
            verify(applicationMapper).countGroupByStatus(eq(TEACHER), isNull());
            verify(timesheetMapper).sumGroupByStatus(anyString(), eq(TEACHER));
        }

        @Test
        @DisplayName("overview：工时超 40 小时置 overLimit=true")
        void overviewOverLimit() {
            when(postMapper.countGroupByStatus(isNull(), isNull())).thenReturn(new ArrayList<Map<String, Object>>());
            when(applicationMapper.countGroupByStatus(isNull(), isNull())).thenReturn(new ArrayList<Map<String, Object>>());
            when(timesheetMapper.sumGroupByStatus(anyString(), isNull())).thenReturn(Collections.singletonList(hoursRow("confirmed", "45.0")));
            when(payrollMapper.sumAmount(anyString(), isNull())).thenReturn(null);
            when(payrollMapper.sumAmount(anyString(), eq("pending"))).thenReturn(null);
            when(payrollMapper.countByMonth(anyString(), isNull())).thenReturn(0);

            Map<String, Object> timesheets = map(ok(service.overview(ADMIN, "admin")).get("timesheets"));

            assertTrue(truthy(timesheets.get("overLimit")));
            assertEquals(0, new BigDecimal("45.0").compareTo((BigDecimal) timesheets.get("confirmedHours")));
        }

        @Test
        @DisplayName("overview：pending 工时超 40 小时也置 overLimit=true")
        void overviewSubmittedOverLimit() {
            when(postMapper.countGroupByStatus(isNull(), isNull())).thenReturn(new ArrayList<Map<String, Object>>());
            when(applicationMapper.countGroupByStatus(isNull(), isNull())).thenReturn(new ArrayList<Map<String, Object>>());
            when(timesheetMapper.sumGroupByStatus(anyString(), isNull())).thenReturn(Collections.singletonList(hoursRow("submitted", "41.0")));
            when(payrollMapper.sumAmount(anyString(), isNull())).thenReturn(null);
            when(payrollMapper.sumAmount(anyString(), eq("pending"))).thenReturn(null);
            when(payrollMapper.countByMonth(anyString(), isNull())).thenReturn(0);

            Map<String, Object> timesheets = map(ok(service.overview(ADMIN, "admin")).get("timesheets"));
            assertTrue(truthy(timesheets.get("overLimit")));
        }

        @Test
        @DisplayName("overview：未知岗位状态不计入具名分组但计入 total")
        void overviewUnknownStatus() {
            when(postMapper.countGroupByStatus(isNull(), isNull())).thenReturn(Collections.singletonList(countRow("status", "archived", 7)));
            when(applicationMapper.countGroupByStatus(isNull(), isNull())).thenReturn(new ArrayList<Map<String, Object>>());
            when(timesheetMapper.sumGroupByStatus(anyString(), isNull())).thenReturn(new ArrayList<Map<String, Object>>());
            when(payrollMapper.sumAmount(anyString(), isNull())).thenReturn(null);
            when(payrollMapper.sumAmount(anyString(), eq("pending"))).thenReturn(null);
            when(payrollMapper.countByMonth(anyString(), isNull())).thenReturn(0);

            Map<String, Object> posts = map(ok(service.overview(ADMIN, "admin")).get("posts"));

            assertEquals(7, counter(posts.get("total")));
            assertEquals(0, counter(posts.get("pending")));
        }
    }

    // =====================================================================
    // I. 视图装配 / 文案 / 健壮性
    // =====================================================================

    @Nested
    @DisplayName("视图装配与文案：枚举中文、日期格式、兜底")
    class TextAndViews {

        @Test
        @DisplayName("postTypeText：fixed=固定岗 / temporary=临时岗 / 其他=未知")
        void postTypeTextTable() {
            assertEquals("固定岗", WorkstudyService.postTypeText("fixed"));
            assertEquals("临时岗", WorkstudyService.postTypeText("temporary"));
            assertEquals("未知", WorkstudyService.postTypeText("other"));
            assertEquals("未知", WorkstudyService.postTypeText(null));
        }

        @Test
        @DisplayName("postStatusText：draft/pending/recruiting/closed/rejected")
        void postStatusTextTable() {
            assertEquals("草稿", WorkstudyService.postStatusText("draft"));
            assertEquals("待审批", WorkstudyService.postStatusText("pending"));
            assertEquals("招募中", WorkstudyService.postStatusText("recruiting"));
            assertEquals("已关闭", WorkstudyService.postStatusText("closed"));
            assertEquals("未通过", WorkstudyService.postStatusText("rejected"));
            assertEquals("未知", WorkstudyService.postStatusText("other"));
        }

        @Test
        @DisplayName("applicationStatusText：七种申请状态中文")
        void applicationStatusTextTable() {
            assertEquals("待审核", WorkstudyService.applicationStatusText("pending"));
            assertEquals("面试通过", WorkstudyService.applicationStatusText("interview_passed"));
            assertEquals("面试未通过", WorkstudyService.applicationStatusText("interview_failed"));
            assertEquals("已录用", WorkstudyService.applicationStatusText("hired"));
            assertEquals("未录用", WorkstudyService.applicationStatusText("rejected"));
            assertEquals("辞职申请中", WorkstudyService.applicationStatusText("resigning"));
            assertEquals("已离岗", WorkstudyService.applicationStatusText("resigned"));
            assertEquals("未知", WorkstudyService.applicationStatusText("other"));
        }

        @Test
        @DisplayName("timesheetStatusText / payrollStatusText 中文表")
        void timesheetAndPayrollStatusText() {
            assertEquals("待确认", WorkstudyService.timesheetStatusText("submitted"));
            assertEquals("已确认", WorkstudyService.timesheetStatusText("confirmed"));
            assertEquals("未知", WorkstudyService.timesheetStatusText("other"));
            assertEquals("待发放", WorkstudyService.payrollStatusText("pending"));
            assertEquals("已发放", WorkstudyService.payrollStatusText("paid"));
            assertEquals("未知", WorkstudyService.payrollStatusText("other"));
        }

        @Test
        @DisplayName("strip：2.50 → 2.5、3.00 → 3、null → 0")
        void stripTrailingZeros() {
            assertEquals(0, new BigDecimal("2.5").compareTo(WorkstudyService.strip(new BigDecimal("2.50"))));
            assertEquals(0, new BigDecimal("3").compareTo(WorkstudyService.strip(new BigDecimal("3.00"))));
            assertEquals(0, BigDecimal.ZERO.compareTo(WorkstudyService.strip(null)));
            assertEquals(0, new BigDecimal("112.50").compareTo(WorkstudyService.strip(new BigDecimal("112.50"))));
        }

        @Test
        @DisplayName("currentTerm：形如 yyyy-yyyy-1/2")
        void currentTermFormat() {
            String term = WorkstudyService.currentTerm();
            assertTrue(term.matches("\\d{4}-\\d{4}-[12]"), "实际 " + term);
        }

        @Test
        @DisplayName("postBrief：装配 applied 标记与中文枚举")
        void postBriefCarriesApplication() {
            WorkstudyPost p = post(9001L, "recruiting", "temporary", TEACHER);

            Map<String, Object> row = service.postBrief(p, app(9001L, 9001L, STUDENT, "rejected"));

            assertEquals("临时岗", row.get("postTypeText"));
            assertEquals("招募中", row.get("statusText"));
            assertTrue(truthy(row.get("applied")));
            assertEquals("未录用", row.get("applicationStatusText"));
        }

        @Test
        @DisplayName("postBrief：无申请时 applied=false 且状态字段为 null")
        void postBriefWithoutApplication() {
            Map<String, Object> row = service.postBrief(post(9001L, "recruiting", "fixed", TEACHER), null);

            assertFalse(truthy(row.get("applied")));
            assertNull(row.get("applicationStatus"));
            assertNull(row.get("applicationStatusText"));
        }

        @Test
        @DisplayName("timesheetBrief：workDate 序列化为 yyyy-MM-dd，overLimit 布尔化")
        void timesheetBriefFormats() {
            Map<String, Object> row = service.timesheetBrief(sheet(9500L, STUDENT, "2026-10", "2026-10-08", "3.0", "confirmed"));
            assertEquals("2026-10-08", row.get("workDate"));
            assertEquals("已确认", row.get("statusText"));
            assertFalse(truthy(row.get("overLimit")));
            assertEquals(TEACHER, row.get("recordedBy"));
        }

        @Test
        @DisplayName("payrollRow：金额/工时/状态中文完整")
        void payrollRowFormats() {
            Map<String, Object> row = service.payrollRow(payroll(9001L, STUDENT, "2026-10", "7.5", "15.00", "112.50"));

            assertEquals("陈大伟", row.get("studentName"));
            assertEquals("2024003", row.get("studentNo"));
            assertEquals("待发放", row.get("statusText"));
            assertEquals(new BigDecimal("112.50"), row.get("amount"));
            assertNull(row.get("paidAt"));
        }

        @Test
        @DisplayName("page：items/total/page/size/pages 五键且 pages 向上取整")
        void pageHelper() {
            Map<String, Object> data = service.page(Collections.singletonList("x"), 21, 1, 10);
            assertEquals(1, ((List<?>) data.get("items")).size());
            assertEquals(21, counter(data.get("total")));
            assertEquals(1, counter(data.get("page")));
            assertEquals(10, counter(data.get("size")));
            assertEquals(3, counter(data.get("pages")));
        }

        @Test
        @DisplayName("isPovertyStudent：family_income_level 非空视为困难生")
        void isPovertyStudent() {
            StudentProfile profile = new StudentProfile();
            profile.setFamilyIncomeLevel("low");
            when(profileMapper.findByUserId(STUDENT)).thenReturn(profile);
            assertTrue(service.isPovertyStudent(STUDENT));
        }

        @Test
        @DisplayName("isPovertyStudent：无画像 / 画像为空视为非困难生")
        void isPovertyStudentFalse() {
            when(profileMapper.findByUserId(STUDENT)).thenReturn(null);
            assertFalse(service.isPovertyStudent(STUDENT));

            StudentProfile blank = new StudentProfile();
            blank.setFamilyIncomeLevel("  ");
            when(profileMapper.findByUserId(OTHER_STUDENT)).thenReturn(blank);
            assertFalse(service.isPovertyStudent(OTHER_STUDENT));

            assertFalse(service.isPovertyStudent(null));
        }
    }

    @Nested
    @DisplayName("健壮性：Mapper 返回 null / 空集合不抛异常")
    class Robustness {

        @Test
        @DisplayName("posts：count 为 0 时不查 list")
        void postsNoRows() {
            when(postMapper.count(isNull(), isNull(), isNull(), eq("recruiting"), isNull())).thenReturn(0);
            Map<String, Object> data = ok(service.posts(STUDENT, "student", 1, 20, null, null, null));
            assertEquals(0, counter(data.get("total")));
            assertTrue(items(data).isEmpty());
        }

        @Test
        @DisplayName("posts：mapper 返回 null 列表不抛异常")
        void postsNullList() {
            when(postMapper.count(isNull(), isNull(), isNull(), eq("recruiting"), isNull())).thenReturn(3);
            when(postMapper.list(isNull(), isNull(), isNull(), eq("recruiting"), isNull(), eq(20), eq(0))).thenReturn(null);
            Map<String, Object> data = ok(service.posts(STUDENT, "student", 1, 20, null, null, null));
            assertTrue(items(data).isEmpty());
            assertEquals(3, counter(data.get("total")));
        }

        @Test
        @DisplayName("posts：listByStudent 返回 null 不抛异常")
        void postsNullMineList() {
            when(postMapper.count(isNull(), isNull(), isNull(), eq("recruiting"), isNull())).thenReturn(1);
            when(postMapper.list(isNull(), isNull(), isNull(), eq("recruiting"), isNull(), eq(20), eq(0)))
                    .thenReturn(Collections.singletonList(recruitingPost()));
            when(applicationMapper.listByStudent(STUDENT, 200, 0)).thenReturn(null);

            Map<String, Object> item = items(ok(service.posts(STUDENT, "student", 1, 20, null, null, null))).get(0);
            assertFalse(truthy(item.get("applied")));
        }

        @Test
        @DisplayName("managePosts：page 超出范围时 offset 钳到 int 上限（不为负）")
        void managePostsHugePage() {
            when(postMapper.count(isNull(), isNull(), isNull(), isNull(), eq(TEACHER))).thenReturn(1);
            when(postMapper.list(isNull(), isNull(), isNull(), isNull(), eq(TEACHER), eq(100), eq(MAX_SAFE_OFFSET)))
                    .thenReturn(new ArrayList<WorkstudyPost>());

            Map<String, Object> data = ok(service.managePosts(TEACHER, "teacher", Integer.MAX_VALUE, Integer.MAX_VALUE, null, null));

            assertEquals(100, counter(data.get("size")));
            assertEquals(1, counter(data.get("pages")));
            verify(postMapper).list(isNull(), isNull(), isNull(), isNull(), eq(TEACHER), eq(100), eq(MAX_SAFE_OFFSET));
        }

        @Test
        @DisplayName("mine：全部 Mapper 返回 null 不抛异常")
        void mineAllNull() {
            when(applicationMapper.listByStudent(STUDENT, 100, 0)).thenReturn(null);
            when(applicationMapper.listHiredByStudent(STUDENT, null, null)).thenReturn(null);
            when(payrollMapper.sumAmount(anyString(), isNull())).thenReturn(null);

            Map<String, Object> data = ok(service.mine(STUDENT, "student"));

            Map<String, Object> summary = map(data.get("summary"));
            assertEquals(0, counter(summary.get("applied")));
            assertEquals(0, counter(summary.get("hired")));
            assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) summary.get("totalHours")));
            assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) summary.get("totalAmount")));
        }

        @Test
        @DisplayName("myTimesheets：sumHours 为 null 时按 0 处理")
        void myTimesheetsNullSum() {
            when(timesheetMapper.countMine(STUDENT, "2026-10")).thenReturn(1);
            when(timesheetMapper.listMine(STUDENT, "2026-10", 200, 0))
                    .thenReturn(Collections.singletonList(sheet(9001L, STUDENT, "2026-10", "2026-10-08", "3.0", "submitted")));
            when(timesheetMapper.sumHours(STUDENT, "2026-10", null)).thenReturn(null);
            when(postMapper.findById(9001L)).thenReturn(recruitingPost());

            Map<String, Object> data = ok(service.myTimesheets(STUDENT, "student", "2026-10"));

            assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) data.get("totalHours")));
            assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) data.get("totalAmount")));
            assertFalse(truthy(data.get("overLimit")));
        }

        @Test
        @DisplayName("myTimesheets：工时行 workDate 为 null 不抛异常")
        void myTimesheetsNullDate() {
            WorkstudyTimesheet blank = sheet(9001L, STUDENT, "2026-10", "2026-10-08", "3.0", "submitted");
            blank.setWorkDate(null);
            when(timesheetMapper.countMine(STUDENT, "2026-10")).thenReturn(1);
            when(timesheetMapper.listMine(STUDENT, "2026-10", 200, 0)).thenReturn(Collections.singletonList(blank));
            when(timesheetMapper.sumHours(STUDENT, "2026-10", null)).thenReturn(new BigDecimal("3.0"));

            Map<String, Object> data = ok(service.myTimesheets(STUDENT, "student", "2026-10"));

            assertNull(items(data).get(0).get("workDate"));
            assertEquals(0, new BigDecimal("3.0").compareTo((BigDecimal) data.get("totalHours")));
        }

        @Test
        @DisplayName("manageTimesheets：rows 为空时不抛异常")
        void manageTimesheetsEmpty() {
            when(timesheetMapper.countManage(isNull(), isNull(), isNull(), eq(TEACHER), isNull())).thenReturn(0);
            Map<String, Object> data = ok(service.manageTimesheets(TEACHER, "teacher", null, null, null, 0, 0));
            assertEquals(1, counter(data.get("page")));
            assertEquals(1, counter(data.get("size")));
            assertTrue(items(data).isEmpty());
        }

        @Test
        @DisplayName("listPayroll：sumAmount 为 null 时按 0 处理")
        void listPayrollNullSum() {
            when(payrollMapper.countList(isNull(), isNull())).thenReturn(0);
            when(payrollMapper.sumAmount(anyString(), eq("pending"))).thenReturn(null);

            Map<String, Object> summary = map(ok(service.listPayroll(ADMIN, "admin", null, null, 1, 20)).get("summary"));

            assertEquals(0, counter(summary.get("people")));
            assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) summary.get("unpaid")));
        }

        @Test
        @DisplayName("generatePayroll：listConfirmedGroups 返回 null 不抛异常")
        void generatePayrollNullGroups() {
            when(timesheetMapper.listConfirmedGroups("2026-10")).thenReturn(null);
            Map<String, Object> data = ok(service.generatePayroll(ADMIN, "admin", body("workMonth", "2026-10")));
            assertEquals(0, counter(data.get("items")));
            assertEquals(0, counter(data.get("people")));
        }

        @Test
        @DisplayName("overview：全部统计 Mapper 返回 null 不抛异常")
        void overviewAllNull() {
            when(postMapper.countGroupByStatus(isNull(), isNull())).thenReturn(null);
            when(applicationMapper.countGroupByStatus(isNull(), isNull())).thenReturn(null);
            when(timesheetMapper.sumGroupByStatus(anyString(), isNull())).thenReturn(null);
            when(payrollMapper.sumAmount(anyString(), isNull())).thenReturn(null);
            when(payrollMapper.sumAmount(anyString(), eq("pending"))).thenReturn(null);
            when(payrollMapper.countByMonth(anyString(), isNull())).thenReturn(0);

            Map<String, Object> data = ok(service.overview(ADMIN, "admin"));

            assertEquals(0, counter(map(data.get("posts")).get("total")));
            assertEquals(0, counter(map(data.get("applications")).get("pending")));
            assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) map(data.get("timesheets")).get("confirmedHours")));
            assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) map(data.get("payroll")).get("amount")));
        }

        @Test
        @DisplayName("数据脏值：未知枚举值统一回显「未知」而不是抛异常")
        void unknownStatusRendersUnknown() {
            WorkstudyPost weird = post(9001L, "archived", "fixed", TEACHER);
            when(postMapper.count(isNull(), isNull(), isNull(), eq("recruiting"), isNull())).thenReturn(1);
            when(postMapper.list(isNull(), isNull(), isNull(), eq("recruiting"), isNull(), eq(20), eq(0)))
                    .thenReturn(Collections.singletonList(weird));

            Map<String, Object> item = items(ok(service.posts(STUDENT, "student", 1, 20, null, null, null))).get(0);

            assertEquals("未知", item.get("statusText"));
            assertEquals("固定岗", item.get("postTypeText"));
        }
    }
}
