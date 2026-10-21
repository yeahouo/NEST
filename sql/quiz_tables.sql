USE codeclass;

-- ========================================
-- 测验表
-- ========================================
CREATE TABLE IF NOT EXISTS quizzes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    course_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    description TEXT,
    time_limit INT COMMENT '限时（分钟），NULL=不限时',
    total_score INT NOT NULL DEFAULT 100,
    due_time DATETIME NOT NULL,
    allow_retake TINYINT NOT NULL DEFAULT 0 COMMENT '0否 1是',
    max_attempts INT DEFAULT 1,
    shuffle_questions TINYINT NOT NULL DEFAULT 0 COMMENT '0否 1是',
    show_result TINYINT NOT NULL DEFAULT 1 COMMENT '1提交后显示 2截止后显示',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1草稿 2已发布',
    deleted TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_course_id (course_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ========================================
-- 测验题目表
-- ========================================
CREATE TABLE IF NOT EXISTS quiz_questions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    quiz_id BIGINT NOT NULL,
    question_text TEXT NOT NULL,
    question_type TINYINT NOT NULL COMMENT '1单选 2填空 3多选 4文本 5编程',
    score INT NOT NULL DEFAULT 10,
    options JSON COMMENT '选项列表 ["A","B","C","D"]',
    correct_answer JSON COMMENT '正确答案',
    sort_order INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_quiz_id (quiz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ========================================
-- 测验提交表
-- ========================================
CREATE TABLE IF NOT EXISTS quiz_submissions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    quiz_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    attempt_no INT NOT NULL DEFAULT 1,
    is_latest TINYINT NOT NULL DEFAULT 1 COMMENT '1是 0否',
    auto_score INT COMMENT '客观题自动得分',
    manual_score INT COMMENT '主观题教师评分',
    total_score INT COMMENT '最终总分',
    is_graded TINYINT NOT NULL DEFAULT 0 COMMENT '0未完成 1已完成',
    start_time DATETIME,
    submit_time DATETIME,
    grade_time DATETIME,
    grader_id BIGINT,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_quiz_id (quiz_id),
    INDEX idx_user_id (user_id),
    INDEX idx_quiz_user_latest (quiz_id, user_id, is_latest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ========================================
-- 测验答题记录表
-- ========================================
CREATE TABLE IF NOT EXISTS quiz_answers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    submission_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    student_answer JSON COMMENT '学生答案',
    is_correct TINYINT COMMENT '0否 1是（仅客观题）',
    auto_score INT,
    manual_score INT,
    feedback TEXT,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_submission_id (submission_id),
    INDEX idx_question_id (question_id),
    UNIQUE KEY uk_submission_question (submission_id, question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
