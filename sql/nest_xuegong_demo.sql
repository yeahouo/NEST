-- =============================================================================
-- 学工平台 · 演示档案数据（可重复执行）
--
--   目的：给已有的学生账号建立学工档案，并把辅导员分派好，
--         这样「请假审批」的收件箱范围（按 student_profile.counselor_id）才有数据可验。
--
--   幂等：每条都带 NOT EXISTS，重复执行不会新增第二行。
--   执行：cmd /c "mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest -t < sql\nest_xuegong_demo.sql"
--
--   辅导员分派（演示口径）：
--     lisi(id=3)      → 王小明(4) 赵小红(5) 陈大伟(6)   —— 软工 2401 / 2402
--     zhangsan(id=2)  → 刘思琪(7) 周杰(8)               —— 计科 2401
--
--   注意：这是演示数据，部署到学校时请由管理员用 /api/student/profile 接口录入，
--         或按真实名单改写下面的 SELECT 条件（不要盲跑进生产库）。
-- =============================================================================

USE nest;

-- lisi 名下：软工 2401
INSERT INTO student_profile
    (user_id, student_no, class_name, major, grade, counselor_id,
     dorm_building, dorm_room, political_status, family_contact, family_income_level,
     status, created_at, updated_at)
SELECT u.id, u.student_id, u.class_name, u.major, u.grade, 3,
       'A 栋', 'A-305', '共青团员', '13800000001', 'medium',
       'active', NOW(), NOW()
FROM users u
WHERE u.deleted = 0 AND u.role = 'student' AND u.username IN ('wangxm', 'zhaoxh')
  AND NOT EXISTS (SELECT 1 FROM student_profile p WHERE p.user_id = u.id);

-- lisi 名下：软工 2402
INSERT INTO student_profile
    (user_id, student_no, class_name, major, grade, counselor_id,
     dorm_building, dorm_room, political_status, family_contact, family_income_level,
     status, created_at, updated_at)
SELECT u.id, u.student_id, u.class_name, u.major, u.grade, 3,
       'A 栋', 'A-412', '入党积极分子', '13800000002', 'low',
       'active', NOW(), NOW()
FROM users u
WHERE u.deleted = 0 AND u.role = 'student' AND u.username = 'chendw'
  AND NOT EXISTS (SELECT 1 FROM student_profile p WHERE p.user_id = u.id);

-- zhangsan 名下：计科 2401
INSERT INTO student_profile
    (user_id, student_no, class_name, major, grade, counselor_id,
     dorm_building, dorm_room, political_status, family_contact, family_income_level,
     status, created_at, updated_at)
SELECT u.id, u.student_id, u.class_name, u.major, u.grade, 2,
       'B 栋', 'B-201', '共青团员', '13800000003', 'medium',
       'active', NOW(), NOW()
FROM users u
WHERE u.deleted = 0 AND u.role = 'student' AND u.username IN ('liusq', 'zhoujie')
  AND NOT EXISTS (SELECT 1 FROM student_profile p WHERE p.user_id = u.id);

-- 自检：应有 5 条档案，其中 lisi 名下 3 条、zhangsan 名下 2 条
SELECT u.username, p.student_no, p.class_name, p.counselor_id, p.status
FROM student_profile p JOIN users u ON u.id = p.user_id
WHERE p.deleted = 0
ORDER BY p.counselor_id, p.student_no;

SELECT 'lisi(3) 名下' AS scope, COUNT(*) AS cnt FROM student_profile WHERE deleted = 0 AND counselor_id = 3
UNION ALL
SELECT 'zhangsan(2) 名下', COUNT(*) FROM student_profile WHERE deleted = 0 AND counselor_id = 2;
