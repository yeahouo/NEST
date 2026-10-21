-- nest_appeal_v3.sql —— 申诉跨块试算（第三级）所需的列
--
-- 背景：申诉的前两级（房间级、块级）在提交时就算完了，结果写在 payload_json。
-- 第三级「跨块」要把「同专业大类集中」这类政策关掉再重新分块，规模是「同性别的整个年级」，
-- 只能在后台线程里跑，所以需要单独的列来记它的状态与结论，而不是覆盖 payload_json。
--
-- 幂等：用 information_schema 判断列是否已存在，重复执行不报错。
-- 执行方式：
--   Get-Content -Raw -Encoding UTF8 sql\nest_appeal_v3.sql |
--     & mysql.exe "--defaults-extra-file=$env:TEMP\nest_my.cnf"

USE nest;

SET @db := DATABASE();

-- cross_state：NONE / QUEUED / RUNNING / DONE / FAILED
SET @sql := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE dorm_appeal ADD COLUMN cross_state VARCHAR(24) NOT NULL DEFAULT ''NONE'' COMMENT ''跨块试算状态''',
  'SELECT ''cross_state 已存在，跳过'' AS skipped')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'dorm_appeal' AND COLUMN_NAME = 'cross_state');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- cross_note：给人看的一句话说明（排队中 / 失败原因 / 结论摘要）
SET @sql := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE dorm_appeal ADD COLUMN cross_note TEXT NULL COMMENT ''跨块试算说明''',
  'SELECT ''cross_note 已存在，跳过'' AS skipped')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'dorm_appeal' AND COLUMN_NAME = 'cross_note');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- cross_json：跨块试算的完整结果（AppealResult 的 JSON），与 payload_json 并存
SET @sql := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE dorm_appeal ADD COLUMN cross_json MEDIUMTEXT NULL COMMENT ''跨块试算结果 JSON''',
  'SELECT ''cross_json 已存在，跳过'' AS skipped')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'dorm_appeal' AND COLUMN_NAME = 'cross_json');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- cross_elapsed_ms：跨块试算耗时，用来判断这台服务器扛不扛得住
SET @sql := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE dorm_appeal ADD COLUMN cross_elapsed_ms BIGINT NOT NULL DEFAULT 0 COMMENT ''跨块试算耗时ms''',
  'SELECT ''cross_elapsed_ms 已存在，跳过'' AS skipped')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'dorm_appeal' AND COLUMN_NAME = 'cross_elapsed_ms');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'dorm_appeal'
  AND COLUMN_NAME IN ('cross_state','cross_note','cross_json','cross_elapsed_ms')
ORDER BY ORDINAL_POSITION;
