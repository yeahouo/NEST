-- nest_quiz_v2.sql —— 让「出题 / 改题」真正跑起来所缺的列
--
-- 背景：原来的测验模块只有教师端的增删改查，学生端一个接口都没有，
-- 客观题也不会自动判分。补齐这套流程时发现两处必须落库的信息：
--
--   1. 主观题（文本 / 编程）没有「参考答案或评分要点」可存的地方。
--      question_text 是给学生看的题干，correct_answer 是机器对答案用的精确值，
--      两者都不能代替评卷依据。没有它，AI 批改只能凭空猜测，老师也无从校对。
--   2. AI 预批改的建议必须与学生作答、教师评分分开存。
--      老师的 manual_score 是最终成绩，AI 的 ai_score 只是建议；
--      混在一列里，事后无法分辨「这个分是人打的还是机器打的」。
--
-- 幂等：用 information_schema 判断列是否已存在，重复执行不报错。
-- 执行方式（注意用管道，mysql -e "source ..." 在 MySQL 9 上会报 1064）：
--   Get-Content -Raw -Encoding UTF8 sql\nest_quiz_v2.sql |
--     & mysql.exe "--defaults-extra-file=$env:TEMP\nest_my.cnf"

USE nest;

SET @db := DATABASE();

-- ── 1. quiz_questions.reference_answer：主观题的参考答案 / 评分要点 ─────────
SET @sql := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE quiz_questions ADD COLUMN reference_answer TEXT NULL COMMENT ''参考答案/评分要点（主观题 AI 批改依据）''',
  'SELECT ''reference_answer 已存在，跳过'' AS skipped')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'quiz_questions' AND COLUMN_NAME = 'reference_answer');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ── 2. quiz_answers 上的 AI 预批改建议（与人工评分分列存放）─────────────────
-- ai_score：AI 建议分；ai_confidence：模型自评置信度 0~1；
-- ai_reason：AI 给的理由（老师据此判断是否采纳）；
-- ai_model：哪个模型批的（换模型后可以对比质量）；
-- ai_graded_at：什么时候批的。
SET @sql := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE quiz_answers ADD COLUMN ai_score INT NULL COMMENT ''AI 建议分''',
  'SELECT ''ai_score 已存在，跳过'' AS skipped')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'quiz_answers' AND COLUMN_NAME = 'ai_score');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE quiz_answers ADD COLUMN ai_confidence DECIMAL(4,3) NULL COMMENT ''AI 置信度 0~1''',
  'SELECT ''ai_confidence 已存在，跳过'' AS skipped')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'quiz_answers' AND COLUMN_NAME = 'ai_confidence');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE quiz_answers ADD COLUMN ai_reason TEXT NULL COMMENT ''AI 批改理由''',
  'SELECT ''ai_reason 已存在，跳过'' AS skipped')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'quiz_answers' AND COLUMN_NAME = 'ai_reason');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE quiz_answers ADD COLUMN ai_model VARCHAR(64) NULL COMMENT ''批改所用模型''',
  'SELECT ''ai_model 已存在，跳过'' AS skipped')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'quiz_answers' AND COLUMN_NAME = 'ai_model');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE quiz_answers ADD COLUMN ai_graded_at DATETIME NULL COMMENT ''AI 批改时间''',
  'SELECT ''ai_graded_at 已存在，跳过'' AS skipped')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'quiz_answers' AND COLUMN_NAME = 'ai_graded_at');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @db
  AND ((TABLE_NAME = 'quiz_questions' AND COLUMN_NAME = 'reference_answer')
    OR (TABLE_NAME = 'quiz_answers'
        AND COLUMN_NAME IN ('ai_score','ai_confidence','ai_reason','ai_model','ai_graded_at')))
ORDER BY TABLE_NAME, ORDINAL_POSITION;
