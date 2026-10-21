-- CodeClass 用户表
-- 包含设置页面所有功能所需的字段

USE codeclass;

-- ========================================
-- 用户表
-- ========================================
CREATE TABLE IF NOT EXISTS users (
    -- 基础字段
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password_hash VARCHAR(255) NOT NULL COMMENT '密码哈希',
    nickname VARCHAR(50) COMMENT '昵称',
    email VARCHAR(100) COMMENT '邮箱',
    phone VARCHAR(20) COMMENT '手机号（含区号）',
    role VARCHAR(20) NOT NULL DEFAULT 'student' COMMENT '角色：student/teacher/admin',
    avatar VARCHAR(255) COMMENT '头像URL',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1正常 0禁用',
    last_login_time DATETIME COMMENT '最后登录时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 安全验证字段
    phone_verified TINYINT NOT NULL DEFAULT 0 COMMENT '手机号已验证：0否 1是',
    email_verified TINYINT NOT NULL DEFAULT 0 COMMENT '邮箱已验证：0否 1是',
    password_updated_at DATETIME COMMENT '密码最后修改时间',

    -- 个人资料字段 (Personal Profile)
    real_name VARCHAR(50) COMMENT '真实姓名',
    student_id VARCHAR(20) COMMENT '学号（学生专用）',
    school VARCHAR(100) COMMENT '所属学校',
    department VARCHAR(100) COMMENT '所属院系',
    major VARCHAR(100) COMMENT '所属专业',
    class_name VARCHAR(50) COMMENT '班级名称',
    grade VARCHAR(20) COMMENT '年级（如：2023级）',
    enrollment_date DATE COMMENT '入学时间',

    -- 账号绑定字段 (Account Binding)
    wechat_openid VARCHAR(100) COMMENT '微信OpenID',
    wechat_nickname VARCHAR(100) COMMENT '微信昵称',
    wechat_bound_at DATETIME COMMENT '微信绑定时间',
    email_bound_at DATETIME COMMENT '邮箱绑定时间',

    -- 通知设置字段 (Notification Settings - 预留)
    notify_assignment_deadline TINYINT NOT NULL DEFAULT 1 COMMENT '作业到期提醒：0关闭 1开启',
    notify_private_message TINYINT NOT NULL DEFAULT 1 COMMENT '私信提醒：0关闭 1开启',
    notify_topic_reply TINYINT NOT NULL DEFAULT 1 COMMENT '话题回复提醒：0关闭 1开启',

    -- 索引
    INDEX idx_email (email),
    INDEX idx_phone (phone),
    INDEX idx_role (role),
    INDEX idx_student_id (student_id),
    INDEX idx_wechat_openid (wechat_openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ========================================
-- 设置页面功能与字段对照表
-- ========================================
--
-- 【Basic Account Info】
--   Username     -> username (只读显示)
--   Role         -> role (只读显示)
--   Phone        -> phone (可修改) + phone_verified (验证状态)
--   Password     -> password_hash (可修改) + password_updated_at (修改时间)
--
-- 【Personal Profile】
--   Real Name        -> real_name
--   Student ID       -> student_id
--   School           -> school
--   Department       -> department
--   Major            -> major
--   Class            -> class_name
--   Grade            -> grade
--   Enrollment Date  -> enrollment_date
--
-- 【Account Binding】
--   WeChat       -> wechat_openid + wechat_nickname + wechat_bound_at
--   Email        -> email + email_verified + email_bound_at
--
-- 【Avatar】
--   头像         -> avatar
--
-- 【Notification Settings - 预留】
--   作业到期提醒  -> notify_assignment_deadline
--   私信提醒      -> notify_private_message
--   话题回复提醒  -> notify_topic_reply
--
-- ========================================

-- 验证表结构
DESCRIBE users;
