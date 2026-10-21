-- 作业表增量更新（如果表已存在）
-- 运行此脚本添加缺失的字段

USE demo2;

-- 1. 检查并添加 assignments 表缺失的字段
-- 添加 start_time 字段（如果不存在）
SET @exist_start := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'demo2' AND TABLE_NAME = 'assignments' AND COLUMN_NAME = 'start_time');
SET @sql_start := IF(@exist_start = 0, 'ALTER TABLE assignments ADD COLUMN start_time DATETIME COMMENT "开始时间" AFTER total_score', 'SELECT "start_time already exists"');
PREPARE stmt FROM @sql_start;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 添加 allow_late 字段
SET @exist_late := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'demo2' AND TABLE_NAME = 'assignments' AND COLUMN_NAME = 'allow_late');
SET @sql_late := IF(@exist_late = 0, 'ALTER TABLE assignments ADD COLUMN allow_late TINYINT NOT NULL DEFAULT 0 COMMENT "是否允许迟交" AFTER start_time', 'SELECT "allow_late already exists"');
PREPARE stmt FROM @sql_late;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 添加 late_penalty 字段
SET @exist_penalty := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'demo2' AND TABLE_NAME = 'assignments' AND COLUMN_NAME = 'late_penalty');
SET @sql_penalty := IF(@exist_penalty = 0, 'ALTER TABLE assignments ADD COLUMN late_penalty INT DEFAULT 10 COMMENT "迟交扣分百分比" AFTER allow_late', 'SELECT "late_penalty already exists"');
PREPARE stmt FROM @sql_penalty;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 添加 allow_resubmit 字段
SET @exist_resubmit := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'demo2' AND TABLE_NAME = 'assignments' AND COLUMN_NAME = 'allow_resubmit');
SET @sql_resubmit := IF(@exist_resubmit = 0, 'ALTER TABLE assignments ADD COLUMN allow_resubmit TINYINT NOT NULL DEFAULT 1 COMMENT "是否允许重交" AFTER late_penalty', 'SELECT "allow_resubmit already exists"');
PREPARE stmt FROM @sql_resubmit;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 添加 status 字段
SET @exist_status := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'demo2' AND TABLE_NAME = 'assignments' AND COLUMN_NAME = 'status');
SET @sql_status := IF(@exist_status = 0, 'ALTER TABLE assignments ADD COLUMN status TINYINT NOT NULL DEFAULT 1 COMMENT "1草稿 2已发布" AFTER allow_resubmit', 'SELECT "status already exists"');
PREPARE stmt FROM @sql_status;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 添加 deleted 字段
SET @exist_deleted := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'demo2' AND TABLE_NAME = 'assignments' AND COLUMN_NAME = 'deleted');
SET @sql_deleted := IF(@exist_deleted = 0, 'ALTER TABLE assignments ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT "逻辑删除" AFTER status', 'SELECT "deleted already exists"');
PREPARE stmt FROM @sql_deleted;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 检查并添加 submissions 表缺失的字段
-- 添加 submit_no 字段
SET @exist_no := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'demo2' AND TABLE_NAME = 'submissions' AND COLUMN_NAME = 'submit_no');
SET @sql_no := IF(@exist_no = 0, 'ALTER TABLE submissions ADD COLUMN submit_no INT NOT NULL DEFAULT 1 COMMENT "第几次提交" AFTER user_id', 'SELECT "submit_no already exists"');
PREPARE stmt FROM @sql_no;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 添加 is_latest 字段
SET @exist_latest := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'demo2' AND TABLE_NAME = 'submissions' AND COLUMN_NAME = 'is_latest');
SET @sql_latest := IF(@exist_latest = 0, 'ALTER TABLE submissions ADD COLUMN is_latest TINYINT NOT NULL DEFAULT 1 COMMENT "是否最新提交" AFTER submit_no', 'SELECT "is_latest already exists"');
PREPARE stmt FROM @sql_latest;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 添加 is_late 字段
SET @exist_islate := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'demo2' AND TABLE_NAME = 'submissions' AND COLUMN_NAME = 'is_late');
SET @sql_islate := IF(@exist_islate = 0, 'ALTER TABLE submissions ADD COLUMN is_late TINYINT NOT NULL DEFAULT 0 COMMENT "是否迟交 0准时 1迟交" AFTER is_latest', 'SELECT "is_late already exists"');
PREPARE stmt FROM @sql_islate;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 添加索引
SET @exist_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'demo2' AND TABLE_NAME = 'submissions' AND INDEX_NAME = 'idx_latest');
SET @sql_idx := IF(@exist_idx = 0, 'CREATE INDEX idx_latest ON submissions(assignment_id, user_id, is_latest)', 'SELECT "idx_latest already exists"');
PREPARE stmt FROM @sql_idx;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 显示结果
SELECT 'Assignments 表结构:' AS '';
DESCRIBE assignments;

SELECT 'Submissions 表结构:' AS '';
DESCRIBE submissions;

SELECT '更新完成!' AS message;
