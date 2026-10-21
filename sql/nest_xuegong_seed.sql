-- ============================================================
-- 学工平台 —— 综合素质测评指标种子
-- 执行：mysql --defaults-extra-file=<my.cnf> -D nest < sql/nest_xuegong_seed.sql
-- 幂等：INSERT ... ON DUPLICATE KEY UPDATE（按 code 唯一键），可重复执行、可反复调权重。
-- 维度：moral 德 / intellectual 智 / physical 体 / aesthetic 美 / labor 劳
-- 口径：每维内 weight 合计 1.0000；total = Σ(维度得分 × 维度权重)，维度权重见下方说明。
--       维度权重：德 0.25 / 智 0.35 / 体 0.15 / 美 0.10 / 劳 0.15（在 service 层常量里，可配置）
-- ============================================================

USE nest;

INSERT INTO eval_indicator (dimension, code, name, description, max_score, weight, sort_order, active) VALUES
  -- 德
  ('moral',       'MORAL_BASIC',   '思想政治表现',   '政治学习、主题教育参与、党团活动', 100.00, 0.4000, 10, 1),
  ('moral',       'MORAL_ETHICS',  '品德行为',       '日常行为规范、诚信表现、宿舍文明', 100.00, 0.3000, 20, 1),
  ('moral',       'MORAL_HONOR',   '表彰与荣誉',     '先进个人、优秀团员等荣誉加分',     100.00, 0.2000, 30, 1),
  ('moral',       'MORAL_DISCIPLINE','违纪扣分',     '纪律处分及通报批评扣分',           100.00, 0.1000, 40, 1),
  -- 智
  ('intellectual','INTEL_GPA',     '学业成绩',       '学期加权平均分换算',               100.00, 0.6000, 110, 1),
  ('intellectual','INTEL_RANK',    '学业排名',       '专业内排名分位换算',               100.00, 0.2000, 120, 1),
  ('intellectual','INTEL_COMPETITION','学科竞赛',    '学科与创新创业竞赛获奖',           100.00, 0.1500, 130, 1),
  ('intellectual','INTEL_RESEARCH','科研与创新',    '论文、专利、大创项目',             100.00, 0.0500, 140, 1),
  -- 体
  ('physical',    'PHYS_PE',       '体育课程',       '体育课成绩',                       100.00, 0.4000, 210, 1),
  ('physical',    'PHYS_TEST',     '体质健康测试',   '国家学生体质健康标准',             100.00, 0.4000, 220, 1),
  ('physical',    'PHYS_ACTIVITY', '体育活动',       '校运会、体育社团、赛事参与',       100.00, 0.2000, 230, 1),
  -- 美
  ('aesthetic',   'AEST_COURSE',   '美育课程',       '艺术类课程与讲座',                 100.00, 0.4000, 310, 1),
  ('aesthetic',   'AEST_PRACTICE', '文艺实践',       '文艺演出、社团、展览参与',         100.00, 0.4000, 320, 1),
  ('aesthetic',   'AEST_AWARD',    '文艺获奖',       '文艺类比赛获奖',                   100.00, 0.2000, 330, 1),
  -- 劳
  ('labor',       'LABOR_PRACTICE','劳动实践',       '劳动教育课程与实践',               100.00, 0.4000, 410, 1),
  ('labor',       'LABOR_VOLUNTEER','志愿服务',      '志愿时长与公益服务',               100.00, 0.4000, 420, 1),
  ('labor',       'LABOR_DORM',    '宿舍劳动',       '宿舍卫生检查与内务',               100.00, 0.2000, 430, 1)
ON DUPLICATE KEY UPDATE
  dimension   = VALUES(dimension),
  name        = VALUES(name),
  description = VALUES(description),
  max_score   = VALUES(max_score),
  weight      = VALUES(weight),
  sort_order  = VALUES(sort_order),
  active      = VALUES(active);

-- 自检：每维应各有 3~4 项，weight 合计 1.0000
SELECT dimension,
       COUNT(*)              AS items,
       SUM(weight)           AS weight_sum,
       MIN(sort_order)       AS first_order
FROM eval_indicator
WHERE active = 1
GROUP BY dimension
ORDER BY MIN(sort_order);
