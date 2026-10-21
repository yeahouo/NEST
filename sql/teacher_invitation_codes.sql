-
- ============================================================
-- 教师邀请码表 (teacher_invitation_codes)
-- 用途: 管理教师注册邀请码，替代配置文件中的固定邀请码
-- ============================================================

-- 创建表
CREATE TABLE IF NOT EXISTS teacher_invitation_codes (
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

-- 插入一个默认邀请码（可选）
INSERT INTO teacher_invitation_codes (code, description, status, created_by)
VALUES ('TEACHER2024', '默认教师邀请码', 1, 1)
ON DUPLICATE KEY UPDATE description = '默认教师邀请码';
