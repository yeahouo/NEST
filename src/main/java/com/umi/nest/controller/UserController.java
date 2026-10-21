package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.dto.*;
import com.umi.nest.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 用户设置控制器
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserService userService;

    // ==================== 查看设置 ====================

    /**
     * 获取用户设置页面所有数据
     * GET /api/user/settings
     */
    @GetMapping("/settings")
    public Result<?> getSettings(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Result.error("未登录");
        }
        return userService.getSettings(userId);
    }

    // ==================== 个人资料 ====================

    /**
     * 更新个人资料
     * PUT /api/user/profile
     */
    @PutMapping("/profile")
    public Result<?> updateProfile(@RequestBody UpdateProfileDTO dto, HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Result.error("未登录");
        }
        return userService.updateProfile(userId, dto);
    }

    // ==================== 账号安全 ====================

    /**
     * 修改密码
     * PUT /api/user/password
     */
    @PutMapping("/password")
    public Result<?> updatePassword(@RequestBody UpdatePasswordDTO dto, HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Result.error("未登录");
        }
        return userService.updatePassword(userId, dto);
    }

    /**
     * 修改手机号
     * PUT /api/user/phone
     */
    @PutMapping("/phone")
    public Result<?> updatePhone(@RequestBody UpdatePhoneDTO dto, HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Result.error("未登录");
        }
        return userService.updatePhone(userId, dto);
    }

    /**
     * 修改邮箱
     * PUT /api/user/email
     */
    @PutMapping("/email")
    public Result<?> updateEmail(@RequestBody UpdateEmailDTO dto, HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Result.error("未登录");
        }
        return userService.updateEmail(userId, dto);
    }

    // ==================== 账号绑定 ====================

    /**
     * 发送验证码（改手机号 / 绑邮箱）
     * POST /api/user/verify-code  body: {"scene":"phone"|"email","target":"+8613812345678"}
     *
     * <p>响应 data 里的 {@code delivered=false} 表示「码是真的，但当前没有接短信/邮件网关，
     * 只写进了服务器日志」——前端据此如实提示，不假装发出去了。
     */
    @PostMapping("/verify-code")
    public Result<?> sendVerifyCode(@RequestBody SendVerifyCodeDTO dto, HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Result.error("未登录");
        }
        return userService.sendBindCode(userId, dto.getScene(), dto.getTarget());
    }

    /**
     * 绑定微信
     * POST /api/user/bind-wechat
     */
    @PostMapping("/bind-wechat")
    public Result<?> bindWechat(@RequestBody BindWechatDTO dto, HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Result.error("未登录");
        }
        return userService.bindWechat(userId, dto);
    }

    /**
     * 解绑微信
     * DELETE /api/user/unbind-wechat
     */
    @DeleteMapping("/unbind-wechat")
    public Result<?> unbindWechat(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Result.error("未登录");
        }
        return userService.unbindWechat(userId);
    }

    // ==================== 通知设置 ====================

    /**
     * 更新通知设置
     * PUT /api/user/notifications
     */
    @PutMapping("/notifications")
    public Result<?> updateNotifications(@RequestBody NotificationSettingsDTO dto, HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Result.error("未登录");
        }
        return userService.updateNotificationSettings(userId, dto);
    }

    // ==================== 头像上传 ====================

    /**
     * 上传头像
     * POST /api/user/avatar
     *
     * <p>{@code file} 刻意声明为可选：如果写死 {@code @RequestParam("file")}，
     * 匿名或非 multipart 的请求会在参数解析阶段就抛
     * {@code Current request is not a multipart request}，被全局异常处理器兜成
     * <b>HTTP 500</b>——登录判断还没跑到就炸了。改成可选后这类请求会走正常分支：
     * 未登录 → 未登录；已登录但没带文件 → 服务层给「请选择要上传的文件」。
     */
    @PostMapping("/avatar")
    public Result<?> uploadAvatar(@RequestParam(value = "file", required = false) MultipartFile file,
                                  HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Result.error("未登录");
        }
        return userService.uploadAvatar(userId, file);
    }

    /**
     * 获取用户头像
     * GET /api/user/avatar
     * GET /api/user/avatar?t=timestamp (防缓存)
     */
    @GetMapping("/avatar")
    public ResponseEntity<Resource> getAvatar(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            // 返回默认头像
            return userService.getDefaultAvatar();
        }
        return userService.getAvatar(userId);
    }

    /**
     * 通过文件名获取头像文件
     * GET /api/user/avatar/file/{filename}
     *
     * <p>要求已登录：头像是个人信息的一部分，未登录不该能按文件名枚举。
     * 文件名本身的合法性（不含路径分隔符、不含 ..）由
     * {@code UserService.loadAvatarFile} 的白名单把关。
     */
    @GetMapping("/avatar/file/{filename}")
    public ResponseEntity<Resource> getAvatarFile(@PathVariable String filename, HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }
        return userService.loadAvatarFile(filename);
    }
}
