package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.dto.AdminUserCreateDTO;
import com.umi.nest.dto.AdminUserUpdateDTO;
import com.umi.nest.dto.InvitationCodeCreateDTO;
import com.umi.nest.entity.TeacherInvitationCode;
import com.umi.nest.entity.User;
import com.umi.nest.mapper.AdminMapper;
import com.umi.nest.mapper.TeacherInvitationCodeMapper;
import com.umi.nest.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 管理员服务
 */
@Service
public class AdminService {

    @Autowired
    private AdminMapper adminMapper;

    @Autowired
    private TeacherInvitationCodeMapper invitationCodeMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ==================== 仪表盘 ====================

    /**
     * 获取仪表盘统计数据
     */
    public Result<?> getDashboard() {
        Map<String, Object> data = new HashMap<>();

        // 用户统计
        data.put("totalUsers", adminMapper.countUsers());
        data.put("students", adminMapper.countStudents());
        data.put("teachers", adminMapper.countTeachers());

        // 今日统计
        data.put("todayNewUsers", adminMapper.countTodayNewUsers());

        // 活跃用户
        data.put("activeUsers", adminMapper.countActiveUsers());

        return Result.success(data);
    }

    // ==================== 邀请码管理 ====================

    /**
     * 创建邀请码
     */
    public Result<?> createInvitationCode(InvitationCodeCreateDTO dto, Long adminId) {
        // 生成或使用自定义邀请码
        String code = dto.getCode();
        if (code == null || code.trim().isEmpty()) {
            code = generateCode();
        }

        // 检查邀请码是否已存在
        TeacherInvitationCode existing = invitationCodeMapper.findByCode(code);
        if (existing != null) {
            return Result.error("邀请码已存在");
        }

        // 构建实体
        TeacherInvitationCode entity = new TeacherInvitationCode();
        entity.setCode(code.trim());
        entity.setDescription(dto.getDescription());
        entity.setStatus(1);
        entity.setUsageCount(0);
        entity.setMaxUsage(dto.getMaxUsage());
        entity.setCreatedBy(adminId);

        // 解析过期时间
        if (dto.getExpiresAt() != null && !dto.getExpiresAt().isEmpty()) {
            try {
                entity.setExpiresAt(LocalDateTime.parse(dto.getExpiresAt(),
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            } catch (Exception e) {
                try {
                    entity.setExpiresAt(LocalDateTime.parse(dto.getExpiresAt() + " 23:59:59",
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                } catch (Exception ex) {
                    return Result.error("过期时间格式错误，请使用 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss 格式");
                }
            }
        }

        invitationCodeMapper.insert(entity);

        Map<String, Object> data = new HashMap<>();
        data.put("id", entity.getId());
        data.put("code", entity.getCode());

        return Result.success("邀请码创建成功", data);
    }

    /**
     * 生成随机邀请码
     */
    private String generateCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random random = new Random();
        StringBuilder sb = new StringBuilder("TCH");
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /**
     * 获取邀请码列表
     */
    public Result<?> getInvitationCodes() {
        List<TeacherInvitationCode> codes = invitationCodeMapper.findAll();

        // 转换为前端友好的格式
        List<Map<String, Object>> list = new ArrayList<>();
        for (TeacherInvitationCode code : codes) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", code.getId());
            item.put("code", code.getCode());
            item.put("description", code.getDescription());
            item.put("status", code.getStatus());
            item.put("usageCount", code.getUsageCount());
            item.put("maxUsage", code.getMaxUsage());
            item.put("expiresAt", code.getExpiresAt());
            item.put("createdBy", code.getCreatedBy());
            item.put("createTime", code.getCreateTime());

            // 计算是否可用
            boolean usable = code.getStatus() == 1;
            if (usable && code.getMaxUsage() != null) {
                usable = code.getUsageCount() < code.getMaxUsage();
            }
            if (usable && code.getExpiresAt() != null) {
                usable = code.getExpiresAt().isAfter(LocalDateTime.now());
            }
            item.put("usable", usable);

            list.add(item);
        }

        return Result.success(list);
    }

    /**
     * 删除邀请码
     */
    public Result<?> deleteInvitationCode(Long id) {
        TeacherInvitationCode code = invitationCodeMapper.findById(id);
        if (code == null) {
            return Result.error("邀请码不存在");
        }

        invitationCodeMapper.deleteById(id);
        return Result.success("邀请码已删除");
    }

    /**
     * 切换邀请码状态
     */
    public Result<?> toggleInvitationCode(Long id) {
        TeacherInvitationCode code = invitationCodeMapper.findById(id);
        if (code == null) {
            return Result.error("邀请码不存在");
        }

        int newStatus = code.getStatus() == 1 ? 0 : 1;
        invitationCodeMapper.toggleStatus(id, newStatus);

        return Result.success(newStatus == 1 ? "邀请码已启用" : "邀请码已禁用");
    }

    // ==================== 用户管理 ====================

    /**
     * 获取用户列表
     */
    public Result<?> getUsers(String role, Integer status, String keyword, Integer page, Integer size) {
        if (page == null || page < 1) page = 1;
        if (size == null || size < 1) size = 20;

        int offset = (page - 1) * size;

        List<User> users = adminMapper.findUsers(role, status, keyword, offset, size);
        int total = adminMapper.countUsersByFilter(role, status, keyword);

        // 转换为前端友好的格式
        List<Map<String, Object>> list = new ArrayList<>();
        for (User user : users) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", user.getId());
            item.put("username", user.getUsername());
            item.put("nickname", user.getNickname());
            item.put("email", user.getEmail());
            item.put("phone", user.getPhone());
            item.put("role", user.getRole());
            item.put("avatar", user.getAvatar());
            item.put("status", user.getStatus());
            item.put("lastLoginTime", user.getLastLoginTime());
            item.put("createTime", user.getCreateTime());
            item.put("realName", user.getRealName());
            item.put("studentId", user.getStudentId());
            item.put("school", user.getSchool());
            // 登录失败锁定状态（等保：登录失败处理）——列表里要能一眼看出谁被锁了、锁到什么时候、
            // 还剩几次机会；locked 由服务端算好，免得前端各自比较时间（时区与格式都不一样）。
            item.put("failedLoginCount", user.getFailedLoginCount());
            item.put("lockedUntil", user.getLockedUntil());
            item.put("locked", isLocked(user));
            list.add(item);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", total);
        data.put("page", page);
        data.put("size", size);
        data.put("totalPages", (int) Math.ceil((double) total / size));

        return Result.success(data);
    }

    /**
     * 获取用户详情
     */
    public Result<?> getUserDetail(Long id) {
        User user = userMapper.findById(id);
        if (user == null) {
            return Result.error("用户不存在");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("email", user.getEmail());
        data.put("phone", user.getPhone());
        data.put("role", user.getRole());
        data.put("avatar", user.getAvatar());
        data.put("status", user.getStatus());
        data.put("lastLoginTime", user.getLastLoginTime());
        data.put("createTime", user.getCreateTime());
        data.put("updateTime", user.getUpdateTime());
        data.put("realName", user.getRealName());
        data.put("studentId", user.getStudentId());
        data.put("school", user.getSchool());
        data.put("department", user.getDepartment());
        data.put("major", user.getMajor());
        data.put("className", user.getClassName());
        data.put("grade", user.getGrade());
        data.put("failedLoginCount", user.getFailedLoginCount());
        data.put("lockedUntil", user.getLockedUntil());
        data.put("locked", isLocked(user));

        return Result.success(data);
    }

    /** 账号此刻是否处于登录失败锁定状态（locked_until 在未来即为锁定中）。 */
    private static boolean isLocked(User user) {
        return user.getLockedUntil() != null && user.getLockedUntil().isAfter(java.time.LocalDateTime.now());
    }

    /**
     * 更新用户状态
     */
    public Result<?> updateUserStatus(Long id, Integer status) {
        User user = userMapper.findById(id);
        if (user == null) {
            return Result.error("用户不存在");
        }

        // 不能禁用自己
        adminMapper.updateUserStatus(id, status);

        return Result.success(status == 1 ? "用户已启用" : "用户已禁用");
    }

    /**
     * 解除登录失败锁定（等保：登录失败处理）
     *
     * <p>为什么必须有这个官方出口：账号被锁 15 分钟后虽然会自动解锁，但连错 5 次的用户
     * （打错密码又急着交作业）需要一个立刻能用的通道；没有它，运维只能手工改数据库，
     * 而手工改库恰恰是审计最查不到的动作。本方法调用本身会被
     * {@code AuditLogInterceptor} 记成一条 {@code ADMIN} 审计（target=/api/admin/users/{id}/unlock）。
     */
    public Result<?> unlockUser(Long id) {
        User user = userMapper.findById(id);
        if (user == null) {
            return Result.error("用户不存在");
        }

        boolean wasLocked = user.getLockedUntil() != null
                || (user.getFailedLoginCount() != null && user.getFailedLoginCount() > 0);
        userMapper.resetLoginFailure(id);

        if (wasLocked) {
            return Result.success("已解除锁定");
        }
        return Result.success("账号未被锁定，已重置失败计数");
    }

    /**
     * 更新用户角色
     */
    public Result<?> updateUserRole(Long id, String role) {
        User user = userMapper.findById(id);
        if (user == null) {
            return Result.error("用户不存在");
        }

        if (!Arrays.asList("student", "teacher", "admin").contains(role)) {
            return Result.error("无效的角色");
        }

        adminMapper.updateUserRole(id, role);

        return Result.success("角色更新成功");
    }

    /**
     * 更新用户信息
     */
    public Result<?> updateUser(Long id, AdminUserUpdateDTO dto) {
        User user = userMapper.findById(id);
        if (user == null) {
            return Result.error("用户不存在");
        }

        // 检查邮箱是否被其他用户使用
        if (dto.getEmail() != null && !dto.getEmail().isEmpty()) {
            User existing = userMapper.findByEmail(dto.getEmail());
            if (existing != null && !existing.getId().equals(id)) {
                return Result.error("邮箱已被其他用户使用");
            }
        }

        // 检查手机号是否被其他用户使用
        if (dto.getPhone() != null && !dto.getPhone().isEmpty()) {
            User existing = userMapper.findByPhone(dto.getPhone());
            if (existing != null && !existing.getId().equals(id)) {
                return Result.error("手机号已被其他用户使用");
            }
        }

        // 更新字段
        if (dto.getNickname() != null) user.setNickname(dto.getNickname());
        if (dto.getEmail() != null) user.setEmail(dto.getEmail());
        if (dto.getPhone() != null) user.setPhone(dto.getPhone());
        if (dto.getRole() != null) user.setRole(dto.getRole());
        if (dto.getStatus() != null) user.setStatus(dto.getStatus());

        adminMapper.adminUpdateUser(user);

        return Result.success("用户信息更新成功");
    }

    // ==================== 教师列表 ====================

    /**
     * 获取教师列表（辅导员分派下拉使用）
     */
    public Result<?> getTeachers() {
        List<User> teachers = adminMapper.findTeachers();
        List<Map<String, Object>> list = new ArrayList<>();
        for (User t : teachers) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", t.getId());
            item.put("username", t.getUsername());
            item.put("nickname", t.getNickname());
            list.add(item);
        }
        return Result.success(list);
    }

    // ==================== 用户创建/删除 ====================

    /**
     * 管理员创建用户
     */
    public Result<?> createUser(AdminUserCreateDTO dto) {
        if (dto.getUsername() == null || dto.getUsername().trim().isEmpty()) {
            return Result.error("用户名不能为空");
        }
        if (dto.getPassword() == null || dto.getPassword().trim().isEmpty()) {
            return Result.error("密码不能为空");
        }

        // 用户名唯一
        if (userMapper.findByUsername(dto.getUsername()) != null) {
            return Result.error("用户名已存在");
        }

        String role = dto.getRole();
        if (role == null || !Arrays.asList("student", "teacher", "admin").contains(role)) {
            role = "student";
        }

        User user = new User();
        user.setUsername(dto.getUsername().trim());
        user.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname() != null ? dto.getNickname() : dto.getUsername());
        user.setEmail(dto.getEmail());
        user.setPhone(dto.getPhone());
        user.setRole(role);

        adminMapper.adminInsertUser(user);

        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("role", user.getRole());
        return Result.success("用户创建成功", data);
    }

    /**
     * 删除用户（逻辑删除）
     */
    public Result<?> deleteUser(Long id) {
        User user = userMapper.findById(id);
        if (user == null) {
            return Result.error("用户不存在");
        }
        if ("admin".equals(user.getRole())) {
            return Result.error("不能删除管理员账户");
        }

        adminMapper.deleteUser(id);
        return Result.success("用户已删除");
    }
}
