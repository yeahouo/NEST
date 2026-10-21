-- =============================================================================
-- 学工平台 · 演示数据自检（只读，可反复执行）
-- tools/verify-demo.sql
--
-- 输出一张「模块 | 实际行数 | 预期(≥) | 判定」表，供 tools/demo-seed.ps1 收尾
-- 或单独执行核对演示数据是否加载齐。
--
-- 判定口径：
--   OK    —— 行数达到预期下限（有演示数据可看）
--   EMPTY —— 该模块本应无演示数据（由运行时计算/用户操作产生），不算失败
--   LOW   —— 行数低于预期，说明某个 demo 文件没加载成功
-- =============================================================================

SELECT module, actual, expect, 
       CASE WHEN expect = 0 THEN 'EMPTY'
            WHEN actual >= expect THEN 'OK'
            ELSE 'LOW' END AS verdict
FROM (
  SELECT '用户(学生5/教师2/管理员1)' AS module, 
         (SELECT COUNT(*) FROM users WHERE username IN ('admin','zhangsan','lisi','wangxm','zhaoxh','chendw','liusq','zhoujie')) AS actual, 8 AS expect
  UNION ALL SELECT '学生档案', (SELECT COUNT(*) FROM student_profile WHERE user_id BETWEEN 4 AND 8), 5
  UNION ALL SELECT '社区活动', (SELECT COUNT(*) FROM community_activity WHERE title NOT LIKE 'e2e-%' AND title NOT LIKE 'SMOKE%'), 4
  UNION ALL SELECT '社区活动报名', (SELECT COUNT(*) FROM community_activity_signup), 6
  UNION ALL SELECT '文明宿舍', (SELECT COUNT(*) FROM community_civil_room), 6
  UNION ALL SELECT '志愿者项目', (SELECT COUNT(*) FROM volunteer_project), 4
  UNION ALL SELECT '志愿者报名', (SELECT COUNT(*) FROM volunteer_enrollment), 6
  UNION ALL SELECT '志愿者工时记录', (SELECT COUNT(*) FROM volunteer_record), 6
  UNION ALL SELECT '志愿者黑名单', (SELECT COUNT(*) FROM volunteer_blacklist), 2
  UNION ALL SELECT '志愿者申诉', (SELECT COUNT(*) FROM volunteer_appeal), 2
  UNION ALL SELECT '社团', (SELECT COUNT(*) FROM club), 8
  UNION ALL SELECT '社团成员', (SELECT COUNT(*) FROM club_member), 4
  UNION ALL SELECT '社团年审', (SELECT COUNT(*) FROM club_review), 3
  UNION ALL SELECT '社团星级评定', (SELECT COUNT(*) FROM club_rating), 3
  UNION ALL SELECT '社团公示', (SELECT COUNT(*) FROM club_publicity), 2
  UNION ALL SELECT '第二课堂板块', (SELECT COUNT(*) FROM second_class_category), 7
  UNION ALL SELECT '第二课堂活动', (SELECT COUNT(*) FROM second_class_activity), 5
  UNION ALL SELECT '第二课堂申报', (SELECT COUNT(*) FROM second_class_record), 4
  UNION ALL SELECT '综测批次', (SELECT COUNT(*) FROM rank_batch WHERE batch_name NOT LIKE 'e2e-%' AND batch_name NOT LIKE 'SMOKE%'), 3
  UNION ALL SELECT '综测明细(运行时计算)', (SELECT COUNT(*) FROM rank_row), 0
  UNION ALL SELECT '排名异议', (SELECT COUNT(*) FROM rank_objection), 0
  UNION ALL SELECT '勤工岗位', (SELECT COUNT(*) FROM workstudy_post), 3
  UNION ALL SELECT '勤工申请', (SELECT COUNT(*) FROM workstudy_application), 6
  UNION ALL SELECT '资助申请', (SELECT COUNT(*) FROM aid_application), 35
  UNION ALL SELECT '助学贷款', (SELECT COUNT(*) FROM student_loan), 4
  UNION ALL SELECT '荣誉批次', (SELECT COUNT(*) FROM honor_batch), 2
  UNION ALL SELECT '荣誉申报', (SELECT COUNT(*) FROM honor_application), 5
  UNION ALL SELECT '荣誉审核', (SELECT COUNT(*) FROM honor_review), 4
  UNION ALL SELECT '党建申请', (SELECT COUNT(*) FROM party_application), 3
  UNION ALL SELECT '党建考察', (SELECT COUNT(*) FROM party_observation), 3
  UNION ALL SELECT '党建阶段', (SELECT COUNT(*) FROM party_stage), 3
  UNION ALL SELECT '党建班次', (SELECT COUNT(*) FROM party_training), 1
  UNION ALL SELECT '党建班次学员', (SELECT COUNT(*) FROM party_training_member), 3
  UNION ALL SELECT '党建公示', (SELECT COUNT(*) FROM party_publicity), 4
  UNION ALL SELECT '党建材料', (SELECT COUNT(*) FROM party_material), 6
  UNION ALL SELECT '心理量表', (SELECT COUNT(*) FROM psyche_instrument), 3
  UNION ALL SELECT '心理普查批次', (SELECT COUNT(*) FROM psyche_batch), 2
  UNION ALL SELECT '心理普查任务', (SELECT COUNT(*) FROM psyche_task), 5
  UNION ALL SELECT '心理关注', (SELECT COUNT(*) FROM psyche_focus), 2
  UNION ALL SELECT '心理转介', (SELECT COUNT(*) FROM psyche_referral), 1
  UNION ALL SELECT '心理跟进', (SELECT COUNT(*) FROM psyche_followup), 2
  UNION ALL SELECT '心理预约', (SELECT COUNT(*) FROM psyche_appointment), 4
  UNION ALL SELECT '请假', (SELECT COUNT(*) FROM leave_request), 3
  UNION ALL SELECT '公告', (SELECT COUNT(*) FROM announcements WHERE deleted = 0), 4
) AS t
ORDER BY module;
