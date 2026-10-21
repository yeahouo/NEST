-- =====================================================================
-- NEST 学工平台 · P10 勤工助学（workstudy）演示数据
-- 幂等：先按固定 id 清理演示行，再插入；不硬编码 users.id（一律 SELECT username 取真实 id）。
--
-- 预置演示账号（密码均 123456）：
--   admin   / zhangsan(张三·teacher) / lisi(李四·teacher)
--   chendw(陈大伟·student) / liusq(刘思琪·student) / wangxm(王小明·student)
-- 先执行 sql/nest_workstudy.sql，再执行本脚本：
--   mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest < sql\nest_workstudy_demo.sql
-- =====================================================================

USE nest;

SET @admin_id   = (SELECT id FROM users WHERE username = 'admin'   AND deleted = 0 LIMIT 1);
SET @zhangsan   = (SELECT id FROM users WHERE username = 'zhangsan' AND deleted = 0 LIMIT 1);
SET @lisi       = (SELECT id FROM users WHERE username = 'lisi'    AND deleted = 0 LIMIT 1);
SET @chendw     = (SELECT id FROM users WHERE username = 'chendw'  AND deleted = 0 LIMIT 1);
SET @liusq      = (SELECT id FROM users WHERE username = 'liusq'   AND deleted = 0 LIMIT 1);
SET @wangxm     = (SELECT id FROM users WHERE username = 'wangxm'  AND deleted = 0 LIMIT 1);

-- 清理演示行（固定 id 区间，先子表后父表）
DELETE FROM workstudy_payroll WHERE id BETWEEN 9001 AND 9099;
DELETE FROM workstudy_timesheet WHERE id BETWEEN 9001 AND 9099;
DELETE FROM workstudy_application WHERE id BETWEEN 9001 AND 9099;
DELETE FROM workstudy_post WHERE id BETWEEN 9001 AND 9099;

-- ---------------------------------------------------------------------
-- 1. 岗位：① 图书馆·固定岗·recruiting ② 教务处·临时岗·recruiting ③ 后勤保障处·固定岗·pending（待资助中心审批）
--    hourly_wage/hours_* 是「岗位设定的用工条件上限」，与办法第十六/二十三条的常规线对照：
--      ① 15 元/小时·周 8·月 40（正好卡线）② 时薪 15·周 4·月 16 ③ 时薪 99·周 7·月 35
-- ---------------------------------------------------------------------
INSERT INTO workstudy_post
    (id, post_type, unit, unit_user_id, name, content, skill, headcount, hired_count,
     hourly_wage, hours_per_week, hours_per_month, academic_term, apply_deadline, interview_at,
     status, review_note, reviewer_id, reviewed_at, created_at, updated_at, deleted)
VALUES
    (9001, 'fixed', '图书馆', @zhangsan, '图书整理与借阅服务',
     '图书上架、读者引导、借阅台协助与阅览室秩序维护', '细心、有责任心，每周可固定到岗', 3, 1,
     15.00, 8.0, 40.0, '2025-2026-1', '2026-10-20 18:00:00', '2026-10-22 14:00:00',
     'recruiting', '用工条件符合办法第十六/二十三条，同意设岗并公告招募', @admin_id, '2026-10-08 09:30:00',
     '2026-10-01 09:00:00', '2026-10-08 09:30:00', 0),
    (9002, 'temporary', '教务处', @lisi, '教务资料录入（临时岗）',
     '期末教学资料整理与录入，一次性完成，约 3 周', '熟练使用 Excel，打字速度较快', 2, 1,
     15.00, 4.0, 16.0, '2025-2026-1', '2026-12-05 18:00:00', '2026-12-08 15:00:00',
     'recruiting', '临时岗用工时间与酬劳明确，同意设岗', @admin_id, '2026-10-08 09:40:00',
     '2026-10-01 09:10:00', '2026-10-08 09:40:00', 0),
    (9003, 'fixed', '后勤保障处', @lisi, '教室多媒体设备巡检',
     '教室多媒体设备日常巡检与简单维护', '动手能力强，能处理常见设备故障', 2, 0,
     99.00, 7.0, 35.0, '2025-2026-1', '2026-11-01 18:00:00', '2026-11-04 10:00:00',
     'pending', NULL, NULL, NULL,
     '2026-10-09 16:00:00', '2026-10-09 16:00:00', 0)
ON DUPLICATE KEY UPDATE
    unit_user_id = VALUES(unit_user_id), hired_count = VALUES(hired_count),
    hourly_wage = VALUES(hourly_wage), hours_per_week = VALUES(hours_per_week),
    hours_per_month = VALUES(hours_per_month), status = VALUES(status),
    review_note = VALUES(review_note), reviewer_id = VALUES(reviewer_id),
    reviewed_at = VALUES(reviewed_at), updated_at = NOW();

-- ---------------------------------------------------------------------
-- 2. 申请：覆盖 pending / interview_passed / hired / rejected / resigned
-- ---------------------------------------------------------------------
INSERT INTO workstudy_application
    (id, post_id, student_id, academic_term, reason, evidence, status, interview_result,
     interview_note, applied_at, hired_at, resign_reason, note, created_at, updated_at, deleted)
VALUES
    (9001, 9001, @chendw, '2025-2026-1', '家庭经济困难，希望通过勤工助学承担部分生活费，可在周二/周四下午固定到岗',
     'family_income_level=low', 'hired', 'pass', '面试表现认真，熟悉图书分类，同意录用',
     '2026-10-09 10:00:00', '2026-10-10 11:00:00', NULL,
     '已录用，报资助中心备案并建立学生勤工助学档案',
     '2026-10-09 10:00:00', '2026-10-10 11:00:00', 0),
    (9002, 9002, @liusq, '2025-2026-1', '课余时间充足，希望参与临时性岗位积累办公经验',
     NULL, 'interview_passed', 'pass', 'Excel 操作熟练，待岗位活动开始后录用',
     '2026-10-09 10:30:00', NULL, NULL, '面试通过，等待办理录用手续',
     '2026-10-09 10:30:00', '2026-10-10 09:00:00', 0),
    (9003, 9001, @wangxm, '2025-2026-1', '希望利用晚间时间参与图书馆服务',
     NULL, 'pending', NULL, NULL,
     '2026-10-11 20:00:00', NULL, NULL, NULL,
     '2026-10-11 20:00:00', '2026-10-11 20:00:00', 0),
    (9004, 9002, @wangxm, '2025-2026-1', '希望尝试教务资料录入岗位',
     NULL, 'rejected', 'fail', '本次岗位更侧重打字速度，暂不匹配；建议关注下期岗位',
     '2026-10-09 11:00:00', NULL, NULL, '面试未通过，已告知学生',
     '2026-10-09 11:00:00', '2026-10-10 09:10:00', 0),
    (9005, 9003, @liusq, '2025-2026-1', '对设备巡检有兴趣，希望承担该岗位实现自立',
     NULL, 'pending', NULL, NULL,
     '2026-10-10 09:00:00', NULL, NULL, '岗位尚未通过资助中心审批，申请暂挂',
     '2026-10-10 09:00:00', '2026-10-10 09:00:00', 0),
    (9006, 9001, @liusq, '2025-2026-1', '图书馆岗位时间与课表冲突，申请离岗',
     NULL, 'resigned', NULL, NULL,
     '2026-10-05 09:00:00', '2026-10-06 09:00:00', '课程调整，无法保证固定到岗时间',
     '已确认离岗，按办法停发酬劳',
     '2026-10-05 09:00:00', '2026-10-08 09:00:00', 0)
ON DUPLICATE KEY UPDATE
    status = VALUES(status), interview_result = VALUES(interview_result),
    interview_note = VALUES(interview_note), hired_at = VALUES(hired_at),
    resign_reason = VALUES(resign_reason), note = VALUES(note), updated_at = NOW();

-- ---------------------------------------------------------------------
-- 3. 工时：submitted（待确认）/ confirmed（已确认），含一条超 40 小时的提示行
--    常规上限：每周 ≤8 小时、每月 ≤40 小时（第十六条，寒暑假可延长）
-- ---------------------------------------------------------------------
INSERT INTO workstudy_timesheet
    (id, application_id, post_id, student_id, work_month, work_date, hours, content,
     over_limit, status, recorder_id, created_at, updated_at, deleted)
VALUES
    (9001, 9001, 9001, @chendw, '2026-10', '2026-10-08', 3.0, '图书上架与整理',   0, 'confirmed', @zhangsan, '2026-10-09 08:30:00', '2026-10-09 08:30:00', 0),
    (9002, 9001, 9001, @chendw, '2026-10', '2026-10-12', 4.5, '借阅台协助与读者引导', 0, 'confirmed', @zhangsan, '2026-10-13 08:30:00', '2026-10-13 08:30:00', 0),
    (9003, 9001, 9001, @chendw, '2026-10', '2026-10-15', 2.0, '阅览室秩序维护',   0, 'submitted', @zhangsan, '2026-10-16 08:30:00', '2026-10-16 08:30:00', 0),
    (9004, 9001, 9001, @chendw, '2026-10', '2026-10-19', 8.0, '期末图书清点（加班时段）', 1, 'submitted', @zhangsan, '2026-10-20 08:30:00', '2026-10-20 08:30:00', 0),
    (9005, 9001, 9001, @chendw, '2026-10', '2026-10-22', 30.0, '全月累计补录（演示超 40 小时提示）', 1, 'submitted', @zhangsan, '2026-10-23 08:30:00', '2026-10-23 08:30:00', 0),
    (9006, 9002, 9002, @liusq,  '2026-10', '2026-10-14', 3.0, '教务资料录入',     0, 'confirmed', @lisi,     '2026-10-15 09:30:00', '2026-10-15 09:30:00', 0),
    (9007, 9002, 9002, @liusq,  '2026-10', '2026-10-16', 2.0, '教学资料整理归档', 0, 'submitted', @lisi,     '2026-10-17 09:30:00', '2026-10-17 09:30:00', 0)
ON DUPLICATE KEY UPDATE
    hours = VALUES(hours), content = VALUES(content), over_limit = VALUES(over_limit),
    status = VALUES(status), recorder_id = VALUES(recorder_id), updated_at = NOW();

-- ---------------------------------------------------------------------
-- 4. 酬金：一张已发放、一张待发放（金额 = 已确认工时 × 时薪，幂等键 学生+岗位+月份）
-- ---------------------------------------------------------------------
INSERT INTO workstudy_payroll
    (id, student_id, post_id, application_id, work_month, total_hours, hourly_wage, amount,
     assessment, status, payer_id, paid_at, created_at, updated_at, deleted)
VALUES
    (9001, @chendw, 9001, 9001, '2026-10', 7.5, 15.00, 112.50,
     '工作认真负责，图书整理与读者引导完成良好（合格）', 'paid', @admin_id, '2026-10-25 10:00:00',
     '2026-10-24 09:00:00', '2026-10-25 10:00:00', 0),
    (9002, @liusq, 9002, 9002, '2026-10', 3.0, 15.00, 45.00,
     '资料录入准确，待活动结束后次月一并报送（合格）', 'pending', NULL, NULL,
     '2026-10-24 09:00:00', '2026-10-24 09:00:00', 0)
ON DUPLICATE KEY UPDATE
    total_hours = VALUES(total_hours), hourly_wage = VALUES(hourly_wage),
    amount = VALUES(amount), assessment = VALUES(assessment), status = VALUES(status),
    payer_id = VALUES(payer_id), paid_at = VALUES(paid_at), updated_at = NOW();

-- ---------------------------------------------------------------------
-- 演示数据自检（用 -N -B 看数值，勿用 \G）
-- ---------------------------------------------------------------------
SELECT 'workstudy_post' AS table_name, COUNT(*) AS rows_now FROM workstudy_post
UNION ALL SELECT 'workstudy_application', COUNT(*) FROM workstudy_application
UNION ALL SELECT 'workstudy_timesheet', COUNT(*) FROM workstudy_timesheet
UNION ALL SELECT 'workstudy_payroll', COUNT(*) FROM workstudy_payroll;

SELECT p.id, p.unit, p.name, p.post_type, p.status, p.hired_count,
       (SELECT COUNT(*) FROM workstudy_application a WHERE a.post_id = p.id AND a.deleted = 0) AS apps
FROM workstudy_post p WHERE p.id BETWEEN 9001 AND 9099 ORDER BY p.id;
