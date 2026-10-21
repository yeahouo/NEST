package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.AwardPunishment;
import com.umi.nest.entity.StudentProfile;
import com.umi.nest.entity.User;
import com.umi.nest.mapper.AwardPunishmentMapper;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * 奖励与处分（学工 P5）的权限边界、字段校验与分页。
 *
 * <p>这类缺陷不会抛异常：校验漏一条就把处分记录挂上奖学金金额，
 * 可见范围错一格就让辅导员改了别人名下的学生。真机 E2E（68 项全 PASS）
 * 已经把口径验过一遍，这里把它钉在单测里，防止重构服务层时松动。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AwardPunishmentService - 奖惩权限、校验与分页")
class AwardPunishmentServiceTest {

    private static final Long ADMIN = 1L;
    private static final Long OTHER_TEACHER = 2L;
    private static final Long CHE_WU = 3L;      // 有分派的辅导员
    private static final Long STUDENT_USER = 7L; // 学生 users.id

    @Mock
    private AwardPunishmentMapper awardMapper;

    @Mock
    private StudentProfileMapper profileMapper;

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private AwardPunishmentService service;

    // ------------------------------------------------------------------ 夹具

    private User student() {
        User u = new User();
        u.setId(STUDENT_USER);
        u.setRole("student");
        u.setUsername("zhangsan");
        u.setStatus(1);
        return u;
    }

    private User teacherAccount() {
        User u = new User();
        u.setId(OTHER_TEACHER);
        u.setRole("teacher");
        u.setUsername("lisi");
        u.setStatus(1);
        return u;
    }

    private StudentProfile profile(Long userId, Long counselorId) {
        StudentProfile p = new StudentProfile();
        p.setId(userId);
        p.setUserId(userId);
        p.setStudentNo("202400" + userId);
        p.setClassName("软工2401");
        p.setCounselorId(counselorId);
        p.setStatus("active");
        return p;
    }

    private AwardPunishment record(Long id, Long studentId, String kind) {
        AwardPunishment a = new AwardPunishment();
        a.setId(id);
        a.setStudentId(studentId);
        a.setKind(kind);
        a.setTitle("国家励志奖学金");
        a.setLevel("national");
        a.setOperatorId(CHE_WU);
        a.setDeleted(0);
        return a;
    }

    private Map<String, String> body(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private Map<String, Object> row(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Result<?> r) {
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stats(Result<?> r) {
        return (Map<String, Object>) data(r).get("stats");
    }

    // ------------------------------------------------------------------ 权限

    @Nested
    @DisplayName("权限闸")
    class Permission {

        @Test
        @DisplayName("未登录先判 401，先于 403（角色为空也不放行）")
        void unauthenticatedBeatsForbidden() {
            Result<?> r = service.list(null, null, null, null, null, null, null, 1, 20);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());

            Result<?> r2 = service.list(null, "student", null, null, null, null, null, 1, 20);
            assertEquals(401, r2.getCode());
            assertEquals("未登录", r2.getMessage());
        }

        @Test
        @DisplayName("学生角色读列表一律 403")
        void studentCannotList() {
            Result<?> r = service.list(STUDENT_USER, "student", null, null, null, null, null, 1, 20);
            assertEquals(403, r.getCode());
            assertEquals("无权限查看奖惩记录", r.getMessage());
        }

        @Test
        @DisplayName("create/update/delete 同样先 401 再 403，且一次写都不发")
        void writeGates() {
            assertEquals(401, service.create(null, "teacher", body("studentId", "7")).getCode());
            assertEquals(401, service.update(null, "teacher", 5L, body("title", "x")).getCode());
            assertEquals(401, service.delete(null, "teacher", 5L).getCode());

            Result<?> c = service.create(STUDENT_USER, "student", body("studentId", "7"));
            assertEquals(403, c.getCode());
            assertEquals("无权限录入奖惩记录", c.getMessage());

            Result<?> u = service.update(STUDENT_USER, "student", 5L, body("title", "x"));
            assertEquals(403, u.getCode());
            assertEquals("无权限修改奖惩记录", u.getMessage());

            Result<?> d = service.delete(STUDENT_USER, "student", 5L);
            assertEquals(403, d.getCode());
            assertEquals("无权限删除奖惩记录", d.getMessage());

            verify(awardMapper, never()).insert(any(AwardPunishment.class));
            verify(awardMapper, never()).update(any(AwardPunishment.class));
            verify(awardMapper, never()).softDelete(anyLong());
        }
    }

    // ------------------------------------------------------------------ 列表

    @Nested
    @DisplayName("GET /api/counselor/awards")
    class ListScope {

        @Test
        @DisplayName("admin 看全校：scopeCounselorId 传 null，且不查分派数")
        void adminSeesAll() {
            Result<?> r = service.list(ADMIN, "admin", null, null, null, null, null, 1, 20);

            assertEquals(200, r.getCode());
            assertEquals("all", data(r).get("scope"));
            assertEquals(0, data(r).get("total"));
            verify(awardMapper).list(isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(20));
            verify(profileMapper, never()).countByCounselor(anyLong());
        }

        @Test
        @DisplayName("scope=all 时辅导员也能显式切到全校，且不查分派数")
        void scopeAllOverridesTeacher() {
            Result<?> r = service.list(CHE_WU, "teacher", null, null, null, null, "all", 1, 20);

            assertEquals(200, r.getCode());
            assertEquals("all", data(r).get("scope"));
            verify(awardMapper).list(isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(20));
            verify(profileMapper, never()).countByCounselor(anyLong());
        }

        @Test
        @DisplayName("有分派的辅导员只看名下：scopeCounselorId = actorId")
        void teacherScopedToOwn() {
            when(profileMapper.countByCounselor(CHE_WU)).thenReturn(3);

            Result<?> r = service.list(CHE_WU, "teacher", null, null, null, null, null, 1, 20);

            assertEquals(200, r.getCode());
            assertEquals("mine", data(r).get("scope"));
            verify(awardMapper).list(isNull(), isNull(), isNull(), isNull(), eq(CHE_WU), eq(0), eq(20));
        }

        @Test
        @DisplayName("未分派的辅导员看全校（避免「还没分派就什么都看不到」）")
        void teacherUnassignedSeesAll() {
            when(profileMapper.countByCounselor(CHE_WU)).thenReturn(0);

            Result<?> r = service.list(CHE_WU, "teacher", null, null, null, null, null, 1, 20);

            assertEquals(200, r.getCode());
            assertEquals("all", data(r).get("scope"));
            verify(awardMapper).list(isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(20));
        }

        @Test
        @DisplayName("size 上界是 200（不是 20），offset 按页算")
        void pagingCeiling() {
            Result<?> r = service.list(ADMIN, "admin", null, null, null, null, null, 2, 999);

            assertEquals(200, r.getCode());
            assertEquals(200, data(r).get("size"));
            assertEquals(2, data(r).get("page"));
            verify(awardMapper).list(isNull(), isNull(), isNull(), isNull(), isNull(), eq(200), eq(200));
        }

        @Test
        @DisplayName("page<1 归一到第一页，size<1 归一到 1，offset=0")
        void pagingFloor() {
            Result<?> r = service.list(ADMIN, "admin", null, null, null, null, null, 0, 0);

            assertEquals(200, r.getCode());
            assertEquals(1, data(r).get("page"));
            assertEquals(1, data(r).get("size"));
            verify(awardMapper).list(isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(1));
        }

        @Test
        @DisplayName("kind/level 筛选白名单之外的取值被拒，且一次查询都不发")
        void badFilters() {
            Result<?> k = service.list(ADMIN, "admin", "banana", null, null, null, null, 1, 20);
            assertEquals(400, k.getCode());
            assertEquals("奖惩类型筛选不正确", k.getMessage());

            Result<?> l = service.list(ADMIN, "admin", null, "galaxy", null, null, null, 1, 20);
            assertEquals(400, l.getCode());
            assertEquals("级别筛选不正确", l.getMessage());

            verify(awardMapper, never()).list(any(), any(), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("筛选值两侧空白被 trim 掉后仍合法，且落库参数是 trim 过的值")
        void filtersTrimmed() {
            Result<?> r = service.list(ADMIN, "admin", " award ", " school ", null, null, null, 1, 20);

            assertEquals(200, r.getCode());
            verify(awardMapper).list(isNull(), eq("award"), eq("school"), isNull(), isNull(), eq(0), eq(20));
        }

        @Test
        @DisplayName("指定别人名下的学生 → 403，且不查列表")
        void studentNotMine() {
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, OTHER_TEACHER));

            Result<?> r = service.list(CHE_WU, "teacher", null, null, null, STUDENT_USER, null, 1, 20);

            assertEquals(403, r.getCode());
            assertEquals("该学生不在你的名下", r.getMessage());
            verify(awardMapper, never()).list(any(), any(), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("未分派（counselor_id 为空）的学生，任何辅导员都能查")
        void unassignedStudentVisible() {
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, null));

            Result<?> r = service.list(CHE_WU, "teacher", null, null, null, STUDENT_USER, null, 1, 20);

            assertEquals(200, r.getCode());
            verify(awardMapper).list(eq(STUDENT_USER), isNull(), isNull(), isNull(), any(), eq(0), eq(20));
        }

        @Test
        @DisplayName("列表返回体带 stats 与 scope")
        void carriesStats() {
            when(awardMapper.countGroupByKind(isNull(), isNull()))
                    .thenReturn(Collections.singletonList(row("kind", "award", "cnt", 4)));

            Result<?> r = service.list(ADMIN, "admin", null, null, null, null, null, 1, 20);

            assertEquals(4, stats(r).get("award"));
            assertEquals(0, stats(r).get("punishment"));
            assertEquals(4, stats(r).get("total"));
        }
    }

    // -------------------------------------------------------------- 学生自查

    @Nested
    @DisplayName("GET /api/student/awards")
    class Mine {

        @Test
        @DisplayName("未登录 401")
        void unauthenticated() {
            Result<?> r = service.mine(null, 1, 20);
            assertEquals(401, r.getCode());
            assertEquals("未登录", r.getMessage());
        }

        @Test
        @DisplayName("学生只见自己：studentId = userId，size 钳到 100")
        void scopedToSelf() {
            Result<?> r = service.mine(STUDENT_USER, 0, 500);

            assertEquals(200, r.getCode());
            assertEquals(1, data(r).get("page"));
            assertEquals(100, data(r).get("size"));
            verify(awardMapper).list(eq(STUDENT_USER), isNull(), isNull(), isNull(), isNull(), eq(0), eq(100));
            verify(awardMapper).count(eq(STUDENT_USER), isNull(), isNull(), isNull(), isNull());
        }

        @Test
        @DisplayName("自查返回体也带 stats")
        void carriesStats() {
            when(awardMapper.countGroupByKind(eq(STUDENT_USER), isNull()))
                    .thenReturn(Collections.singletonList(row("kind", "punishment", "cnt", 1)));

            Result<?> r = service.mine(STUDENT_USER, 1, 20);

            assertEquals(1, stats(r).get("punishment"));
            assertEquals(1, stats(r).get("total"));
        }
    }

    // ------------------------------------------------------------------ 录入

    @Nested
    @DisplayName("POST /api/counselor/awards")
    class Create {

        @Test
        @DisplayName("没选学生 / 学生 id 不是数字 → 请选择学生")
        void missingStudent() {
            assertEquals("请选择学生", service.create(ADMIN, "admin", null).getMessage());
            assertEquals("请选择学生", service.create(ADMIN, "admin", body("title", "x")).getMessage());
            assertEquals("请选择学生", service.create(ADMIN, "admin", body("studentId", "abc")).getMessage());
            verify(awardMapper, never()).insert(any(AwardPunishment.class));
        }

        @Test
        @DisplayName("学生不存在或不是 student 账号 → 400")
        void badStudent() {
            Result<?> missing = service.create(ADMIN, "admin", body("studentId", "7"));
            assertEquals(400, missing.getCode());
            assertEquals("指定的学生不存在", missing.getMessage());

            when(userMapper.findById(STUDENT_USER)).thenReturn(teacherAccount());
            Result<?> notStudent = service.create(ADMIN, "admin", body("studentId", "7"));
            assertEquals(400, notStudent.getCode());
            assertEquals("指定的学生不存在", notStudent.getMessage());

            verify(awardMapper, never()).insert(any(AwardPunishment.class));
        }

        @Test
        @DisplayName("辅导员给不在名下的学生录入 → 403")
        void teacherCannotTouchOthers() {
            when(userMapper.findById(STUDENT_USER)).thenReturn(student());
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, OTHER_TEACHER));

            Result<?> r = service.create(CHE_WU, "teacher", body("studentId", "7", "title", "x"));

            assertEquals(403, r.getCode());
            assertEquals("该学生不在你的名下", r.getMessage());
            verify(awardMapper, never()).insert(any(AwardPunishment.class));
        }

        @Test
        @DisplayName("kind 非法 → 奖惩类型不正确")
        void badKind() {
            when(userMapper.findById(STUDENT_USER)).thenReturn(student());

            Result<?> r = service.create(ADMIN, "admin", body("studentId", "7", "kind", "banana"));

            assertEquals(400, r.getCode());
            assertEquals("奖惩类型不正确（可选：奖励 / 处分）", r.getMessage());
            verify(awardMapper, never()).insert(any(AwardPunishment.class));
        }

        @Test
        @DisplayName("level 非法 → 级别不正确")
        void badLevel() {
            when(userMapper.findById(STUDENT_USER)).thenReturn(student());

            Result<?> r = service.create(ADMIN, "admin",
                    body("studentId", "7", "kind", "award", "level", "galaxy", "title", "x"));

            assertEquals(400, r.getCode());
            assertEquals("级别不正确（可选：国家级 / 省级 / 校级 / 院级）", r.getMessage());
            verify(awardMapper, never()).insert(any(AwardPunishment.class));
        }

        @Test
        @DisplayName("title 空 → 请填写奖惩名称")
        void blankTitle() {
            when(userMapper.findById(STUDENT_USER)).thenReturn(student());

            Result<?> r = service.create(ADMIN, "admin",
                    body("studentId", "7", "kind", "award", "title", "   "));

            assertEquals(400, r.getCode());
            assertEquals("请填写奖惩名称", r.getMessage());
            verify(awardMapper, never()).insert(any(AwardPunishment.class));
        }

        @Test
        @DisplayName("金额不是数 → 金额不正确，且不落库")
        void badAmount() {
            when(userMapper.findById(STUDENT_USER)).thenReturn(student());

            Result<?> r = service.create(ADMIN, "admin",
                    body("studentId", "7", "kind", "award", "title", "x", "amount", "abc"));

            assertEquals(400, r.getCode());
            assertEquals("金额不正确", r.getMessage());
            verify(awardMapper, never()).insert(any(AwardPunishment.class));
        }

        @Test
        @DisplayName("金额为负或超上限 → 金额不正确")
        void amountOutOfRange() {
            when(userMapper.findById(STUDENT_USER)).thenReturn(student());

            assertEquals("金额不正确", service.create(ADMIN, "admin",
                    body("studentId", "7", "kind", "award", "title", "x", "amount", "-1")).getMessage());
            assertEquals("金额不正确", service.create(ADMIN, "admin",
                    body("studentId", "7", "kind", "award", "title", "x", "amount", "10000000")).getMessage());

            verify(awardMapper, never()).insert(any(AwardPunishment.class));
        }

        @Test
        @DisplayName("日期写成 2026/06/01 → 日期格式不正确（应为 2026-09-01）")
        void badDate() {
            when(userMapper.findById(STUDENT_USER)).thenReturn(student());

            Result<?> r = service.create(ADMIN, "admin",
                    body("studentId", "7", "kind", "award", "title", "x", "grantedOn", "2026/06/01"));

            assertEquals(400, r.getCode());
            assertEquals("日期格式不正确（应为 2026-09-01）", r.getMessage());
            verify(awardMapper, never()).insert(any(AwardPunishment.class));
        }

        @Test
        @DisplayName("处分强制清空金额：记录上绝不挂奖学金金额")
        void punishmentNeverCarriesAmount() {
            when(userMapper.findById(STUDENT_USER)).thenReturn(student());
            when(awardMapper.insert(any(AwardPunishment.class))).thenReturn(1);

            Result<?> r = service.create(ADMIN, "admin",
                    body("studentId", "7", "kind", "punishment", "title", "警告处分", "amount", "5000"));

            assertEquals(200, r.getCode());
            assertEquals("已录入", r.getMessage());
            ArgumentCaptor<AwardPunishment> captor = ArgumentCaptor.forClass(AwardPunishment.class);
            verify(awardMapper).insert(captor.capture());
            assertEquals("punishment", captor.getValue().getKind());
            assertNull(captor.getValue().getAmount());
            assertEquals(0, captor.getValue().getDeleted());
        }

        @Test
        @DisplayName("奖励录入：金额保留、录入人/学生/默认授予日期都写对")
        void awardHappyPath() {
            when(userMapper.findById(STUDENT_USER)).thenReturn(student());
            when(awardMapper.insert(any(AwardPunishment.class))).thenReturn(1);

            Result<?> r = service.create(CHE_WU, "teacher",
                    body("studentId", "7", "kind", "award", "level", "national",
                            "title", "国家励志奖学金", "amount", "5000"));

            assertEquals(200, r.getCode());
            ArgumentCaptor<AwardPunishment> captor = ArgumentCaptor.forClass(AwardPunishment.class);
            verify(awardMapper).insert(captor.capture());
            AwardPunishment saved = captor.getValue();
            assertEquals(STUDENT_USER, saved.getStudentId());
            assertEquals(CHE_WU, saved.getOperatorId());
            assertEquals("award", saved.getKind());
            assertEquals("national", saved.getLevel());
            assertEquals(0, new BigDecimal("5000").compareTo(saved.getAmount()));
            assertEquals(LocalDate.now(), saved.getGrantedOn());
        }

        @Test
        @DisplayName("奖励金额留空 → 存 null，而不是 0")
        void blankAmountMeansNull() {
            when(userMapper.findById(STUDENT_USER)).thenReturn(student());
            when(awardMapper.insert(any(AwardPunishment.class))).thenReturn(1);

            Result<?> r = service.create(ADMIN, "admin",
                    body("studentId", "7", "kind", "award", "title", "x", "amount", ""));

            assertEquals(200, r.getCode());
            ArgumentCaptor<AwardPunishment> captor = ArgumentCaptor.forClass(AwardPunishment.class);
            verify(awardMapper).insert(captor.capture());
            assertNull(captor.getValue().getAmount());
        }
    }

    // ------------------------------------------------------------------ 修改

    @Nested
    @DisplayName("PUT /api/counselor/awards/{id}")
    class Update {

        @Test
        @DisplayName("空 body / null body → 没有要保存的内容")
        void emptyBody() {
            assertEquals("没有要保存的内容", service.update(ADMIN, "admin", 5L, new HashMap<>()).getMessage());
            assertEquals("没有要保存的内容", service.update(ADMIN, "admin", 5L, null).getMessage());
            verify(awardMapper, never()).update(any(AwardPunishment.class));
        }

        @Test
        @DisplayName("改不存在的 id → 404")
        void notFound() {
            Result<?> r = service.update(ADMIN, "admin", 5L, body("title", "x"));

            assertEquals(404, r.getCode());
            assertEquals("奖惩记录不存在", r.getMessage());
            verify(awardMapper, never()).update(any(AwardPunishment.class));
        }

        @Test
        @DisplayName("越权改别人名下学生的记录 → 403，且不落库")
        void forbidden() {
            when(awardMapper.findById(5L)).thenReturn(record(5L, STUDENT_USER, "award"));
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, OTHER_TEACHER));

            Result<?> r = service.update(CHE_WU, "teacher", 5L, body("title", "改名"));

            assertEquals(403, r.getCode());
            assertEquals("该学生不在你的名下", r.getMessage());
            verify(awardMapper, never()).update(any(AwardPunishment.class));
        }

        @Test
        @DisplayName("update 影响 0 行 → 保存失败，记录可能已被删除（不假装成功）")
        void zeroRows() {
            when(awardMapper.findById(5L)).thenReturn(record(5L, STUDENT_USER, "award"));
            when(awardMapper.update(any(AwardPunishment.class))).thenReturn(0);

            Result<?> r = service.update(ADMIN, "admin", 5L, body("title", "改名"));

            assertEquals(400, r.getCode());
            assertEquals("保存失败，记录可能已被删除", r.getMessage());
        }

        @Test
        @DisplayName("保存成功：字段落库，且回读最新记录")
        void happyPath() {
            when(awardMapper.findById(5L)).thenReturn(record(5L, STUDENT_USER, "award"));
            when(awardMapper.update(any(AwardPunishment.class))).thenReturn(1);

            Result<?> r = service.update(ADMIN, "admin", 5L,
                    body("title", "校级三好学生", "level", "school", "reason", "学年综合测评第一"));

            assertEquals(200, r.getCode());
            assertEquals("已保存", r.getMessage());
            ArgumentCaptor<AwardPunishment> captor = ArgumentCaptor.forClass(AwardPunishment.class);
            verify(awardMapper).update(captor.capture());
            assertEquals("校级三好学生", captor.getValue().getTitle());
            assertEquals("school", captor.getValue().getLevel());
            assertEquals("学年综合测评第一", captor.getValue().getReason());
            verify(awardMapper, times(2)).findById(5L);
        }

        @Test
        @DisplayName("改记录不能把记录搬到别人名下（body 里的 studentId 无效）")
        void studentCannotBeMoved() {
            AwardPunishment existing = record(5L, STUDENT_USER, "award");
            when(awardMapper.findById(5L)).thenReturn(existing);
            when(awardMapper.update(any(AwardPunishment.class))).thenReturn(1);

            Result<?> r = service.update(ADMIN, "admin", 5L,
                    body("studentId", "999", "title", "改名"));

            assertEquals(200, r.getCode());
            assertEquals(STUDENT_USER, existing.getStudentId());
        }

        @Test
        @DisplayName("修改时日期格式不对照样被拒")
        void badDateRejected() {
            when(awardMapper.findById(5L)).thenReturn(record(5L, STUDENT_USER, "award"));

            Result<?> r = service.update(ADMIN, "admin", 5L, body("grantedOn", "2026年6月1日"));

            assertEquals(400, r.getCode());
            assertEquals("日期格式不正确（应为 2026-09-01）", r.getMessage());
            verify(awardMapper, never()).update(any(AwardPunishment.class));
        }
    }

    // ------------------------------------------------------------------ 删除

    @Nested
    @DisplayName("DELETE /api/counselor/awards/{id}")
    class Delete {

        @Test
        @DisplayName("删除是软删：只调 softDelete(id)，不物理删")
        void softDelete() {
            when(awardMapper.findById(5L)).thenReturn(record(5L, STUDENT_USER, "award"));

            Result<?> r = service.delete(ADMIN, "admin", 5L);

            assertEquals(200, r.getCode());
            assertEquals("已删除", r.getMessage());
            assertNull(r.getData());
            verify(awardMapper).softDelete(5L);
            verify(awardMapper, never()).update(any(AwardPunishment.class));
        }

        @Test
        @DisplayName("删不存在的 id → 404，且不发删除语句")
        void notFound() {
            Result<?> r = service.delete(ADMIN, "admin", 5L);

            assertEquals(404, r.getCode());
            assertEquals("奖惩记录不存在", r.getMessage());
            verify(awardMapper, never()).softDelete(anyLong());
        }

        @Test
        @DisplayName("越权删别人名下学生的记录 → 403")
        void forbidden() {
            when(awardMapper.findById(5L)).thenReturn(record(5L, STUDENT_USER, "award"));
            when(profileMapper.findByUserId(STUDENT_USER)).thenReturn(profile(STUDENT_USER, OTHER_TEACHER));

            Result<?> r = service.delete(CHE_WU, "teacher", 5L);

            assertEquals(403, r.getCode());
            assertEquals("该学生不在你的名下", r.getMessage());
            verify(awardMapper, never()).softDelete(anyLong());
        }
    }

    // ------------------------------------------------------------------ 汇总

    @Nested
    @DisplayName("奖惩汇总 statsOf")
    class Stats {

        @Test
        @DisplayName("按 kind 分组计数（Map 键就是列原名 kind/cnt），total 是各组之和")
        void counts() {
            when(awardMapper.countGroupByKind(any(), any())).thenReturn(Arrays.asList(
                    row("kind", "award", "cnt", 3),
                    row("kind", "punishment", "cnt", 1L)));

            Map<String, Object> s = service.statsOf(STUDENT_USER, null);

            assertEquals(3, s.get("award"));
            assertEquals(1, s.get("punishment"));
            assertEquals(4, s.get("total"));
        }

        @Test
        @DisplayName("未知 kind 只进 total，不污染 award/punishment")
        void unknownKind() {
            when(awardMapper.countGroupByKind(any(), any()))
                    .thenReturn(Collections.singletonList(row("kind", "banana", "cnt", 2)));

            Map<String, Object> s = service.statsOf(STUDENT_USER, null);

            assertEquals(0, s.get("award"));
            assertEquals(0, s.get("punishment"));
            assertEquals(2, s.get("total"));
        }

        @Test
        @DisplayName("没有记录时各计数为 0")
        void empty() {
            Map<String, Object> s = service.statsOf(STUDENT_USER, null);

            assertEquals(0, s.get("award"));
            assertEquals(0, s.get("punishment"));
            assertEquals(0, s.get("total"));
        }

        @Test
        @DisplayName("汇总按传入的可见范围查询（学生 / 辅导员各自的作用域）")
        void scopeIsPassedThrough() {
            service.statsOf(null, CHE_WU);

            verify(awardMapper).countGroupByKind(isNull(), eq(CHE_WU));
        }
    }
}
