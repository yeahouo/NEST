-- =====================================================================
-- P12 评奖评优（荣誉称号在线申报）演示数据
-- =====================================================================
-- 依据一手出处：
--   《关于评选2026届优秀毕业生的通知》
--   https://xgb.xujc.com/2026/0507/c1962a169810/page.htm
--   程序：学生线上申请 + 材料线下交学院 → 学院审核材料、报送初评名单 →
--         学工部复核并报评审领导小组审批 → 公示 → 发文表彰。
--   本平台是申报与审核载体：学生线上申请、辅导员与学院在线审核，
--   《通知》明确「名单以平台导出为准」，驳回要让学生看到进度与意见。
--   （《…评选办法》正文页 /2024/0914/c1985a155583/page.psp 抓不到正文，
--     故本文件不对各类荣誉称号的名额/比例作断言。）
--
-- 幂等：本文件每次执行先删除自己写入的两个演示批次（连带其申报与审核记录），
--       再重新写入。可安全重复执行。
--
-- 执行：cmd /c "mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest -t < sql\nest_honor_demo.sql"
-- =====================================================================
SET NAMES utf8mb4;

-- ── 0. 清理本文件写入的演示数据（幂等） ───────────────────────────────
DELETE hr FROM honor_review hr
  JOIN honor_application ha ON ha.id = hr.application_id
  JOIN honor_batch hb ON hb.id = ha.batch_id
 WHERE hb.batch_name IN ('2026届优秀毕业生评选（演示）', '2026年三好学生评选（演示）');

DELETE ha FROM honor_application ha
  JOIN honor_batch hb ON hb.id = ha.batch_id
 WHERE hb.batch_name IN ('2026届优秀毕业生评选（演示）', '2026年三好学生评选（演示）');

DELETE FROM honor_batch
 WHERE batch_name IN ('2026届优秀毕业生评选（演示）', '2026年三好学生评选（演示）');

-- ── 1. 批次 A：评审中（有在办申报，演示各审核节点） ────────────────────
INSERT INTO honor_batch
  (batch_name, honor_type, academic_year, eval_term, quota, quota_note, criteria, materials,
   apply_start, apply_end, review_end, publicity_days, publicity_start, publicity_end,
   status, notice_title, notice_url, created_by)
VALUES
  ('2026届优秀毕业生评选（演示）', 'graduate_outstanding', '2025-2026', '2025-2026-1',
   3, '学院推荐名额以上级下达指标为准；本条为演示数据',
   '1. 在校期间无违法违纪记录，且无不及格科目；\n2. 综合素质测评毕业排名在班级前 15%；\n3. 校级以上荣誉称号 ≥2 次，或 ≥1 次且获得国家/校级奖学金 ≥1 次；\n4. 体质健康测试成绩 ≥50 分；\n5. 同等条件下，服兵役、志愿服务、创新创业等表现突出者优先。',
   '优秀毕业生申请表（附件3，学院--学生名字--优秀毕业生申请表.doc）；成绩单；校外奖项需附证书复印件（校内奖项不需证明）',
   '2026-05-01 08:00:00', '2026-05-13 23:59:59', '2026-05-20 17:00:00',
   5, '2026-05-21', '2026-05-26',
   'reviewing', '关于评选2026届优秀毕业生的通知',
   'https://xgb.xujc.com/2026/0507/c1962a169810/page.htm', 1);
SET @bidA = LAST_INSERT_ID();

-- 申报：覆盖「待辅导员初审 / 待学院初评 / 待学工部复核 / 已驳回 / 草稿」五种在办状态
INSERT INTO honor_application
  (batch_id, student_id, honor_type, academic_year, self_statement, honors_text, scholarships_text,
   pe_score, no_fail_declared, no_violation_declared, material_note, contact_phone,
   status, reject_reason, counselor_id, submitted_at)
VALUES
  (@bidA, 6, 'graduate_outstanding', '2025-2026',
   '本人四年专业课平均分 88.6，连续三年获校级奖学金，任班级学习委员期间组织学业帮扶 12 次；毕业设计被评为院级优秀，已无不及格科目，综测毕业排名班级第一。',
   '校级三好学生 2 次；院级优秀学生干部 1 次',
   '校级一等奖学金 2 次；国家励志奖学金 1 次',
   86.50, 1, 1,
   '申请表与成绩单已交学院辅导员办公室；获奖证书复印件（校外）2 份',
   '13806000001', 'submitted', NULL, 3, '2026-05-06 10:12:00'),

  (@bidA, 4, 'graduate_outstanding', '2025-2026',
   '本人在校期间无违纪记录，累计志愿服务 210 小时，主持院级创新创业项目 1 项并结题；专业课无不及格科目，综测毕业排名班级前 15%，希望申请优秀毕业生。',
   '校级优秀共青团员 1 次；校级三好学生 1 次',
   '校级二等奖学金 1 次',
   78.00, 1, 1,
   '申请表、成绩单与证书复印件已全部交至学院',
   '13806000002', 'college_approved', NULL, 3, '2026-05-05 09:30:00'),

  (@bidA, 5, 'graduate_outstanding', '2025-2026',
   '本人已完成全部课程且无不及格科目，参加全国大学生数学建模竞赛获省级奖项，综测排名班级第二，特此申请优秀毕业生称号。',
   '省级数学建模竞赛二等奖 1 次；校级三好学生 1 次',
   '校级三等奖学金 1 次',
   74.00, 1, 1,
   '申请表与成绩单已交学院；证书复印件 1 份',
   '13806000003', 'counselor_approved', NULL, 3, '2026-05-05 14:05:00'),

  (@bidA, 8, 'graduate_outstanding', '2025-2026',
   '本人无违纪记录，专业课无不及格科目，担任班长两年，组织班级活动 8 次，希望在毕业前申请优秀毕业生。',
   '院级优秀学生干部 1 次',
   '无',
   68.00, 1, 1,
   '申请表已交学院，成绩单待补',
   '13806000005', 'draft', NULL, 2, NULL),

  (@bidA, 7, 'graduate_outstanding', '2025-2026',
   '本人无违纪记录，无不及格科目，参加省级程序设计竞赛获三等奖，综测排名班级第 6，申请优秀毕业生。',
   '省级程序设计竞赛三等奖 1 次',
   '无',
   66.00, 1, 1,
   '申请表与成绩单已交学院',
   '13806000004', 'rejected',
   '综测毕业排名为班级第 6 名（前 15% 之外），未达到《通知》第二条第 2 项要求，驳回后可在申报期内补齐材料重新提交。',
   2, '2026-05-04 16:40:00');

SET @aStu6 = (SELECT id FROM honor_application WHERE batch_id = @bidA AND student_id = 6);
SET @aStu4 = (SELECT id FROM honor_application WHERE batch_id = @bidA AND student_id = 4);
SET @aStu5 = (SELECT id FROM honor_application WHERE batch_id = @bidA AND student_id = 5);
SET @aStu8 = (SELECT id FROM honor_application WHERE batch_id = @bidA AND student_id = 8);
SET @aStu7 = (SELECT id FROM honor_application WHERE batch_id = @bidA AND student_id = 7);

-- 审核记录：student_id=4 已过辅导员 + 学院两关；student_id=5 已过辅导员；student_id=7 被辅导员驳回
INSERT INTO honor_review (application_id, node, verdict, comment, reviewer_id, reviewer_role, reviewed_at)
VALUES
  (@aStu4, 'counselor', 'pass', '该生在校四年无违纪记录，志愿服务与创新创业表现突出，材料齐全，同意推荐参加学院初评。', 3, 'counselor', '2026-05-07 09:20:00'),
  (@aStu4, 'college', 'pass', '学院初评小组已核验成绩单与获奖证书，均与申报信息一致，同意报学工部复核。', 3, 'college', '2026-05-12 15:40:00'),
  (@aStu5, 'counselor', 'pass', '综测排名班级第二，无不及格科目，省级竞赛获奖属实，同意推荐参加学院初评。', 3, 'counselor', '2026-05-08 11:05:00'),
  (@aStu7, 'counselor', 'reject', '综测毕业排名为班级第 6 名，未进入前 15%，不符合《通知》第二条第 2 项硬性条件，驳回。', 2, 'counselor', '2026-05-11 10:15:00');

-- ── 2. 批次 B：申报中（申请窗口开着，便于验收与演示全流程） ─────────────
INSERT INTO honor_batch
  (batch_name, honor_type, academic_year, eval_term, quota, quota_note, criteria, materials,
   apply_start, apply_end, review_end, publicity_days, status, notice_title, notice_url, created_by)
VALUES
  ('2026年三好学生评选（演示）', 'merit_student', '2026-2027', '2025-2026-1',
   NULL, '名额以上级下达指标为准；本条为演示数据',
   '1. 本学年无违法违纪记录，无不及格科目；\n2. 综合素质测评排名在班级前 30%；\n3. 体质健康测试成绩合格（≥50 分）。',
   '三好学生申请表；本学年成绩单；获奖证书复印件（校外奖项）',
   '2026-10-01 08:00:00', '2026-11-30 23:59:59', '2026-12-10 17:00:00',
   5, 'recruiting', '关于开展2026年三好学生评选工作的通知（演示）', NULL, 1);

-- ── 3. 自检：应打印两批、五条申报（五种状态各一）、四条审核记录 ──────────
SELECT id, batch_name, honor_type, status, apply_start, apply_end, eval_term, quota
  FROM honor_batch WHERE batch_name LIKE '%（演示）' ORDER BY id;

SELECT hb.batch_name, ha.student_id, u.username, ha.status, ha.counselor_id,
       ha.no_fail_declared, ha.no_violation_declared, ha.pe_score
  FROM honor_application ha
  JOIN honor_batch hb ON hb.id = ha.batch_id
  LEFT JOIN users u ON u.id = ha.student_id
 WHERE hb.batch_name LIKE '%（演示）' ORDER BY ha.batch_id, ha.student_id;

SELECT hb.batch_name, hr.application_id, hr.node, hr.verdict, hr.reviewer_id, LEFT(hr.comment, 24) AS comment_head
  FROM honor_review hr
  JOIN honor_application ha ON ha.id = hr.application_id
  JOIN honor_batch hb ON hb.id = ha.batch_id
 WHERE hb.batch_name LIKE '%（演示）' ORDER BY hr.id;

SELECT COUNT(*) AS batch_cnt, (SELECT COUNT(*) FROM honor_application ha JOIN honor_batch hb ON hb.id = ha.batch_id WHERE hb.batch_name LIKE '%（演示）') AS app_cnt,
       (SELECT COUNT(*) FROM honor_review hr JOIN honor_application ha ON ha.id = hr.application_id JOIN honor_batch hb ON hb.id = ha.batch_id WHERE hb.batch_name LIKE '%（演示）') AS review_cnt
  FROM honor_batch WHERE batch_name LIKE '%（演示）';
