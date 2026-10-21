package com.umi.nest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.umi.nest.common.Result;
import com.umi.nest.config.SecurityConfig;
import com.umi.nest.dto.LoginDTO;
import com.umi.nest.dto.RegisterDTO;
import com.umi.nest.dto.ResetPasswordDTO;
import com.umi.nest.dto.SendCodeDTO;
import com.umi.nest.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthController 接口测试
 * 覆盖: register, login, logout, me, send-code, reset-password
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@DisplayName("AuthController - 认证接口")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserService userService;

    // ==================== 辅助方法 ====================

    private MockHttpSession createUserSession(Long userId, String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", userId);
        session.setAttribute("role", role);
        return session;
    }

    // ==================== POST /api/auth/register ====================

    @Nested
    @DisplayName("POST /api/auth/register - 用户注册")
    class RegisterTests {

        @Test
        @DisplayName("学生注册 - 成功")
        void studentRegisterSuccess() throws Exception {
            RegisterDTO dto = new RegisterDTO();
            dto.setUsername("testuser");
            dto.setPassword("Test@123");
            dto.setConfirmPassword("Test@123");
            dto.setPhone("+8613800138000");
            dto.setUserType("student");

            doReturn(Result.success("注册成功", null))
                    .when(userService).register(any(RegisterDTO.class));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("注册成功"));
        }

        @Test
        @DisplayName("教师注册 - 需要邀请码")
        void teacherRegisterWithInvitationCode() throws Exception {
            RegisterDTO dto = new RegisterDTO();
            dto.setUsername("teacher1");
            dto.setPassword("Teacher@123");
            dto.setConfirmPassword("Teacher@123");
            dto.setPhone("+8613800138001");
            dto.setUserType("teacher");
            dto.setInvitationCode("TEACHER2024");

            doReturn(Result.success("注册成功", null))
                    .when(userService).register(any(RegisterDTO.class));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("注册 - 缺少必填字段")
        void registerMissingFields() throws Exception {
            RegisterDTO dto = new RegisterDTO();
            // 只设置用户名，缺少密码等

            doReturn(Result.error("请填写完整注册信息"))
                    .when(userService).register(any(RegisterDTO.class));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400));
        }
    }

    // ==================== POST /api/auth/login ====================

    @Nested
    @DisplayName("POST /api/auth/login - 用户登录")
    class LoginTests {

        @Test
        @DisplayName("登录成功")
        void loginSuccess() throws Exception {
            LoginDTO dto = new LoginDTO();
            dto.setUsername("testuser");
            dto.setPassword("Test@123");

            doReturn(Result.success("登录成功", Map.of(
                            "id", 1L,
                            "username", "testuser",
                            "role", "student"
                    )))
                    .when(userService).login(any(LoginDTO.class));

            mockMvc.perform(post("/api/auth/login")
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.role").value("student"));
        }

        @Test
        @DisplayName("登录失败 - 密码错误")
        void loginWrongPassword() throws Exception {
            LoginDTO dto = new LoginDTO();
            dto.setUsername("testuser");
            dto.setPassword("wrong");

            doReturn(Result.error("用户名或密码错误"))
                    .when(userService).login(any(LoginDTO.class));

            mockMvc.perform(post("/api/auth/login")
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("用户名或密码错误"));
        }

        @Test
        @DisplayName("登录失败 - 用户不存在")
        void loginUserNotFound() throws Exception {
            LoginDTO dto = new LoginDTO();
            dto.setUsername("nonexistent");
            dto.setPassword("Test@123");

            doReturn(Result.error("用户名或密码错误"))
                    .when(userService).login(any(LoginDTO.class));

            mockMvc.perform(post("/api/auth/login")
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400));
        }
    }

    // ==================== POST /api/auth/logout ====================

    @Nested
    @DisplayName("POST /api/auth/logout - 用户登出")
    class LogoutTests {

        @Test
        @DisplayName("登出成功")
        void logoutSuccess() throws Exception {
            MockHttpSession session = createUserSession(1L, "student");

            mockMvc.perform(post("/api/auth/logout")
                            .session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("登出成功"));
        }
    }

    // ==================== GET /api/auth/me ====================

    @Nested
    @DisplayName("GET /api/auth/me - 获取当前用户")
    class MeTests {

        @Test
        @DisplayName("已登录 - 获取用户信息")
        void meAuthenticated() throws Exception {
            MockHttpSession session = createUserSession(1L, "student");

            doReturn(Result.success(Map.of(
                            "id", 1L,
                            "username", "testuser",
                            "role", "student"
                    )))
                    .when(userService).getUserInfo(1L);

            mockMvc.perform(get("/api/auth/me")
                            .session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.username").value("testuser"));
        }

        @Test
        @DisplayName("未登录 - 拒绝访问")
        void meUnauthenticated() throws Exception {
            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("未登录"));
        }
    }

    // ==================== POST /api/auth/send-code ====================

    @Nested
    @DisplayName("POST /api/auth/send-code - 发送验证码")
    class SendCodeTests {

        @Test
        @DisplayName("发送验证码成功")
        void sendCodeSuccess() throws Exception {
            SendCodeDTO dto = new SendCodeDTO();
            dto.setPhone("+8613800138000");

            doReturn(Result.success("验证码已发送"))
                    .when(userService).sendVerificationCode(anyString());

            mockMvc.perform(post("/api/auth/send-code")
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    // ==================== POST /api/auth/reset-password ====================

    @Nested
    @DisplayName("POST /api/auth/reset-password - 重置密码")
    class ResetPasswordTests {

        @Test
        @DisplayName("重置密码成功")
        void resetPasswordSuccess() throws Exception {
            ResetPasswordDTO dto = new ResetPasswordDTO();
            dto.setPhone("+8613800138000");
            dto.setNewPassword("NewPass@123");
            dto.setConfirmPassword("NewPass@123");
            dto.setVerificationCode("123456");

            doReturn(Result.success("密码重置成功"))
                    .when(userService).resetPassword(any(ResetPasswordDTO.class));

            mockMvc.perform(post("/api/auth/reset-password")
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("密码重置成功"));
        }
    }
}
