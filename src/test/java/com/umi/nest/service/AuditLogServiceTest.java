package com.umi.nest.service;

import com.umi.nest.entity.AuditLog;
import com.umi.nest.mapper.AuditLogMapper;
import com.umi.nest.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计日志服务测试（差距清单 P0-1）。
 *
 * <p>重点不是「能写进去」，而是三条不变量：审计失败不阻断业务、敏感字段不落原文、
 * 查询分页与留存期清理的边界正确。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogService - 审计日志")
class AuditLogServiceTest {

    @Mock
    private AuditLogMapper auditLogMapper;

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private AuditLogService service;

    @Test
    @DisplayName("record 补齐默认值：时间/类型/动作/成功标记")
    void recordFillsDefaults() {
        AuditLog entry = new AuditLog();
        service.record(entry);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        AuditLog saved = captor.getValue();
        assertNotNull(saved.getEventTime());
        assertEquals(AuditLogService.TYPE_API, saved.getEventType());
        assertEquals("UNKNOWN", saved.getEventAction());
        assertEquals(Boolean.TRUE, saved.getSuccess());
    }

    @Test
    @DisplayName("record 截断超长字段，避免超列宽写入失败")
    void recordTruncatesLongFields() {
        AuditLog entry = new AuditLog();
        entry.setEventType(AuditLogService.TYPE_ADMIN);
        entry.setEventAction("POST");
        entry.setDetail("x".repeat(900));
        entry.setTarget("t".repeat(400));
        service.record(entry);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        assertEquals(500, captor.getValue().getDetail().length());
        assertEquals(255, captor.getValue().getTarget().length());
    }

    @Test
    @DisplayName("审计写入失败必须被吞掉：业务不允许因审计而失败")
    void recordSwallowsFailure() {
        doThrow(new RuntimeException("db down")).when(auditLogMapper).insert(any(AuditLog.class));
        AuditLog entry = new AuditLog();
        entry.setEventType(AuditLogService.TYPE_LOGIN);
        assertDoesNotThrow(() -> service.record(entry));
    }

    @Test
    @DisplayName("record 会按 userId 补用户名，且用户名查询失败不影响写入")
    void recordResolvesUsername() {
        com.umi.nest.entity.User u = new com.umi.nest.entity.User();
        u.setId(7L);
        u.setUsername("chendw");
        when(userMapper.findById(7L)).thenReturn(u);

        AuditLog entry = new AuditLog();
        entry.setUserId(7L);
        service.record(entry);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        assertEquals("chendw", captor.getValue().getUsername());

        // 第二次同 userId 走缓存，不再查库
        service.record(new AuditLog());
        verify(userMapper, org.mockito.Mockito.times(1)).findById(7L);
    }

    @Test
    @DisplayName("maskQuery 把密码/验证码/令牌一律写成 ***")
    void maskQueryMasksSecrets() {
        assertEquals("phone=13800138000&password=***&code=***",
                AuditLogService.maskQuery("phone=13800138000&password=abc123&code=9999"));
        assertEquals("token=***", AuditLogService.maskQuery("token=abcdef"));
        assertNull(AuditLogService.maskQuery(null));
        assertNull(AuditLogService.maskQuery(""));
        assertTrue(AuditLogService.maskQuery("k=" + "v".repeat(400)).length() <= 302);
    }

    @Test
    @DisplayName("路径 → 事件类型的映射")
    void typeOfPathMapping() {
        assertEquals(AuditLogService.TYPE_ADMIN, AuditLogService.typeOfPath("/api/admin/users"));
        assertEquals(AuditLogService.TYPE_DORM, AuditLogService.typeOfPath("/api/dorm/rooms"));
        assertEquals(AuditLogService.TYPE_ANNOUNCEMENT, AuditLogService.typeOfPath("/api/announcements"));
        assertEquals(AuditLogService.TYPE_API, AuditLogService.typeOfPath("/api/other/thing"));
        assertEquals(AuditLogService.TYPE_API, AuditLogService.typeOfPath(null));
    }

    @Test
    @DisplayName("CSV 导出：转义逗号/引号，成功列是中文")
    void csvEscapesAndLocalizes() {
        AuditLog row = new AuditLog();
        row.setId(1L);
        row.setEventTime(LocalDateTime.of(2026, 10, 13, 2, 30, 0));
        row.setEventType(AuditLogService.TYPE_ANNOUNCEMENT);
        row.setEventAction("POST");
        row.setSuccess(false);
        row.setDetail("含,逗号与\"引号");
        String csv = service.toCsv(List.of(row));

        assertTrue(csv.startsWith("id,event_time,event_type"));
        assertTrue(csv.contains("2026-10-13 02:30:00"));
        assertTrue(csv.contains("失败"));
        assertTrue(csv.contains("\"含,逗号与\"\"引号\""));
    }

    @Test
    @DisplayName("分页查询：页大小被限制在 200 以内，页码从 1 起")
    void queryClampsPageSize() {
        when(auditLogMapper.count(any(), any(), any(), any(), any())).thenReturn(0L);
        when(auditLogMapper.query(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        service.query(null, null, null, null, null, 0, 5000);

        verify(auditLogMapper).query(any(), any(), any(), any(), any(), eq(0), eq(200));
    }

    @Test
    @DisplayName("留存期清理：删除 N 天前的记录，并为自己留一条审计")
    void purgeDeletesAndAudits() {
        when(auditLogMapper.deleteBefore(any(LocalDateTime.class))).thenReturn(7);
        int removed = service.purgeBeforeDays(30);
        assertEquals(7, removed);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        assertEquals("PURGE", captor.getValue().getEventAction());
        assertTrue(captor.getValue().getDetail().contains("7"));
    }

    @Test
    @DisplayName("recordAuth：登录失败等事件能记住被尝试的账号")
    void recordAuthKeepsAttemptedAccount() {
        service.recordAuth(AuditLogService.TYPE_LOGIN, "LOGIN", "attacker", null, false, "密码错误");
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        assertEquals("attacker", captor.getValue().getUsername());
        assertEquals(Boolean.FALSE, captor.getValue().getSuccess());
        verify(userMapper, never()).findById(any());
    }
}
