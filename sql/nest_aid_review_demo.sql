-- =============================================================================
-- P11 · 家庭经济困难学生认定 · 演示数据（可反复执行）
-- -----------------------------------------------------------------------------
-- 目的：造 4 条认定数据，覆盖 4 个不同阶段，其中至少 1 条「公示中」留给前端与 E2E：
--   9001  wangxm(4)  committee  待民主评议（无级别、无公示）
--   9002  zhaoxh(5)  publicity  公示中（objection_deadline = 未来 4 天）★E2E/前端用
--   9003  liusq(7)   approved   已定级 特困（2025-2026 学年，already 认定完毕）
--   9004  zhoujie(8) objection  复议处理中（公示期已过，objection_deadline = 昨天）
--   9005  chendw(6)  publicity  公示已过期（publicity_end = objection_deadline = 昨天）
--        ★「公示期外提复议 → 400」的专用夹具（stage 仍停在 publicity，未发布过复议）
--        9101  挂在 9004 上的复议记录（status=pending，答复截止 = 未来）
--   「公示已过期」共两条（9004/9005），「公示中」共一条（9002），供 E2E 双向断言。
--
-- 幂等做法：固定 id（9001+ / 9101+）+ 先 DELETE 同 id 再 INSERT。
--   ⇒ 连跑两次，分组计数完全一致，且 id 稳定（不依赖 AUTO_INCREMENT）。
--   9001+ 段位远离真实数据（当前 aid_application 最大 id = 12），不会误删业务数据。
--
-- 辅导员分派（沿用 sql\nest_xuegong_demo.sql）：
--   lisi(3)  → wangxm(4) zhaoxh(5) chendw(6)
--   zhangsan(2) → liusq(7) zhoujie(8)
--
-- 执行：cmd /c "mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest -t < sql\nest_aid_review_demo.sql"
-- =============================================================================

USE nest;

-- ------------------------------------------------------------
-- 0. 公示接口要露「姓名/性别/级别」，先补齐演示学生的性别
--    （仅当列为空才写，不覆盖管理员录入过的真实性别）
-- ------------------------------------------------------------
UPDATE student_profile p
JOIN users u ON u.id = p.user_id
SET p.gender = CASE u.username
                 WHEN 'wangxm' THEN 'male'
                 WHEN 'zhaoxh' THEN 'female'
                 WHEN 'chendw' THEN 'male'
                 WHEN 'liusq'  THEN 'female'
                 WHEN 'zhoujie' THEN 'male'
                 ELSE p.gender
               END
WHERE u.deleted = 0
  AND u.username IN ('wangxm', 'zhaoxh', 'chendw', 'liusq', 'zhoujie')
  AND (p.gender IS NULL OR p.gender = '');

-- ------------------------------------------------------------
-- 1. 清掉上一次演示（先子表后主表，只动 9001+ 段位）
-- ------------------------------------------------------------
DELETE FROM aid_objection WHERE id = 9101 OR application_id IN (9001, 9002, 9003, 9004, 9005);
DELETE FROM aid_application WHERE id IN (9001, 9002, 9003, 9004, 9005);

-- ------------------------------------------------------------
-- 2. 四条认定数据
-- ------------------------------------------------------------

-- 9001 待民主评议：学生已提交，民主评议小组尚未开会
INSERT INTO aid_application
  (id, student_id, aid_type, level, poverty_level, amount, reason, evidence, materials, family_note,
   status, approver_id, approve_note, academic_year, cert_year,
   review_stage, committee_note, report_note,
   publicity_start, publicity_end, objection_deadline,
   recheck_result, rechecked_at, created_at, updated_at, deleted)
VALUES
  (9001, 4, 'need', NULL, NULL, NULL,
   '家庭收入低于当地低保线，父亲务农，母亲长期患病需常年服药，申请家庭经济困难认定。',
   '低保证明|家庭收入证明|母亲诊断证明|村委会困难证明',
   '["低保证明（村委会盖章）","家庭收入证明（近 6 个月银行流水）","母亲诊断证明（县医院）","村委会困难情况证明"]',
   '户籍农村，家庭 4 口人，年收入约 1.2 万元，人均低于当地低保线。',
   'pending', NULL, NULL, '2026-2027', '2026-2027',
   'committee', NULL, NULL,
   NULL, NULL, NULL,
   NULL, NULL, NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 2 DAY, 0);

-- 9002 公示中 ★：公示期还剩 4 天，公示接口与复议提交都能用它
INSERT INTO aid_application
  (id, student_id, aid_type, level, poverty_level, amount, reason, evidence, materials, family_note,
   status, approver_id, approve_note, academic_year, cert_year,
   review_stage, committee_note, report_note,
   publicity_start, publicity_end, objection_deadline,
   recheck_result, rechecked_at, created_at, updated_at, deleted)
VALUES
  (9002, 5, 'need', 'general', '贫困', NULL,
   '父母双双下岗，姐姐在读大学，家庭仅靠父亲打零工维持，申请困难认定。',
   '下岗证明|收入证明|在读证明|社区困难证明',
   '["下岗证明（原单位）","父亲收入证明","姐姐在读证明","社区困难情况证明"]',
   '城镇户口，父母均下岗，家庭月收入约 2600 元，需同时供两名大学生。',
   'pending', NULL, NULL, '2026-2027', '2026-2027',
   'publicity',
   '民主评议小组 7 人应到 7 人，实到 7 人；申请人陈述属实，全票通过，结论 pass。',
   NULL,
   NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 4 DAY, NOW() + INTERVAL 4 DAY,
   NULL, NULL, NOW() - INTERVAL 4 DAY, NOW() - INTERVAL 1 DAY, 0);

-- 9003 已定级（上一认定学年走完全流程：特困）
INSERT INTO aid_application
  (id, student_id, aid_type, level, poverty_level, amount, reason, evidence, materials, family_note,
   status, approver_id, approve_note, academic_year, cert_year,
   review_stage, committee_note, report_note,
   publicity_start, publicity_end, objection_deadline,
   recheck_result, rechecked_at, created_at, updated_at, deleted)
VALUES
  (9003, 7, 'need', 'special', '特困', NULL,
   '孤儿，由祖父母抚养，无固定经济来源，申请家庭经济困难认定。',
   '孤儿证明|低保证明|祖父母收入证明|村委困难证明',
   '["孤儿证明（民政部门）","低保证明","祖父母收入证明","村委会困难证明"]',
   '由年迈祖父母抚养，家庭无劳动力，年收入不足 6000 元。',
   'approved', 2, '情况属实，认定为特困，已报学生资助管理中心与领导小组审批。',
   '2025-2026', '2025-2026',
   'approved',
   '民主评议小组 9 人全到，全票通过，结论 pass。',
   '经院系认定工作组审核，该生家庭经济特别困难，建议认定为特困等级；公示 5 个工作日无异议，报资助中心审核、领导小组审批通过。',
   NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 23 DAY, NOW() - INTERVAL 23 DAY,
   NULL, NULL, NOW() - INTERVAL 40 DAY, NOW() - INTERVAL 20 DAY, 0);

-- 9004 复议处理中：公示期已结束（截止 = 昨天），挂着一条待答复的复议
INSERT INTO aid_application
  (id, student_id, aid_type, level, poverty_level, amount, reason, evidence, materials, family_note,
   status, approver_id, approve_note, academic_year, cert_year,
   review_stage, committee_note, report_note,
   publicity_start, publicity_end, objection_deadline,
   recheck_result, rechecked_at, created_at, updated_at, deleted)
VALUES
  (9004, 8, 'need', 'general', '贫困', NULL,
   '家庭主要劳动力患重病，医疗支出大，申请家庭经济困难认定。',
   '诊断证明|医疗费用清单|收入证明|村委会证明',
   '["父亲诊断证明","医疗费用清单（近一年）","家庭收入证明","村委会困难证明"]',
   '父亲患慢性病需长期治疗，家庭年医疗支出约 4 万元。',
   'pending', NULL, NULL, '2026-2027', '2026-2027',
   'objection',
   '民主评议小组 7 人全到，同意认定，结论 pass。',
   NULL,
   NOW() - INTERVAL 8 DAY, NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 1 DAY,
   NULL, NULL, NOW() - INTERVAL 12 DAY, NOW() - INTERVAL 1 DAY, 0);

-- 9005 公示已过期 ★：stage 停在 publicity，公示窗口整体在过去
--   ⇒ E2E 用它断言「公示期外提交复议 → 400（公示期已结束）」
INSERT INTO aid_application
  (id, student_id, aid_type, level, poverty_level, amount, reason, evidence, materials, family_note,
   status, approver_id, approve_note, academic_year, cert_year,
   review_stage, committee_note, report_note,
   publicity_start, publicity_end, objection_deadline,
   recheck_result, rechecked_at, created_at, updated_at, deleted)
VALUES
  (9005, 6, 'need', 'difficult', '贫困', NULL,
   '家庭收入低于当地低保线，母亲务农，家庭负担两名在读学生，申请家庭经济困难认定。',
   '低保证明|家庭收入证明|在读证明|村委会困难证明',
   '["低保证明（村委会盖章）","家庭收入证明（近 6 个月）","在读证明","村委会困难情况证明"]',
   '户籍农村，家庭 5 口人，年收入约 1.5 万元，人均低于当地低保线。',
   'pending', NULL, NULL, '2026-2027', '2026-2027',
   'publicity',
   '民主评议小组 7 人全到，申请人陈述属实，同意认定，结论 pass。',
   NULL,
   NOW() - INTERVAL 10 DAY, NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 3 DAY,
   NULL, NULL, NOW() - INTERVAL 15 DAY, NOW() - INTERVAL 3 DAY, 0);

-- ------------------------------------------------------------
-- 3. 复议记录（挂在 9004 上；答复截止 = 提交 + 7 天，演示用约数）
-- ------------------------------------------------------------
INSERT INTO aid_objection
  (id, application_id, student_id, objector_id, objector_name, content, evidence,
   status, answer, answered_by, deadline, answered_at, created_at, updated_at, deleted)
VALUES
  (9101, 9004, 8, 8, '周杰',
   '公示名单中我的困难等级为「贫困」，但我父亲今年确诊重病、医疗支出已超家庭年收入，申请复核并调整为「特困」。',
   '父亲住院病历与近一年医疗费用清单（复印件已交院系认定工作组）',
   'pending', NULL, NULL, NOW() + INTERVAL 6 DAY, NULL,
   NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 1 DAY, 0);

-- ------------------------------------------------------------
-- 4. 自检输出
-- ------------------------------------------------------------
SELECT '4.1 演示认定数据' AS 检查项, d.id, u.username, u.real_name,
       IFNULL(d.cert_year, '(NULL)') AS cert_year, d.review_stage,
       IFNULL(d.poverty_level, '(NULL)') AS poverty_level, d.status,
       DATE_FORMAT(d.publicity_start, '%Y-%m-%d %H:%i') AS pub_start,
       DATE_FORMAT(d.publicity_end, '%Y-%m-%d %H:%i') AS pub_end,
       DATE_FORMAT(d.objection_deadline, '%Y-%m-%d %H:%i') AS obj_deadline
FROM aid_application d
LEFT JOIN users u ON u.id = d.student_id
WHERE d.id IN (9001, 9002, 9003, 9004, 9005)
ORDER BY d.id;

SELECT '4.2 按 review_stage 分组（全部认定数据）' AS 检查项,
       IFNULL(review_stage, '(NULL)') AS review_stage, COUNT(*) AS cnt
FROM aid_application
WHERE deleted = 0 AND aid_type IN ('need', '困难认定')
GROUP BY review_stage
ORDER BY review_stage;

SELECT '4.3 公示中（objection_deadline >= NOW()）' AS 检查项, COUNT(*) AS cnt
FROM aid_application
WHERE deleted = 0 AND aid_type IN ('need', '困难认定')
  AND review_stage IN ('publicity', 'objection')
  AND objection_deadline IS NOT NULL AND objection_deadline >= NOW();

SELECT '4.3b 公示已过期（objection_deadline < NOW()）' AS 检查项, COUNT(*) AS cnt
FROM aid_application
WHERE deleted = 0 AND aid_type IN ('need', '困难认定')
  AND review_stage IN ('publicity', 'objection')
  AND objection_deadline IS NOT NULL AND objection_deadline < NOW();

SELECT '4.4 公示接口三条脱敏字段预览' AS 检查项,
       COALESCE(NULLIF(u.real_name, ''), u.nickname, u.username) AS name,
       IFNULL(sp.gender, '') AS gender, d.poverty_level AS level
FROM aid_application d
LEFT JOIN users u ON u.id = d.student_id AND u.deleted = 0
LEFT JOIN student_profile sp ON sp.user_id = d.student_id AND sp.deleted = 0
WHERE d.id IN (9001, 9002, 9003, 9004, 9005)
ORDER BY d.id;

SELECT '4.5 aid_objection 行数' AS 检查项, COUNT(*) AS cnt FROM aid_objection WHERE deleted = 0;

SELECT '4.6 复议记录' AS 检查项, id, application_id, objector_name, status,
       DATE_FORMAT(deadline, '%Y-%m-%d %H:%i') AS deadline
FROM aid_objection WHERE id = 9101;
