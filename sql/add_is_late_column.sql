-- 简单版本：直接添加缺失的字段
-- 如果字段已存在会报错，可以忽略

USE demo2;

-- 添加 submissions 表缺失的字段
ALTER TABLE submissions ADD COLUMN submit_no INT NOT NULL DEFAULT 1 COMMENT '第几次提交' AFTER user_id;
ALTER TABLE submissions ADD COLUMN is_latest TINYINT NOT NULL DEFAULT 1 COMMENT '是否最新提交' AFTER submit_no;
ALTER TABLE submissions ADD COLUMN is_late TINYINT NOT NULL DEFAULT 0 COMMENT '是否迟交 0准时 1迟交' AFTER is_latest;

-- 添加索引
CREATE INDEX idx_latest ON submissions(assignment_id, user_id, is_latest);

-- 添加 assignments 表缺失的字段
ALTER TABLE assignments ADD COLUMN start_time DATETIME COMMENT '开始时间' AFTER total_score;
ALTER TABLE assignments ADD COLUMN allow_late TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许迟交' AFTER end_time;
ALTER TABLE assignments ADD COLUMN late_penalty INT DEFAULT 10 COMMENT '迟交扣分百分比' AFTER allow_late;
ALTER TABLE assignments ADD COLUMN allow_resubmit TINYINT NOT NULL DEFAULT 1 COMMENT '是否允许重交' AFTER late_penalty;
ALTER TABLE assignments ADD COLUMN attachments TEXT COMMENT '教师上传的附件URL' AFTER allow_resubmit;
ALTER TABLE assignments ADD COLUMN status TINYINT NOT NULL DEFAULT 1 COMMENT '1草稿 2已发布' AFTER attachments;
ALTER TABLE assignments ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除' AFTER status;

-- 更新现有数据：将所有作业设为已发布
UPDATE assignments SET status = 2 WHERE status IS NULL OR status = 0;
-- 更新现有提交：设为最新
UPDATE submissions SET is_latest = 1 WHERE is_latest IS NULL;
UPDATE submissions SET submit_no = 1 WHERE submit_no IS NULL OR submit_no = 0;

SELECT 'Done!' AS message;
