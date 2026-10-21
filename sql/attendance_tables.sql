-- 签到功能数据库表
-- 请在 MySQL 中执行此脚本

-- ========================================
-- 签到会话表
-- ========================================
CREATE TABLE IF NOT EXISTS attendance_sessions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    course_id BIGINT NOT NULL COMMENT '课程ID',
    session_name VARCHAR(100) COMMENT '签到名称，如"周一课程"、"实验课"等',
    code VARCHAR(6) NOT NULL COMMENT '签到码',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1进行中 0已结束',
    created_by BIGINT NOT NULL COMMENT '创建者ID（教师）',
    start_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',
    end_time DATETIME COMMENT '结束时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_course_id (course_id),
    INDEX idx_code (code),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='签到会话表';

-- 如果表已存在，添加 session_name 字段
-- ALTER TABLE attendance_sessions ADD COLUMN session_name VARCHAR(100) COMMENT '签到名称' AFTER course_id;

-- ========================================
-- 签到记录表
-- ========================================
CREATE TABLE IF NOT EXISTS attendance_records (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    session_id BIGINT NOT NULL COMMENT '签到会话ID',
    user_id BIGINT NOT NULL COMMENT '用户ID（学生）',
    status VARCHAR(20) NOT NULL DEFAULT 'present' COMMENT '状态：present签到 late迟到 absent缺席',
    check_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '签到时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_session_user (session_id, user_id),
    INDEX idx_session_id (session_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='签到记录表';
