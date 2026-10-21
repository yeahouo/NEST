-- =============================================================================
-- NEST · P18 第二课堂 —— 演示数据清理（只删 id 段 93001–93099，绝不 DELETE 全表）
-- 依赖：sql/nest_secondclass_demo.sql 的 id 约定
-- 执行：cmd /c "mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest -t < sql\clean-secondclass-demo.sql"
-- =============================================================================
DELETE FROM second_class_record   WHERE id BETWEEN 93001 AND 93099;
DELETE FROM second_class_activity WHERE id BETWEEN 93001 AND 93099;
DELETE FROM second_class_category WHERE id BETWEEN 93001 AND 93099;

SELECT '--- 清理后计数 ---' AS t;
SELECT (SELECT COUNT(*) FROM second_class_category) AS categories,
       (SELECT COUNT(*) FROM second_class_activity) AS activities,
       (SELECT COUNT(*) FROM second_class_record)   AS records;
