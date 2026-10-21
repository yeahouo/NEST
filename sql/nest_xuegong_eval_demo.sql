-- ============================================================
-- 学工平台 —— 综合素质测评演示数据（2025-2026-1 学期）
-- 执行：mysql --defaults-extra-file=<my.cnf> -D nest < sql/nest_xuegong_eval_demo.sql
-- 幂等：ON DUPLICATE KEY UPDATE，可重复执行。
-- 说明：给 5 名演示学生按「学生 id + 指标序号」生成确定性分数（不是随机，反复跑结果一致），
--       并给陈大伟再补一份辅导员评分，用来演示「辅导员评分优先于自评」的口径。
--       汇总（总分/排名/等级）由 POST /api/eval/compute 生成，不在本脚本里算。
-- ============================================================

USE nest;

-- 1. 学生自评：五名学生 × 全部启用指标
INSERT INTO eval_score (student_id, indicator_id, academic_term, score, source, evidence, scorer_id)
SELECT u.id,
       i.id,
       '2025-2026-1',
       ROUND(LEAST(i.max_score, 62 + MOD(u.id * 7 + i.sort_order, 36)), 2),
       'self',
       NULL,
       u.id
FROM users u
JOIN eval_indicator i ON i.active = 1
WHERE u.deleted = 0
  AND u.username IN ('chendw', 'wangxm', 'zhaoxh', 'liusq', 'zhoujie')
ON DUPLICATE KEY UPDATE
  score      = VALUES(score),
  evidence   = VALUES(evidence),
  updated_at = NOW();

-- 2. 辅导员评分：陈大伟（演示「辅导员评分优先」）
INSERT INTO eval_score (student_id, indicator_id, academic_term, score, source, evidence, scorer_id)
SELECT u.id,
       i.id,
       '2025-2026-1',
       ROUND(LEAST(i.max_score, 78 + MOD(u.id * 3 + i.sort_order, 18)), 2),
       'counselor',
       '辅导员复核',
       c.id
FROM users u
JOIN eval_indicator i ON i.active = 1
JOIN users c ON c.username = 'lisi' AND c.deleted = 0
WHERE u.deleted = 0
  AND u.username = 'chendw'
ON DUPLICATE KEY UPDATE
  score      = VALUES(score),
  evidence   = VALUES(evidence),
  scorer_id  = VALUES(scorer_id),
  updated_at = NOW();

-- 自检：得分条数应按来源分开看
SELECT source, COUNT(*) AS rows_now, COUNT(DISTINCT student_id) AS students
FROM eval_score
WHERE academic_term = '2025-2026-1'
GROUP BY source
ORDER BY source;
