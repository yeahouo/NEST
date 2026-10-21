package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.dto.*;
import com.umi.nest.entity.TeacherInvitationCode;
import com.umi.nest.entity.User;
import com.umi.nest.mapper.TeacherInvitationCodeMapper;
import com.umi.nest.mapper.UserMapper;
import com.umi.nest.wechat.WeChatOAuthClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 用户服务
 */
@Service
public class UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TeacherInvitationCodeMapper invitationCodeMapper;

    @Autowired
    private VerifyCodeService verifyCodeService;

    @Autowired
    private WeChatOAuthClient weChatOAuthClient;

    /**
     * 审计服务（差距清单 P0-1）。{@code required = false} 是刻意的：单元测试用
     * {@code @InjectMocks} 构造本类时不会注入它，而审计永远只是旁路——
     * 缺了它业务必须照常跑通，所以下面的 {@code audit(...)} 帮助方法会空判。
     */
    @Autowired(required = false)
    private AuditLogService auditLogService;

    @Value("${app.teacher.invitation-code:TEACHER2024}")
    private String teacherInvitationCode;

    /** 连续登录失败多少次后锁定账户（等保：登录失败锁定）。 */
    @Value("${nest.security.max-failed-logins:5}")
    private int maxFailedLogins = 5;

    /** 锁定分钟数（到点自动放行）。 */
    @Value("${nest.security.lock-minutes:15}")
    private int lockMinutes = 15;

    /**
     * 写一条认证类审计记录（登录/注册/重置密码）。审计不可用时静默跳过。
     */
    private void audit(String eventType, String action, String username, Long userId,
                       boolean success, String detail) {
        if (auditLogService != null) {
            auditLogService.recordAuth(eventType, action, username, userId, success, detail);
        }
    }

    // ==================== 认证相关方法 ====================

    /**
     * 用户注册
     */
    public Result<?> register(RegisterDTO dto) {
        // 1. 用户名唯一校验
        if (userMapper.findByUsername(dto.getUsername()) != null) {
            return Result.error("用户名已存在");
        }

        // 2. 手机号唯一校验
        if (dto.getPhone() != null && !dto.getPhone().isEmpty()) {
            if (userMapper.findByPhone(dto.getPhone()) != null) {
                return Result.error("手机号已被注册");
            }
        }

        // 3. 密码确认校验
        if (dto.getConfirmPassword() != null && !dto.getPassword().equals(dto.getConfirmPassword())) {
            return Result.error("两次密码输入不一致");
        }

        // 3.5 密码复杂度（等保：鉴别信息具复杂度要求并定期更换）。
        //     以前这一层完全没有，所以 123456 一直是合法密码——前端提示挡不住直接调接口。
        String policyError = PasswordPolicy.check(dto.getPassword(), dto.getUsername(), dto.getPhone());
        if (policyError != null) {
            audit(AuditLogService.TYPE_REGISTER, "REGISTER", dto.getUsername(), null, false, policyError);
            return Result.error(policyError);
        }

        // 4. 通过邀请码确定角色（优先使用数据库邀请码）
        String inputCode = dto.getInvitationCode();
        String role;

        if (inputCode != null && !inputCode.trim().isEmpty()) {
            // 有邀请码，先从数据库验证
            TeacherInvitationCode codeEntity = invitationCodeMapper.findByCode(inputCode.trim());

            if (codeEntity != null) {
                // 数据库邀请码验证
                // 检查是否过期
                if (codeEntity.getExpiresAt() != null && codeEntity.getExpiresAt().isBefore(LocalDateTime.now())) {
                    return Result.error("邀请码已过期");
                }

                // 检查使用次数
                if (codeEntity.getMaxUsage() != null && codeEntity.getUsageCount() >= codeEntity.getMaxUsage()) {
                    return Result.error("邀请码已达到最大使用次数");
                }

                // 验证通过，增加使用次数
                invitationCodeMapper.incrementUsageCount(codeEntity.getId());
                role = "teacher";
            } else if (teacherInvitationCode.equals(inputCode.trim())) {
                // 后备：使用配置文件中的邀请码
                role = "teacher";
            } else {
                return Result.error("邀请码错误");
            }
        } else {
            // 无邀请码，默认为学生
            role = "student";
        }

        // 5. 构建用户实体
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getUsername()); // 默认昵称同用户名
        user.setPhone(dto.getPhone());
        user.setRole(role);
        user.setStatus(1);      // 默认正常
        user.setDeleted(0);     // 默认未删除

        // 初始化新字段默认值
        // phone_verified 一律从 0 开始：注册时只是"填了一个手机号"，
        // 没有人给这个号码发过验证码、也没有人证明过它属于本人。
        // 原来这里写的是 dto.getPhone() != null ? 1 : 0 —— 填了就当作已验证，
        // 于是"已验证"这个标记从一开始就是假的，用户看到绿勾其实是自己刚输入的。
        // 现在只有走完 VerifyCodeService 的校验（改绑手机号那条路）才会变成 1。
        user.setPhoneVerified(0);
        user.setEmailVerified(0);
        user.setNotifyAssignmentDeadline(1);
        user.setNotifyPrivateMessage(1);
        user.setNotifyTopicReply(1);

        // 7. 保存
        userMapper.insert(user);

        // 8. 返回用户信息（不含密码）
        audit(AuditLogService.TYPE_REGISTER, "REGISTER", user.getUsername(), user.getId(), true,
                "注册成功，角色 " + user.getRole());
        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("phone", user.getPhone());
        data.put("role", user.getRole());

        return Result.success("注册成功", data);
    }

    /**
     * 用户登录
     */
    public Result<?> login(LoginDTO dto) {
        // 1. 根据用户名或邮箱查询
        User user = userMapper.findByUsernameOrEmail(dto.getAccount());

        // 2. 用户不存在（使用模糊提示避免枚举攻击）
        if (user == null) {
            // 失败时也记审计：等保要求「事件是否成功」可查，撞库尝试必须留痕
            audit(AuditLogService.TYPE_LOGIN, "LOGIN", dto.getAccount(), null, false, "账号不存在");
            return Result.error("用户名或密码错误");
        }

        // 2.5 账户锁定检查（等保：登录失败锁定）。
        //     提示里给出剩余分钟数是可以的——能走到这里说明密码是对的过一次的账号，
        //     但对「密码错误」的分支必须继续用模糊提示，否则「剩余尝试次数」这种文案
        //     会变成用户名枚举的信道。
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            long minutes = java.time.Duration.between(LocalDateTime.now(), user.getLockedUntil()).toMinutes() + 1;
            audit(AuditLogService.TYPE_LOGIN, "LOGIN", user.getUsername(), user.getId(), false,
                    "账户锁定中，剩余 " + minutes + " 分钟");
            return Result.error("账户已锁定，请 " + minutes + " 分钟后再试");
        }

        // 3. 密码校验
        if (!passwordEncoder.matches(dto.getPassword(), user.getPasswordHash())) {
            int failed = (user.getFailedLoginCount() == null ? 0 : user.getFailedLoginCount()) + 1;
            boolean lock = failed >= maxFailedLogins;
            userMapper.registerLoginFailure(user.getId(), failed,
                    lock ? LocalDateTime.now().plusMinutes(lockMinutes) : null);
            audit(AuditLogService.TYPE_LOGIN, "LOGIN", user.getUsername(), user.getId(), false,
                    lock ? "密码错误 " + failed + " 次，账户锁定 " + lockMinutes + " 分钟" : "密码错误");
            if (lock) {
                return Result.error("密码错误次数过多，账户已锁定 " + lockMinutes + " 分钟");
            }
            return Result.error("用户名或密码错误");
        }

        // 4. 状态检查
        if (user.getStatus() == 0) {
            audit(AuditLogService.TYPE_LOGIN, "LOGIN", user.getUsername(), user.getId(), false, "账户已被禁用");
            return Result.error("账户已被禁用");
        }

        // 5. 登录成功：清零失败计数、解除锁定、更新最后登录时间
        boolean hadFailures = user.getFailedLoginCount() != null && user.getFailedLoginCount() > 0;
        if (hadFailures || user.getLockedUntil() != null) {
            userMapper.resetLoginFailure(user.getId());
        }
        userMapper.updateLastLoginTime(user.getId());
        audit(AuditLogService.TYPE_LOGIN, "LOGIN", user.getUsername(), user.getId(), true, "登录成功");

        // 6. 返回用户信息（不含密码）
        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("email", user.getEmail());
        data.put("phone", user.getPhone());
        data.put("role", user.getRole());
        data.put("avatar", user.getAvatar());

        return Result.success("登录成功", data);
    }

    /**
     * 重置密码（通过手机号）
     */
    public Result<?> resetPassword(ResetPasswordDTO dto) {
        // 1. 验证手机号是否已注册（容忍历史数据里的号码格式差异）
        User user = findUserByPhoneNumber(dto.getPhone());
        if (user == null) {
            return Result.error("该手机号未注册");
        }

        // 2. 密码确认校验
        if (dto.getConfirmPassword() != null && !dto.getNewPassword().equals(dto.getConfirmPassword())) {
            return Result.error("两次密码输入不一致");
        }

        // 2.5 密码复杂度（等保：鉴别信息具复杂度要求）
        String policyError = PasswordPolicy.check(dto.getNewPassword(), user.getUsername(), user.getPhone());
        if (policyError != null) {
            audit(AuditLogService.TYPE_PASSWORD_RESET, "RESET", user.getUsername(), user.getId(), false, policyError);
            return Result.error(policyError);
        }

        // 3. 验证码校验。
        //    这一步以前是空的（注释写着「预留，暂不强制校验」），后果不是「少一道校验」而是
        //    「任何人都能改掉任何人的密码」：手机号由前端 sessionStorage 提供，服务端
        //    再不做任何确认，就等于把「证明你拥有这个手机号」这件事完全交给了攻击者。
        String badCode = verifyCodeService.check(
                VerifyCodeService.SCENE_RESET_PASSWORD, dto.getPhone(), dto.getVerificationCode());
        if (badCode != null) {
            return Result.error(badCode);
        }

        // 4. 更新密码。这里用查出来的 user.getPhone() 而不是 dto.getPhone()：
        //    上面是宽容匹配（可能剥掉了 +86），而 UPDATE 是按精确值匹配的，
        //    若拿前端的写法去 UPDATE，宽容查到的那个用户会一行也更新不到。
        String newPasswordHash = passwordEncoder.encode(dto.getNewPassword());
        int result = userMapper.updatePasswordByPhone(user.getPhone(), newPasswordHash);

        if (result > 0) {
            audit(AuditLogService.TYPE_PASSWORD_RESET, "RESET", user.getUsername(), user.getId(), true,
                    "通过手机验证码重置密码");
            return Result.success("密码重置成功");
        } else {
            audit(AuditLogService.TYPE_PASSWORD_RESET, "RESET", user.getUsername(), user.getId(), false,
                    "数据库更新影响行数为 0");
            return Result.error("密码重置失败");
        }
    }

    /**
     * 发送「忘记密码」验证码（POST /api/auth/send-code）。
     *
     * <p>只给已注册的手机号发码。这确实会泄露「某个手机号有没有注册过」，
     * 对学校内部系统这是可接受的取舍：老师忘密码时最需要的就是
     * 「这个号到底是不是我当年留的那个」这一句明确回答。
     */
    public Result<?> sendVerificationCode(String phone) {
        User user = findUserByPhoneNumber(phone);
        if (user == null) {
            return Result.error("该手机号未注册");
        }
        return verifyCodeService.send(VerifyCodeService.SCENE_RESET_PASSWORD, phone);
    }

    /**
     * 按手机号找用户，容忍「+86 13800000004」和「13800000004」两种写法。
     *
     * <p>为什么需要：前端提交的永远是选区号拼出来的「+8613800000004」，
     * 而早期 SQL 种子里 8 个演示账号的手机号直接写成了 11 位的「13800000004」。
     * 两边都「没错」，只是对不上 —— 结果就是演示账号点「忘记密码」永远得到
     * 「该手机号未注册」。与其在功能开发中途去改用户数据（文档和试点名单里
     * 引用的都是旧写法），不如让查询本身宽容一点。
     *
     * <p>只做一件事：带国号的长号第一次查不到时，剥掉 +86 再查一次。
     * 不做模糊匹配、不猜区号，宁可返回 null 也不猜。
     */
    private User findUserByPhoneNumber(String phone) {
        User user = userMapper.findByPhone(phone);
        if (user != null) {
            return user;
        }
        String digits = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        if (digits.length() == 13 && digits.startsWith("86")) {
            return userMapper.findByPhone(digits.substring(2));
        }
        return null;
    }

    /**
     * 发送「改手机号 / 绑邮箱」验证码（设置页，需登录）。
     *
     * <p>发送前先把「这个新手机号/邮箱是不是已经被别人占了」检查掉 ——
     * 让用户拿到码、填完、提交时才被拒绝，是很糟的体验。
     */
    public Result<?> sendBindCode(Long userId, String scene, String target) {
        User user = userMapper.findById(userId);
        if (user == null) {
            return Result.error("用户不存在");
        }

        String kind;
        String finalScene;
        if ("phone".equalsIgnoreCase(scene) || VerifyCodeService.SCENE_BIND_PHONE.equals(scene)) {
            kind = "手机号";
            finalScene = VerifyCodeService.SCENE_BIND_PHONE;
            User existing = userMapper.findByPhone(target);
            if (existing != null && !existing.getId().equals(userId)) {
                return Result.error("该手机号已被其他账号绑定");
            }
        } else if ("email".equalsIgnoreCase(scene) || VerifyCodeService.SCENE_BIND_EMAIL.equals(scene)) {
            kind = "邮箱";
            finalScene = VerifyCodeService.SCENE_BIND_EMAIL;
            User existing = userMapper.findByEmail(target);
            if (existing != null && !existing.getId().equals(userId)) {
                return Result.error("该邮箱已被其他账号绑定");
            }
        } else {
            return Result.error("未知的验证码场景");
        }

        if (target == null || target.trim().isEmpty()) {
            return Result.error("请先填写" + kind);
        }
        return verifyCodeService.send(finalScene, target);
    }

    /**
     * 根据用户ID获取用户信息（供 Session 恢复使用）
     */
    public Result<?> getUserInfo(Long userId) {
        User user = userMapper.findById(userId);
        if (user == null) {
            return Result.error("用户不存在");
        }

        // 检查状态
        if (user.getStatus() == 0 || user.getDeleted() == 1) {
            return Result.error("账户异常");
        }

        // 返回用户信息（不含密码）
        return Result.success(buildUserInfoMap(user));
    }

    // ==================== 设置模块方法 ====================

    /**
     * 获取用户设置页面所有数据
     */
    public Result<?> getSettings(Long userId) {
        User user = userMapper.findById(userId);
        if (user == null) {
            return Result.error("用户不存在");
        }

        Map<String, Object> settings = new HashMap<>();

        // 基础账号信息
        Map<String, Object> account = new HashMap<>();
        account.put("username", user.getUsername());
        account.put("role", user.getRole());
        account.put("phone", user.getPhone());
        account.put("phoneVerified", user.getPhoneVerified());
        account.put("email", user.getEmail());
        account.put("emailVerified", user.getEmailVerified());
        account.put("passwordUpdatedAt", user.getPasswordUpdatedAt());
        settings.put("account", account);

        // 个人资料信息
        Map<String, Object> profile = new HashMap<>();
        profile.put("realName", user.getRealName());
        profile.put("studentId", user.getStudentId());
        profile.put("school", user.getSchool());
        profile.put("department", user.getDepartment());
        profile.put("major", user.getMajor());
        profile.put("className", user.getClassName());
        profile.put("grade", user.getGrade());
        profile.put("enrollmentDate", user.getEnrollmentDate());
        settings.put("profile", profile);

        // 账号绑定状态
        Map<String, Object> bindings = new HashMap<>();
        bindings.put("wechatBound", user.getWechatOpenid() != null);
        bindings.put("wechatNickname", user.getWechatNickname());
        bindings.put("wechatBoundAt", user.getWechatBoundAt());
        // 微信能不能真的绑：设置页据此决定「Bind」按钮是发起授权还是直接说明原因。
        // 以前前端在这种情况下的做法是 console.log('WeChat OAuth integration required') ——
        // 用户点了按钮，屏幕上什么都不会发生。
        bindings.put("wechatAvailable", weChatOAuthClient.configured());
        bindings.put("wechatUnavailableReason", weChatOAuthClient.configured() ? null : weChatOAuthClient.unavailableReason());
        bindings.put("emailBound", user.getEmail() != null && user.getEmailVerified() == 1);
        bindings.put("emailBoundAt", user.getEmailBoundAt());
        settings.put("bindings", bindings);

        // 通知设置
        Map<String, Object> notifications = new HashMap<>();
        notifications.put("assignmentDeadline", user.getNotifyAssignmentDeadline());
        notifications.put("privateMessage", user.getNotifyPrivateMessage());
        notifications.put("topicReply", user.getNotifyTopicReply());
        settings.put("notifications", notifications);

        return Result.success(settings);
    }

    /**
     * 更新个人资料
     */
    public Result<?> updateProfile(Long userId, UpdateProfileDTO dto) {
        User user = userMapper.findById(userId);
        if (user == null) {
            return Result.error("用户不存在");
        }

        // 如果填写了学号，检查是否已被其他用户使用
        if (dto.getStudentId() != null && !dto.getStudentId().isEmpty()) {
            User existing = userMapper.findByStudentId(dto.getStudentId());
            if (existing != null && !existing.getId().equals(userId)) {
                return Result.error("学号已被使用");
            }
        }

        // 更新字段
        user.setRealName(dto.getRealName());
        user.setStudentId(dto.getStudentId());
        user.setSchool(dto.getSchool());
        user.setDepartment(dto.getDepartment());
        user.setMajor(dto.getMajor());
        user.setClassName(dto.getClassName());
        user.setGrade(dto.getGrade());
        if (dto.getEnrollmentDate() != null && !dto.getEnrollmentDate().isEmpty()) {
            user.setEnrollmentDate(java.time.LocalDate.parse(dto.getEnrollmentDate()));
        }
        user.setNickname(dto.getNickname());
        user.setAvatar(dto.getAvatar());

        int result = userMapper.updateById(user);
        if (result > 0) {
            return Result.success("资料更新成功", buildUserInfoMap(user));
        } else {
            return Result.error("资料更新失败");
        }
    }

    /**
     * 修改密码（需要验证旧密码）
     */
    public Result<?> updatePassword(Long userId, UpdatePasswordDTO dto) {
        User user = userMapper.findById(userId);
        if (user == null) {
            return Result.error("用户不存在");
        }

        // 验证旧密码（兼容前端 currentPassword 字段）
        if (!passwordEncoder.matches(dto.getCurrentPassword(), user.getPasswordHash())) {
            return Result.error("原密码错误");
        }

        // 验证新密码确认
        if (!dto.getNewPassword().equals(dto.getConfirmPassword())) {
            return Result.error("两次密码输入不一致");
        }

        // 新密码不能与旧密码相同
        if (passwordEncoder.matches(dto.getNewPassword(), user.getPasswordHash())) {
            return Result.error("新密码不能与原密码相同");
        }

        // 密码复杂度（等保：鉴别信息具复杂度要求）
        String policyError = PasswordPolicy.check(dto.getNewPassword(), user.getUsername(), user.getPhone());
        if (policyError != null) {
            audit(AuditLogService.TYPE_PASSWORD_CHANGE, "CHANGE", user.getUsername(), userId, false, policyError);
            return Result.error(policyError);
        }

        // 更新密码
        String newPasswordHash = passwordEncoder.encode(dto.getNewPassword());
        int result = userMapper.updatePasswordById(userId, newPasswordHash);

        if (result > 0) {
            audit(AuditLogService.TYPE_PASSWORD_CHANGE, "CHANGE", user.getUsername(), userId, true,
                    "用户自助修改密码");
            return Result.success("密码修改成功");
        } else {
            return Result.error("密码修改失败");
        }
    }

    /**
     * 修改手机号
     */
    public Result<?> updatePhone(Long userId, UpdatePhoneDTO dto) {
        User user = userMapper.findById(userId);
        if (user == null) {
            return Result.error("用户不存在");
        }

        // 验证当前密码（如果提供了密码）
        String currentPwd = dto.getCurrentPassword();
        if (currentPwd != null && !currentPwd.isEmpty()) {
            if (!passwordEncoder.matches(currentPwd, user.getPasswordHash())) {
                return Result.error("密码错误");
            }
        }

        // 检查新手机号是否已被使用
        User existing = userMapper.findByPhone(dto.getNewPhone());
        if (existing != null && !existing.getId().equals(userId)) {
            return Result.error("该手机号已被其他账号绑定");
        }

        // 验证码校验：证明「这个新手机号现在在你手上」。
        String badCode = verifyCodeService.check(
                VerifyCodeService.SCENE_BIND_PHONE, dto.getNewPhone(), dto.getVerificationCode());
        if (badCode != null) {
            return Result.error(badCode);
        }

        // 更新手机号（UserMapper.updatePhone 同时把 phone_verified 置 1）
        int result = userMapper.updatePhone(userId, dto.getNewPhone());
        if (result > 0) {
            return Result.success("手机号修改成功");
        } else {
            return Result.error("手机号修改失败");
        }
    }

    /**
     * 修改邮箱
     */
    public Result<?> updateEmail(Long userId, UpdateEmailDTO dto) {
        User user = userMapper.findById(userId);
        if (user == null) {
            return Result.error("用户不存在");
        }

        // 验证当前密码（如果提供了密码）
        String currentPwd = dto.getCurrentPassword();
        if (currentPwd != null && !currentPwd.isEmpty()) {
            if (!passwordEncoder.matches(currentPwd, user.getPasswordHash())) {
                return Result.error("密码错误");
            }
        }

        // 检查新邮箱是否已被使用
        User existing = userMapper.findByEmail(dto.getNewEmail());
        if (existing != null && !existing.getId().equals(userId)) {
            return Result.error("该邮箱已被其他账号绑定");
        }

        // 验证码校验：证明「这个邮箱现在在你手上」。
        String badCode = verifyCodeService.check(
                VerifyCodeService.SCENE_BIND_EMAIL, dto.getNewEmail(), dto.getVerificationCode());
        if (badCode != null) {
            return Result.error(badCode);
        }

        // 更新邮箱（UserMapper.updateEmail 同时把 email_verified 置 1、写 email_bound_at）
        int result = userMapper.updateEmail(userId, dto.getNewEmail());
        if (result > 0) {
            return Result.success("邮箱绑定成功");
        } else {
            return Result.error("邮箱绑定失败");
        }
    }

    /**
     * 绑定微信
     */
    public Result<?> bindWechat(Long userId, BindWechatDTO dto) {
        User user = userMapper.findById(userId);
        if (user == null) {
            return Result.error("用户不存在");
        }

        // 已绑定检查
        if (user.getWechatOpenid() != null) {
            return Result.error("已绑定微信，请先解绑");
        }

        // 这一段以前是这样的：
        //     // TODO: 通过 code 调用微信 API 获取 openid 和 nickname
        //     String openid = "mock_openid_" + System.currentTimeMillis();
        //     String nickname = "微信用户";
        // 也就是「收到绑定请求就编一个 openid 写库，然后回答绑定成功」。
        // 那不是在描述一个已发生的绑定，而是在编造一个从未发生的事实——而且这条假
        // openid 会被下面那条「该微信已绑定其他账号」的校验当成真凭据去比对。
        // 现在：没接微信开放平台就直说没接，一行数据都不写。
        if (!weChatOAuthClient.configured()) {
            return Result.error(weChatOAuthClient.unavailableReason());
        }
        if (dto == null || dto.getCode() == null || dto.getCode().isBlank()) {
            return Result.error("缺少微信授权码（code）：绑定需要先跳转微信授权页，拿到 code 后再回调本站。");
        }

        WeChatOAuthClient.Exchange exchanged = weChatOAuthClient.exchange(dto.getCode());
        if (!exchanged.ok()) {
            return Result.error("微信授权失败：" + exchanged.error());
        }

        // 检查 openid 是否已被其他账号绑定
        User existing = userMapper.findByWechatOpenid(exchanged.openid());
        if (existing != null && !java.util.Objects.equals(existing.getId(), userId)) {
            return Result.error("该微信已绑定其他账号");
        }

        int result = userMapper.bindWechat(userId, exchanged.openid(), exchanged.nickname());
        if (result > 0) {
            return Result.success("微信绑定成功");
        } else {
            return Result.error("微信绑定失败");
        }
    }

    /**
     * 解绑微信
     */
    public Result<?> unbindWechat(Long userId) {
        User user = userMapper.findById(userId);
        if (user == null) {
            return Result.error("用户不存在");
        }

        // 检查是否绑定了微信
        if (user.getWechatOpenid() == null) {
            return Result.error("未绑定微信");
        }

        // 安全检查：至少保留一种登录方式（密码或手机号）
        if (user.getPasswordHash() == null && user.getPhone() == null) {
            return Result.error("解绑后无法登录，请先设置密码或绑定手机号");
        }

        int result = userMapper.unbindWechat(userId);
        if (result > 0) {
            return Result.success("微信解绑成功");
        } else {
            return Result.error("微信解绑失败");
        }
    }

    /**
     * 更新通知设置
     */
    public Result<?> updateNotificationSettings(Long userId, NotificationSettingsDTO dto) {
        User user = userMapper.findById(userId);
        if (user == null) {
            return Result.error("用户不存在");
        }

        // 更新通知设置
        if (dto.getNotifyAssignmentDeadline() != null) {
            user.setNotifyAssignmentDeadline(dto.getNotifyAssignmentDeadline());
        }
        if (dto.getNotifyPrivateMessage() != null) {
            user.setNotifyPrivateMessage(dto.getNotifyPrivateMessage());
        }
        if (dto.getNotifyTopicReply() != null) {
            user.setNotifyTopicReply(dto.getNotifyTopicReply());
        }

        int result = userMapper.updateNotificationSettings(user);
        if (result > 0) {
            return Result.success("通知设置更新成功");
        } else {
            return Result.error("通知设置更新失败");
        }
    }

    // ==================== 头像上传 ====================

    // 允许的图片类型
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
    // 最大文件大小 2MB
    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024;
    // 上传目录
    @Value("${app.upload.dir:uploads/avatars}")
    private String uploadDir;

    /**
     * 上传头像
     */
    public Result<?> uploadAvatar(Long userId, MultipartFile file) {
        User user = userMapper.findById(userId);
        if (user == null) {
            return Result.error("用户不存在");
        }

        // 1. 校验文件
        if (file == null || file.isEmpty()) {
            return Result.error("请选择要上传的文件");
        }

        // 2. 校验文件大小
        if (file.getSize() > MAX_FILE_SIZE) {
            return Result.error("图片大小不能超过 2MB");
        }

        // 3. 校验文件类型
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            return Result.error("只支持 JPG、PNG、GIF、WEBP 格式的图片");
        }

        try {
            // 4. 创建上传目录
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // 5. 生成唯一文件名
            String originalFilename = file.getOriginalFilename();
            String extension = originalFilename != null && originalFilename.contains(".")
                    ? originalFilename.substring(originalFilename.lastIndexOf("."))
                    : ".jpg";
            String filename = userId + "_" + UUID.randomUUID().toString().substring(0, 8) + extension;

            // 6. 保存文件
            Path filePath = uploadPath.resolve(filename);
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            // 7. 更新用户头像路径
            String avatarPath = "/api/user/avatar/file/" + filename;
            user.setAvatar(avatarPath);
            userMapper.updateById(user);

            // 8. 返回结果
            Map<String, Object> data = new HashMap<>();
            data.put("avatar", avatarPath);
            return Result.success("头像上传成功", data);

        } catch (IOException e) {
            e.printStackTrace();
            return Result.error("头像上传失败，请重试");
        }
    }

    /**
     * 获取用户头像
     */
    public ResponseEntity<Resource> getAvatar(Long userId) {
        System.out.println("[Avatar] getAvatar called for userId: " + userId);

        User user = userMapper.findById(userId);
        if (user == null) {
            System.out.println("[Avatar] User not found!");
            return getDefaultAvatar();
        }

        String avatarPath = user.getAvatar();
        System.out.println("[Avatar] User avatar path: " + avatarPath);

        if (avatarPath == null || avatarPath.isEmpty()) {
            System.out.println("[Avatar] Avatar is null or empty, returning default");
            return getDefaultAvatar();
        }

        if (avatarPath.startsWith("/api/user/avatar/file/")) {
            String filename = avatarPath.substring("/api/user/avatar/file/".length());
            System.out.println("[Avatar] Loading file: " + filename);
            return loadAvatarFile(filename);
        }

        System.out.println("[Avatar] Avatar path doesn't match expected format");
        return getDefaultAvatar();
    }

    /**
     * 头像文件名白名单：字母或数字开头，随后只允许字母、数字、点、下划线、连字符。
     *
     * <p>这是安全边界而不是风格偏好：{@code GET /api/user/avatar/file/{filename}}
     * 以前直接把 filename 拼到上传目录上（{@code resolve(filename).normalize()}），
     * 没有做任何约束。在 Windows 上路径分隔符同时接受 {@code \} 和 {@code /}，
     * 于是 {@code avatar/file/..%5C..%5Capplication.properties} 会被解成上传目录的
     * 上一级再上一级，把应用配置文件原样读出去。白名单把「路径」这一类输入
     * 收窄成「一个文件名」，配合下面的 startsWith 兜底，才闭得上这个口子。
     */
    private static final java.util.regex.Pattern AVATAR_FILE_NAME =
            java.util.regex.Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    /**
     * 加载头像文件
     */
    public ResponseEntity<Resource> loadAvatarFile(String filename) {
        // 文件名不合法（含路径分隔符、上级目录、空串、超长）一律 403，不回默认头像，
        // 免得调用方把「被拒绝」误读成「这个用户没头像」。
        if (filename == null || filename.contains("..") || !AVATAR_FILE_NAME.matcher(filename).matches()) {
            System.out.println("[Avatar] Rejected illegal file name: " + filename);
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }
        try {
            // 检查 uploadDir 是否配置
            if (uploadDir == null || uploadDir.isEmpty()) {
                System.err.println("[Avatar] ERROR: uploadDir is not configured!");
                return getDefaultAvatar();
            }

            // 使用绝对路径
            Path uploadPath = Paths.get(uploadDir).toAbsolutePath();
            Path filePath = uploadPath.resolve(filename).normalize();
            // 兜底：即使白名单被放宽，解析结果也必须仍在上传目录内
            if (!filePath.startsWith(uploadPath)) {
                System.out.println("[Avatar] Rejected path outside upload dir: " + filePath);
                return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
            }
            Resource resource = new UrlResource(filePath.toUri());

            // 调试日志
            System.out.println("[Avatar] uploadDir: " + uploadDir);
            System.out.println("[Avatar] Loading file from: " + filePath.toString());
            System.out.println("[Avatar] File exists: " + resource.exists() + ", readable: " + resource.isReadable());

            if (resource.exists() && resource.isReadable()) {
                // 根据文件扩展名确定 Content-Type
                String contentType = "image/jpeg";
                if (filename.endsWith(".png")) {
                    contentType = "image/png";
                } else if (filename.endsWith(".gif")) {
                    contentType = "image/gif";
                } else if (filename.endsWith(".webp")) {
                    contentType = "image/webp";
                }

                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(contentType))
                        .header(HttpHeaders.CACHE_CONTROL, "max-age=86400") // 缓存1天
                        .body(resource);
            } else {
                return getDefaultAvatar();
            }
        } catch (MalformedURLException e) {
            return getDefaultAvatar();
        }
    }

    /**
     * 获取默认头像
     */
    public ResponseEntity<Resource> getDefaultAvatar() {
        // 返回一个 SVG 格式的默认头像
        String svg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
              <rect fill="#0751a6" width="100" height="100"/>
              <circle cx="50" cy="35" r="20" fill="white"/>
              <ellipse cx="50" cy="80" rx="30" ry="25" fill="white"/>
            </svg>
            """;

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("image/svg+xml"))
                .header(HttpHeaders.CACHE_CONTROL, "max-age=86400")
                .body(new ByteArrayResource(svg.getBytes()));
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 构建用户信息返回 Map（不含敏感信息）。
     *
     * <p>这里补上的六个字段不是装饰：设置页读 {@code phoneVerified} 决定要不要显示
     * 「已验证」徽标、读 {@code wechatOpenid} 决定要不要把按钮换成 Unbind。
     * 在补上之前，这两个字段从来不在响应里，于是那两个判断**恒为假**——
     * 一个已经绑定微信的用户，在界面永远看不到解绑按钮，也没有任何报错。
     *
     * <p>微信这部分特意只暴露布尔 {@code wechatBound}，而不是 {@code wechatOpenid}：
     * 前端只需要知道「绑没绑」，把 openid 发给浏览器没有好处。
     */
    private Map<String, Object> buildUserInfoMap(User user) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("email", user.getEmail());
        data.put("phone", user.getPhone());
        data.put("role", user.getRole());
        data.put("avatar", user.getAvatar());
        data.put("realName", user.getRealName());
        data.put("studentId", user.getStudentId());
        data.put("school", user.getSchool());
        data.put("department", user.getDepartment());
        data.put("major", user.getMajor());
        data.put("className", user.getClassName());
        data.put("grade", user.getGrade());
        data.put("enrollmentDate", user.getEnrollmentDate());
        // 设置页要用的绑定状态
        data.put("phoneVerified", user.getPhoneVerified());
        data.put("emailVerified", user.getEmailVerified());
        data.put("wechatBound", user.getWechatOpenid() != null);
        data.put("wechatNickname", user.getWechatNickname());
        // 微信能不能真的绑（凭据齐不齐），以及不能绑时的原因
        data.put("wechatAvailable", weChatOAuthClient.configured());
        data.put("wechatUnavailableReason", weChatOAuthClient.configured() ? null : weChatOAuthClient.unavailableReason());
        // AppID 可以给前端（授权链接里本来就明文带着它），AppSecret 不行
        data.put("wechatAppid", weChatOAuthClient.appid());
        return data;
    }
}
