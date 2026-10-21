package com.umi.nest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.umi.nest.common.Result;
import com.umi.nest.config.SecurityConfig;
import com.umi.nest.service.AnnouncementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.boot.test.mock.mockito.MockBean;
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
 * AnnouncementController 接口测试
 * 覆盖: 列表(全校公告), 创建, 编辑, 删除
 */
@WebMvcTest(AnnouncementController.class)
@Import(SecurityConfig.class)
@DisplayName("AnnouncementController - 公告接口")
class AnnouncementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AnnouncementService announcementService;

    private MockHttpSession createTeacherSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", 2L);
        session.setAttribute("role", "teacher");
        return session;
    }

    private MockHttpSession createStudentSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", 1L);
        session.setAttribute("role", "student");
        return session;
    }

    // ==================== GET /api/announcements ====================

    @Nested
    @DisplayName("GET /api/announcements - 公告列表")
    class GetAnnouncementsTests {

        @Test
        @DisplayName("获取公告列表成功（不传分页参数时由服务端取默认值）")
        void getAnnouncementsSuccess() throws Exception {
            doReturn(Result.success(Map.of("items", List.of(), "total", 0)))
                    .when(announcementService).getAnnouncements(isNull(), isNull());

            mockMvc.perform(get("/api/announcements").session(createTeacherSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("分页参数透传给服务层")
        void getAnnouncementsWithPaging() throws Exception {
            doReturn(Result.success(Map.of("items", List.of(), "total", 0)))
                    .when(announcementService).getAnnouncements(eq(2), eq(5));

            mockMvc.perform(get("/api/announcements")
                            .param("page", "2")
                            .param("size", "5")
                            .session(createStudentSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("未登录 - 拒绝访问")
        void getAnnouncementsUnauthenticated() throws Exception {
            mockMvc.perform(get("/api/announcements"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("未登录"));
        }
    }

    // ==================== POST /api/announcements ====================

    @Nested
    @DisplayName("POST /api/announcements - 发布公告")
    class CreateAnnouncementTests {

        @Test
        @DisplayName("教师发布公告成功（会话角色透传给服务层）")
        void createSuccess() throws Exception {
            doReturn(Result.success("公告发布成功"))
                    .when(announcementService).createAnnouncement(eq(2L), eq("teacher"), eq("重要通知"), eq("明天停课一次"));

            mockMvc.perform(post("/api/announcements")
                            .session(createTeacherSession())
                            .contentType(APPLICATION_JSON)
                            .content("{\"title\":\"重要通知\",\"content\":\"明天停课一次\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("学生发布 - 服务层返回 403")
        void createForbiddenForStudent() throws Exception {
            doReturn(Result.error(403, "无权限发布公告"))
                    .when(announcementService).createAnnouncement(eq(1L), eq("student"), any(), any());

            mockMvc.perform(post("/api/announcements")
                            .session(createStudentSession())
                            .contentType(APPLICATION_JSON)
                            .content("{\"title\":\"test\",\"content\":\"test\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
        }

        @Test
        @DisplayName("未登录 - 拒绝发布")
        void createUnauthenticated() throws Exception {
            mockMvc.perform(post("/api/announcements")
                            .contentType(APPLICATION_JSON)
                            .content("{\"title\":\"test\",\"content\":\"test\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("未登录"));
        }
    }

    // ==================== PUT /api/announcements/{announcementId} ====================

    @Nested
    @DisplayName("PUT /api/announcements/{announcementId} - 编辑公告")
    class UpdateAnnouncementTests {

        @Test
        @DisplayName("编辑公告成功")
        void updateSuccess() throws Exception {
            doReturn(Result.success("公告更新成功"))
                    .when(announcementService).updateAnnouncement(eq(1L), eq(2L), eq("teacher"),
                            eq("更新标题"), eq("更新内容"));

            mockMvc.perform(put("/api/announcements/1")
                            .session(createTeacherSession())
                            .contentType(APPLICATION_JSON)
                            .content("{\"title\":\"更新标题\",\"content\":\"更新内容\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("未登录 - 拒绝编辑")
        void updateUnauthenticated() throws Exception {
            mockMvc.perform(put("/api/announcements/1")
                            .contentType(APPLICATION_JSON)
                            .content("{\"title\":\"test\",\"content\":\"test\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("未登录"));
        }
    }

    // ==================== DELETE /api/announcements/{announcementId} ====================

    @Nested
    @DisplayName("DELETE /api/announcements/{announcementId} - 删除公告")
    class DeleteAnnouncementTests {

        @Test
        @DisplayName("删除公告成功")
        void deleteSuccess() throws Exception {
            doReturn(Result.success("公告已删除"))
                    .when(announcementService).deleteAnnouncement(1L, 2L, "teacher");

            mockMvc.perform(delete("/api/announcements/1").session(createTeacherSession()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("未登录 - 拒绝删除")
        void deleteUnauthenticated() throws Exception {
            mockMvc.perform(delete("/api/announcements/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("未登录"));
        }
    }
}
