package com.umi.nest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.umi.nest.common.Result;
import com.umi.nest.config.SecurityConfig;
import com.umi.nest.dto.AdminUserUpdateDTO;
import com.umi.nest.dto.InvitationCodeCreateDTO;
import com.umi.nest.service.AdminService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doReturn;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminController 接口测试
 * 覆盖: dashboard, invitation-codes(CRUD+toggle), users(列表/详情/状态/角色/更新)
 */
@WebMvcTest(AdminController.class)
@Import(SecurityConfig.class)
@DisplayName("AdminController - 管理员接口")
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AdminService adminService;

    private MockHttpSession createAdminSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", 1L);
        session.setAttribute("role", "admin");
        return session;
    }

    private MockHttpSession createTeacherSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", 2L);
        session.setAttribute("role", "teacher");
        return session;
    }

    private MockHttpSession createStudentSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", 3L);
        session.setAttribute("role", "student");
        return session;
    }

    // ==================== GET /api/admin/dashboard ====================

    @Nested
    @DisplayName("GET /api/admin/dashboard - 仪表盘")
    class DashboardTests {

        @Test
        @DisplayName("管理员获取仪表盘数据成功")
        void getDashboardSuccess() throws Exception {
            doReturn(Result.success(Map.of("totalUsers", 100)))
                    .when(adminService).getDashboard();

            mockMvc.perform(get("/api/admin/dashboard").session(createAdminSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.totalUsers").value(100));
        }

        @Test
        @DisplayName("非管理员 - 无权限")
        void getDashboardForbidden() throws Exception {
            mockMvc.perform(get("/api/admin/dashboard").session(createTeacherSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
        }

        @Test
        @DisplayName("未登录 - 拒绝访问")
        void getDashboardUnauthenticated() throws Exception {
            mockMvc.perform(get("/api/admin/dashboard"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    // ==================== POST /api/admin/invitation-codes ====================

    @Nested
    @DisplayName("POST /api/admin/invitation-codes - 创建邀请码")
    class CreateInvitationCodeTests {

        @Test
        @DisplayName("创建邀请码成功（自动生成）")
        void createAutoSuccess() throws Exception {
            InvitationCodeCreateDTO dto = new InvitationCodeCreateDTO();
            dto.setDescription("测试邀请码");
            dto.setMaxUsage(10);

            doReturn(Result.success("邀请码创建成功", Map.of("code", "ABC12345")))
                    .when(adminService).createInvitationCode(any(InvitationCodeCreateDTO.class), eq(1L));

            mockMvc.perform(post("/api/admin/invitation-codes")
                            .session(createAdminSession())
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("创建邀请码成功（指定码值）")
        void createWithCodeSuccess() throws Exception {
            InvitationCodeCreateDTO dto = new InvitationCodeCreateDTO();
            dto.setCode("TEACH2026");
            dto.setDescription("2026年教师邀请码");

            doReturn(Result.success("邀请码创建成功"))
                    .when(adminService).createInvitationCode(any(InvitationCodeCreateDTO.class), eq(1L));

            mockMvc.perform(post("/api/admin/invitation-codes")
                            .session(createAdminSession())
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("非管理员 - 无权限")
        void createForbidden() throws Exception {
            mockMvc.perform(post("/api/admin/invitation-codes")
                            .session(createStudentSession())
                            .contentType(APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
        }
    }

    // ==================== GET /api/admin/invitation-codes ====================

    @Nested
    @DisplayName("GET /api/admin/invitation-codes - 邀请码列表")
    class GetInvitationCodesTests {

        @Test
        @DisplayName("获取邀请码列表成功")
        void getListSuccess() throws Exception {
            doReturn(Result.success(List.of())).when(adminService).getInvitationCodes();

            mockMvc.perform(get("/api/admin/invitation-codes").session(createAdminSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("未登录 - 拒绝访问")
        void getListUnauthenticated() throws Exception {
            mockMvc.perform(get("/api/admin/invitation-codes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    // ==================== DELETE /api/admin/invitation-codes/{id} ====================

    @Nested
    @DisplayName("DELETE /api/admin/invitation-codes/{id} - 删除邀请码")
    class DeleteInvitationCodeTests {

        @Test
        @DisplayName("删除邀请码成功")
        void deleteSuccess() throws Exception {
            doReturn(Result.success("邀请码已删除")).when(adminService).deleteInvitationCode(1L);

            mockMvc.perform(delete("/api/admin/invitation-codes/1").session(createAdminSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("非管理员 - 无权限")
        void deleteForbidden() throws Exception {
            mockMvc.perform(delete("/api/admin/invitation-codes/1").session(createTeacherSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
        }
    }

    // ==================== PUT /api/admin/invitation-codes/{id}/toggle ====================

    @Nested
    @DisplayName("PUT /api/admin/invitation-codes/{id}/toggle - 切换邀请码状态")
    class ToggleInvitationCodeTests {

        @Test
        @DisplayName("切换状态成功")
        void toggleSuccess() throws Exception {
            doReturn(Result.success("状态已切换")).when(adminService).toggleInvitationCode(1L);

            mockMvc.perform(put("/api/admin/invitation-codes/1/toggle").session(createAdminSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    // ==================== GET /api/admin/users ====================

    @Nested
    @DisplayName("GET /api/admin/users - 用户列表")
    class GetUsersTests {

        @Test
        @DisplayName("获取全部用户列表成功")
        void getUsersSuccess() throws Exception {
            doReturn(Result.success(Map.of("list", List.of(), "total", 0)))
                    .when(adminService).getUsers(isNull(), isNull(), isNull(), eq(1), eq(20));

            mockMvc.perform(get("/api/admin/users").session(createAdminSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("按角色筛选用户")
        void getUsersByRole() throws Exception {
            doReturn(Result.success(Map.of("list", List.of(), "total", 0)))
                    .when(adminService).getUsers(eq("teacher"), isNull(), isNull(), eq(1), eq(20));

            mockMvc.perform(get("/api/admin/users")
                            .param("role", "teacher")
                            .session(createAdminSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("按关键词搜索用户")
        void getUsersByKeyword() throws Exception {
            doReturn(Result.success(Map.of("list", List.of(), "total", 0)))
                    .when(adminService).getUsers(isNull(), isNull(), eq("张三"), eq(1), eq(20));

            mockMvc.perform(get("/api/admin/users")
                            .param("keyword", "张三")
                            .session(createAdminSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("分页查询用户")
        void getUsersWithPagination() throws Exception {
            doReturn(Result.success(Map.of("list", List.of(), "total", 0)))
                    .when(adminService).getUsers(isNull(), isNull(), isNull(), eq(2), eq(10));

            mockMvc.perform(get("/api/admin/users")
                            .param("page", "2")
                            .param("size", "10")
                            .session(createAdminSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("未登录 - 拒绝访问")
        void getUsersUnauthenticated() throws Exception {
            mockMvc.perform(get("/api/admin/users"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    // ==================== GET /api/admin/users/{id} ====================

    @Nested
    @DisplayName("GET /api/admin/users/{id} - 用户详情")
    class GetUserDetailTests {

        @Test
        @DisplayName("获取用户详情成功")
        void getUserDetailSuccess() throws Exception {
            doReturn(Result.success(Map.of("id", 1L, "username", "testuser")))
                    .when(adminService).getUserDetail(1L);

            mockMvc.perform(get("/api/admin/users/1").session(createAdminSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("非管理员 - 无权限")
        void getUserDetailForbidden() throws Exception {
            mockMvc.perform(get("/api/admin/users/1").session(createStudentSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
        }
    }

    // ==================== PUT /api/admin/users/{id}/status ====================

    @Nested
    @DisplayName("PUT /api/admin/users/{id}/status - 更新用户状态")
    class UpdateUserStatusTests {

        @Test
        @DisplayName("禁用用户成功")
        void disableUserSuccess() throws Exception {
            doReturn(Result.success("用户状态已更新")).when(adminService).updateUserStatus(1L, 0);

            mockMvc.perform(put("/api/admin/users/1/status")
                            .session(createAdminSession())
                            .contentType(APPLICATION_JSON)
                            .content("{\"status\":0}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("启用用户成功")
        void enableUserSuccess() throws Exception {
            doReturn(Result.success("用户状态已更新")).when(adminService).updateUserStatus(1L, 1);

            mockMvc.perform(put("/api/admin/users/1/status")
                            .session(createAdminSession())
                            .contentType(APPLICATION_JSON)
                            .content("{\"status\":1}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("状态不能为空")
        void statusEmpty() throws Exception {
            mockMvc.perform(put("/api/admin/users/1/status")
                            .session(createAdminSession())
                            .contentType(APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("状态不能为空"));
        }
    }

    // ==================== PUT /api/admin/users/{id}/role ====================

    @Nested
    @DisplayName("PUT /api/admin/users/{id}/role - 更新用户角色")
    class UpdateUserRoleTests {

        @Test
        @DisplayName("更新角色成功")
        void updateRoleSuccess() throws Exception {
            doReturn(Result.success("角色已更新")).when(adminService).updateUserRole(1L, "teacher");

            mockMvc.perform(put("/api/admin/users/1/role")
                            .session(createAdminSession())
                            .contentType(APPLICATION_JSON)
                            .content("{\"role\":\"teacher\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("角色不能为空")
        void roleEmpty() throws Exception {
            mockMvc.perform(put("/api/admin/users/1/role")
                            .session(createAdminSession())
                            .contentType(APPLICATION_JSON)
                            .content("{\"role\":\"\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("角色不能为空"));
        }
    }

    // ==================== PUT /api/admin/users/{id} ====================

    @Nested
    @DisplayName("PUT /api/admin/users/{id} - 更新用户信息")
    class UpdateUserTests {

        @Test
        @DisplayName("更新用户信息成功")
        void updateUserSuccess() throws Exception {
            AdminUserUpdateDTO dto = new AdminUserUpdateDTO();
            dto.setNickname("新昵称");
            dto.setEmail("new@test.com");
            dto.setPhone("+8613800138888");

            doReturn(Result.success("用户信息已更新"))
                    .when(adminService).updateUser(eq(1L), any(AdminUserUpdateDTO.class));

            mockMvc.perform(put("/api/admin/users/1")
                            .session(createAdminSession())
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    // ==================== POST /api/admin/users/{id}/unlock ====================

    @Nested
    @DisplayName("POST /api/admin/users/{id}/unlock - 解除登录失败锁定")
    class UnlockUserTests {

        @Test
        @DisplayName("管理员解除锁定成功")
        void unlockSuccess() throws Exception {
            doReturn(Result.success("已解除锁定")).when(adminService).unlockUser(7L);

            mockMvc.perform(post("/api/admin/users/7/unlock").session(createAdminSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("已解除锁定"));
        }

        @Test
        @DisplayName("用户不存在 - 返回失败")
        void unlockUserMissing() throws Exception {
            doReturn(Result.error("用户不存在")).when(adminService).unlockUser(404L);

            mockMvc.perform(post("/api/admin/users/404/unlock").session(createAdminSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("用户不存在"));
        }

        @Test
        @DisplayName("教师会话 - 无权限（不得越权解锁）")
        void unlockForbidden() throws Exception {
            mockMvc.perform(post("/api/admin/users/7/unlock").session(createTeacherSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
        }

        @Test
        @DisplayName("未登录 - 返回 401")
        void unlockUnauthenticated() throws Exception {
            mockMvc.perform(post("/api/admin/users/7/unlock"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }
}
