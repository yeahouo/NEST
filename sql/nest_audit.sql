-- NEST 审计日志与登录防护（差距清单 P0-1 / P0-2）
--
-- 依据（等保测评指标原文，见 docs/NEST-差距分析与路线图.md §2.12）：
--   「审计记录应包括事件的日期和时间、用户、事件类型、事件是否成功及其他与审计相关的信息」
--   「身份标识具有唯一性、鉴别信息具有复杂度要求并定期更换」
--
-- 幂等：本脚本可重复执行（CREATE TABLE IF NOT EXISTS + information_schema 判定列是否存在）。
-- 执行：& mysql.exe "--defaults-extra-file=$env:TEMP\nest_my.cnf" -N -B -e "source D:/project/NEST/sql/nest_audit.sql"

CREATE TABLE IF NOT EXISTS audit_log (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  event_time    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事件发生时间（毫秒精度）',
  event_type    VARCHAR(32)  NOT NULL COMMENT '事件类型：LOGIN/LOGOUT/REGISTER/PASSWORD_RESET/PASSWORD_CHANGE/ADMIN/DORM/COURSE/...',
  event_action  VARCHAR(16)  NOT NULL COMMENT '动作：HTTP 方法，或服务端判定的领域动作',
  user_id       BIGINT       NULL COMMENT '操作者用户 id（登录失败等场景可能为空）',
  username      VARCHAR(64)  NULL COMMENT '操作者账号（登录失败时记录的是被尝试的账号）',
  role          VARCHAR(32)  NULL COMMENT '操作者角色快照',
  target        VARCHAR(255) NULL COMMENT '目标：请求路径或业务对象标识',
  http_method   VARCHAR(8)   NULL COMMENT 'HTTP 方法',
  ip            VARCHAR(64)  NULL COMMENT '来源 IP（X-Forwarded-For 优先）',
  user_agent    VARCHAR(255) NULL COMMENT 'User-Agent（截断）',
  success       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '事件是否成功（1 成功 / 0 失败）',
  status_code   INT          NULL COMMENT 'HTTP 状态码',
  duration_ms   INT          NULL COMMENT '处理耗时（毫秒）',
  detail        VARCHAR(500) NULL COMMENT '补充说明；查询串中的密码/验证码等敏感字段一律写 ***',
  PRIMARY KEY (id),
  KEY idx_audit_time (event_time),
  KEY idx_audit_user (user_id, event_time),
  KEY idx_audit_type (event_type, success),
  KEY idx_audit_target (target(64), event_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '审计日志：时间/用户/事件类型/是否成功（等保测评指标对齐）';

-- users 增加「连续登录失败次数」与「锁定截止时间」（幂等）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'failed_login_count');
SET @s := IF(@c = 0,
  'ALTER TABLE users ADD COLUMN failed_login_count INT NOT NULL DEFAULT 0 COMMENT ''连续登录失败次数''',
  'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'locked_until');
SET @s := IF(@c = 0,
  'ALTER TABLE users ADD COLUMN locked_until DATETIME NULL COMMENT ''锁定截止时间（NULL 表示未锁定）''',
  'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 自检（应输出 1 / 1 / 1）
SELECT
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log')                        AS has_audit_log,
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'
      AND COLUMN_NAME = 'failed_login_count')                                            AS has_failed_count,
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users'
      AND COLUMN_NAME = 'locked_until')                                                  AS has_locked_until;
