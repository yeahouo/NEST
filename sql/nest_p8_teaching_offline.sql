-- ============================================================================
-- 学工平台改造 · P8：教学模块下线（数据库侧）
-- ============================================================================
-- 背景：P8 已删除全部课程 / 作业 / 测验 / 签到 / 资料的代码、Mapper、页面与单测，
--       这些表不再被任何代码引用。本脚本把它们从库里摘掉，并把公告表上已无意义的
--       course_id 列删除（公告从「课程定向」改成「全校公告」）。
--
-- 用法（先备份！）：
--   mysqldump -u root -p nest > nest-backup-before-p8.sql
--   mysql     -u root -p -D nest < sql/nest_p8_teaching_offline.sql
--
-- 幂等：重复执行安全（表 / 列不存在时跳过）。全部是 DROP / UPDATE，
--       不建新表、不改其它业务表结构。
-- 不可逆：下面 11 张表里的数据会被删除（列出的行数是通用演示库的量级，
--         学校正式库请先确认这些表确实不需要留档）。
-- ============================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 0. 先看清要删什么（执行前打印一份，便于留档）
-- ---------------------------------------------------------------------------
SELECT '将要删除的教学表行数' AS phase;
SELECT 'courses' AS table_name, COUNT(*) AS rows_now FROM courses
UNION ALL SELECT 'course_members',      COUNT(*) FROM course_members
UNION ALL SELECT 'assignments',         COUNT(*) FROM assignments
UNION ALL SELECT 'submissions',         COUNT(*) FROM submissions
UNION ALL SELECT 'quizzes',             COUNT(*) FROM quizzes
UNION ALL SELECT 'quiz_questions',      COUNT(*) FROM quiz_questions
UNION ALL SELECT 'quiz_answers',        COUNT(*) FROM quiz_answers
UNION ALL SELECT 'quiz_submissions',    COUNT(*) FROM quiz_submissions
UNION ALL SELECT 'attendance_sessions', COUNT(*) FROM attendance_sessions
UNION ALL SELECT 'attendance_records',  COUNT(*) FROM attendance_records
UNION ALL SELECT 'materials',           COUNT(*) FROM materials;

-- ---------------------------------------------------------------------------
-- 1. 公告改成学工口径（只动演示数据：id + 原标题 都对得上才改）
--    学校的正式库若没有这几条，UPDATE 影响 0 行，什么也不会发生。
-- ---------------------------------------------------------------------------
UPDATE announcements SET title = '2026 年秋季学期综合素质测评启动',
       content = '本学期综测自评窗口为 10 月 8 日至 10 月 20 日，请在「综合素质测评」页完成德智体美劳五维自评并保存；辅导员复核评分后由学院统一重算总分与班级排名。'
 WHERE id = 1 AND title = '期末考试安排通知';
UPDATE announcements SET title = '国庆假期离校请假办理须知',
       content = '国庆假期需要离校或外出的同学，请在「请假 · 离校」页提交申请，写清去向、随行人与联系电话；辅导员批准后凭批件出行，返校后记得在系统里销假。'
 WHERE id = 2 AND title = '第11周实验课调整';
UPDATE announcements SET title = '2025-2026 学年国家助学金评审结果公示',
       content = '本学年国家助学金评审已完成，结果在学生名册与「资助申请」页可查。对认定档次有异议的同学，请在公示期内向辅导员提交书面说明。'
 WHERE id = 3 AND title = '数据库课程设计任务书';
UPDATE announcements SET title = '宿舍安全与卫生检查安排',
       content = '本月 15 日与 28 日进行宿舍安全与卫生检查，重点检查违规电器与通道堆物。检查结果计入劳动素养测评，请提前整理。'
 WHERE id = 4 AND title = '前端项目评审标准';

-- ---------------------------------------------------------------------------
-- 2. 删除 announcements.course_id（公告不再挂到课程上）
--    2026-10 起公告由 /api/announcements 提供，INSERT 不再写这一列。
-- ---------------------------------------------------------------------------
SET @has_course_id := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'announcements' AND COLUMN_NAME = 'course_id'
);
SET @sql := IF(@has_course_id = 1,
               'ALTER TABLE announcements DROP COLUMN course_id',
               'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SELECT IF(@has_course_id = 1, 'announcements.course_id 已删除', 'announcements.course_id 本来就不存在') AS phase_2;

-- ---------------------------------------------------------------------------
-- 3. 删除教学表（依赖顺序：子表在前，父表在后）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS submissions;
DROP TABLE IF EXISTS assignments;
DROP TABLE IF EXISTS quiz_answers;
DROP TABLE IF EXISTS quiz_submissions;
DROP TABLE IF EXISTS quiz_questions;
DROP TABLE IF EXISTS quizzes;
DROP TABLE IF EXISTS attendance_records;
DROP TABLE IF EXISTS attendance_sessions;
DROP TABLE IF EXISTS materials;
DROP TABLE IF EXISTS course_members;
DROP TABLE IF EXISTS courses;

-- ---------------------------------------------------------------------------
-- 4. 自检：库里不应该再有上面任何一张表；公告列里也不应再有 course_id
-- ---------------------------------------------------------------------------
SELECT '教学表残留' AS phase_3, COUNT(*) AS still_there
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('courses','course_members','assignments','submissions',
                      'quizzes','quiz_questions','quiz_answers','quiz_submissions',
                      'attendance_sessions','attendance_records','materials');

SELECT '公告仍带 course_id' AS phase_4, COUNT(*) AS still_there
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'announcements' AND COLUMN_NAME = 'course_id';

SELECT '公告条数（应保留全部）' AS phase_5, COUNT(*) AS announcements FROM announcements WHERE deleted = 0;
