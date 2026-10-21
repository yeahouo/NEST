-- ============================================================
-- CodeClass 完整数据库初始化脚本 (合并版)
-- 版本: MySQL 8.x / 9.x
-- 说明: 此脚本会删除并重建所有表，请谨慎在生产环境执行
-- ============================================================

-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS codeclass
DEFAULT CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;

USE codeclass;

SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 1. 用户表 (users)
-- ============================================================
DROP TABLE IF EXISTS users;
CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
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

    -- 安全验证
    phone_verified TINYINT NOT NULL DEFAULT 0 COMMENT '手机号已验证: 0否 1是',
    email_verified TINYINT NOT NULL DEFAULT 0 COMMENT '邮箱已验证: 0否 1是',
    password_updated_at DATETIME COMMENT '密码最后修改时间',

    -- 个人资料
    real_name VARCHAR(50) COMMENT '真实姓名',
    student_id VARCHAR(20) COMMENT '学号',
    school VARCHAR(100) COMMENT '所属学校',
    department VARCHAR(100) COMMENT '所属院系',
    major VARCHAR(100) COMMENT '所属专业',
    class_name VARCHAR(50) COMMENT '班级名称',
    grade VARCHAR(20) COMMENT '年级',
    enrollment_date DATE COMMENT '入学时间',

    -- 账号绑定
    wechat_openid VARCHAR(100) COMMENT '微信OpenID',
    wechat_nickname VARCHAR(100) COMMENT '微信昵称',
    wechat_bound_at DATETIME COMMENT '微信绑定时间',
    email_bound_at DATETIME COMMENT '邮箱绑定时间',

    -- 通知设置
    notify_assignment_deadline TINYINT NOT NULL DEFAULT 1 COMMENT '作业到期提醒: 0关闭 1开启',
    notify_private_message TINYINT NOT NULL DEFAULT 1 COMMENT '私信提醒: 0关闭 1开启',
    notify_topic_reply TINYINT NOT NULL DEFAULT 1 COMMENT '话题回复提醒: 0关闭 1开启',

    -- 时间戳
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 索引
    UNIQUE KEY uk_username (username),
    INDEX idx_email (email),
    INDEX idx_phone (phone),
    INDEX idx_role (role),
    INDEX idx_student_id (student_id),
    INDEX idx_status (status),
    INDEX idx_wechat_openid (wechat_openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ============================================================
-- 2. 课程表 (courses)
-- ============================================================
DROP TABLE IF EXISTS courses;
CREATE TABLE courses (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '课程ID',
    course_code VARCHAR(20) NOT NULL COMMENT '课程码(用于学生加入)',
    name VARCHAR(100) NOT NULL COMMENT '课程名称',
    description TEXT COMMENT '课程描述',
    cover_index VARCHAR(20) DEFAULT '1' COMMENT '封面索引',
    teacher_id BIGINT NOT NULL COMMENT '教师ID',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1正常 0停用',
    max_students INT NOT NULL DEFAULT 100 COMMENT '最大学生数',
    student_count INT NOT NULL DEFAULT 0 COMMENT '当前学生数',
    credit_hours INT NULL COMMENT '学分',
    semester VARCHAR(32) NULL COMMENT '学期，如 Fall 2024',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0正常 1删除',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_course_code (course_code),
    INDEX idx_teacher_id (teacher_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='课程表';

-- ============================================================
-- 3. 课程成员表 (course_members)
-- ============================================================
DROP TABLE IF EXISTS course_members;
CREATE TABLE course_members (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '成员记录ID',
    course_id BIGINT NOT NULL COMMENT '课程ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role VARCHAR(20) NOT NULL DEFAULT 'student' COMMENT '成员角色: student/assistant',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1正常 0退出',
    join_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_course_user (course_id, user_id),
    INDEX idx_course_id (course_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='课程成员表';

-- ============================================================
-- 4. 作业表 (assignments)
-- ============================================================
DROP TABLE IF EXISTS assignments;
CREATE TABLE assignments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '作业ID',
    course_id BIGINT NOT NULL COMMENT '课程ID',
    title VARCHAR(100) NOT NULL COMMENT '作业标题',
    description TEXT COMMENT '作业描述',
    type TINYINT NOT NULL DEFAULT 1 COMMENT '类型: 1普通作业 2代码作业',
    total_score INT NOT NULL DEFAULT 100 COMMENT '总分',
    start_time DATETIME COMMENT '开始时间',
    end_time DATETIME NOT NULL COMMENT '截止时间',
    allow_late TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许迟交: 0否 1是',
    late_penalty INT DEFAULT 10 COMMENT '迟交扣分比例(百分比)',
    allow_resubmit TINYINT NOT NULL DEFAULT 1 COMMENT '是否允许重复提交: 0否 1是',
    attachments TEXT COMMENT '教师上传的附件URL(逗号分隔)',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1草稿 2已发布',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0正常 1删除',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_course_id (course_id),
    INDEX idx_status (status),
    INDEX idx_end_time (end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='作业表';

-- ============================================================
-- 5. 作业提交表 (submissions)
-- ============================================================
DROP TABLE IF EXISTS submissions;
CREATE TABLE submissions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '提交ID',
    assignment_id BIGINT NOT NULL COMMENT '作业ID',
    user_id BIGINT NOT NULL COMMENT '学生ID',
    submit_no INT NOT NULL DEFAULT 1 COMMENT '第几次提交',
    is_latest TINYINT NOT NULL DEFAULT 1 COMMENT '是否最新提交: 0否 1是',
    is_late TINYINT NOT NULL DEFAULT 0 COMMENT '是否迟交: 0准时 1迟交',
    content TEXT COMMENT '提交内容(文本)',
    file_url VARCHAR(500) COMMENT '附件URL',
    code_language VARCHAR(20) COMMENT '代码语言',
    code_content MEDIUMTEXT COMMENT '代码内容',
    score INT COMMENT '得分',
    feedback TEXT COMMENT '教师反馈',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1已提交 2已批改',
    submit_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    grade_time DATETIME COMMENT '批改时间',
    grader_id BIGINT COMMENT '批改人ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_assignment_id (assignment_id),
    INDEX idx_user_id (user_id),
    INDEX idx_assignment_user_latest (assignment_id, user_id, is_latest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='作业提交表';

-- ============================================================
-- 6. 签到会话表 (attendance_sessions)
-- ============================================================
DROP TABLE IF EXISTS attendance_sessions;
CREATE TABLE attendance_sessions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '签到会话ID',
    course_id BIGINT NOT NULL COMMENT '课程ID',
    session_name VARCHAR(100) DEFAULT '课程签到' COMMENT '签到名称',
    code VARCHAR(6) NOT NULL COMMENT '签到码',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1进行中 0已结束',
    created_by BIGINT NOT NULL COMMENT '创建者ID(教师)',
    start_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',
    end_time DATETIME COMMENT '结束时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_course_id (course_id),
    INDEX idx_code (code),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='签到会话表';

-- ============================================================
-- 7. 签到记录表 (attendance_records)
-- ============================================================
DROP TABLE IF EXISTS attendance_records;
CREATE TABLE attendance_records (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '签到记录ID',
    session_id BIGINT NOT NULL COMMENT '签到会话ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    status VARCHAR(20) NOT NULL DEFAULT 'present' COMMENT '状态: present出勤/late迟到/early_leave早退/absent旷课',
    check_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '签到时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_session_user (session_id, user_id),
    INDEX idx_session_id (session_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='签到记录表';

-- ============================================================
-- 8. 课程资料表 (materials)
-- ============================================================
DROP TABLE IF EXISTS materials;
CREATE TABLE materials (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '资料ID',
    course_id BIGINT NOT NULL COMMENT '课程ID',
    folder_id BIGINT COMMENT '父文件夹ID(NULL表示根目录)',
    name VARCHAR(100) NOT NULL COMMENT '文件/文件夹名称',
    type ENUM('file', 'folder') NOT NULL DEFAULT 'file' COMMENT '类型',
    file_path VARCHAR(500) COMMENT '存储路径',
    file_size BIGINT COMMENT '文件大小(字节)',
    mime_type VARCHAR(100) COMMENT 'MIME类型',
    uploader_id BIGINT COMMENT '上传者ID',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0正常 1删除',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_course_id (course_id),
    INDEX idx_folder_id (folder_id),
    INDEX idx_course_folder (course_id, folder_id),
    INDEX idx_uploader (uploader_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='课程资料表';

-- ============================================================
-- 9. 公告表 (announcements)
-- ============================================================
DROP TABLE IF EXISTS announcements;
CREATE TABLE announcements (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '公告ID',
    course_id BIGINT NOT NULL COMMENT '课程ID',
    author_id BIGINT NOT NULL COMMENT '作者ID',
    title VARCHAR(100) NOT NULL COMMENT '标题',
    content TEXT COMMENT '内容',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0正常 1删除',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_course_id (course_id),
    INDEX idx_author_id (author_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公告表';

-- ============================================================
-- 10. 教师邀请码表 (teacher_invitation_codes)
-- ============================================================
DROP TABLE IF EXISTS teacher_invitation_codes;
CREATE TABLE teacher_invitation_codes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '邀请码ID',
    code VARCHAR(20) NOT NULL COMMENT '邀请码',
    description VARCHAR(200) COMMENT '描述/备注',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1有效 0禁用',
    usage_count INT NOT NULL DEFAULT 0 COMMENT '已使用次数',
    max_usage INT DEFAULT NULL COMMENT '最大使用次数(NULL无限制)',
    expires_at DATETIME COMMENT '过期时间(NULL永不过期)',
    created_by BIGINT NOT NULL COMMENT '创建者ID',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0正常 1删除',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_code (code),
    INDEX idx_status (status),
    INDEX idx_created_by (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='教师邀请码表';

-- ============================================================
-- 11. 测验表 (quizzes)
-- ============================================================
DROP TABLE IF EXISTS quizzes;
CREATE TABLE quizzes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '测验ID',
    course_id BIGINT NOT NULL COMMENT '课程ID',
    title VARCHAR(100) NOT NULL COMMENT '测验标题',
    description TEXT COMMENT '测验描述',
    time_limit INT COMMENT '限时(分钟)，NULL=不限时',
    total_score INT NOT NULL DEFAULT 100 COMMENT '总分',
    due_time DATETIME NOT NULL COMMENT '截止时间',
    allow_retake TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许重考: 0否 1是',
    max_attempts INT DEFAULT 1 COMMENT '最大尝试次数',
    shuffle_questions TINYINT NOT NULL DEFAULT 0 COMMENT '是否随机题目顺序: 0否 1是',
    show_result TINYINT NOT NULL DEFAULT 1 COMMENT '显示结果: 1提交后显示 2截止后显示',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1草稿 2已发布',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_course_id (course_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测验表';

-- ============================================================
-- 12. 测验题目表 (quiz_questions)
-- ============================================================
DROP TABLE IF EXISTS quiz_questions;
CREATE TABLE quiz_questions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '题目ID',
    quiz_id BIGINT NOT NULL COMMENT '测验ID',
    question_text TEXT NOT NULL COMMENT '题目内容',
    question_type TINYINT NOT NULL COMMENT '类型: 1单选 2填空 3多选 4文本 5编程',
    score INT NOT NULL DEFAULT 10 COMMENT '分值',
    options JSON COMMENT '选项列表 ["A","B","C","D"]',
    correct_answer JSON COMMENT '正确答案',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序序号',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_quiz_id (quiz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测验题目表';

-- ============================================================
-- 13. 测验提交表 (quiz_submissions)
-- ============================================================
DROP TABLE IF EXISTS quiz_submissions;
CREATE TABLE quiz_submissions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '提交ID',
    quiz_id BIGINT NOT NULL COMMENT '测验ID',
    user_id BIGINT NOT NULL COMMENT '学生ID',
    attempt_no INT NOT NULL DEFAULT 1 COMMENT '第几次尝试',
    is_latest TINYINT NOT NULL DEFAULT 1 COMMENT '是否最新: 1是 0否',
    auto_score INT COMMENT '客观题自动得分',
    manual_score INT COMMENT '主观题教师评分',
    total_score INT COMMENT '最终总分',
    is_graded TINYINT NOT NULL DEFAULT 0 COMMENT '是否完成批改: 0否 1是',
    start_time DATETIME COMMENT '开始时间',
    submit_time DATETIME COMMENT '提交时间',
    grade_time DATETIME COMMENT '批改时间',
    grader_id BIGINT COMMENT '批改人ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_quiz_id (quiz_id),
    INDEX idx_user_id (user_id),
    INDEX idx_quiz_user_latest (quiz_id, user_id, is_latest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测验提交表';

-- ============================================================
-- 14. 测验答题记录表 (quiz_answers)
-- ============================================================
DROP TABLE IF EXISTS quiz_answers;
CREATE TABLE quiz_answers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '答题记录ID',
    submission_id BIGINT NOT NULL COMMENT '提交ID',
    question_id BIGINT NOT NULL COMMENT '题目ID',
    student_answer JSON COMMENT '学生答案',
    is_correct TINYINT COMMENT '是否正确: 0否 1是(仅客观题)',
    auto_score INT COMMENT '自动评分',
    manual_score INT COMMENT '教师评分',
    feedback TEXT COMMENT '教师反馈',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_submission_id (submission_id),
    INDEX idx_question_id (question_id),
    UNIQUE KEY uk_submission_question (submission_id, question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测验答题记录表';

-- ============================================================
-- 恢复外键检查
-- ============================================================
SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 初始数据
-- ============================================================
INSERT INTO teacher_invitation_codes (code, description, status, created_by)
VALUES ('TEACHER2024', '默认教师邀请码', 1, 1)
ON DUPLICATE KEY UPDATE description = '默认教师邀请码';

SELECT '数据库初始化完成' AS message;
SHOW TABLES;
