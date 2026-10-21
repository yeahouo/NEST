package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.User;
import com.umi.nest.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminService.unlockUser 的测试（等保：登录失败锁定必须有官方解锁出口）。
 *
 * <p>为什么这个出口必须存在：账号自动解锁要等 15 分钟，而连错 5 次的用户需要一个立刻可用的
 * 通道；没有它，运维只能手工改数据库，手工改库恰恰是审计查不到的动作。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminService - 解除登录失败锁定")
class AdminServiceUnlockTest {

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private AdminService adminService;

    private static User user(long id, String username, Integer failures, LocalDateTime lockedUntil) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setFailedLoginCount(failures);
        u.setLockedUntil(lockedUntil);
        return u;
    }

    @Test
    @DisplayName("锁定中的账号 - 解除锁定并清零")
    void unlockLockedUser() {
        when(userMapper.findById(7L))
                .thenReturn(user(7L, "liusq", 5, LocalDateTime.now().plusMinutes(10)));

        Result<?> r = adminService.unlockUser(7L);

        assertEquals(200, r.getCode());
        // 注意：Result.success(T data) 的单参重载把文案放进 data，message 固定是 "success"
        //（全仓库既有约定，前端读 res.data），这里如实断言这个约定。
        assertEquals("已解除锁定", r.getData());
        assertEquals("success", r.getMessage());
        verify(userMapper).resetLoginFailure(7L);
    }

    @Test
    @DisplayName("只剩失败计数（未到锁定时间）- 也算已解除")
    void unlockCountedFailuresOnly() {
        when(userMapper.findById(7L)).thenReturn(user(7L, "liusq", 3, null));

        Result<?> r = adminService.unlockUser(7L);

        assertEquals(200, r.getCode());
        assertEquals("已解除锁定", r.getData());
        verify(userMapper).resetLoginFailure(7L);
    }

    @Test
    @DisplayName("本来没被锁 - 也清零，但消息如实说明")
    void unlockWhenNotLocked() {
        when(userMapper.findById(7L)).thenReturn(user(7L, "liusq", 0, null));

        Result<?> r = adminService.unlockUser(7L);

        assertEquals(200, r.getCode());
        assertEquals("账号未被锁定，已重置失败计数", r.getData());
        verify(userMapper).resetLoginFailure(7L);
    }

    @Test
    @DisplayName("用户不存在 - 拒绝且不写库")
    void unlockMissingUser() {
        when(userMapper.findById(404L)).thenReturn(null);

        Result<?> r = adminService.unlockUser(404L);

        assertEquals(400, r.getCode());
        assertEquals("用户不存在", r.getMessage());
        verify(userMapper).findById(404L);
        verify(userMapper, never()).resetLoginFailure(404L);
    }
}
