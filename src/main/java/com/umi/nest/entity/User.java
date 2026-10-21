package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户实体类
 */
@Data
public class User {

    private Long id;

    private String username;

    @JsonIgnore  // 序列化时忽略密码字段
    private String passwordHash;

    private String nickname;

    private String email;

    private String phone;

    private String role;

    private String avatar;

    private Integer status;

    private LocalDateTime lastLoginTime;

    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ==================== 安全验证字段 ====================

    /**
     * 手机号是否验证：0未验证 1已验证
     */
    private Integer phoneVerified;

    /**
     * 邮箱是否验证：0未验证 1已验证
     */
    private Integer emailVerified;

    /**
     * 密码最后修改时间
     */
    private LocalDateTime passwordUpdatedAt;

    /**
     * 连续登录失败次数（等保：登录失败锁定；登录成功后清零）
     */
    private Integer failedLoginCount;

    /**
     * 锁定截止时间（NULL 表示未锁定；到点后自动放行）
     */
    private LocalDateTime lockedUntil;

    // ==================== 个人资料字段 ====================

    /**
     * 真实姓名
     */
    private String realName;

    /**
     * 学号（学生专用）
     */
    private String studentId;

    /**
     * 所属学校
     */
    private String school;

    /**
     * 所属院系
     */
    private String department;

    /**
     * 所属专业
     */
    private String major;

    /**
     * 班级名称
     */
    private String className;

    /**
     * 年级（如：2023级）
     */
    private String grade;

    /**
     * 入学时间
     */
    private LocalDate enrollmentDate;

    // ==================== 账号绑定字段 ====================

    /**
     * 微信OpenID
     */
    private String wechatOpenid;

    /**
     * 微信昵称
     */
    private String wechatNickname;

    /**
     * 微信绑定时间
     */
    private LocalDateTime wechatBoundAt;

    /**
     * 邮箱绑定时间
     */
    private LocalDateTime emailBoundAt;

    // ==================== 通知设置字段 ====================

    /**
     * 作业到期提醒：0关闭 1开启
     */
    private Integer notifyAssignmentDeadline;

    /**
     * 私信提醒：0关闭 1开启
     */
    private Integer notifyPrivateMessage;

    /**
     * 话题回复提醒：0关闭 1开启
     */
    private Integer notifyTopicReply;
}
