-- =============================================================================
-- 学工平台 · P13 综测排名演示数据（sql/nest_rank_demo.sql）
--
-- 配套建表脚本：sql/nest_rank.sql（先执行它，再执行本文件）
-- 执行（Windows / cmd）：
--   cmd /c "mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest -t < sql\nest_rank_demo.sql"
--
-- 本文件只放**源数据**：学习成绩 A 台账 + 加分/扣分申报 + 批次定义。
-- 排名快照（rank_row）由管理端「一键计算」生成 —— 这正是要演示的计算链路，
-- 因此在 SQL 里不伪造快照；公示异议也由公示期内提交产生。
--
-- 幂等：演示数据只用固定 id 区间（批次 9001-9009 / 数据 90001-90099），
--       重复执行前会先清掉这批 id 再插入，不会污染真实数据。
--
-- 演示口径（与《学工平台功能对齐说明》§8 一致）：
--   · 2025-2026-1：软件工程 2024 级 3 人（88 / 80 / 80）、计算机科学 2024 级 2 人（79.5 / 79）
--     ⇒ 同一次计算里同时演示 μ 的上限钳制（软工 (88-80)/3 = 2.67 → 取 0.7）
--       与下限钳制（计科 (79.5-79)/2 = 0.25 → 取 0.5）
--   · 王小明的加分合计 3 + 4 + 4 + 2 - 0.2 = 12.8 → 分类封顶后 11.8 → 总封顶 C = 8
--     ⇒ B = 8 × 0.7 = 5.6，M = 88 + 5.6 = 93.6
--   · 2025-2026-2：只有 2 人有 A ⇒ 演示管理端「待补数据」清单
--   · 批次 9003 只用于演示 graduation_last 的 B = 0 / M = A 口径
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 0. 清理上一轮演示数据（固定 id 区间）
-- -----------------------------------------------------------------------------
DELETE FROM rank_row       WHERE batch_id IN (9001, 9002, 9003);
DELETE FROM rank_objection WHERE batch_id IN (9001, 9002, 9003);
DELETE FROM rank_batch     WHERE id BETWEEN 9001 AND 9009;
DELETE FROM eval_bonus     WHERE id BETWEEN 90001 AND 90099;
DELETE FROM eval_academic  WHERE id BETWEEN 90001 AND 90099;


-- -----------------------------------------------------------------------------
-- 1. 学习成绩 A 台账（教务源数据，平台只登记）
-- -----------------------------------------------------------------------------
INSERT INTO eval_academic
    (id, student_id, academic_term, required_avg, elective_avg, total_credits, course_count, score_a, source, imported_by, note)
VALUES
    (90001, 4, '2025-2026-1', 88.40, 87.20, 24.00, 6, 88.00, 'manual', 3, '演示数据：教务部提供的学期学习成绩 A'),
    (90002, 5, '2025-2026-1', 80.10, 79.80, 23.00, 6, 80.00, 'manual', 3, '演示数据：与陈大伟同分，用于演示同分并列名次'),
    (90003, 6, '2025-2026-1', 79.90, 80.20, 22.50, 6, 80.00, 'manual', 3, '演示数据：与赵小红同分'),
    (90004, 7, '2025-2026-1', 79.60, 79.30, 25.00, 7, 79.50, 'manual', 2, '演示数据：计算机科学，用于演示 μ 下限钳制'),
    (90005, 8, '2025-2026-1', 79.20, 78.60, 24.00, 6, 79.00, 'manual', 2, '演示数据：计算机科学'),
    -- 2025-2026-2：仅 2 人有 A，故意留出「待补数据」清单
    (90006, 4, '2025-2026-2', 88.60, 88.00, 25.00, 6, 88.50, 'import', 1, '演示数据：教务导入'),
    (90007, 5, '2025-2026-2', 81.20, 80.40, 24.00, 6, 81.00, 'import', 1, '演示数据：教务导入');


-- -----------------------------------------------------------------------------
-- 2. 综合素质表现得分 C 的加分 / 扣分申报
--    只有 counselor_approved 计入 C；其余状态用来演示申报与审核流程
-- -----------------------------------------------------------------------------
INSERT INTO eval_bonus
    (id, student_id, academic_term, category, item_name, points, evidence, status, reject_reason, reviewer_id, reviewed_at)
VALUES
    -- 王小明：合计 12.8 → 分类封顶 11.8 → 总封顶 C = 8
    (90001, 4, '2025-2026-1', 'academic_competition', '校程序设计竞赛一等奖（演示）', 3.00, '获奖证书 DEMO-AC-1（线下交件后由辅导员核验）', 'counselor_approved', NULL, 3, NOW()),
    (90002, 4, '2025-2026-1', 'service', '班长（学年考核优秀）', 2.00, '学院学生工作办公室考核表 DEMO-SV-1', 'counselor_approved', NULL, 3, NOW()),
    (90003, 4, '2025-2026-1', 'service', '学生会部长（学年考核良好）', 2.00, '团委考核表 DEMO-SV-2', 'counselor_approved', NULL, 3, NOW()),
    (90004, 4, '2025-2026-1', 'research', '校级大学生创新创业训练计划结题', 4.00, '结题证书 DEMO-RS-1', 'counselor_approved', NULL, 3, NOW()),
    (90005, 4, '2025-2026-1', 'media', '校官网新闻稿（第一作者）', 2.00, '稿件链接与署名截图 DEMO-MD-1', 'counselor_approved', NULL, 3, NOW()),
    (90006, 4, '2025-2026-1', 'penalty', '上课缺勤 1 次', -0.20, '教务处考勤记录 DEMO-PN-1', 'counselor_approved', NULL, 3, NOW()),
    -- 赵小红：1.2 + 0.5 = 1.7
    (90007, 5, '2025-2026-1', 'nonacademic_competition', '省级非学术科技竞赛二等奖（演示）', 1.20, '获奖证书 DEMO-NA-1', 'counselor_approved', NULL, 3, NOW()),
    (90008, 5, '2025-2026-1', 'quality', '全国大学英语六级（CET-6）通过', 0.50, '成绩单 DEMO-QU-1', 'counselor_approved', NULL, 3, NOW()),
    -- 陈大伟：1.6 已通过 + 1 条待审（演示辅导员待办）
    (90009, 6, '2025-2026-1', 'service', '班级生活委员（学年考核良好）', 1.60, '学院考核表 DEMO-SV-3', 'counselor_approved', NULL, 3, NOW()),
    (90010, 6, '2025-2026-1', 'quality', '普通话水平测试二级甲等', 0.50, '证书 DEMO-QU-2', 'submitted', NULL, NULL, NULL),
    -- 刘思琪：1 条通过 + 1 条驳回（演示驳回理由可见）
    (90011, 7, '2025-2026-1', 'research', '北大核心期刊论文（合作者）', 1.00, '期刊页与录用函 DEMO-RS-2', 'counselor_approved', NULL, 2, NOW()),
    (90012, 7, '2025-2026-1', 'research', '校内内刊文章（不予认定）', 1.00, '内刊样刊 DEMO-RS-3', 'rejected', '内刊、增刊、专刊、年刊按《评分参考细则》不予认定加分', 2, NOW()),
    -- 周杰：草稿（演示学生自己的草稿箱）
    (90013, 8, '2025-2026-1', 'nonacademic_competition', '院系级非学术科技竞赛三等奖（演示）', 0.10, '获奖名单 DEMO-NA-2', 'draft', NULL, NULL, NULL);


-- -----------------------------------------------------------------------------
-- 3. 排名批次（比例与 μ 边界按批次存，历史批次可复现当时的算法）
-- -----------------------------------------------------------------------------
INSERT INTO rank_batch
    (id, batch_name, academic_term, graduation_last, mu_min, mu_max, qualify_pct, tier1_pct, tier2_pct, tier3_pct,
     publicity_days, status, notice_title, notice_url, created_by)
VALUES
    (9001, '2025-2026 学年第一学期综测排名（演示）', '2025-2026-1', 0, 0.500, 0.700, 30, 5, 10, 15, 5, 'draft',
     '2025-2026 学年第一学期学生综合素质测评结果公示（演示）', NULL, 1),
    (9002, '2025-2026 学年第二学期综测排名（演示·待补数据）', '2025-2026-2', 0, 0.500, 0.700, 30, 5, 10, 15, 5, 'draft',
     '2025-2026 学年第二学期学生综合素质测评结果公示（演示）', NULL, 1),
    (9003, '毕业学年第二学期综测排名（演示·B=0、M=A）', '2025-2026-1', 1, 0.500, 0.700, 30, 5, 10, 15, 5, 'draft',
     '毕业学年第二学期学习成绩测评排名公示（演示）', NULL, 1);

-- 说明：9003 按办法第十条「毕业学年第二学期只按往年所有学期学习成绩测评得分排名」，
--       真正的实现要用往年各学期 A 汇总；演示里直接借用 2025-2026-1 的 A，
--       目的是让操作者看到 graduation_last=1 时的 B=0 / M=A 效果，不代表真实学年。


-- =============================================================================
-- 4. 自检：演示数据是否就位（应看到 A 7 行、加分 13 行、批次 3 行）
-- =============================================================================
SELECT '4.1 行数' AS 检查项, 'eval_academic' AS 表, COUNT(*) AS 行数 FROM eval_academic WHERE id BETWEEN 90001 AND 90099
UNION ALL SELECT '4.1 行数', 'eval_bonus',    COUNT(*) FROM eval_bonus    WHERE id BETWEEN 90001 AND 90099
UNION ALL SELECT '4.1 行数', 'rank_batch',    COUNT(*) FROM rank_batch    WHERE id BETWEEN 9001 AND 9009;

SELECT '4.2 学习成绩 A' AS 检查项, u.real_name AS 学生, u.username AS 账号, p.major AS 专业, p.class_name AS 班级,
       a.academic_term AS 学期, a.score_a AS A, a.source AS 来源
FROM eval_academic a
JOIN users u ON u.id = a.student_id
LEFT JOIN student_profile p ON p.user_id = a.student_id
WHERE a.id BETWEEN 90001 AND 90099
ORDER BY a.academic_term, p.major, a.score_a DESC;

SELECT '4.3 每人加分合计' AS 检查项, u.real_name AS 学生, b.academic_term AS 学期,
       SUM(CASE WHEN b.status = 'counselor_approved' THEN b.points ELSE 0 END) AS 已通过分值,
       SUM(CASE WHEN b.status = 'submitted' THEN 1 ELSE 0 END) AS 待审条数,
       SUM(CASE WHEN b.status = 'rejected' THEN 1 ELSE 0 END) AS 已驳回条数,
       SUM(CASE WHEN b.status = 'draft' THEN 1 ELSE 0 END) AS 草稿条数
FROM eval_bonus b
JOIN users u ON u.id = b.student_id
WHERE b.id BETWEEN 90001 AND 90099
GROUP BY u.real_name, b.academic_term
ORDER BY b.academic_term, 已通过分值 DESC;

SELECT '4.4 批次' AS 检查项, id, batch_name AS 批次, academic_term AS 学期, graduation_last AS 毕业学年,
       qualify_pct AS 资格线, tier1_pct AS 一等, tier2_pct AS 二等, tier3_pct AS 三等, publicity_days AS 公示工作日, status AS 状态
FROM rank_batch WHERE id BETWEEN 9001 AND 9009 ORDER BY id;

SELECT '4.5 提示' AS 提示
UNION ALL SELECT '演示批次为 draft，登录管理端「综测排名管理」页点「一键计算」即生成排名快照，再点「公示」进入公示期'
UNION ALL SELECT '王小明的 C 会先按类封顶（社会工作 4 → 3）再总封顶 8：M = 88 + 8 × 0.7 = 93.6'
UNION ALL SELECT '2025-2026-2 只有 2 人有 A，点计算后「待补数据」清单里会有其余 3 人';
