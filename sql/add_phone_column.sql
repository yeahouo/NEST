-- 为 users 表添加 phone 字段（如果不存在）
-- 用于支持手机号注册和重置密码功能

USE demo2;

-- 添加 phone 字段
ALTER TABLE users ADD COLUMN IF NOT EXISTS phone VARCHAR(20) COMMENT '手机号（含区号）';

-- 添加 phone 唯一索引（如果不存在）
ALTER TABLE users ADD UNIQUE INDEX IF NOT EXISTS idx_phone (phone);

-- 验证字段已添加
DESCRIBE users;
