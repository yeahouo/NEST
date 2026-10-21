package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.dto.LoginDTO;
import com.umi.nest.dto.RegisterDTO;
import com.umi.nest.dto.UpdatePasswordDTO;
import com.umi.nest.entity.User;
import com.umi.nest.mapper.TeacherInvitationCodeMapper;
import com.umi.nest.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录安全与密码策略的接线测试（差距清单 P0-1 / P0-9）。
 *
 * <p>这几个用例锁住三件以前完全没有的事：登录失败会累计并锁定、锁定期间不校验密码、
 * 弱密码在服务端被拒（前端提示挡不住直接调接口）。审计调用用 {@code @Mock} 旁路，
 * 只验证「该留痕的地方真的留了」。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService - 登录锁定与密码策略")
class UserServiceLoginSecurityTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TeacherInvitationCodeMapper invitationCodeMapper;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private UserService userService;

    private static User user(long id, String username, int status) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setPasswordHash("$2a$10$hashhashhashhashhashha");
        u.setRole("student");
        u.setStatus(status);
        u.setDeleted(0);
        return u;
    }

    private static LoginDTO loginDto(String username, String password) {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        return dto;
    }

    @Test
    @DisplayName("锁定中的账户直接拒绝，且不去比对密码")
    void rejectsLockedAccount() {
        User u = user(9L, "chendw", 1);
        u.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(userMapper.findByUsernameOrEmail("chendw")).thenReturn(u);

        Result<?> r = userService.login(loginDto("chendw", "Whatever@1"));

        assertNotEquals(200, r.getCode());
        assertTrue(r.getMessage().contains("锁定"), "提示应说明账户被锁定：" + r.getMessage());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(userMapper, never()).registerLoginFailure(any(), anyInt(), any());
    }

    @Test
    @DisplayName("密码错误累加失败次数，但提示保持模糊（不给剩余次数）")
    void countsPasswordFailures() {
        User u = user(1L, "chendw", 1);
        u.setFailedLoginCount(2);
        when(userMapper.findByUsernameOrEmail("chendw")).thenReturn(u);
        when(passwordEncoder.matches("bad", u.getPasswordHash())).thenReturn(false);

        Result<?> r = userService.login(loginDto("chendw", "bad"));

        assertNotEquals(200, r.getCode());
        assertEquals("用户名或密码错误", r.getMessage());
        verify(userMapper).registerLoginFailure(eq(1L), eq(3), isNull());
        verify(auditLogService).recordAuth(eq(AuditLogService.TYPE_LOGIN), eq("LOGIN"),
                eq("chendw"), eq(1L), eq(false), anyString());
    }

    @Test
    @DisplayName("失败次数达到上限即写入锁定截止时间")
    void locksAccountAtThreshold() {
        User u = user(1L, "chendw", 1);
        u.setFailedLoginCount(4);
        when(userMapper.findByUsernameOrEmail("chendw")).thenReturn(u);
        when(passwordEncoder.matches("bad", u.getPasswordHash())).thenReturn(false);

        Result<?> r = userService.login(loginDto("chendw", "bad"));

        assertTrue(r.getMessage().contains("锁定 15 分钟"), r.getMessage());
        verify(userMapper).registerLoginFailure(eq(1L), eq(5), notNull());
    }

    @Test
    @DisplayName("登录成功：清零失败计数并更新最后登录时间")
    void successResetsFailureCounter() {
        User u = user(1L, "chendw", 1);
        u.setFailedLoginCount(3);
        when(userMapper.findByUsernameOrEmail("chendw")).thenReturn(u);
        when(passwordEncoder.matches("Good@1234", u.getPasswordHash())).thenReturn(true);

        Result<?> r = userService.login(loginDto("chendw", "Good@1234"));

        assertEquals(200, r.getCode());
        assertNotNull(r.getData());
        verify(userMapper).resetLoginFailure(1L);
        verify(userMapper).updateLastLoginTime(1L);
    }

    @Test
    @DisplayName("从没有失败过的账户不必写清零语句")
    void successSkipsResetWhenClean() {
        User u = user(1L, "chendw", 1);
        u.setFailedLoginCount(0);
        when(userMapper.findByUsernameOrEmail("chendw")).thenReturn(u);
        when(passwordEncoder.matches("Good@1234", u.getPasswordHash())).thenReturn(true);

        assertEquals(200, userService.login(loginDto("chendw", "Good@1234")).getCode());
        verify(userMapper, never()).resetLoginFailure(any());
        verify(userMapper).updateLastLoginTime(1L);
    }

    @Test
    @DisplayName("被禁用的账户即使密码正确也拒绝")
    void rejectsDisabledAccount() {
        User u = user(1L, "chendw", 0);
        when(userMapper.findByUsernameOrEmail("chendw")).thenReturn(u);
        when(passwordEncoder.matches("Good@1234", u.getPasswordHash())).thenReturn(true);

        Result<?> r = userService.login(loginDto("chendw", "Good@1234"));

        assertTrue(r.getMessage().contains("禁用"), r.getMessage());
        verify(userMapper, never()).updateLastLoginTime(any());
    }

    @Test
    @DisplayName("账号不存在也要留痕（撞库尝试可查）")
    void auditsUnknownAccount() {
        when(userMapper.findByUsernameOrEmail("ghost")).thenReturn(null);

        Result<?> r = userService.login(loginDto("ghost", "Whatever@1"));

        assertNotEquals(200, r.getCode());
        verify(auditLogService).recordAuth(eq(AuditLogService.TYPE_LOGIN), eq("LOGIN"),
                eq("ghost"), isNull(), eq(false), anyString());
    }

    @Test
    @DisplayName("注册弱密码被服务端拒绝，且不写库")
    void registerRejectsWeakPassword() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("newbie");
        dto.setPassword("123456");
        dto.setConfirmPassword("123456");
        when(userMapper.findByUsername("newbie")).thenReturn(null);

        Result<?> r = userService.register(dto);

        assertTrue(r.getMessage().contains("至少 8 位"), r.getMessage());
        verify(userMapper, never()).insert(any(User.class));
        verify(auditLogService).recordAuth(eq(AuditLogService.TYPE_REGISTER), eq("REGISTER"),
                eq("newbie"), isNull(), eq(false), anyString());
    }

    @Test
    @DisplayName("注册强密码可以正常写库")
    void registerAcceptsStrongPassword() {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("newbie");
        dto.setPassword("Nest@2026x");
        dto.setConfirmPassword("Nest@2026x");
        when(userMapper.findByUsername("newbie")).thenReturn(null);
        when(passwordEncoder.encode("Nest@2026x")).thenReturn("$2a$10$newhash");

        Result<?> r = userService.register(dto);

        assertEquals(200, r.getCode());
        verify(userMapper).insert(any(User.class));
    }

    @Test
    @DisplayName("改密码：弱新密码被拒，且不更新口令")
    void updatePasswordRejectsWeakNewPassword() {
        User u = user(5L, "chendw", 1);
        when(userMapper.findById(5L)).thenReturn(u);
        when(passwordEncoder.matches("Old@1234x", u.getPasswordHash())).thenReturn(true);
        when(passwordEncoder.matches("Abcd1234", u.getPasswordHash())).thenReturn(false);

        UpdatePasswordDTO dto = new UpdatePasswordDTO();
        dto.setCurrentPassword("Old@1234x");
        dto.setNewPassword("Abcd1234");
        dto.setConfirmPassword("Abcd1234");

        Result<?> r = userService.updatePassword(5L, dto);

        assertTrue(r.getMessage().contains("连续序列"), r.getMessage());
        verify(userMapper, never()).updatePasswordById(any(), anyString());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    @DisplayName("改密码：合规的新口令被更新并留痕")
    void updatePasswordAcceptsStrongPassword() {
        User u = user(5L, "chendw", 1);
        when(userMapper.findById(5L)).thenReturn(u);
        when(passwordEncoder.matches("Old@1234x", u.getPasswordHash())).thenReturn(true);
        when(passwordEncoder.matches("Nest@2026x", u.getPasswordHash())).thenReturn(false);
        when(passwordEncoder.encode("Nest@2026x")).thenReturn("$2a$10$newhash");
        when(userMapper.updatePasswordById(5L, "$2a$10$newhash")).thenReturn(1);

        UpdatePasswordDTO dto = new UpdatePasswordDTO();
        dto.setCurrentPassword("Old@1234x");
        dto.setNewPassword("Nest@2026x");
        dto.setConfirmPassword("Nest@2026x");

        Result<?> r = userService.updatePassword(5L, dto);

        assertEquals(200, r.getCode());
        verify(auditLogService).recordAuth(eq(AuditLogService.TYPE_PASSWORD_CHANGE), eq("CHANGE"),
                eq("chendw"), eq(5L), eq(true), anyString());
    }
}
