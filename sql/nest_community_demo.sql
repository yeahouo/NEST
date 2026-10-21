-- =============================================================================
-- 学工平台 · P15 社区管理 · 演示数据（sql/nest_community_demo.sql）
--
-- 幂等：全部使用固定 id 区间（community_batch 9001-9099 / community_inspection 9101-9199 /
--       community_late 9201-9299 / community_violation 9301-9399 / community_activity 9401-9499 /
--       community_activity_signup 9501-9599 / community_civil_room 9601-9699），
--       文件开头先按区间 DELETE 再 INSERT，可反复执行。
-- 时间：一律相对 NOW() 生成（DATE_ADD/DATE_SUB），演示数据不会随时间过期失效。
--
-- 覆盖的演示场景：
--   · 4 个检查批次：1 已公布（published，供文明宿舍汇总）+ 1 检查中（checking）
--     + 1 已结束（finished，上学期）+ 1 草稿（draft，第二学期）
--   · 10 条房间检查记录（6 间宿舍）：含 1 条已复查通过（accepted）、1 条复查驳回（rejected）、
--     1 条待整改（pending），覆盖 excellent/good/pass/fail 四个等级
--   · 5 条晚归/夜不归宿（late 4 + absent 1），覆盖 card/check/report 三种来源
--   · 4 条违规台账，全部带 rule_ref（10.2-4：依据条文必填）
--   · 4 个活动（finished/signup/ongoing/draft）+ 6 条报名（含 attended 与 award；9402 名额 2 已满）
--   · 6 条 2025-2026-1 学期文明宿舍结果（含 2 条 awarded 已评）
--
-- 真实库事实（写入前已连库 SELECT 确认）：
--   users.id：1 系统管理员(admin)、2 张三(teacher)、3 李四(teacher)、
--             4 王小明(2024001/A 栋/A-305/counselor 3)、5 赵小红(2024002/A 栋/A-305/counselor 3)、
--             6 陈大伟(2024003/A 栋/A-412/counselor 3)、7 刘思琪(2024004/B 栋/B-201/counselor 2)、
--             8 周杰(2024005/B 栋/B-201/counselor 2)
--   学期字符串沿用库中既有口径：2025-2026-1 / 2025-2026-2 / 2024-2025-2
--   楼栋与房间号沿用 student_profile.dorm_building / dorm_room 原值（「A 栋」「B 栋」带空格，房间号如 A-305）
--
-- 口径标注（10.2-2）：criteria_json 里的五项各 20 分是**平台自带演示评分项，非学校发布口径**，
--   学校可在批次上自行录入 criteria_json 覆盖。
--
-- 执行：
--   cmd /c "mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest -t < sql\nest_community_demo.sql"
-- =============================================================================

-- 先清演示区间（子表在前）
DELETE FROM community_activity_signup WHERE id BETWEEN 9501 AND 9599;
DELETE FROM community_violation       WHERE id BETWEEN 9301 AND 9399;
DELETE FROM community_inspection      WHERE id BETWEEN 9101 AND 9199;
DELETE FROM community_late            WHERE id BETWEEN 9201 AND 9299;
DELETE FROM community_civil_room      WHERE id BETWEEN 9601 AND 9699;
DELETE FROM community_activity        WHERE id BETWEEN 9401 AND 9499;
DELETE FROM community_batch           WHERE id BETWEEN 9001 AND 9099;


-- -----------------------------------------------------------------------------
-- 1. 检查批次（4 个）
-- -----------------------------------------------------------------------------
INSERT INTO community_batch
    (id, batch_name, term, check_type, scope_building, start_date, end_date, publish_date, criteria_json, status, note, created_by, created_at, updated_at, deleted)
VALUES
    (9001, '2025-2026 学年第一学期宿舍卫生检查', '2025-2026-1', 'hygiene', NULL,
     DATE(DATE_SUB(NOW(), INTERVAL 30 DAY)), DATE(DATE_SUB(NOW(), INTERVAL 20 DAY)), DATE_SUB(NOW(), INTERVAL 30 DAY),
     '[{"key":"power","name":"用电安全","max":20,"ruleRef":"园区管理规定第十条"},{"key":"fire","name":"消防通道","max":20,"ruleRef":"学生宿舍消防安全管理规定第五条"},{"key":"hygiene","name":"内务卫生","max":20,"ruleRef":"学生住宿管理规定第十五条"},{"key":"property","name":"财物与家具","max":20,"ruleRef":"学生住宿管理规定第十八条"},{"key":"conduct","name":"文明行为","max":20,"ruleRef":"学生住宿管理规定第二十条"}]',
     'published', '演示评分项，非学校发布口径；已公布，供文明宿舍汇总用', 1,
     DATE_SUB(NOW(), INTERVAL 30 DAY), DATE_SUB(NOW(), INTERVAL 20 DAY), 0),
    (9002, '2025-2026 学年第一学期宿舍安全检查（A 栋）', '2025-2026-1', 'safety', 'A 栋',
     DATE(DATE_SUB(NOW(), INTERVAL 5 DAY)), DATE(DATE_ADD(NOW(), INTERVAL 5 DAY)), NULL,
     '[{"key":"power","name":"用电安全","max":20,"ruleRef":"园区管理规定第十条"},{"key":"fire","name":"消防通道","max":20,"ruleRef":"学生宿舍消防安全管理规定第五条"},{"key":"hygiene","name":"内务卫生","max":20,"ruleRef":"学生住宿管理规定第十五条"},{"key":"property","name":"财物与家具","max":20,"ruleRef":"学生住宿管理规定第十八条"},{"key":"conduct","name":"文明行为","max":20,"ruleRef":"学生住宿管理规定第二十条"}]',
     'checking', '演示评分项，非学校发布口径；检查中，尚未公布故不参与文明宿舍汇总', 2,
     DATE_SUB(NOW(), INTERVAL 5 DAY), NOW(), 0),
    (9003, '2025-2026 学年第二学期宿舍卫生检查（草案）', '2025-2026-2', 'joint', NULL,
     DATE(DATE_ADD(NOW(), INTERVAL 30 DAY)), DATE(DATE_ADD(NOW(), INTERVAL 40 DAY)), NULL,
     '[{"key":"power","name":"用电安全","max":20,"ruleRef":"园区管理规定第十条"},{"key":"fire","name":"消防通道","max":20,"ruleRef":"学生宿舍消防安全管理规定第五条"},{"key":"hygiene","name":"内务卫生","max":20,"ruleRef":"学生住宿管理规定第十五条"},{"key":"property","name":"财物与家具","max":20,"ruleRef":"学生住宿管理规定第十八条"},{"key":"conduct","name":"文明行为","max":20,"ruleRef":"学生住宿管理规定第二十条"}]',
     'draft', '演示评分项，非学校发布口径；草稿批次不可录入检查记录', 1,
     DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 0),
    (9004, '2024-2025 学年第二学期宿舍卫生检查', '2024-2025-2', 'hygiene', NULL,
     DATE(DATE_SUB(NOW(), INTERVAL 200 DAY)), DATE(DATE_SUB(NOW(), INTERVAL 190 DAY)), DATE_SUB(NOW(), INTERVAL 200 DAY),
     '[{"key":"power","name":"用电安全","max":20,"ruleRef":"园区管理规定第十条"},{"key":"fire","name":"消防通道","max":20,"ruleRef":"学生宿舍消防安全管理规定第五条"},{"key":"hygiene","name":"内务卫生","max":20,"ruleRef":"学生住宿管理规定第十五条"},{"key":"property","name":"财物与家具","max":20,"ruleRef":"学生住宿管理规定第十八条"},{"key":"conduct","name":"文明行为","max":20,"ruleRef":"学生住宿管理规定第二十条"}]',
     'finished', '演示评分项，非学校发布口径；上学期已结束批次，同样参与文明宿舍汇总', 1,
     DATE_SUB(NOW(), INTERVAL 200 DAY), DATE_SUB(NOW(), INTERVAL 185 DAY), 0);


-- -----------------------------------------------------------------------------
-- 2. 房间检查记录（10 条 / 6 间宿舍）
--    9001 批次（已公布）：A-305 95 excellent / A-412 82 good / A-307 76 pass /
--                        A-306 58 fail（复查驳回，可重新提交）/ B-201 88 good / B-202 55 fail（复查通过）
--    9002 批次（检查中）：A-305 92 excellent / A-412 45 fail（待整改）/ A-306 90 excellent
--    9004 批次（已结束）：B-201 85 good
-- -----------------------------------------------------------------------------
INSERT INTO community_inspection
    (id, batch_id, building, room_no, leader_student_id, check_date, inspector_id, item_json, score, grade, problems,
     rectify_required, rectify_deadline, rectify_status, rectify_note, rectify_at, recheck_by, recheck_at, recheck_result,
     source, created_at, updated_at, deleted)
VALUES
    (9101, 9001, 'A 栋', 'A-305', 4, DATE(DATE_SUB(NOW(), INTERVAL 28 DAY)), 1,
     '{"power":20,"fire":19,"hygiene":19,"property":19,"conduct":18}', 95.00, 'excellent', NULL,
     0, NULL, 'none', NULL, NULL, NULL, NULL, NULL, 'manual', DATE_SUB(NOW(), INTERVAL 28 DAY), DATE_SUB(NOW(), INTERVAL 28 DAY), 0),
    (9102, 9001, 'A 栋', 'A-412', 6, DATE(DATE_SUB(NOW(), INTERVAL 28 DAY)), 2,
     '{"power":16,"fire":17,"hygiene":17,"property":16,"conduct":16}', 82.00, 'good', '桌面物品摆放较乱',
     0, NULL, 'none', NULL, NULL, NULL, NULL, NULL, 'manual', DATE_SUB(NOW(), INTERVAL 28 DAY), DATE_SUB(NOW(), INTERVAL 28 DAY), 0),
    (9103, 9001, 'B 栋', 'B-201', 7, DATE(DATE_SUB(NOW(), INTERVAL 27 DAY)), 2,
     '{"power":18,"fire":18,"hygiene":17,"property":18,"conduct":17}', 88.00, 'good', NULL,
     0, NULL, 'none', NULL, NULL, NULL, NULL, NULL, 'manual', DATE_SUB(NOW(), INTERVAL 27 DAY), DATE_SUB(NOW(), INTERVAL 27 DAY), 0),
    (9104, 9001, 'B 栋', 'B-202', NULL, DATE(DATE_SUB(NOW(), INTERVAL 27 DAY)), 2,
     '{"power":11,"fire":11,"hygiene":11,"property":11,"conduct":11}', 55.00, 'fail', '私拉电线、地面杂物堆积',
     1, DATE(DATE_SUB(NOW(), INTERVAL 20 DAY)), 'accepted', '已拆除私接插排并清理地面杂物，附整改照片', DATE_SUB(NOW(), INTERVAL 24 DAY), 2, DATE_SUB(NOW(), INTERVAL 22 DAY), 'pass',
     'manual', DATE_SUB(NOW(), INTERVAL 27 DAY), DATE_SUB(NOW(), INTERVAL 22 DAY), 0),
    (9105, 9001, 'A 栋', 'A-306', NULL, DATE(DATE_SUB(NOW(), INTERVAL 26 DAY)), 3,
     '{"power":12,"fire":12,"hygiene":12,"property":11,"conduct":11}', 58.00, 'fail', '使用大功率电热锅、消防通道堆物',
     1, DATE(DATE_SUB(NOW(), INTERVAL 19 DAY)), 'rejected', '电热锅已交辅导员暂存，通道杂物已清理', DATE_SUB(NOW(), INTERVAL 23 DAY), 3, DATE_SUB(NOW(), INTERVAL 21 DAY), 'fail',
     'manual', DATE_SUB(NOW(), INTERVAL 26 DAY), DATE_SUB(NOW(), INTERVAL 21 DAY), 0),
    (9106, 9001, 'A 栋', 'A-307', NULL, DATE(DATE_SUB(NOW(), INTERVAL 26 DAY)), 3,
     '{"power":16,"fire":15,"hygiene":15,"property":15,"conduct":15}', 76.00, 'pass', '阳台晾晒物品未收纳',
     0, NULL, 'none', NULL, NULL, NULL, NULL, NULL, 'csv', DATE_SUB(NOW(), INTERVAL 26 DAY), DATE_SUB(NOW(), INTERVAL 26 DAY), 0),
    (9107, 9002, 'A 栋', 'A-305', 4, DATE(DATE_SUB(NOW(), INTERVAL 3 DAY)), 2,
     '{"power":19,"fire":18,"hygiene":19,"property":18,"conduct":18}', 92.00, 'excellent', NULL,
     0, NULL, 'none', NULL, NULL, NULL, NULL, NULL, 'manual', DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY), 0),
    (9108, 9002, 'A 栋', 'A-412', 6, DATE(DATE_SUB(NOW(), INTERVAL 3 DAY)), 2,
     '{"power":9,"fire":9,"hygiene":9,"property":9,"conduct":9}', 45.00, 'fail', '插排串联使用、灭火器被杂物遮挡',
     1, DATE(DATE_ADD(NOW(), INTERVAL 4 DAY)), 'pending', NULL, NULL, NULL, NULL, NULL, 'manual', DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY), 0),
    (9109, 9002, 'A 栋', 'A-306', NULL, DATE(DATE_SUB(NOW(), INTERVAL 2 DAY)), 3,
     '{"power":18,"fire":18,"hygiene":18,"property":18,"conduct":18}', 90.00, 'excellent', NULL,
     0, NULL, 'none', NULL, NULL, NULL, NULL, NULL, 'manual', DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), 0),
    (9110, 9004, 'B 栋', 'B-201', 7, DATE(DATE_SUB(NOW(), INTERVAL 195 DAY)), 1,
     '{"power":17,"fire":17,"hygiene":17,"property":17,"conduct":17}', 85.00, 'good', NULL,
     0, NULL, 'none', NULL, NULL, NULL, NULL, NULL, 'manual', DATE_SUB(NOW(), INTERVAL 195 DAY), DATE_SUB(NOW(), INTERVAL 195 DAY), 0);


-- -----------------------------------------------------------------------------
-- 3. 晚归 / 夜不归宿台账（5 条）
--    判定照 S19 第七条：正常出入 6:00-23:30，之外为晚归（in_time 23:55 之类）
--    handling_note 为内部处理备注（学生端由服务层抹掉）
-- -----------------------------------------------------------------------------
INSERT INTO community_late
    (id, student_id, building, room_no, occur_date, type, out_time, in_time, source, reason, reason_note, student_note,
     confirmed, confirmed_by, confirmed_at, counselor_id, handling, handling_note, violation_id, created_at, updated_at, deleted)
VALUES
    (9201, 4, 'A 栋', 'A-305', DATE(DATE_SUB(NOW(), INTERVAL 3 DAY)), 'late', '22:40:00', '23:55:00', 'card', 'late_return',
     '门禁记录显示 23:55 回园区', '参加社团例会，返校较晚，已向辅导员报备',
     1, 3, DATE_SUB(NOW(), INTERVAL 2 DAY), 3, 'closed', '已电话联系学生本人并提醒按时回园区（内部记录）', NULL,
     DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), 0),
    (9202, 5, 'A 栋', 'A-305', DATE(DATE_SUB(NOW(), INTERVAL 5 DAY)), 'late', '21:30:00', '00:20:00', 'card', 'late_return',
     '门禁记录显示次日 00:20 回园区', NULL,
     1, 3, DATE_SUB(NOW(), INTERVAL 4 DAY), 3, 'closed', '已与家长沟通，学生承诺按时归寝（内部记录）', NULL,
     DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 4 DAY), 0),
    (9203, 8, 'B 栋', 'B-201', DATE(DATE_SUB(NOW(), INTERVAL 2 DAY)), 'absent', NULL, NULL, 'report', 'night_out',
     '寝室长上报当晚未归寝，电话未接通', '在同学宿舍留宿，未办理请假手续',
     1, 2, DATE_SUB(NOW(), INTERVAL 1 DAY), 2, 'contacted', '已联系学生与寝室长，要求补办请假手续（内部记录）', 9302,
     DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 0),
    (9204, 6, 'A 栋', 'A-412', DATE(DATE_SUB(NOW(), INTERVAL 7 DAY)), 'late', NULL, '23:45:00', 'check', 'late_return',
     '夜间巡查发现 23:45 归寝', NULL,
     1, 3, DATE_SUB(NOW(), INTERVAL 6 DAY), 3, 'closed', '已提醒学生遵守作息（内部记录）', NULL,
     DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_SUB(NOW(), INTERVAL 6 DAY), 0),
    (9205, 7, 'B 栋', 'B-201', DATE(DATE_SUB(NOW(), INTERVAL 1 DAY)), 'late', '23:10:00', '23:40:00', 'card', 'late_return',
     '门禁记录显示 23:40 回园区', NULL,
     0, NULL, NULL, 2, 'pending', NULL, 9304,
     DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 0);


-- -----------------------------------------------------------------------------
-- 4. 违规台账（4 条，rule_ref 必填 —— 10.2-4）
-- -----------------------------------------------------------------------------
INSERT INTO community_violation
    (id, student_id, building, room_no, inspection_id, late_id, type, occurred_at, detail, rule_ref,
     advice, handling, counselor_id, handled_by, handled_at, result_note, created_at, updated_at, deleted)
VALUES
    (9301, 4, 'A 栋', 'A-305', NULL, NULL, 'appliance', DATE_SUB(NOW(), INTERVAL 4 DAY),
     '宿舍内使用大功率电热锅（额定功率 1200W）', '厦门大学嘉庚学院学生住宿管理规定第十二条',
     'warning', 'closed', 3, 3, DATE_SUB(NOW(), INTERVAL 3 DAY),
     '已暂存违规电器并出具提醒单，纳入本学期社区表现记录（内部处理说明）',
     DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY), 0),
    (9302, 8, 'B 栋', 'B-201', NULL, 9203, 'absence', DATE_SUB(NOW(), INTERVAL 2 DAY),
     '未办理请假手续夜不归宿 1 次', '园区管理规定第十条',
     'serious_warning', 'notified', 2, 2, DATE_SUB(NOW(), INTERVAL 1 DAY),
     '已告知学生本人与家长，要求提交书面情况说明（内部处理说明）',
     DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 0),
    (9303, 6, 'A 栋', 'A-412', 9108, NULL, 'fire', DATE_SUB(NOW(), INTERVAL 3 DAY),
     '插排串联使用且灭火器被杂物遮挡，存在消防隐患', '学生宿舍消防安全管理规定第五条',
     'education', 'pending', 3, NULL, NULL, NULL,
     DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY), 0),
    (9304, 7, 'B 栋', 'B-201', NULL, 9205, 'late', DATE_SUB(NOW(), INTERVAL 1 DAY),
     '晚归 1 次（23:40 回园区）', '园区管理规定第十条',
     'education', 'pending', 2, NULL, NULL, NULL,
     DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 0);


-- -----------------------------------------------------------------------------
-- 5. 社区活动（4 个：finished / signup / ongoing / draft）
-- -----------------------------------------------------------------------------
INSERT INTO community_activity
    (id, title, activity_type, term, level, start_at, end_at, location, signup_deadline, quota, content, host, status, notice_url, created_by, created_at, updated_at, deleted)
VALUES
    (9401, '2025 年社区文化节', 'culture_festival', '2025-2026-1', 'school',
     DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_SUB(NOW(), INTERVAL 5 DAY), '学生活动中心', DATE_SUB(NOW(), INTERVAL 15 DAY), 100,
     '以宿舍为单位报名参加社区文化节，含才艺展演、宿舍文化墙评比等环节。', '学生工作处', 'finished', '/notice/community-culture-festival', 1,
     DATE_SUB(NOW(), INTERVAL 25 DAY), DATE_SUB(NOW(), INTERVAL 5 DAY), 0),
    (9402, '宿舍风采大赛', 'room_contest', '2025-2026-1', 'college',
     DATE_ADD(NOW(), INTERVAL 10 DAY), DATE_ADD(NOW(), INTERVAL 15 DAY), '社区广场', DATE_ADD(NOW(), INTERVAL 7 DAY), 2,
     '展示宿舍风貌与创意布置，按宿舍报名，每间宿舍限报 1 人（名额 2 人，已满）。', '社区管理中心', 'signup', '/notice/room-style-contest', 2,
     DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 0),
    (9403, '社区志愿服务：垃圾分类引导', 'volunteer', '2025-2026-1', 'community',
     DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 2 DAY), '各楼栋垃圾投放点', DATE_SUB(NOW(), INTERVAL 2 DAY), 20,
     '在校学生参与垃圾分类引导志愿服务，按班次签到。', '社区志愿服务队', 'ongoing', NULL, 3,
     DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), 0),
    (9404, '2025-2026 学年第二学期宿舍奥运会（筹备中）', 'room_olympic', '2025-2026-2', 'school',
     DATE_ADD(NOW(), INTERVAL 30 DAY), DATE_ADD(NOW(), INTERVAL 33 DAY), '体育馆', DATE_ADD(NOW(), INTERVAL 25 DAY), 0,
     '筹备中的宿舍奥运会，报名未开放（status=draft）。', '学生工作处', 'draft', NULL, 1,
     DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), 0);


-- -----------------------------------------------------------------------------
-- 6. 活动报名（6 条，含 attended 与 award；9402 的 2 个名额已被 9504/9505 占满）
-- -----------------------------------------------------------------------------
INSERT INTO community_activity_signup
    (id, activity_id, student_id, building, room_no, signup_at, status, reject_reason, award, score, note, reviewed_by, reviewed_at, created_at, updated_at, deleted)
VALUES
    (9501, 9401, 4, 'A 栋', 'A-305', DATE_SUB(NOW(), INTERVAL 22 DAY), 'attended', NULL, 'first', 95.50, '宿舍文化墙一等奖',
     1, DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 22 DAY), DATE_SUB(NOW(), INTERVAL 6 DAY), 0),
    (9502, 9401, 5, 'A 栋', 'A-305', DATE_SUB(NOW(), INTERVAL 22 DAY), 'attended', NULL, 'excellent', 88.00, '才艺展演优秀奖',
     1, DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 22 DAY), DATE_SUB(NOW(), INTERVAL 6 DAY), 0),
    (9503, 9401, 7, 'B 栋', 'B-201', DATE_SUB(NOW(), INTERVAL 21 DAY), 'absent', NULL, 'none', NULL, '报名后未到场',
     1, DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 21 DAY), DATE_SUB(NOW(), INTERVAL 6 DAY), 0),
    (9504, 9402, 6, 'A 栋', 'A-412', DATE_SUB(NOW(), INTERVAL 2 DAY), 'signed', NULL, 'none', NULL, NULL,
     NULL, NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), 0),
    (9505, 9402, 8, 'B 栋', 'B-201', DATE_SUB(NOW(), INTERVAL 3 DAY), 'approved', NULL, 'none', NULL, NULL,
     2, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 0),
    (9506, 9402, 4, 'A 栋', 'A-305', DATE_SUB(NOW(), INTERVAL 3 DAY), 'rejected', '该宿舍已推荐参加校级评选，不再重复报名', 'none', NULL, NULL,
     2, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 0);


-- -----------------------------------------------------------------------------
-- 7. 文明宿舍结果（2025-2026-1，仅统计已公布/已结束批次；此处对应批次 9001）
--    名单与均分应与 POST /api/community/civil-rooms/compute 对 2025-2026-1 的结果一致：
--    A 栋：A-305 95 名次 1 / A-412 82 名次 2 / A-307 76 名次 3 / A-306 58 名次 4
--    B 栋：B-201 88 名次 1 / B-202 55 名次 2
-- -----------------------------------------------------------------------------
INSERT INTO community_civil_room
    (id, term, batch_ids, building, room_no, member_count, avg_score, min_score, rank_in_building, awarded, award_level, published_at, created_at, updated_at, deleted)
VALUES
    (9601, '2025-2026-1', '[9001]', 'A 栋', 'A-305', 1, 95.00, 95.00, 1, 1, 'school', DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 0),
    (9602, '2025-2026-1', '[9001]', 'A 栋', 'A-412', 1, 82.00, 82.00, 2, 0, NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 0),
    (9603, '2025-2026-1', '[9001]', 'A 栋', 'A-307', 1, 76.00, 76.00, 3, 0, NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 0),
    (9604, '2025-2026-1', '[9001]', 'A 栋', 'A-306', 1, 58.00, 58.00, 4, 0, NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 0),
    (9605, '2025-2026-1', '[9001]', 'B 栋', 'B-201', 1, 88.00, 88.00, 1, 1, 'college', DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 0),
    (9606, '2025-2026-1', '[9001]', 'B 栋', 'B-202', 1, 55.00, 55.00, 2, 0, NULL, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 0);


-- =============================================================================
-- 8. 自检段：演示数据行数（应分别为 4 / 10 / 5 / 4 / 4 / 6 / 6）
-- =============================================================================
SELECT '演示行数' AS 检查项, 'community_batch' AS 表, COUNT(*) AS 行数 FROM community_batch           WHERE id BETWEEN 9001 AND 9099 AND deleted = 0
UNION ALL SELECT '演示行数', 'community_inspection', COUNT(*) FROM community_inspection      WHERE id BETWEEN 9101 AND 9199 AND deleted = 0
UNION ALL SELECT '演示行数', 'community_late', COUNT(*) FROM community_late            WHERE id BETWEEN 9201 AND 9299 AND deleted = 0
UNION ALL SELECT '演示行数', 'community_violation', COUNT(*) FROM community_violation       WHERE id BETWEEN 9301 AND 9399 AND deleted = 0
UNION ALL SELECT '演示行数', 'community_activity', COUNT(*) FROM community_activity        WHERE id BETWEEN 9401 AND 9499 AND deleted = 0
UNION ALL SELECT '演示行数', 'community_activity_signup', COUNT(*) FROM community_activity_signup WHERE id BETWEEN 9501 AND 9599 AND deleted = 0
UNION ALL SELECT '演示行数', 'community_civil_room', COUNT(*) FROM community_civil_room      WHERE id BETWEEN 9601 AND 9699 AND deleted = 0;
