-- CodeClass 课程模块数据库表
-- 执行前请确保 demo2 数据库已存在

USE demo2;

-- ========================================
-- 课程表
-- ========================================
CREATE TABLE IF NOT EXISTS courses (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    course_code VARCHAR(20) NOT NULL UNIQUE COMMENT '课程码',
    name VARCHAR(100) NOT NULL COMMENT '课程名称',
    description TEXT COMMENT '课程描述',
    cover_index VARCHAR(20) DEFAULT '1' COMMENT '封面索引',
    teacher_id BIGINT NOT NULL COMMENT '教师ID',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1正常 0停用',
    max_students INT NOT NULL DEFAULT 100 COMMENT '最大学生数',
    credit_hours INT NULL COMMENT '学分',
    semester VARCHAR(32) NULL COMMENT '学期，如 Fall 2024',
    student_count INT NOT NULL DEFAULT 0 COMMENT '当前学生数',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_teacher_id (teacher_id),
    INDEX idx_course_code (course_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程表';

-- ========================================
-- 课程成员表
-- ========================================
CREATE TABLE IF NOT EXISTS course_members (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    course_id BIGINT NOT NULL COMMENT '课程ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role VARCHAR(20) NOT NULL DEFAULT 'student' COMMENT '成员角色：student/assistant',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1正常 0退出',
    join_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_course_user (course_id, user_id),
    INDEX idx_course_id (course_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程成员表';

-- 验证表创建
SHOW TABLES LIKE 'course%';
