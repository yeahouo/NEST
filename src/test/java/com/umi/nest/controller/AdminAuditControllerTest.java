package com.umi.nest.controller;

import com.umi.nest.config.SecurityConfig;
import com.umi.nest.entity.AuditLog;
import com.umi.nest.service.AuditLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdminAuditController 接口测试（差距清单 P0-1）。
 *
 * <p>审计日志是「谁做了什么」的账本，所以这里最关键的两条断言是<strong>没登录/不是管理员就查不到</strong>，
 * 以及导出确实是带 BOM 的 CSV（不带 BOM 的 UTF-8 CSV 在 Excel 里是乱码，等于导出不可用）。
 */
@WebMvcTest(AdminAuditController.class)
@Import(SecurityConfig.class)
@DisplayName("AdminAuditController - 审计日志接口")
class AdminAuditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuditLogService auditLogService;

    private static MockHttpSession sessionOf(Long userId, String role) {
        MockHttpSession session = new MockHttpSession();
        if (userId != null) {
            session.setAttribute("userId", userId);
        }
        if (role != null) {
            session.setAttribute("role", role);
        }
        return session;
    }

    private static Map<String, Object> emptyPage() {
        return Map.ofEntries(entry("total", 0L), entry("page", 1), entry("size", 50),
                entry("items", List.of()));
    }

    // 注意：本类跑在完整 MVC 栈上，所以先答话的是「学工 API 角色粗筛」
    // （ApiRoleGuardInterceptor，/api/admin/** 只放 admin）。控制器内的 checkAdmin 仍然在，
    // 作为第二道线；它的回答由文件末尾的 controllerGuardIsStillTheSecondLine 用不含拦截器的
    // standalone MVC 单独钉住。这里断言的是第一道线。
    @Test
    @DisplayName("未登录访问列表：被角色粗筛挡回（code=401）")
    void listWithoutSessionIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("未登录")));
    }

    @Test
    @DisplayName("学生/教师访问列表：被角色粗筛挡回（code=403）")
    void listAsNonAdminIsForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/audit-logs").session(sessionOf(3L, "teacher")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("无权访问该接口")));
    }

    @Test
    @DisplayName("管理员查询列表：默认 page=1 / size=50 透传给服务层")
    void listAsAdminDelegatesWithPaging() throws Exception {
        when(auditLogService.query(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(emptyPage());

        mockMvc.perform(get("/api/admin/audit-logs").session(sessionOf(1L, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(0));

        verify(auditLogService).query(eq(null), eq(null), eq(null), eq(null), eq(null), eq(1), eq(50));
    }

    @Test
    @DisplayName("管理员查询列表：筛选条件被透传")
    void listPassesFilters() throws Exception {
        when(auditLogService.query(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(emptyPage());

        mockMvc.perform(get("/api/admin/audit-logs")
                        .param("eventType", "LOGIN")
                        .param("username", "chendw")
                        .param("success", "false")
                        .param("from", "2026-10-01")
                        .param("to", "2026-10-13")
                        .session(sessionOf(1L, "admin")))
                .andExpect(status().isOk());

        verify(auditLogService).query(eq("LOGIN"), eq("chendw"), eq(false),
                eq("2026-10-01"), eq("2026-10-13"), eq(1), eq(50));
    }

    @Test
    @DisplayName("按类型汇总：默认看最近 7 天")
    void summaryDefaultsToSevenDays() throws Exception {
        when(auditLogService.summary(7)).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/audit-logs/summary").session(sessionOf(1L, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(auditLogService).summary(7);
    }

    @Test
    @DisplayName("导出 CSV：attachment + 文件名 + UTF-8 BOM")
    void exportReturnsCsvWithBom() throws Exception {
        when(auditLogService.exportRows(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(auditLogService.toCsv(any())).thenReturn("id,event_time\r\n");

        MvcResult result = mockMvc.perform(get("/api/admin/audit-logs/export").session(sessionOf(1L, "admin")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment; filename=\"audit-log-")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertTrue(body.length >= 3, "导出内容不应为空");
        assertEquals((byte) 0xEF, body[0]);
        assertEquals((byte) 0xBB, body[1]);
        assertEquals((byte) 0xBF, body[2]);
        String text = new String(body, StandardCharsets.UTF_8);
        assertTrue(text.contains("id,event_time"), text);
    }

    @Test
    @DisplayName("未登录导出：被角色粗筛挡回，拿不到 CSV 数据")
    void exportWithoutSessionIsForbidden() throws Exception {
        // 修前这条断言的是 HTTP 403（控制器自己回 ResponseEntity.status(403)）；
        // 现在第一道线是角色粗筛，按全站接口约定回 HTTP 200 + code 401，同样没有数据流出。
        mockMvc.perform(get("/api/admin/audit-logs/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("第二道线：去掉拦截器后，控制器自己的 checkAdmin 依然拦得干净")
    void controllerGuardIsStillTheSecondLine() throws Exception {
        // standaloneSetup 不注册任何 HandlerInterceptor —— 正好用来验证「万一把闸门摘了，
        // 控制器还兜不兜得住」。这一层不能只剩测试意义：它是审计日志的账本底线。
        MockMvc controllerOnly = MockMvcBuilders.standaloneSetup(new AdminAuditController(auditLogService)).build();

        controllerOnly.perform(get("/api/admin/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        controllerOnly.perform(get("/api/admin/audit-logs").session(sessionOf(3L, "teacher")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权限访问"));

        controllerOnly.perform(get("/api/admin/audit-logs/export"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("留存期清理：天数从请求体读取，并回显清理条数")
    void purgeUsesRequestBodyDays() throws Exception {
        when(auditLogService.purgeBeforeDays(30)).thenReturn(7);

        mockMvc.perform(post("/api/admin/audit-logs/purge")
                        .contentType(APPLICATION_JSON)
                        .content("{\"days\":30}")
                        .session(sessionOf(1L, "admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("30")))
                .andExpect(jsonPath("$.data").value(7));

        verify(auditLogService).purgeBeforeDays(30);
    }

    @Test
    @DisplayName("留存期清理：非法天数回落到默认 180 天")
    void purgeFallsBackToDefaultDays() throws Exception {
        when(auditLogService.purgeBeforeDays(180)).thenReturn(0);

        mockMvc.perform(post("/api/admin/audit-logs/purge")
                        .contentType(APPLICATION_JSON)
                        .content("{\"days\":\"not-a-number\"}")
                        .session(sessionOf(1L, "admin")))
                .andExpect(status().isOk());

        verify(auditLogService).purgeBeforeDays(180);
    }
}
