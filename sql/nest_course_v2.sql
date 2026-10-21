-- ============================================================
-- 课程表补列：credit_hours / semester
-- ============================================================
-- 背景（这是基座系统带过来的一个真实缺口，不是本次新加的功能）：
--   `courses` 表的 DDL（course_tables.sql / init.sql / nest_init.sql / schema.sql）
--   从来没有 credit_hours 和 semester 两列，但是
--     · Course.java 有 creditHours / semester 两个字段
--     · CourseMapper.xml 的 Base_Column_List 在 SELECT 里点名了这两列
--     · CourseMapper.xml 的 insert / updateById 也在写这两列
--     · CourseCreateDTO、CourseService.buildCourseVO 一路都在用
--     · 教师端 teacher-dashboard.html 的「新建课程」表单和
--       teacher-course-detail.html 的「编辑课程」都在提交 creditHours / semester
--   结果是：`SELECT id, ..., credit_hours, semester, ... FROM courses` 直接
--   报 `Unknown column 'credit_hours' in 'field list'`，
--   课程详情、创建课程、编辑课程这三条路一直是坏的（报 500，不是 404）。
--
-- 修法选择：给表补列，而不是把代码里的字段删掉。
--   理由：学分和学期本来就是课程应有的属性，前端表单、DTO、VO 都按它们设计，
--   删字段会牵动一大片已经写好的界面，而补两列是可空列、对既有数据零影响。
--
-- 幂等：靠 information_schema 判存在后再 ALTER，可以重复执行。
-- ============================================================

USE nest;

SET @db := DATABASE();

SET @sql := (
  SELECT IF(COUNT(*) = 0,
            'ALTER TABLE courses ADD COLUMN credit_hours INT NULL COMMENT ''学分''',
            'SELECT ''courses.credit_hours already exists'' AS skipped')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'courses' AND COLUMN_NAME = 'credit_hours'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := (
  SELECT IF(COUNT(*) = 0,
            'ALTER TABLE courses ADD COLUMN semester VARCHAR(32) NULL COMMENT ''学期，如 Fall 2024''',
            'SELECT ''courses.semester already exists'' AS skipped')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'courses' AND COLUMN_NAME = 'semester'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 补完之后，教师端编辑课程里已经填过的值就能存住了；
-- 存量课程这两列先是 NULL，界面上显示 '-'，不影响任何查询。
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'courses'
  AND COLUMN_NAME IN ('credit_hours', 'semester');
