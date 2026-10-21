package com.umi.nest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.umi.nest.common.Result;
import com.umi.nest.config.SecurityConfig;
import com.umi.nest.dto.*;
import com.umi.nest.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UserController 接口测试
 * 覆盖: settings, profile, password, phone, email, bind-wechat, unbind-wechat,
 *       notifications, avatar(POST/GET), avatar/file
 */
@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
@DisplayName("UserController - 用户接口")
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserService userService;

    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();
        session.setAttribute("userId", 1L);
        session.setAttribute("role", "student");
    }

    // ==================== GET /api/user/settings ====================

    @Nested
    @DisplayName("GET /api/user/settings - 获取用户设置")
    class GetSettingsTests {

        @Test
        @DisplayName("已登录 - 获取设置成功")
        void getSettingsSuccess() throws Exception {
            doReturn(Result.success(Map.of("nickname", "test")))
                    .when(userService).getSettings(1L);

            mockMvc.perform(get("/api/user/settings").session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("未登录 - 拒绝访问")
        void getSettingsUnauthenticated() throws Exception {
            mockMvc.perform(get("/api/user/settings"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("未登录"));
        }
    }

    // ==================== PUT /api/user/profile ====================

    @Nested
    @DisplayName("PUT /api/user/profile - 更新个人资料")
    class UpdateProfileTests {

        @Test
        @DisplayName("更新资料成功")
        void updateProfileSuccess() throws Exception {
            UpdateProfileDTO dto = new UpdateProfileDTO();
            dto.setRealName("张三");
            dto.setSchool("测试大学");
            dto.setMajor("计算机科学");

            doReturn(Result.success("更新成功"))
                    .when(userService).updateProfile(eq(1L), any(UpdateProfileDTO.class));

            mockMvc.perform(put("/api/user/profile")
                            .session(session)
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("未登录 - 拒绝更新")
        void updateProfileUnauthenticated() throws Exception {
            UpdateProfileDTO dto = new UpdateProfileDTO();
            dto.setRealName("张三");

            mockMvc.perform(put("/api/user/profile")
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("未登录"));
        }
    }

    // ==================== PUT /api/user/password ====================

    @Nested
    @DisplayName("PUT /api/user/password - 修改密码")
    class UpdatePasswordTests {

        @Test
        @DisplayName("修改密码成功")
        void updatePasswordSuccess() throws Exception {
            UpdatePasswordDTO dto = new UpdatePasswordDTO();
            dto.setCurrentPassword("OldPass@123");
            dto.setNewPassword("NewPass@456");
            dto.setConfirmPassword("NewPass@456");

            doReturn(Result.success("密码修改成功"))
                    .when(userService).updatePassword(eq(1L), any(UpdatePasswordDTO.class));

            mockMvc.perform(put("/api/user/password")
                            .session(session)
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("未登录 - 拒绝修改")
        void updatePasswordUnauthenticated() throws Exception {
            UpdatePasswordDTO dto = new UpdatePasswordDTO();
            mockMvc.perform(put("/api/user/password")
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("未登录"));
        }
    }

    // ==================== PUT /api/user/phone ====================

    @Nested
    @DisplayName("PUT /api/user/phone - 修改手机号")
    class UpdatePhoneTests {

        @Test
        @DisplayName("修改手机号成功")
        void updatePhoneSuccess() throws Exception {
            UpdatePhoneDTO dto = new UpdatePhoneDTO();
            dto.setNewPhone("+8613800139999");
            dto.setVerificationCode("123456");

            doReturn(Result.success("手机号修改成功"))
                    .when(userService).updatePhone(eq(1L), any(UpdatePhoneDTO.class));

            mockMvc.perform(put("/api/user/phone")
                            .session(session)
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("未登录 - 拒绝修改")
        void updatePhoneUnauthenticated() throws Exception {
            mockMvc.perform(put("/api/user/phone")
                            .contentType(APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("未登录"));
        }
    }

    // ==================== PUT /api/user/email ====================

    @Nested
    @DisplayName("PUT /api/user/email - 修改邮箱")
    class UpdateEmailTests {

        @Test
        @DisplayName("修改邮箱成功")
        void updateEmailSuccess() throws Exception {
            UpdateEmailDTO dto = new UpdateEmailDTO();
            dto.setNewEmail("new@test.com");
            dto.setVerificationCode("654321");

            doReturn(Result.success("邮箱修改成功"))
                    .when(userService).updateEmail(eq(1L), any(UpdateEmailDTO.class));

            mockMvc.perform(put("/api/user/email")
                            .session(session)
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("未登录 - 拒绝修改")
        void updateEmailUnauthenticated() throws Exception {
            mockMvc.perform(put("/api/user/email")
                            .contentType(APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("未登录"));
        }
    }

    // ==================== POST /api/user/bind-wechat ====================

    @Nested
    @DisplayName("POST /api/user/bind-wechat - 绑定微信")
    class BindWechatTests {

        @Test
        @DisplayName("绑定微信成功")
        void bindWechatSuccess() throws Exception {
            BindWechatDTO dto = new BindWechatDTO();
            dto.setCode("wechat_auth_code");

            doReturn(Result.success("微信绑定成功"))
                    .when(userService).bindWechat(eq(1L), any(BindWechatDTO.class));

            mockMvc.perform(post("/api/user/bind-wechat")
                            .session(session)
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("未登录 - 拒绝绑定")
        void bindWechatUnauthenticated() throws Exception {
            mockMvc.perform(post("/api/user/bind-wechat")
                            .contentType(APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("未登录"));
        }
    }

    // ==================== DELETE /api/user/unbind-wechat ====================

    @Nested
    @DisplayName("DELETE /api/user/unbind-wechat - 解绑微信")
    class UnbindWechatTests {

        @Test
        @DisplayName("解绑微信成功")
        void unbindWechatSuccess() throws Exception {
            doReturn(Result.success("微信解绑成功"))
                    .when(userService).unbindWechat(1L);

            mockMvc.perform(delete("/api/user/unbind-wechat")
                            .session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("未登录 - 拒绝解绑")
        void unbindWechatUnauthenticated() throws Exception {
            mockMvc.perform(delete("/api/user/unbind-wechat"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("未登录"));
        }
    }

    // ==================== PUT /api/user/notifications ====================

    @Nested
    @DisplayName("PUT /api/user/notifications - 更新通知设置")
    class UpdateNotificationsTests {

        @Test
        @DisplayName("更新通知设置成功")
        void updateNotificationsSuccess() throws Exception {
            NotificationSettingsDTO dto = new NotificationSettingsDTO();
            dto.setNotifyAssignmentDeadline(1);
            dto.setNotifyPrivateMessage(0);
            dto.setNotifyTopicReply(1);

            doReturn(Result.success("通知设置已更新"))
                    .when(userService).updateNotificationSettings(eq(1L), any(NotificationSettingsDTO.class));

            mockMvc.perform(put("/api/user/notifications")
                            .session(session)
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("未登录 - 拒绝更新")
        void updateNotificationsUnauthenticated() throws Exception {
            mockMvc.perform(put("/api/user/notifications")
                            .contentType(APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("未登录"));
        }
    }

    // ==================== POST /api/user/avatar ====================

    @Nested
    @DisplayName("POST /api/user/avatar - 上传头像")
    class UploadAvatarTests {

        @Test
        @DisplayName("上传头像成功")
        void uploadAvatarSuccess() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "avatar.png", "image/png", "fake-image".getBytes());

            doReturn(Result.success("头像上传成功", Map.of("avatar", "1_xxx.png")))
                    .when(userService).uploadAvatar(eq(1L), any());

            mockMvc.perform(multipart("/api/user/avatar")
                            .file(file)
                            .session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("未登录 - 拒绝上传")
        void uploadAvatarUnauthenticated() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "avatar.png", "image/png", "fake-image".getBytes());

            mockMvc.perform(multipart("/api/user/avatar").file(file))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("未登录"));
        }

        @Test
        @DisplayName("已登录但没带文件 - 交服务层给出提示，不再抛 500")
        void uploadAvatarWithoutFile() throws Exception {
            doReturn(Result.error("请选择要上传的文件"))
                    .when(userService).uploadAvatar(eq(1L), org.mockito.ArgumentMatchers.isNull());

            mockMvc.perform(post("/api/user/avatar").session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("请选择要上传的文件"));
        }
    }

    // ==================== GET /api/user/avatar ====================

    @Nested
    @DisplayName("GET /api/user/avatar - 获取头像")
    class GetAvatarTests {

        @Test
        @DisplayName("已登录 - 获取头像")
        void getAvatarAuthenticated() throws Exception {
            Resource resource = new ByteArrayResource("fake-image".getBytes());
            doReturn(ResponseEntity.ok()
                            .contentType(MediaType.IMAGE_PNG)
                            .body(resource))
                    .when(userService).getAvatar(1L);

            mockMvc.perform(get("/api/user/avatar").session(session))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("未登录 - 返回默认头像")
        void getAvatarUnauthenticated() throws Exception {
            Resource resource = new ByteArrayResource("default-avatar".getBytes());
            doReturn(ResponseEntity.ok()
                            .contentType(MediaType.IMAGE_PNG)
                            .body(resource))
                    .when(userService).getDefaultAvatar();

            mockMvc.perform(get("/api/user/avatar"))
                    .andExpect(status().isOk());
        }
    }

    // ==================== GET /api/user/avatar/file/{filename} ====================

    @Nested
    @DisplayName("GET /api/user/avatar/file/{filename} - 获取头像文件")
    class GetAvatarFileTests {

        @Test
        @DisplayName("获取头像文件")
        void getAvatarFileSuccess() throws Exception {
            Resource resource = new ByteArrayResource("fake-image".getBytes());
            doReturn(ResponseEntity.ok()
                            .contentType(MediaType.IMAGE_PNG)
                            .body(resource))
                    .when(userService).loadAvatarFile("1_xxx.png");

            mockMvc.perform(get("/api/user/avatar/file/1_xxx.png").session(session))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("未登录 - 403，且不触碰 service")
        void getAvatarFileAnonymousForbidden() throws Exception {
            mockMvc.perform(get("/api/user/avatar/file/1_xxx.png"))
                    .andExpect(status().isForbidden());

            org.mockito.Mockito.verify(userService, org.mockito.Mockito.never())
                    .loadAvatarFile(org.mockito.ArgumentMatchers.anyString());
        }
    }
}
