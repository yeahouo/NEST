package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.dto.LoginDTO;
import com.umi.nest.dto.RegisterDTO;
import com.umi.nest.dto.ResetPasswordDTO;
import com.umi.nest.dto.SendCodeDTO;
import com.umi.nest.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.Map;

/**
 * 认证控制器
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserService userService;

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public Result<?> register(@RequestBody RegisterDTO dto) {
        return userService.register(dto);
    }

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public Result<?> login(@RequestBody LoginDTO dto, HttpSession session) {
        Result<?> result = userService.login(dto);

        // 登录成功，只存最小必要信息到 Session
        if (result.getCode() == 200 && result.getData() != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> userData = (Map<String, Object>) result.getData();

            // Session 只存 userId 和 role
            session.setAttribute("userId", userData.get("id"));
            session.setAttribute("role", userData.get("role"));
        }

        return result;
    }

    /**
     * 用户登出
     */
    @PostMapping("/logout")
    public Result<?> logout(HttpSession session) {
        session.invalidate();
        return Result.success("登出成功");
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/me")
    public Result<?> getCurrentUser(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Result.error("未登录");
        }

        // 通过 userId 查询最新用户信息
        return userService.getUserInfo(userId);
    }

    /**
     * 发送重置密码验证码（忘记密码页）。
     *
     * <p>这个方法以前是空的：注释写着「预留接口，暂简化实现」，实现是
     * {@code userService.sendVerificationCode(phone)} —— 只查手机号注册没，
     * 然后直接返回「验证码已发送」，从不生成、从不发送。由于
     * {@code /reset-password} 当时也不校验验证码，两者合起来就是一个真实漏洞：
     * 把任意已注册手机号填进前端页面，就能重置那个账号的密码。
     * 现在码是真的（随机生成、HMAC 后入库、5 分钟过期、一次性、限流），
     * 重置密码也真的校验它。
     */
    @PostMapping("/send-code")
    public Result<?> sendVerificationCode(@RequestBody SendCodeDTO dto) {
        return userService.sendVerificationCode(dto.getPhone());
    }

    /**
     * 重置密码
     */
    @PostMapping("/reset-password")
    public Result<?> resetPassword(@RequestBody ResetPasswordDTO dto) {
        return userService.resetPassword(dto);
    }
}
