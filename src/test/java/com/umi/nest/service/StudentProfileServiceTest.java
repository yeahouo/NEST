package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.LeaveRequest;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.entity.User;
import com.umi.nest.mapper.LeaveRequestMapper;
import com.umi.nest.mapper.StudentProfileMapper;
import com.umi.nest.mapper.UserMapper;
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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 学生档案（学工 P3）的权限边界与字段白名单。
 *
 * <p>这个测试盯的是「谁来改、能改什么」这一类错误——它们不会抛异常，
 * 只会在真实环境里悄悄把别的辅导员的学生改掉、或者让学生自己改学籍状态。
 * 真机 E2E（{@code target/e2e-profile.mjs}，48 项）已经端到端证明过一次，
 * 这里把同样的边界钉在单测里，防止以后重构服务层时松动。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StudentProfileService - 档案权限与字段白名单")
class StudentProfileServiceTest {

    private static final Long CHE_WU = 3L;   // 辅导员 chendw 的档案归属人
    private static final Long ZHANG_SAN = 2L;

    @Mock
    private StudentProfileMapper profileMapper;

    @Mock
    private LeaveRequestMapper leaveMapper;

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private StudentProfileService service;

    private StudentProfile profile(Long id, Long counselorId) {
        StudentProfile p = new StudentProfile();
        p.setId(id);
        p.setUserId(id + 100);
        p.setStudentNo("202400" + id);
        p.setClassName("软工2401");
        p.setCounselorId(counselorId);
        p.setStatus("active");
        return p;
    }

    private User user(String role) {
        User u = new User();
        u.setId(9L);
        u.setRole(role);
        u.setUsername("someone");
        u.setStatus(1);
        return u;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Result<?> r) {
        return (Map<String, Object>) r.getData();
    }

    // ------------------------------------------------------------------ 名册

    @Nested
    @DisplayName("GET /api/counselor/students")
    class List {

        @Test
        @DisplayName("未登录先判 401，而不是 403")
        void unauthenticated() {
            Result<?> r = service.list(null, null, null, null, null, null, 1, 20);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
        }

        @Test
        @DisplayName("学生读名册一律 403")
        void studentForbidden() {
            Result<?> r = service.list(6L, "student", null, null, null, null, 1, 20);
            assertEquals(403, r.getCode());
            assertEquals("无权限查看学生名册", r.getMessage());
        }

        @Test
        @DisplayName("学籍状态白名单之外的筛选值被拒")
        void badStatus() {
            Result<?> r = service.list(3L, "teacher", null, "banana", null, null, 1, 20);
            assertEquals(400, r.getCode());
            assertEquals("学籍状态筛选不正确", r.getMessage());
        }

        @Test
        @DisplayName("有分派的辅导员只看名下学生（scope=mine）")
        void teacherScopedToOwn() {
            when(profileMapper.countByCounselor(CHE_WU)).thenReturn(3);
            when(profileMapper.list(eq(CHE_WU), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Collections.singletonList(profile(1L, CHE_WU)));
            when(profileMapper.count(eq(CHE_WU), any(), any(), any())).thenReturn(1);

            Result<?> r = service.list(CHE_WU, "teacher", null, null, null, null, 1, 20);

            assertEquals(200, r.getCode());
            assertEquals("mine", data(r).get("scope"));
            assertEquals(1, data(r).get("total"));
            verify(profileMapper).list(eq(CHE_WU), isNull(), isNull(), isNull(), eq(0), eq(20));
        }

        @Test
        @DisplayName("未分派的辅导员看全校（避免「还没分派就什么都看不到」）")
        void teacherUnassignedSeesAll() {
            when(profileMapper.countByCounselor(CHE_WU)).thenReturn(0);

            Result<?> r = service.list(CHE_WU, "teacher", null, null, null, null, 1, 20);

            assertEquals(200, r.getCode());
            assertEquals("all", data(r).get("scope"));
            verify(profileMapper).list(isNull(), isNull(), isNull(), isNull(), eq(0), eq(20));
        }

        @Test
        @DisplayName("scope=all 时辅导员也能显式切到全校，且不再查分派数")
        void scopeAllOverridesTeacher() {
            Result<?> r = service.list(CHE_WU, "teacher", null, null, null, "all", 1, 20);

            assertEquals(200, r.getCode());
            assertEquals("all", data(r).get("scope"));
            verify(profileMapper, never()).countByCounselor(anyLong());
        }

        @Test
        @DisplayName("admin 看全校；size 被钳到 200，offset 按页算（这是防翻页打穿数据库的那道闸）")
        void adminPaging() {
            when(profileMapper.list(isNull(), isNull(), isNull(), isNull(), eq(200), eq(200))).thenReturn(Collections.emptyList());

            Result<?> r = service.list(1L, "admin", null, null, null, null, 2, 999);

            assertEquals(200, r.getCode());
            assertEquals("all", data(r).get("scope"));
            verify(profileMapper).list(isNull(), isNull(), isNull(), isNull(), eq(200), eq(200));
        }

        @Test
        @DisplayName("page<1 归一到第一页")
        void pageFloor() {
            Result<?> r = service.list(1L, "admin", null, null, null, null, 0, 20);
            assertEquals(200, r.getCode());
            assertEquals(1, data(r).get("page"));
            verify(profileMapper).list(isNull(), isNull(), isNull(), isNull(), eq(0), eq(20));
        }

        @Test
        @DisplayName("名册返回体里带 stats 与 scope，前端发丝格与切换按钮都靠它")
        void carriesStats() {
            when(profileMapper.countByCounselor(CHE_WU)).thenReturn(3);
            when(profileMapper.countMissingProfile()).thenReturn(11);

            Result<?> r = service.list(CHE_WU, "teacher", null, null, null, null, 1, 20);

            @SuppressWarnings("unchecked")
            Map<String, Object> stats = (Map<String, Object>) data(r).get("stats");
            assertEquals(11, stats.get("missing"));
            assertEquals(0, stats.get("total"));
        }
    }

    // ------------------------------------------------------------------ 详情

    @Nested
    @DisplayName("GET /api/counselor/students/{id}")
    class Detail {

        @Test
        @DisplayName("未登录 401 / 学生 403 / 不存在 404")
        void gates() {
            assertEquals(401, service.detail(null, null, 1L).getCode());
            assertEquals(403, service.detail(6L, "student", 1L).getCode());
            assertEquals(404, service.detail(1L, "admin", 1L).getCode());
        }

        @Test
        @DisplayName("别的辅导员的学生碰不到")
        void otherCounselor() {
            when(profileMapper.findById(5L)).thenReturn(profile(5L, ZHANG_SAN));
            Result<?> r = service.detail(CHE_WU, "teacher", 5L);
            assertEquals(403, r.getCode());
            assertEquals("该学生不在你的名下", r.getMessage());
        }

        @Test
        @DisplayName("未分派（counselor_id 为空）的学生任何辅导员都能看")
        void unassignedStudent() {
            StudentProfile p = profile(5L, null);
            when(profileMapper.findById(5L)).thenReturn(p);
            LeaveRequest leave = new LeaveRequest();
            leave.setStatus("pending");
            when(leaveMapper.listByStudent(p.getUserId(), null, 0, 20))
                    .thenReturn(Arrays.asList(leave, leave));

            Result<?> r = service.detail(CHE_WU, "teacher", 5L);

            assertEquals(200, r.getCode());
            @SuppressWarnings("unchecked")
            Map<String, Object> leaveStats = (Map<String, Object>) data(r).get("leaveStats");
            assertEquals(2, leaveStats.get("pending"));
            assertEquals(0, leaveStats.get("closed"));
        }

        @Test
        @DisplayName("admin 能看任何人")
        void adminAny() {
            when(profileMapper.findById(5L)).thenReturn(profile(5L, ZHANG_SAN));
            assertEquals(200, service.detail(1L, "admin", 5L).getCode());
        }
    }

    // ------------------------------------------------------------------ 修改

    @Nested
    @DisplayName("PUT /api/counselor/students/{id}")
    class Update {

        @Test
        @DisplayName("空 body 拒绝")
        void emptyBody() {
            Result<?> r = service.update(1L, "admin", 5L, new HashMap<>());
            assertEquals(400, r.getCode());
            assertEquals("没有要保存的内容", r.getMessage());
        }

        @Test
        @DisplayName("辅导员改宿舍/家庭信息能落库")
        void teacherSavesDorm() {
            when(profileMapper.findById(5L)).thenReturn(profile(5L, CHE_WU));
            when(profileMapper.update(any(StudentProfile.class))).thenReturn(1);

            Map<String, String> body = new HashMap<>();
            body.put("dormBuilding", "A 栋");
            body.put("dormRoom", "A-305");
            body.put("politicalStatus", "共青团员");
            body.put("familyContact", "13900000001");
            body.put("familyIncomeLevel", "medium");
            Result<?> r = service.update(CHE_WU, "teacher", 5L, body);

            assertEquals(200, r.getCode());
            ArgumentCaptor<StudentProfile> captor = ArgumentCaptor.forClass(StudentProfile.class);
            verify(profileMapper).update(captor.capture());
            assertEquals("A-305", captor.getValue().getDormRoom());
            assertEquals("medium", captor.getValue().getFamilyIncomeLevel());
        }

        @Test
        @DisplayName("辅导员越权改学籍状态或辅导员分派 → 403，且一次写都不发")
        void teacherCannotEscalate() {
            Map<String, String> body = new HashMap<>();
            body.put("status", "graduated");
            body.put("counselorId", "2");

            when(profileMapper.findById(5L)).thenReturn(profile(5L, CHE_WU));
            Result<?> r = service.update(CHE_WU, "teacher", 5L, body);

            assertEquals(403, r.getCode());
            assertEquals("只有管理员可以变更辅导员分派与学生学籍状态", r.getMessage());
            verify(profileMapper, never()).update(any(StudentProfile.class));
        }

        @Test
        @DisplayName("辅导员改不了别人名下的学生")
        void teacherCannotTouchOthers() {
            when(profileMapper.findById(5L)).thenReturn(profile(5L, ZHANG_SAN));
            Map<String, String> body = new HashMap<>();
            body.put("dormRoom", "X-1");
            Result<?> r = service.update(CHE_WU, "teacher", 5L, body);
            assertEquals(403, r.getCode());
            assertEquals("该学生不在你的名下", r.getMessage());
        }

        @Test
        @DisplayName("档次白名单之外的取值被拒，且不落库")
        void badIncome() {
            when(profileMapper.findById(5L)).thenReturn(profile(5L, CHE_WU));
            Map<String, String> body = new HashMap<>();
            body.put("familyIncomeLevel", "rich");
            Result<?> r = service.update(CHE_WU, "teacher", 5L, body);
            assertEquals(400, r.getCode());
            verify(profileMapper, never()).update(any(StudentProfile.class));
        }

        @Test
        @DisplayName("admin 能把学生改派给教师账号")
        void adminReassigns() {
            StudentProfile p = profile(5L, ZHANG_SAN);
            when(profileMapper.findById(5L)).thenReturn(p);
            when(profileMapper.update(any(StudentProfile.class))).thenReturn(1);
            when(userMapper.findById(9L)).thenReturn(user("teacher"));

            Map<String, String> body = new HashMap<>();
            body.put("counselorId", "9");
            body.put("status", "suspended");
            Result<?> r = service.update(1L, "admin", 5L, body);

            assertEquals(200, r.getCode());
            assertEquals(9L, p.getCounselorId());
            assertEquals("suspended", p.getStatus());
        }

        @Test
        @DisplayName("改派给非教师账号被拒")
        void adminReassignsToNonStaff() {
            when(profileMapper.findById(5L)).thenReturn(profile(5L, ZHANG_SAN));
            when(userMapper.findById(9L)).thenReturn(user("student"));

            Map<String, String> body = new HashMap<>();
            body.put("counselorId", "9");

            Result<?> r = service.update(1L, "admin", 5L, body);

            assertEquals(400, r.getCode());
            assertEquals("指定的辅导员不存在或不是教师账号", r.getMessage());
            verify(profileMapper, never()).update(any(StudentProfile.class));
        }

        @Test
        @DisplayName("空串 counselorId 表示取消分派")
        void adminClearsCounselor() {
            StudentProfile p = profile(5L, ZHANG_SAN);
            when(profileMapper.findById(5L)).thenReturn(p);
            when(profileMapper.update(any(StudentProfile.class))).thenReturn(1);

            Map<String, String> body = new HashMap<>();
            body.put("counselorId", "");
            Result<?> r = service.update(1L, "admin", 5L, body);

            assertEquals(200, r.getCode());
            assertNull(p.getCounselorId());
        }

        @Test
        @DisplayName("学籍状态白名单之外的取值被拒")
        void adminBadStatus() {
            when(profileMapper.findById(5L)).thenReturn(profile(5L, ZHANG_SAN));
            Map<String, String> body = new HashMap<>();
            body.put("status", "deleted");
            Result<?> r = service.update(1L, "admin", 5L, body);
            assertEquals(400, r.getCode());
            assertEquals("学籍状态不正确", r.getMessage());
        }

        @Test
        @DisplayName("update 影响 0 行时报「档案可能已被删除」，不假装成功")
        void zeroRows() {
            when(profileMapper.findById(5L)).thenReturn(profile(5L, CHE_WU));
            when(profileMapper.update(any(StudentProfile.class))).thenReturn(0);

            Map<String, String> body = new HashMap<>();
            body.put("dormRoom", "A-1");
            Result<?> r = service.update(CHE_WU, "teacher", 5L, body);

            assertEquals(400, r.getCode());
            assertEquals("保存失败，档案可能已被删除", r.getMessage());
        }
    }

    // -------------------------------------------------------------- 学生自填

    @Nested
    @DisplayName("GET/PUT /api/student/profile")
    class Mine {

        @Test
        @DisplayName("没档案时不报错，返回 exists=false 与占位信息")
        void missingProfilePlaceholder() {
            when(profileMapper.findByUserId(6L)).thenReturn(null);
            when(userMapper.findById(6L)).thenReturn(user("student"));

            Result<?> r = service.mine(6L);

            assertEquals(200, r.getCode());
            assertEquals(Boolean.FALSE, data(r).get("exists"));
        }

        @Test
        @DisplayName("学生自填只写三列：班级/学号等不能借这一次请求被改掉")
        void selfServiceOnlyThreeColumns() {
            StudentProfile p = profile(5L, CHE_WU);
            when(profileMapper.findByUserId(6L)).thenReturn(p);
            when(profileMapper.update(any(StudentProfile.class))).thenReturn(1);

            Map<String, String> body = new HashMap<>();
            body.put("className", "黑客班");
            body.put("status", "graduated");
            body.put("familyContact", "13900000009");

            Result<?> r = service.saveMine(6L, body);

            assertEquals(200, r.getCode());
            assertEquals("软工2401", p.getClassName());
            assertEquals("active", p.getStatus());
            assertEquals("13900000009", p.getFamilyContact());
        }

        @Test
        @DisplayName("第一次填写会自动建档，学号/班级从账号资料带过来")
        void createsOnFirstSave() {
            when(profileMapper.findByUserId(6L)).thenReturn(null);
            User u = user("student");
            u.setStudentId("2024006");
            u.setClassName("软工2403");
            when(userMapper.findById(6L)).thenReturn(u);
            when(profileMapper.insert(any(StudentProfile.class))).thenReturn(1);

            Map<String, String> body = new HashMap<>();
            body.put("politicalStatus", "共青团员");
            body.put("familyIncomeLevel", "low");

            Result<?> r = service.saveMine(6L, body);

            assertEquals(200, r.getCode());
            ArgumentCaptor<StudentProfile> captor = ArgumentCaptor.forClass(StudentProfile.class);
            verify(profileMapper).insert(captor.capture());
            assertEquals("2024006", captor.getValue().getStudentNo());
            assertEquals("软工2403", captor.getValue().getClassName());
            assertEquals("active", captor.getValue().getStatus());
            assertEquals("low", captor.getValue().getFamilyIncomeLevel());
        }

        @Test
        @DisplayName("教师账号不能借自填建档")
        void teacherCannotCreate() {
            when(profileMapper.findByUserId(3L)).thenReturn(null);
            when(userMapper.findById(3L)).thenReturn(user("teacher"));

            Map<String, String> body = new HashMap<>();
            body.put("familyContact", "13900000001");

            Result<?> r = service.saveMine(3L, body);

            assertEquals(403, r.getCode());
            assertEquals("只有学生账号可以建立学工档案", r.getMessage());
        }

        @Test
        @DisplayName("档次白名单之外的取值被拒")
        void badIncome() {
            Map<String, String> body = new HashMap<>();
            body.put("familyIncomeLevel", "rich");
            Result<?> r = service.saveMine(6L, body);
            assertEquals(400, r.getCode());
            assertEquals("家庭经济档次不正确（可选：一般困难 / 困难 / 特别困难）", r.getMessage());
        }
    }

    // ------------------------------------------------------------ 补建档案

    @Nested
    @DisplayName("POST /api/counselor/students/sync")
    class Sync {

        @Test
        @DisplayName("只有 admin 能补建")
        void adminOnly() {
            assertEquals(401, service.syncMissing(null, null).getCode());
            Result<?> r = service.syncMissing(3L, "teacher");
            assertEquals(403, r.getCode());
            assertEquals("只有管理员可以补建学生档案", r.getMessage());
        }

        @Test
        @DisplayName("补建 N 条并回报剩余")
        void creates() {
            StudentProfile a = new StudentProfile();
            a.setUserId(101L);
            a.setStudentNo("2024010");
            StudentProfile b = new StudentProfile();
            b.setUserId(102L);
            when(profileMapper.listMissingProfile(0, 500)).thenReturn(Arrays.asList(a, b));
            when(profileMapper.insert(any(StudentProfile.class))).thenReturn(1);
            when(profileMapper.countMissingProfile()).thenReturn(3);

            Result<?> r = service.syncMissing(1L, "admin");

            assertEquals(200, r.getCode());
            assertEquals("已补建 2 条学生档案，还有 3 条待补建", r.getMessage());
            assertEquals(2, data(r).get("created"));
            assertEquals(3, data(r).get("remain"));
            verify(profileMapper, times(2)).insert(any(StudentProfile.class));
        }

        @Test
        @DisplayName("没有可补建的就不硬说成功")
        void nothingToDo() {
            when(profileMapper.listMissingProfile(0, 500)).thenReturn(Collections.emptyList());
            Result<?> r = service.syncMissing(1L, "admin");
            assertEquals(200, r.getCode());
            assertEquals("没有需要补建的学生档案", r.getMessage());
            verify(profileMapper, never()).insert(any(StudentProfile.class));
        }
    }

    // ------------------------------------------------------------------ 工具

    @Nested
    @DisplayName("展示文案")
    class Texts {

        @Test
        @DisplayName("状态与档次都有中文文案，未知值原样透出、null 有兜底")
        void texts() {
            assertEquals("在读", StudentProfileService.statusText("active"));
            assertEquals("休学", StudentProfileService.statusText("suspended"));
            assertEquals("退学", StudentProfileService.statusText("quit"));
            assertEquals("毕业", StudentProfileService.statusText("graduated"));
            assertEquals("未知", StudentProfileService.statusText(null));
            assertEquals("走读", StudentProfileService.statusText("走读"));
            assertEquals("一般困难", StudentProfileService.incomeText("low"));
            assertEquals("困难", StudentProfileService.incomeText("medium"));
            assertEquals("特别困难", StudentProfileService.incomeText("high"));
            assertEquals("未填写", StudentProfileService.incomeText(null));
            assertEquals("未知档次", StudentProfileService.incomeText("未知档次"));
        }
    }
}
