package com.umi.nest.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 学工 API 角色粗筛（{@link ApiRoleGuardInterceptor}）的回归。
 *
 * <p>这个类钉住四件事，缺一件这道闸门就会变成「看起来在守、其实漏风」：
 * <ol>
 *   <li>管理端前缀对匿名 / 学生 / 教师全拒，只放管理员；</li>
 *   <li>员工面对学生拒、对教师放；</li>
 *   <li><b>双用途路径不被误伤</b>——学生页真的在调 {@code /api/honor/batches}、
 *       {@code /api/community/inspections/{id}} 这类接口，粗筛碰它们就是打断学生端；</li>
 *   <li>拒绝响应是 {@code HTTP 200 + code 401/403} 的 JSON，与全站接口约定一致。</li>
 * </ol>
 */
class ApiRoleGuardInterceptorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ApiRoleGuardInterceptor interceptor() {
        return new ApiRoleGuardInterceptor(objectMapper, "admin", "admin,teacher");
    }

    /** 造一个带会话的请求；role 传 null 表示未登录。 */
    private MockHttpServletRequest request(String method, String uri, Long userId, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (userId != null) {
            request.getSession(true).setAttribute("userId", userId);
            request.getSession(false).setAttribute("role", role);
        }
        return request;
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString());
    }

    // ---------- 管理端：只放管理员 ----------

    @Test
    @DisplayName("管理端 /api/admin/**：匿名被拒（401）")
    void adminPathRejectsAnonymous() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean pass = interceptor().preHandle(request("GET", "/api/admin/users", null, null), response, new Object());

        assertFalse(pass);
        assertEquals(200, response.getStatus(), "拒绝也走 HTTP 200 + 业务码，保持全站接口约定");
        assertEquals(401, body(response).get("code").asInt());
        assertTrue(body(response).get("message").asText().contains("未登录"));
    }

    @Test
    @DisplayName("管理端 /api/admin/**：学生与教师都被拒（403），管理员放行")
    void adminPathOnlyAdmitsAdmin() throws Exception {
        ApiRoleGuardInterceptor guard = interceptor();

        MockHttpServletResponse studentRes = new MockHttpServletResponse();
        assertFalse(guard.preHandle(request("GET", "/api/admin/users", 4L, "student"), studentRes, new Object()));
        assertEquals(403, body(studentRes).get("code").asInt());

        MockHttpServletResponse teacherRes = new MockHttpServletResponse();
        assertFalse(guard.preHandle(request("GET", "/api/admin/users", 3L, "teacher"), teacherRes, new Object()));
        assertEquals(403, body(teacherRes).get("code").asInt());

        MockHttpServletResponse adminRes = new MockHttpServletResponse();
        assertTrue(guard.preHandle(request("GET", "/api/admin/users", 1L, "admin"), adminRes, new Object()));
    }

    @Test
    @DisplayName("管理端前缀是默认拒绝：以后新增的 /api/admin/xxx 自动受保护")
    void adminPrefixCoversFutureEndpoints() {
        assertNotNull(ApiRoleGuardInterceptor.match("/api/admin/anything-brand-new"));
        assertNotNull(ApiRoleGuardInterceptor.match("/api/admin"));
    }

    @Test
    @DisplayName("角色大小写与前后空格不影响判定")
    void roleIsNormalized() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean pass = interceptor().preHandle(request("GET", "/api/admin/users", 1L, " Admin "), response, new Object());

        assertTrue(pass);
    }

    // ---------- 员工面：学生拒、教师放 ----------

    @Test
    @DisplayName("员工面：学生被拒、教师放行")
    void staffSurfaceRejectsStudent() throws Exception {
        ApiRoleGuardInterceptor guard = interceptor();

        MockHttpServletResponse studentRes = new MockHttpServletResponse();
        assertFalse(guard.preHandle(request("GET", "/api/counselor/overview", 4L, "student"), studentRes, new Object()));
        assertEquals(403, body(studentRes).get("code").asInt());

        MockHttpServletResponse teacherRes = new MockHttpServletResponse();
        assertTrue(guard.preHandle(request("GET", "/api/counselor/overview", 3L, "teacher"), teacherRes, new Object()));
    }

    @Test
    @DisplayName("员工面覆盖审核 / 台账 / 导出 / 批量计算这几类")
    void staffSurfacesAreCovered() {
        String[] paths = {
                "/api/aid/review/pending",
                "/api/honor/review/detail/1",
                "/api/honor/export",
                "/api/rank/academic/import",
                "/api/workstudy/manage/posts",
                "/api/psyche/batches/1/stats",
                "/api/second-class/review",
                "/api/second-class/admin/categories",
                "/api/loan/review",
                "/api/loan/review/stats",
                "/api/loan/export",
                "/api/eval/compute",
                "/api/community/batches/1/publish",
                "/api/community/export",
                "/api/community/inspections/import"
        };
        for (String path : paths) {
            assertNotNull(ApiRoleGuardInterceptor.match(path), path + " 应该被粗筛覆盖");
        }
    }

    // ---------- 双用途路径：不能被误伤 ----------

    @Test
    @DisplayName("双用途路径不拦：学生页真的在调用它们")
    void dualUsePathsAreNotGated() {
        String[] studentPaths = {
                "/api/honor/batches",                 // 学生看可申报批次
                "/api/rank/batches",                  // 学生看公示批次
                "/api/rank/bonus/my",
                "/api/community/inspections/1",       // 本宿舍检查结果（数据级归属在服务层）
                "/api/community/activities/1/signup", // 学生报名
                "/api/community/my/room",
                "/api/club/reviews",                  // 学生评价社团
                "/api/second-class/my",
                "/api/user/avatar",
                "/api/admin" + "x"                    // 只是前缀相近，不是管理端
        };
        for (String path : studentPaths) {
            assertNull(ApiRoleGuardInterceptor.match(path), path + " 不该被角色粗筛拦下（归属判断在服务层）");
        }
    }

    @Test
    @DisplayName("双用途路径上学生请求直接放行，交给服务层判归属")
    void dualUseRequestPassesThrough() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean pass = interceptor().preHandle(request("GET", "/api/community/inspections/1", 4L, "student"),
                response, new Object());

        assertTrue(pass);
    }

    // ---------- 配置兜底 ----------

    @Test
    @DisplayName("配置写空 = 回落到出厂值（admin / admin,teacher），而不是全放行也不是全锁死")
    void blankConfigFallsBackToDefaults() throws Exception {
        ApiRoleGuardInterceptor guard = new ApiRoleGuardInterceptor(objectMapper, "  ", " , ");

        assertEquals(java.util.Set.of("admin"), guard.adminRoles());
        assertEquals(java.util.Set.of("admin", "teacher"), guard.staffRoles());

        // 员工面按回落值放行教师……
        MockHttpServletResponse teacherStaffRes = new MockHttpServletResponse();
        assertTrue(guard.preHandle(request("GET", "/api/counselor/overview", 3L, "teacher"), teacherStaffRes, new Object()));

        // ……但管理端仍然只有管理员能进。
        MockHttpServletResponse teacherAdminRes = new MockHttpServletResponse();
        assertFalse(guard.preHandle(request("GET", "/api/admin/users", 3L, "teacher"), teacherAdminRes, new Object()));
        assertEquals(403, body(teacherAdminRes).get("code").asInt());
    }

    @Test
    @DisplayName("规则表摘要可读，便于上线前排一遍")
    void ruleSummaryIsReadable() {
        assertTrue(ApiRoleGuardInterceptor.ruleSummary().size() >= 20);
        assertTrue(ApiRoleGuardInterceptor.ruleSummary().get(0).contains("/api/admin/**"));
    }
}
