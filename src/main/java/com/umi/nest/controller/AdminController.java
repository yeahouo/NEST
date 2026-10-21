package com.umi.nest.controller;

import com.umi.nest.common.Result;
import com.umi.nest.dto.AdminUserCreateDTO;
import com.umi.nest.dto.AdminUserUpdateDTO;
import com.umi.nest.dto.InvitationCodeCreateDTO;
import com.umi.nest.service.AdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;

/**
 * 管理员控制器
 * 所有接口需要管理员权限
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private AdminService adminService;

    // ==================== 权限检查辅助方法 ====================

    /**
     * 检查是否为管理员
     */
    private Result<?> checkAdmin(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return Result.error(401, "未登录");
        }

        String role = (String) session.getAttribute("role");
        if (role == null || !"admin".equalsIgnoreCase(role)) {
            return Result.error(403, "无权限访问");
        }

        return null; // 返回 null 表示检查通过
    }

    /**
     * 获取当前管理员ID
     */
    private Long getAdminId(HttpSession session) {
        return (Long) session.getAttribute("userId");
    }

    // ==================== 仪表盘 ====================

    /**
     * 获取仪表盘统计数据
     * GET /api/admin/dashboard
     */
    @GetMapping("/dashboard")
    public Result<?> getDashboard(HttpSession session) {
        Result<?> check = checkAdmin(session);
        if (check != null) return check;

        return adminService.getDashboard();
    }

    // ==================== 邀请码管理 ====================

    /**
     * 创建邀请码
     * POST /api/admin/invitation-codes
     */
    @PostMapping("/invitation-codes")
    public Result<?> createInvitationCode(@RequestBody InvitationCodeCreateDTO dto, HttpSession session) {
        Result<?> check = checkAdmin(session);
        if (check != null) return check;

        return adminService.createInvitationCode(dto, getAdminId(session));
    }

    /**
     * 获取邀请码列表
     * GET /api/admin/invitation-codes
     */
    @GetMapping("/invitation-codes")
    public Result<?> getInvitationCodes(HttpSession session) {
        Result<?> check = checkAdmin(session);
        if (check != null) return check;

        return adminService.getInvitationCodes();
    }

    /**
     * 删除邀请码
     * DELETE /api/admin/invitation-codes/{id}
     */
    @DeleteMapping("/invitation-codes/{id}")
    public Result<?> deleteInvitationCode(@PathVariable Long id, HttpSession session) {
        Result<?> check = checkAdmin(session);
        if (check != null) return check;

        return adminService.deleteInvitationCode(id);
    }

    /**
     * 切换邀请码状态（启用/禁用）
     * PUT /api/admin/invitation-codes/{id}/toggle
     */
    @PutMapping("/invitation-codes/{id}/toggle")
    public Result<?> toggleInvitationCode(@PathVariable Long id, HttpSession session) {
        Result<?> check = checkAdmin(session);
        if (check != null) return check;

        return adminService.toggleInvitationCode(id);
    }

    // ==================== 用户管理 ====================

    /**
     * 获取用户列表
     * GET /api/admin/users
     */
    @GetMapping("/users")
    public Result<?> getUsers(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "20") Integer size,
            HttpSession session) {
        Result<?> check = checkAdmin(session);
        if (check != null) return check;

        return adminService.getUsers(role, status, keyword, page, size);
    }

    /**
     * 获取用户详情
     * GET /api/admin/users/{id}
     */
    @GetMapping("/users/{id}")
    public Result<?> getUserDetail(@PathVariable Long id, HttpSession session) {
        Result<?> check = checkAdmin(session);
        if (check != null) return check;

        return adminService.getUserDetail(id);
    }

    /**
     * 更新用户状态（启用/禁用）
     * PUT /api/admin/users/{id}/status
     */
    @PutMapping("/users/{id}/status")
    public Result<?> updateUserStatus(
            @PathVariable Long id,
            @RequestBody java.util.Map<String, Integer> body,
            HttpSession session) {
        Result<?> check = checkAdmin(session);
        if (check != null) return check;

        Integer status = body.get("status");
        if (status == null) {
            return Result.error("状态不能为空");
        }

        return adminService.updateUserStatus(id, status);
    }

    /**
     * 解除登录失败锁定
     * POST /api/admin/users/{id}/unlock
     */
    @PostMapping("/users/{id}/unlock")
    public Result<?> unlockUser(@PathVariable Long id, HttpSession session) {
        Result<?> check = checkAdmin(session);
        if (check != null) return check;

        return adminService.unlockUser(id);
    }

    /**
     * 更新用户角色
     * PUT /api/admin/users/{id}/role
     */
    @PutMapping("/users/{id}/role")
    public Result<?> updateUserRole(
            @PathVariable Long id,
            @RequestBody java.util.Map<String, String> body,
            HttpSession session) {
        Result<?> check = checkAdmin(session);
        if (check != null) return check;

        String role = body.get("role");
        if (role == null || role.isEmpty()) {
            return Result.error("角色不能为空");
        }

        return adminService.updateUserRole(id, role);
    }

    /**
     * 更新用户信息
     * PUT /api/admin/users/{id}
     */
    @PutMapping("/users/{id}")
    public Result<?> updateUser(
            @PathVariable Long id,
            @RequestBody AdminUserUpdateDTO dto,
            HttpSession session) {
        Result<?> check = checkAdmin(session);
        if (check != null) return check;

        return adminService.updateUser(id, dto);
    }

    // ==================== 教师列表 ====================

    /**
     * 获取教师列表
     * GET /api/admin/teachers
     */
    @GetMapping("/teachers")
    public Result<?> getTeachers(HttpSession session) {
        Result<?> check = checkAdmin(session);
        if (check != null) return check;

        return adminService.getTeachers();
    }

    /**
     * 创建用户
     * POST /api/admin/users
     */
    @PostMapping("/users")
    public Result<?> createUser(@RequestBody AdminUserCreateDTO dto, HttpSession session) {
        Result<?> check = checkAdmin(session);
        if (check != null) return check;

        return adminService.createUser(dto);
    }

    /**
     * 删除用户
     * DELETE /api/admin/users/{id}
     */
    @DeleteMapping("/users/{id}")
    public Result<?> deleteUser(@PathVariable Long id, HttpSession session) {
        Result<?> check = checkAdmin(session);
        if (check != null) return check;

        return adminService.deleteUser(id);
    }
}
