-- =============================================================================
-- NEST · P18 第二课堂成长手册 —— 演示数据（可选，可重复执行）
--
-- 用途：把「第二课堂」四个页面的空状态填满，方便验收时一眼看到成绩单、
--       申报台账、审核列表与导出的真实形态。
--
-- 口径声明（重要，别被这文件误导）：
--   1) 这是**演示数据**，不是学校真实口径。板块名称用的是「共青团中央第二课堂
--      成绩单」体系里最常见的六个板块叫法，**学时要求 / 学年上限 / 学分系数
--      一律留 NULL（= 未设置）**——数值必须由学校自己定，本文件不替学校拍脑袋。
--   2) 活动与申报记录都是编的（主办单位、日期、学时只为演示流程），
--      真实使用前请先跑 sql/clean-secondclass-demo.sql 清空，或直接按前缀删。
--   3) id 段 93001–93099 为本文件专用，与业务自增 id 不冲突；清理脚本按 id 段删。
--
-- 依赖：先执行 sql/nest_secondclass.sql（建表）。
-- 执行：cmd /c "mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest < sql\nest_secondclass_demo.sql"
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 0) 幂等：先清掉本文件上一次插入的行（按 id 段）
-- ---------------------------------------------------------------------------
DELETE FROM second_class_record   WHERE id BETWEEN 93001 AND 93099;
DELETE FROM second_class_activity WHERE id BETWEEN 93001 AND 93099;
DELETE FROM second_class_category WHERE id BETWEEN 93001 AND 93099;

-- ---------------------------------------------------------------------------
-- 1) 板块（6 个，阈值全留空 = 未设置，页面显示「未设置」而不是 0）
-- ---------------------------------------------------------------------------
INSERT INTO second_class_category (id, name, code, sort_no, required_hours, yearly_cap_hours, credit_per_hour, note, enabled, deleted) VALUES
 (93001, '思想成长', 'demo-ideology',  10, NULL, NULL, NULL, '主题教育、党校团校、理论宣讲等（演示板块，口径由学校自定）', 1, 0),
 (93002, '志愿公益', 'demo-volunteer', 20, NULL, NULL, NULL, '志愿服务、公益活动、支教助残等（演示板块）', 1, 0),
 (93003, '实践实习', 'demo-practice',  30, NULL, NULL, NULL, '社会实践、实习实训、社会调研等（演示板块）', 1, 0),
 (93004, '创新创业', 'demo-innovation', 40, NULL, NULL, NULL, '学科竞赛、创新创业项目、专利软著等（演示板块）', 1, 0),
 (93005, '文体活动', 'demo-culture',   50, NULL, NULL, NULL, '文艺演出、体育竞赛、社团活动等（演示板块）', 1, 0),
 (93006, '工作履历', 'demo-work',      55, NULL, NULL, NULL, '学生干部任职、勤工助学、社会实践组织工作等（演示板块）', 1, 0),
 (93007, '技能特长', 'demo-skill',     60, NULL, NULL, NULL, '职业技能证书、普通话、外语等级等（演示板块）', 1, 0);

-- ---------------------------------------------------------------------------
-- 2) 活动库（5 条：4 条已发布、1 条已结束，覆盖三种 level）
--    status: draft 草稿 / published 已发布（学生可申报）/ closed 已结束
-- ---------------------------------------------------------------------------
INSERT INTO second_class_activity
  (id, category_id, title, organizer, level, start_date, end_date, hours, credit, location, description, status, created_by, deleted) VALUES
 (93001, 93001, '新生入学教育主题讲座', '学生工作部', 'school',  '2026-09-12', '2026-09-12', 2.00, NULL, '主楼报告厅', '签到入场，讲座结束后提交 300 字心得（演示活动）', 'published', 1, 0),
 (93002, 93002, '校园开放日志愿服务', '校青年志愿者协会', 'school', '2026-10-18', '2026-10-19', 8.00, NULL, '校内各引导点', '分两个半天，需全程在岗（演示活动）', 'published', 1, 0),
 (93003, 93003, '暑期社会实践成果汇报', '团委实践部', 'college', '2026-10-25', '2026-10-25', 4.00, NULL, '各院系会议室', '提交实践报告并现场汇报（演示活动）', 'published', 1, 0),
 (93004, 93005, '院系篮球联赛', '体育教学部', 'college', '2026-11-02', '2026-11-20', 6.00, NULL, '东区篮球场', '按队伍报名，缺赛不计学时（演示活动）', 'published', 1, 0),
 (93005, 93006, '普通话水平测试考前培训', '教务处', 'school', '2026-09-20', '2026-09-21', 3.00, NULL, '实训楼 A305', '已结束，仅作历史演示', 'closed', 1, 0);

-- ---------------------------------------------------------------------------
-- 3) 申报记录（4 条，覆盖 confirmed / counselor_approved / submitted / returned）
--    user_id：4=wangxm 5=zhaoxh 6=chendw 7=liusq 8=zhoujie（password 都是 123456）
--    status：submitted / counselor_approved / confirmed / returned / rejected / withdrawn
--    activity_id 为 NULL 表示学生自主申报（无活动载体）
-- ---------------------------------------------------------------------------
INSERT INTO second_class_record
  (id, activity_id, category_id, user_id, title_snapshot, hours, credit, evidence_url, note, status, return_reason, submitted_at, counselor_id, counselor_at, reviewer_id, reviewed_at, deleted) VALUES
 (93001, 93001, 93001, 6, '新生入学教育主题讲座', 2.00, NULL, 'https://example.edu/evidence/demo-93001.pdf', '已提交心得', 'confirmed', NULL, '2026-09-13 10:00:00', 3, '2026-09-14 09:00:00', 1, '2026-09-15 16:00:00', 0),
 (93002, 93002, 93002, 6, '校园开放日志愿服务', 8.00, NULL, 'https://example.edu/evidence/demo-93002.pdf', '两个半天全勤', 'counselor_approved', NULL, '2026-10-20 08:30:00', 3, '2026-10-21 14:00:00', NULL, NULL, 0),
 (93003, NULL,  93004, 7, '校级程序设计竞赛三等奖', 4.00, NULL, 'https://example.edu/evidence/demo-93003.pdf', '获奖证书已上传（自主申报演示）', 'submitted', NULL, '2026-10-22 19:10:00', NULL, NULL, NULL, NULL, 0),
 (93004, 93003, 93003, 8, '暑期社会实践成果汇报', 4.00, NULL, 'https://example.edu/evidence/demo-93004.pdf', '报告已交，材料缺签章', 'returned', '佐证材料缺少实践单位签章，请补传后重交', '2026-10-26 09:00:00', 3, '2026-10-27 11:20:00', NULL, NULL, 0);

-- ---------------------------------------------------------------------------
-- 4) 自检：插完打印一遍，方便肉眼确认
-- ---------------------------------------------------------------------------
SELECT '--- 演示板块 ---' AS t;
SELECT id, code, name, required_hours, yearly_cap_hours, credit_per_hour, enabled FROM second_class_category WHERE id BETWEEN 93001 AND 93099 ORDER BY sort_no;
SELECT '--- 演示活动 ---' AS t;
SELECT id, category_id, title, level, status, hours, start_date FROM second_class_activity WHERE id BETWEEN 93001 AND 93099 ORDER BY id;
SELECT '--- 演示申报 ---' AS t;
SELECT id, activity_id, category_id, user_id, title_snapshot, hours, status FROM second_class_record WHERE id BETWEEN 93001 AND 93099 ORDER BY id;
