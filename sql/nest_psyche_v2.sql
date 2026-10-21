-- ============================================================
-- NEST 心理特征模块 v2 迁移
-- 目标：
--   1. 记录"样本间分歧"——同一个学生让大模型采样两次，两次结果差多少。
--      差得越大说明这个学生的画像越不可靠，前端要能把它显示出来，
--      而不是给一个看起来很确定的数字。
--   2. 记录对话解析与脱敏的结果：
--      parsed_json    —— 这份聊天记录里识别出的说话人、轮次、哪些发言被采用
--      scrubbed       —— 是否已做过出站脱敏
--      scrub_hits_json—— 命中了哪些脱敏规则（手机号/学号/姓名各几处）
-- 幂等：重复执行不报错。
-- ============================================================

USE nest;

-- 1. dorm_psyche_profile.spread_json
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = 'nest' AND TABLE_NAME = 'dorm_psyche_profile'
             AND COLUMN_NAME = 'spread_json');
SET @s := IF(@c = 0,
    'ALTER TABLE dorm_psyche_profile ADD COLUMN spread_json TEXT NULL
       COMMENT ''逐维样本间分歧（多次采样的极差），NULL=只采样了一次''',
    'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = 'nest' AND TABLE_NAME = 'dorm_psyche_profile'
             AND COLUMN_NAME = 'samples');
SET @s := IF(@c = 0,
    'ALTER TABLE dorm_psyche_profile ADD COLUMN samples INT NOT NULL DEFAULT 1
       COMMENT ''实际采样次数''',
    'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2. dorm_psyche_text 的解析与脱敏元数据
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = 'nest' AND TABLE_NAME = 'dorm_psyche_text'
             AND COLUMN_NAME = 'parsed_json');
SET @s := IF(@c = 0,
    'ALTER TABLE dorm_psyche_text ADD COLUMN parsed_json TEXT NULL
       COMMENT ''对话解析结果：说话人、轮次、本人发言字数''',
    'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = 'nest' AND TABLE_NAME = 'dorm_psyche_text'
             AND COLUMN_NAME = 'scrubbed');
SET @s := IF(@c = 0,
    'ALTER TABLE dorm_psyche_text ADD COLUMN scrubbed TINYINT(1) NOT NULL DEFAULT 0
       COMMENT ''是否已对出站文本做过脱敏''',
    'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = 'nest' AND TABLE_NAME = 'dorm_psyche_text'
             AND COLUMN_NAME = 'scrub_hits_json');
SET @s := IF(@c = 0,
    'ALTER TABLE dorm_psyche_text ADD COLUMN scrub_hits_json TEXT NULL
       COMMENT ''脱敏命中统计：手机号/学号/姓名各替换了几处''',
    'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 3. 提取器评测结果留档（管理端"评测"页要能看到最近一次跑的数）
CREATE TABLE IF NOT EXISTS dorm_psyche_eval (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    extractor    VARCHAR(48)  NOT NULL COMMENT 'RULE/LLM/ENSEMBLE',
    variant      VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '清洗后/原样',
    cases        INT          NOT NULL DEFAULT 0,
    mae_hour     DOUBLE       NULL,
    mae01        DOUBLE       NULL,
    pearson_mean DOUBLE       NULL,
    spearman_mean DOUBLE      NULL,
    collapse     DOUBLE       NULL,
    coverage     DOUBLE       NULL,
    elapsed_ms   BIGINT       NOT NULL DEFAULT 0,
    detail_json  LONGTEXT     NULL COMMENT '逐维明细 + 完整报告',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_psyche_eval_time (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT '心理特征提取器的量化评测留档';

SELECT 'nest_psyche_v2 迁移完成' AS msg;
