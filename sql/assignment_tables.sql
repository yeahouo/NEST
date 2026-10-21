-- CodeClass 作业模块数据库表
-- 执行前请确保 demo2 数据库已存在

USE demo2;

-- 如果表已存在，先删除（开发环境）
DROP TABLE IF EXISTS submissions;
DROP TABLE IF EXISTS assignments;

-- ========================================
-- 作业表
-- ========================================
CREATE TABLE assignments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '作业ID',
    course_id BIGINT NOT NULL COMMENT '课程ID',

    -- 基本信息
    title VARCHAR(100) NOT NULL COMMENT '作业标题',
    description TEXT COMMENT '作业描述/要求',
    type TINYINT NOT NULL DEFAULT 1 COMMENT '类型：1普通作业 2代码作业（预留）',
    total_score INT NOT NULL DEFAULT 100 COMMENT '总分',

    -- 时间
    start_time DATETIME COMMENT '开始时间（NULL=立即开始）',
    end_time DATETIME NOT NULL COMMENT '截止时间',

    -- 提交规则
    allow_late TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许迟交：0否 1是',
    late_penalty INT DEFAULT 10 COMMENT '迟交扣分百分比（如10表示扣10%）',
    allow_resubmit TINYINT NOT NULL DEFAULT 1 COMMENT '是否允许重交：0否 1是',

    -- 状态
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1草稿 2已发布',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',

    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_course_id (course_id),
    INDEX idx_status (status),
    INDEX idx_end_time (end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作业表';

-- ========================================
-- 作业提交表
-- ========================================
CREATE TABLE submissions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '提交ID',
    assignment_id BIGINT NOT NULL COMMENT '作业ID',
    user_id BIGINT NOT NULL COMMENT '学生ID',

    -- 提交信息
    submit_no INT NOT NULL DEFAULT 1 COMMENT '第几次提交',
    is_latest TINYINT NOT NULL DEFAULT 1 COMMENT '是否最新提交：1是 0否',
    is_late TINYINT NOT NULL DEFAULT 0 COMMENT '是否迟交：0准时 1迟交',

    -- 普通作业字段
    content TEXT COMMENT '提交内容',
    file_url VARCHAR(500) COMMENT '附件URL',

    -- 代码作业字段（预留）
    code_language VARCHAR(20) COMMENT '代码语言',
    code_content MEDIUMTEXT COMMENT '代码内容',

    -- 批改字段
    score INT COMMENT '得分（最终分，已扣迟交）',
    feedback TEXT COMMENT '教师反馈',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1已提交 2已批改',

    -- 时间
    submit_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    grade_time DATETIME COMMENT '批改时间',
    grader_id BIGINT COMMENT '批改人ID',

    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_assignment_id (assignment_id),
    INDEX idx_user_id (user_id),
    INDEX idx_latest (assignment_id, user_id, is_latest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作业提交表';

-- 验证表创建
SHOW TABLES LIKE 'assignment%';
SHOW TABLES LIKE 'submission%';
