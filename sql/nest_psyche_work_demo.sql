-- =============================================================================
-- 学工平台 · P14 心理健康演示数据（sql/nest_psyche_work_demo.sql）
--
-- 幂等：固定 id 区间（量表 9001-9009 / 批次 9101-9109 / 名单 91001-91099 /
--       作答 92001-92099 / 关注 93001-93099 / 跟进 94001-94099 /
--       预约 95001-95099 / 转介 96001-96099），先 DELETE 再 INSERT，可反复执行。
--
-- 时间全部相对 NOW() 生成 ⇒ 演示数据不会"过期"（预约窗口、普查窗口始终有效）。
--
-- 覆盖的演示场景（与《学工平台功能对齐说明》§9 的验收口径一一对应）：
--   · 量表：1 个「演示自评问卷」（非临床，可作答）+ SCL-90 / SDS 只留记录位（不内置题目）
--   · 批次：1 个进行中（2024 级、需抽查访谈）+ 1 个草稿（非 admin 应看不见）
--   · 名单：已完成 2 人（结果已由中心回填 normal / attention）、作答中 1 人、
--           未开始 1 人、未参加 1 人（S8：必须答完否则按未参加处理）
--   · 关注台账：重点关注 1 条（该生辅导员 3=lisi，已抽查访谈 + 2 条跟进流水）+ 一般关注 1 条
--   · 预约：已申请 1（面询）+ 已确认 1（线上）+ 已完成 1（50 分钟）+ 续约 1（S17 免预约程序）
--   · 转介：1 条转介到校内中心并跟踪中
--
-- 真实库事实（写入前已连库确认）：学生 users.id —— wangxm=4 / zhaoxh=5 / chendw=6（辅导员 3=lisi）；
--   liusq=7 / zhoujie=8（辅导员 2=zhangsan）；grade 列的现网写法是「2024级」而不是「2024」。
--
-- 执行：cmd /c "mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest -t < sql\nest_psyche_work_demo.sql"
-- =============================================================================

DELETE FROM psyche_followup     WHERE id BETWEEN 94001 AND 94099;
DELETE FROM psyche_answer       WHERE id BETWEEN 92001 AND 92099;
DELETE FROM psyche_referral     WHERE id BETWEEN 96001 AND 96099;
DELETE FROM psyche_appointment  WHERE id BETWEEN 95001 AND 95099;
DELETE FROM psyche_focus        WHERE id BETWEEN 93001 AND 93099;
DELETE FROM psyche_task         WHERE id BETWEEN 91001 AND 91099;
DELETE FROM psyche_batch        WHERE id BETWEEN 9101  AND 9109;
DELETE FROM psyche_instrument   WHERE id BETWEEN 9001  AND 9009;


-- -----------------------------------------------------------------------------
-- 1. 量表定义
-- -----------------------------------------------------------------------------
INSERT INTO psyche_instrument (id, code, name, source_note, item_json, band_json, item_count, guide_text, active, created_by) VALUES
(9001, 'demo-self-rating', '演示自评问卷',
 '平台自带的演示问卷：**非临床量表，不可用于诊断**，只用于演示作答/计分/分档链路。',
 '[{"no":1,"text":"最近两周，我感到情绪低落、提不起劲","options":[{"value":0,"label":"没有"},{"value":1,"label":"偶尔"},{"value":2,"label":"经常"},{"value":3,"label":"几乎每天"}]},{"no":2,"text":"最近两周，我对平时感兴趣的事失去兴趣","options":[{"value":0,"label":"没有"},{"value":1,"label":"偶尔"},{"value":2,"label":"经常"},{"value":3,"label":"几乎每天"}]},{"no":3,"text":"最近两周，我入睡困难或睡不安稳","options":[{"value":0,"label":"没有"},{"value":1,"label":"偶尔"},{"value":2,"label":"经常"},{"value":3,"label":"几乎每天"}]},{"no":4,"text":"最近两周，我容易紧张、心慌","options":[{"value":0,"label":"没有"},{"value":1,"label":"偶尔"},{"value":2,"label":"经常"},{"value":3,"label":"几乎每天"}]},{"no":5,"text":"最近两周，我注意力难以集中","options":[{"value":0,"label":"没有"},{"value":1,"label":"偶尔"},{"value":2,"label":"经常"},{"value":3,"label":"几乎每天"}]},{"no":6,"text":"最近两周，我感到孤单或与同学疏远","options":[{"value":0,"label":"没有"},{"value":1,"label":"偶尔"},{"value":2,"label":"经常"},{"value":3,"label":"几乎每天"}]},{"no":7,"text":"最近两周，我食欲明显变化","options":[{"value":0,"label":"没有"},{"value":1,"label":"偶尔"},{"value":2,"label":"经常"},{"value":3,"label":"几乎每天"}]},{"no":8,"text":"最近两周，我觉得自己没价值或让人失望","options":[{"value":0,"label":"没有"},{"value":1,"label":"偶尔"},{"value":2,"label":"经常"},{"value":3,"label":"几乎每天"}]},{"no":9,"text":"最近两周，我因担心而难以放松","options":[{"value":0,"label":"没有"},{"value":1,"label":"偶尔"},{"value":2,"label":"经常"},{"value":3,"label":"几乎每天"}]},{"no":10,"text":"最近两周，我的学习效率明显下降","options":[{"value":0,"label":"没有"},{"value":1,"label":"偶尔"},{"value":2,"label":"经常"},{"value":3,"label":"几乎每天"}]}]',
 '[{"band":"normal","max":10},{"band":"attention","max":18},{"band":"warning","max":30}]',
 10, '请按自己最近 2 周左右的实际情况填写（本题为演示问卷，非临床量表、不可用于诊断）。', 1, 1),
(9002, 'scl90', '症状自评量表（SCL-90）',
 '学校心理测评系统在用（S8 通知点名）。题本不在公开出处，**平台不内置题目**，仅登记结果。',
 NULL, NULL, 90, '题本与常模由学校测评系统提供，本平台不内置。', 0, 1),
(9003, 'sds', 'SDS 抑郁自评量表',
 '学校心理测评系统在用（S8 通知点名）。题本不在公开出处，**平台不内置题目**，仅登记结果。',
 NULL, NULL, 20, '题本与常模由学校测评系统提供，本平台不内置。', 0, 1);


-- -----------------------------------------------------------------------------
-- 2. 普查批次（1 个进行中 + 1 个草稿）
-- -----------------------------------------------------------------------------
INSERT INTO psyche_batch (id, batch_name, grade, academic_year, instrument_id, open_at, close_at,
                          interview_required, interview_note, notice_title, notice_url, status, published_at, created_by) VALUES
(9101, '2024 级学生心理健康普查（演示）', '2024级', '2026-2027', 9001,
 DATE_ADD(NOW(), INTERVAL -7 DAY), DATE_ADD(NOW(), INTERVAL 14 DAY),
 1, '普查结束后随机抽查一部分学生面对面访谈（S8）。',
 '关于开展 2024 级学生心理健康普查的通知（演示）',
 'https://sist.xujc.com/_t1746/2021/1228/c2212a134498/page.htm', 'open', DATE_ADD(NOW(), INTERVAL -7 DAY), 1),
(9102, '2026 级新生心理普查（演示·草稿）', '2026级', '2026-2027', 9002,
 DATE_ADD(NOW(), INTERVAL 30 DAY), DATE_ADD(NOW(), INTERVAL 50 DAY),
 0, NULL, NULL, NULL, 'draft', NULL, 1);


-- -----------------------------------------------------------------------------
-- 3. 名单与进度（5 名学生）
-- -----------------------------------------------------------------------------
INSERT INTO psyche_task (id, batch_id, student_id, status, answer_count, total_items, score_total, score_band,
                         result_level, result_note, result_by, interviewed, interview_note, interviewed_by, interviewed_at,
                         started_at, submitted_at, duration_seconds) VALUES
(91001, 9101, 4, 'completed', 10, 10, 12.00, 'attention', 'attention',
 '中心回填：演示结果，建议辅导员关注并与学生保持联系。', 3, 1,
 '已面谈：情绪平稳，近期因课程压力睡眠偏晚。', 3, DATE_ADD(NOW(), INTERVAL -2 DAY),
 DATE_ADD(NOW(), INTERVAL -3 DAY), DATE_ADD(NOW(), INTERVAL -3 DAY), 420),
(91002, 9101, 5, 'completed', 10, 10, 6.00, 'normal', 'normal',
 '中心回填：演示结果，无异常。', 3, 0, NULL, NULL, NULL,
 DATE_ADD(NOW(), INTERVAL -3 DAY), DATE_ADD(NOW(), INTERVAL -3 DAY), 300),
(91003, 9101, 6, 'in_progress', 4, 10, NULL, NULL, NULL, NULL, NULL, 0, NULL, NULL, NULL,
 DATE_ADD(NOW(), INTERVAL -1 DAY), NULL, NULL),
(91004, 9101, 7, 'pending', 0, 10, NULL, NULL, NULL, NULL, NULL, 0, NULL, NULL, NULL,
 NULL, NULL, NULL),
(91005, 9101, 8, 'absent', 0, 10, NULL, NULL, NULL,
 '未参加：按 S8「必须完成所有题目，否则测试无效，按未参加处理」置位（演示数据）。', NULL, 0, NULL, NULL, NULL,
 DATE_ADD(NOW(), INTERVAL -2 DAY), NULL, NULL);


-- -----------------------------------------------------------------------------
-- 4. 整卷作答（仅已完成的两条）
-- -----------------------------------------------------------------------------
INSERT INTO psyche_answer (id, task_id, student_id, answer_json, answer_count, total_items, score_total, score_band, duration_seconds, submitted_at) VALUES
(92001, 91001, 4, '[{"no":1,"value":2},{"no":2,"value":1},{"no":3,"value":2},{"no":4,"value":1},{"no":5,"value":2},{"no":6,"value":1},{"no":7,"value":1},{"no":8,"value":1},{"no":9,"value":1},{"no":10,"value":0}]',
 10, 10, 12.00, 'attention', 420, DATE_ADD(NOW(), INTERVAL -3 DAY)),
(92002, 91002, 5, '[{"no":1,"value":1},{"no":2,"value":0},{"no":3,"value":1},{"no":4,"value":1},{"no":5,"value":1},{"no":6,"value":0},{"no":7,"value":1},{"no":8,"value":1},{"no":9,"value":0},{"no":10,"value":0}]',
 10, 10, 6.00, 'normal', 300, DATE_ADD(NOW(), INTERVAL -3 DAY));


-- -----------------------------------------------------------------------------
-- 5. 关注台账（辅导员的真实归属：4/5/6 → 3=lisi；7/8 → 2=zhangsan）
-- -----------------------------------------------------------------------------
INSERT INTO psyche_focus (id, student_id, batch_id, level, source, source_id, summary, measures,
                          counselor_id, status, opened_at, created_by) VALUES
(93001, 4, 9101, 'key', 'task', 91001,
 '普查结果提示需关注（演示）：情绪偏低、睡眠偏晚。',
 '已纳入抽查访谈，建立跟进台账；建议继续观察并按需转介中心。',
 3, 'following', DATE_ADD(NOW(), INTERVAL -2 DAY), 3),
(93002, 7, NULL, 'general', 'apply', NULL,
 '学生主动求助（演示）：入学适应与作息问题。',
 '已电话沟通，约定面谈时间。',
 2, 'open', DATE_ADD(NOW(), INTERVAL -5 DAY), 2);


-- -----------------------------------------------------------------------------
-- 6. 关注台账跟进流水
-- -----------------------------------------------------------------------------
INSERT INTO psyche_followup (id, focus_id, content, measures, next_plan, operator_id, happened_at) VALUES
(94001, 93001, '首次面谈：情绪平稳，主诉课程压力与晚睡。', '建议规律作息、减少晚间屏幕时间。', '两周后回访。', 3, DATE_ADD(NOW(), INTERVAL -2 DAY)),
(94002, 93001, '电话回访：作息已调整，状态良好。', '保持现有节奏，必要时联系心理中心。', '一个月后回访，若无异常则结案。', 3, DATE_ADD(NOW(), INTERVAL -1 DAY));


-- -----------------------------------------------------------------------------
-- 7. 预约咨询（已申请 / 已确认（线上）/ 已完成（50 分钟）/ 续约）
-- -----------------------------------------------------------------------------
INSERT INTO psyche_appointment (id, student_id, counselor_id, mode, main_issue, contact_phone,
                                expect_start, expect_end, duration_minutes, status,
                                confirmed_at, confirmed_by, finished_at, cancel_reason, renewed_from, note) VALUES
(95001, 4, 3, 'office', '演示：学业压力与睡眠问题，希望面谈一次。', '13800000004',
 DATE_ADD(NOW(), INTERVAL 3 DAY), DATE_ADD(DATE_ADD(NOW(), INTERVAL 3 DAY), INTERVAL 50 MINUTE), 50, 'requested',
 NULL, NULL, NULL, NULL, NULL, NULL),
(95002, 5, 3, 'online', '演示：人际沟通困扰，希望线上咨询。', '13800000005',
 DATE_ADD(NOW(), INTERVAL 1 DAY), DATE_ADD(DATE_ADD(NOW(), INTERVAL 1 DAY), INTERVAL 50 MINUTE), 50, 'confirmed',
 NOW(), 3, NULL, NULL, NULL, '已按 S17「提前半天短信确认」确认。'),
(95003, 7, 2, 'office', '演示：入学适应问题（已完成一次 50 分钟面询）。', '13800000007',
 DATE_ADD(NOW(), INTERVAL -7 DAY), DATE_ADD(DATE_ADD(NOW(), INTERVAL -7 DAY), INTERVAL 50 MINUTE), 50, 'done',
 DATE_ADD(NOW(), INTERVAL -8 DAY), 2, DATE_ADD(NOW(), INTERVAL -7 DAY), NULL, NULL, NULL),
(95004, 7, 2, 'office', '演示：续约（S17：可直接向咨询师申请，免去网上预约程序）。', '13800000007',
 DATE_ADD(NOW(), INTERVAL 10 DAY), DATE_ADD(DATE_ADD(NOW(), INTERVAL 10 DAY), INTERVAL 50 MINUTE), 50, 'requested',
 NULL, NULL, NULL, NULL, 95003, '由已完成预约 95003 续约生成。');


-- -----------------------------------------------------------------------------
-- 8. 转介登记与跟踪
-- -----------------------------------------------------------------------------
INSERT INTO psyche_referral (id, student_id, source_type, source_id, target, reason, referred_by, referred_at, status, follow_note) VALUES
(96001, 4, 'focus', 93001, 'campus',
 '演示：关注台账升级至校内心理健康服务中心跟进（主5#118，预约电话 0596-6288525）。',
 3, DATE_ADD(NOW(), INTERVAL -1 DAY), 'following',
 '中心已接手，辅导员配合跟踪；下一次回访安排在下周。');


-- =============================================================================
-- 自检
-- =============================================================================
SELECT '1) 各表行数' AS item;
SELECT
  (SELECT COUNT(*) FROM psyche_instrument)  AS instrument_rows,
  (SELECT COUNT(*) FROM psyche_batch)       AS batch_rows,
  (SELECT COUNT(*) FROM psyche_task)        AS task_rows,
  (SELECT COUNT(*) FROM psyche_answer)      AS answer_rows,
  (SELECT COUNT(*) FROM psyche_focus)       AS focus_rows,
  (SELECT COUNT(*) FROM psyche_followup)    AS followup_rows,
  (SELECT COUNT(*) FROM psyche_appointment) AS appointment_rows,
  (SELECT COUNT(*) FROM psyche_referral)    AS referral_rows;

SELECT '2) 批次与名单概览' AS item;
SELECT b.id AS batch_id, b.batch_name, b.grade, b.status, COUNT(t.id) AS tasks
  FROM psyche_batch b LEFT JOIN psyche_task t ON t.batch_id = b.id AND t.deleted = 0
 WHERE b.id IN (9101, 9102) GROUP BY b.id, b.batch_name, b.grade, b.status;

SELECT '3) 名单状态分布（批次 9101）' AS item;
SELECT status, COUNT(*) AS cnt FROM psyche_task WHERE batch_id = 9101 AND deleted = 0 GROUP BY status;

SELECT '4) 预约状态与时长' AS item;
SELECT id, student_id, counselor_id, mode, status, duration_minutes,
       DATE_FORMAT(expect_start, '%Y-%m-%d %H:%i') AS expect_start, renewed_from
  FROM psyche_appointment WHERE id BETWEEN 95001 AND 95004 ORDER BY id;

SELECT '5) 关注等级分布' AS item;
SELECT level, status, COUNT(*) AS cnt FROM psyche_focus WHERE deleted = 0 GROUP BY level, status;
