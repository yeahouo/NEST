-- ============================================================
-- CodeClass 完整数据库初始化脚本
-- 版本: MySQL 8.x / 9.x
-- 生成时间: 2024
-- 说明: 此脚本会删除并重建所有表，请谨慎在生产环境执行
-- ============================================================

-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS codeclass
DEFAULT CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;

USE codeclass;

-- ============================================================
-- 设置外键检查关闭（便于按任意顺序删除表）
-- ============================================================
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 1. 用户表 (users)
-- 用途: 存储所有用户信息，包括学生、教师、管理员
-- ============================================================
DROP TABLE IF EXISTS users;
CREATE TABLE users (
    -- 主键
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',

    -- 基础信息
    username VARCHAR(50) NOT NULL COMMENT '用户名',
    password_hash VARCHAR(255) NOT NULL COMMENT '密码哈希(BCrypt)',
    nickname VARCHAR(50) COMMENT '昵称',
    email VARCHAR(100) COMMENT '邮箱',
    phone VARCHAR(20) COMMENT '手机号',
    role VARCHAR(20) NOT NULL DEFAULT 'student' COMMENT '角色: student/teacher/admin',
    avatar VARCHAR(255) COMMENT '头像URL',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1正常 0禁用',
    last_login_time DATETIME COMMENT '最后登录时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0正常 1删除',

    -- 安全验证字段
    phone_verified TINYINT NOT NULL DEFAULT 0 COMMENT '手机号已验证: 0否 1是',
    email_verified TINYINT NOT NULL DEFAULT 0 COMMENT '邮箱已验证: 0否 1是',
    password_updated_at DATETIME COMMENT '密码最后修改时间',

    -- 个人资料字段
    real_name VARCHAR(50) COMMENT '真实姓名',
    student_id VARCHAR(20) COMMENT '学号',
    school VARCHAR(100) COMMENT '所属学校',
    department VARCHAR(100) COMMENT '所属院系',
    major VARCHAR(100) COMMENT '所属专业',
    class_name VARCHAR(50) COMMENT '班级名称',
    grade VARCHAR(20) COMMENT '年级',
    enrollment_date DATE COMMENT '入学时间',

    -- 账号绑定字段
    wechat_openid VARCHAR(100) COMMENT '微信OpenID',
    wechat_nickname VARCHAR(100) COMMENT '微信昵称',
    wechat_bound_at DATETIME COMMENT '微信绑定时间',
    email_bound_at DATETIME COMMENT '邮箱绑定时间',

    -- 通知设置字段
    notify_assignment_deadline TINYINT NOT NULL DEFAULT 1 COMMENT '作业到期提醒: 0关闭 1开启',
    notify_private_message TINYINT NOT NULL DEFAULT 1 COMMENT '私信提醒: 0关闭 1开启',
    notify_topic_reply TINYINT NOT NULL DEFAULT 1 COMMENT '话题回复提醒: 0关闭 1开启',

    -- 时间戳
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 索引和约束
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_email (email),
    UNIQUE KEY uk_phone (phone),
    UNIQUE KEY uk_student_id (student_id),
    UNIQUE KEY uk_wechat_openid (wechat_openid),
    INDEX idx_role (role),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ============================================================
-- 2. 课程表 (courses)
-- 用途: 存储课程基本信息
-- ============================================================
DROP TABLE IF EXISTS courses;
CREATE TABLE courses (
    -- 主键
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '课程ID',

    -- 基本信息
    course_code VARCHAR(10) NOT NULL COMMENT '课程码(6位，用于学生加入)',
    name VARCHAR(100) NOT NULL COMMENT '课程名称',
    description TEXT COMMENT '课程描述',
    cover_index VARCHAR(20) DEFAULT '1' COMMENT '封面索引(对应前端预设渐变色)',
    teacher_id BIGINT NOT NULL COMMENT '教师ID',

    -- 状态和统计
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1正常 0停用',
    max_students INT NOT NULL DEFAULT 100 COMMENT '最大学生数',
    student_count INT NOT NULL DEFAULT 0 COMMENT '当前学生数(冗余字段)',

    -- 课程属性（Course.java / CourseMapper.xml / 教师端建课表单都在用；
    -- 早期 DDL 漏了这两列，见 sql/nest_course_v2.sql 的说明）
    credit_hours INT NULL COMMENT '学分',
    semester VARCHAR(32) NULL COMMENT '学期，如 Fall 2024',

    -- 逻辑删除
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0正常 1删除',

    -- 时间戳
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 索引和约束
    UNIQUE KEY uk_course_code (course_code),
    INDEX idx_teacher_id (teacher_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='课程表';

-- ============================================================
-- 3. 课程成员表 (course_members)
-- 用途: 存储学生与课程的关联关系
-- ============================================================
DROP TABLE IF EXISTS course_members;
CREATE TABLE course_members (
    -- 主键
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '成员记录ID',

    -- 关联信息
    course_id BIGINT NOT NULL COMMENT '课程ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',

    -- 成员信息
    role VARCHAR(20) NOT NULL DEFAULT 'student' COMMENT '成员角色: student/assistant',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1正常 0退出',

    -- 时间戳
    join_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    -- 索引和约束
    UNIQUE KEY uk_course_user (course_id, user_id),
    INDEX idx_course_id (course_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='课程成员表';

-- ============================================================
-- 4. 作业表 (assignments)
-- 用途: 存储作业信息
-- ============================================================
DROP TABLE IF EXISTS assignments;
CREATE TABLE assignments (
    -- 主键
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '作业ID',

    -- 关联信息
    course_id BIGINT NOT NULL COMMENT '课程ID',

    -- 基本信息
    title VARCHAR(100) NOT NULL COMMENT '作业标题',
    description TEXT COMMENT '作业描述',
    type TINYINT NOT NULL DEFAULT 1 COMMENT '类型: 1普通作业 2代码作业',
    total_score INT NOT NULL DEFAULT 100 COMMENT '总分',

    -- 时间控制
    start_time DATETIME COMMENT '开始时间',
    end_time DATETIME NOT NULL COMMENT '截止时间',

    -- 提交设置
    allow_late TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许迟交: 0否 1是',
    late_penalty INT DEFAULT 10 COMMENT '迟交扣分比例(百分比)',
    allow_resubmit TINYINT NOT NULL DEFAULT 1 COMMENT '是否允许重复提交: 0否 1是',

    -- 状态
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1草稿 2已发布 3已结束',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0正常 1删除',

    -- 时间戳
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 索引
    INDEX idx_course_id (course_id),
    INDEX idx_status (status),
    INDEX idx_end_time (end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='作业表';

-- ============================================================
-- 5. 作业提交表 (submissions)
-- 用途: 存储学生作业提交记录
-- ============================================================
DROP TABLE IF EXISTS submissions;
CREATE TABLE submissions (
    -- 主键
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '提交ID',

    -- 关联信息
    assignment_id BIGINT NOT NULL COMMENT '作业ID',
    user_id BIGINT NOT NULL COMMENT '学生ID',

    -- 提交信息
    submit_no INT NOT NULL DEFAULT 1 COMMENT '第几次提交',
    is_latest TINYINT NOT NULL DEFAULT 1 COMMENT '是否最新提交: 0否 1是',

    -- 普通作业内容
    content TEXT COMMENT '提交内容(文本)',
    file_url VARCHAR(500) COMMENT '附件URL',

    -- 代码作业内容
    code_language VARCHAR(20) COMMENT '代码语言',
    code_content MEDIUMTEXT COMMENT '代码内容',

    -- 批改信息
    score INT COMMENT '得分',
    feedback TEXT COMMENT '教师反馈',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1已提交 2已批改 3已退回',

    -- 时间戳
    submit_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    grade_time DATETIME COMMENT '批改时间',
    grader_id BIGINT COMMENT '批改人ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 索引
    INDEX idx_assignment_id (assignment_id),
    INDEX idx_user_id (user_id),
    INDEX idx_assignment_user (assignment_id, user_id),
    INDEX idx_assignment_user_latest (assignment_id, user_id, is_latest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='作业提交表';

-- ============================================================
-- 6. 签到会话表 (attendance_sessions)
-- 用途: 存储每次签到活动
-- ============================================================
DROP TABLE IF EXISTS attendance_sessions;
CREATE TABLE attendance_sessions (
    -- 主键
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '签到会话ID',

    -- 关联信息
    course_id BIGINT NOT NULL COMMENT '课程ID',

    -- 基本信息
    session_name VARCHAR(100) DEFAULT '课程签到' COMMENT '签到名称',
    code VARCHAR(6) NOT NULL COMMENT '签到码',

    -- 状态
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1进行中 0已结束',

    -- 创建者
    created_by BIGINT NOT NULL COMMENT '创建者ID(教师)',

    -- 时间戳
    start_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',
    end_time DATETIME COMMENT '结束时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    -- 索引
    INDEX idx_course_id (course_id),
    INDEX idx_code (code),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='签到会话表';

-- ============================================================
-- 7. 签到记录表 (attendance_records)
-- 用途: 存储学生签到详情
-- ============================================================
DROP TABLE IF EXISTS attendance_records;
CREATE TABLE attendance_records (
    -- 主键
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '签到记录ID',

    -- 关联信息
    session_id BIGINT NOT NULL COMMENT '签到会话ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',

    -- 签到信息
    status VARCHAR(20) NOT NULL DEFAULT 'present' COMMENT '状态: present出勤/late迟到/early_leave早退/absent旷课',
    check_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '签到时间',

    -- 时间戳
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    -- 索引和约束
    UNIQUE KEY uk_session_user (session_id, user_id),
    INDEX idx_session_id (session_id),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='签到记录表';

-- ============================================================
-- 8. 课程资料表 (materials)
-- 用途: 存储课程文件和文件夹
-- ============================================================
DROP TABLE IF EXISTS materials;
CREATE TABLE materials (
    -- 主键
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '资料ID',

    -- 关联信息
    course_id BIGINT NOT NULL COMMENT '课程ID',
    folder_id BIGINT COMMENT '父文件夹ID(NULL表示根目录)',

    -- 基本信息
    name VARCHAR(100) NOT NULL COMMENT '文件/文件夹名称',
    type ENUM('file', 'folder') NOT NULL DEFAULT 'file' COMMENT '类型',

    -- 文件信息(仅type=file时有效)
    file_path VARCHAR(500) COMMENT '存储路径',
    file_size BIGINT COMMENT '文件大小(字节)',
    mime_type VARCHAR(100) COMMENT 'MIME类型',
    uploader_id BIGINT COMMENT '上传者ID',

    -- 逻辑删除
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0正常 1删除',

    -- 时间戳
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 索引
    INDEX idx_course_id (course_id),
    INDEX idx_folder_id (folder_id),
    INDEX idx_course_folder (course_id, folder_id),
    INDEX idx_uploader (uploader_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='课程资料表';

-- ============================================================
-- 9. 公告表 (announcements)
-- 用途: 存储课程公告
-- ============================================================
DROP TABLE IF EXISTS announcements;
CREATE TABLE announcements (
    -- 主键
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '公告ID',

    -- 关联信息
    course_id BIGINT NOT NULL COMMENT '课程ID',
    author_id BIGINT NOT NULL COMMENT '作者ID',

    -- 内容
    title VARCHAR(100) NOT NULL COMMENT '标题',
    content TEXT COMMENT '内容',

    -- 逻辑删除
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0正常 1删除',

    -- 时间戳
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 索引
    INDEX idx_course_id (course_id),
    INDEX idx_author_id (author_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公告表';

-- ============================================================
-- 10. 教师邀请码表 (teacher_invitation_codes)
-- 用途: 管理教师注册邀请码
-- ============================================================
DROP TABLE IF EXISTS teacher_invitation_codes;
CREATE TABLE teacher_invitation_codes (
    -- 主键
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '邀请码ID',

    -- 邀请码信息
    code VARCHAR(20) NOT NULL COMMENT '邀请码',
    description VARCHAR(200) COMMENT '描述/备注',

    -- 状态控制
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1有效 0禁用',

    -- 使用限制
    usage_count INT NOT NULL DEFAULT 0 COMMENT '已使用次数',
    max_usage INT DEFAULT NULL COMMENT '最大使用次数(NULL无限制)',

    -- 有效期
    expires_at DATETIME COMMENT '过期时间(NULL永不过期)',

    -- 创建者
    created_by BIGINT NOT NULL COMMENT '创建者ID',

    -- 逻辑删除
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0正常 1删除',

    -- 时间戳
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 索引
    UNIQUE KEY uk_code (code),
    INDEX idx_status (status),
    INDEX idx_created_by (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='教师邀请码表';

-- ============================================================
-- 恢复外键检查
-- ============================================================
SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 完成
-- ============================================================
SELECT '数据库初始化完成' AS message;
SHOW TABLES;
