-- =============================================================================
-- P11 · 家庭经济困难学生认定（严格按厦门大学嘉庚学院《家庭经济困难学生认定工作实施的意见》）
-- -----------------------------------------------------------------------------
-- 内容：
--   1) aid_application 幂等加列（12 列，用 information_schema + PREPARE 守卫，可反复执行）
--   2) aid_objection 复议表（新建，IF NOT EXISTS）
--   3) 级别口径迁移：特别困难/一般困难/困难 → 特困/贫困（打印映射前后计数）
--   4) 存量「困难认定」行的 review_stage / cert_year 兜底回填
--   5) 末尾自检 SELECT（列清单 / 分组计数 / 表行数）
--
-- 执行：cmd /c "mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest -t < sql\nest_aid_review.sql"
-- 幂等：连跑两次，第 2 次不产生任何结构变化、行数不变。
--
-- 设计口径（为什么加列而不是新建表）：
--   认定流程与资助申请是**同一笔业务**的两种视图（同一个学生、同一学年、同一条记录），
--   另起一张 aid_review 表会立刻产生「两张表谁是真源」的双写一致性问题；
--   P5 的 68 项 E2E / 44 项单测读的是 aid_application，加列对它们是纯增量（新列默认 NULL）。
--   aid_objection 是**多对一**的新实体（一条认定可有多条复议），才必须独立建表。
-- =============================================================================

USE nest;

-- ------------------------------------------------------------
-- 1. aid_application 加列（每列一段：不存在才 ALTER）
-- ------------------------------------------------------------

-- 1.1 认定级别：只有两档 —— 特困 / 贫困（不再有「一般困难」）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aid_application'
             AND COLUMN_NAME = 'poverty_level');
SET @s := IF(@c = 0,
    'ALTER TABLE aid_application ADD COLUMN poverty_level VARCHAR(16) NULL
       COMMENT ''P11 认定级别：特困 / 贫困（院系工作组定级，公示与终态口径）''',
    'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1.2 流程阶段：apply/committee/workgroup/publicity/objection/report/center/leader/approved/rejected
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aid_application'
             AND COLUMN_NAME = 'review_stage');
SET @s := IF(@c = 0,
    'ALTER TABLE aid_application ADD COLUMN review_stage VARCHAR(24) NULL
       COMMENT ''P11 认定阶段（唯一权威状态机，见 AidReviewService.STAGES）''',
    'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1.3 认定学年：形如 2025-2026
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aid_application'
             AND COLUMN_NAME = 'cert_year');
SET @s := IF(@c = 0,
    'ALTER TABLE aid_application ADD COLUMN cert_year VARCHAR(16) NULL
       COMMENT ''P11 认定学年（形如 2025-2026），结果只在该学年有效''',
    'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1.4 公示开始
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aid_application'
             AND COLUMN_NAME = 'publicity_start');
SET @s := IF(@c = 0,
    'ALTER TABLE aid_application ADD COLUMN publicity_start DATETIME NULL
       COMMENT ''P11 公示发布时刻''',
    'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1.5 公示结束 = 发布 + 5 个工作日
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aid_application'
             AND COLUMN_NAME = 'publicity_end');
SET @s := IF(@c = 0,
    'ALTER TABLE aid_application ADD COLUMN publicity_end DATETIME NULL
       COMMENT ''P11 公示结束 = publicity_start + 5 个工作日（跳周六周日）''',
    'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1.6 复议截止（= 公示结束）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aid_application'
             AND COLUMN_NAME = 'objection_deadline');
SET @s := IF(@c = 0,
    'ALTER TABLE aid_application ADD COLUMN objection_deadline DATETIME NULL
       COMMENT ''P11 复议截止时刻（= publicity_end，过期不再受理）''',
    'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1.7 民主评议记录
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aid_application'
             AND COLUMN_NAME = 'committee_note');
SET @s := IF(@c = 0,
    'ALTER TABLE aid_application ADD COLUMN committee_note VARCHAR(1000) NULL
       COMMENT ''P11 民主评议小组评议记录（含结论 pass/reject）''',
    'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1.8 院系书面评审报告
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aid_application'
             AND COLUMN_NAME = 'report_note');
SET @s := IF(@c = 0,
    'ALTER TABLE aid_application ADD COLUMN report_note VARCHAR(1000) NULL
       COMMENT ''P11 院系认定工作组书面评审报告''',
    'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1.9 复查结论
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aid_application'
             AND COLUMN_NAME = 'recheck_result');
SET @s := IF(@c = 0,
    'ALTER TABLE aid_application ADD COLUMN recheck_result VARCHAR(255) NULL
       COMMENT ''P11 每学期复查/随机抽查结论''',
    'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1.10 复查时间
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aid_application'
             AND COLUMN_NAME = 'rechecked_at');
SET @s := IF(@c = 0,
    'ALTER TABLE aid_application ADD COLUMN rechecked_at DATETIME NULL
       COMMENT ''P11 最近一次复查时刻''',
    'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1.11【口径外补充列 1/2】四项证明材料（JSON 数组）
--   理由：POST /api/aid/poverty/apply 的 body 固定带 materials:[4 项字符串]，
--   而既有 evidence 是 VARCHAR(255)（P5 的「证明材料说明」），塞不下 4 条完整材料；
--   列在 DDL 清单之外，但在 DDL 清单之内无任何一列能无损承载它。
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aid_application'
             AND COLUMN_NAME = 'materials');
SET @s := IF(@c = 0,
    'ALTER TABLE aid_application ADD COLUMN materials VARCHAR(600) NULL
       COMMENT ''P11 四项证明材料（JSON 数组字符串）''',
    'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1.12【口径外补充列 2/2】家庭情况说明
--   理由：apply 的 body 带 familyNote，语义不等于 reason（申请理由），
--   合并进 reason(500) 会污染 P5 收件箱展示。
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aid_application'
             AND COLUMN_NAME = 'family_note');
SET @s := IF(@c = 0,
    'ALTER TABLE aid_application ADD COLUMN family_note VARCHAR(1000) NULL
       COMMENT ''P11 家庭情况说明''',
    'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1.13 认定列表查询用索引（按阶段/学年筛选 + 范围过滤都走它）
SET @c := (SELECT COUNT(*) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aid_application'
             AND INDEX_NAME = 'idx_aa_review');
SET @s := IF(@c = 0,
    'ALTER TABLE aid_application ADD KEY idx_aa_review (aid_type, review_stage, cert_year)',
    'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 1.14 公示接口必须露「姓名/性别/级别」，而 users 与 student_profile 原本都没有性别列。
--   只加 DB 列、不动 StudentProfile 实体（本模块只把它当查询列用），
--   避免牵连 P5 的 StudentProfileMapper 与其单测。
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'student_profile'
             AND COLUMN_NAME = 'gender');
SET @s := IF(@c = 0,
    'ALTER TABLE student_profile ADD COLUMN gender VARCHAR(8) NULL
       COMMENT ''P11 公示用性别（male/female/其他，脱敏公示只露姓名/性别/级别）''',
    'DO 0');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ------------------------------------------------------------
-- 2. aid_objection 复议表（一条认定可有多条复议 ⇒ 必须独立建表）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS aid_objection (
  id           BIGINT        NOT NULL AUTO_INCREMENT,
  application_id BIGINT      NOT NULL COMMENT 'aid_application.id',
  student_id   BIGINT        NOT NULL COMMENT '被认定学生 users.id',
  objector_id  BIGINT        NOT NULL COMMENT '提出人 users.id（学生本人）',
  objector_name VARCHAR(50)  DEFAULT NULL,
  content      VARCHAR(1000) NOT NULL COMMENT '复议内容',
  evidence     VARCHAR(1000) DEFAULT NULL COMMENT '复议佐证',
  status       VARCHAR(16)   NOT NULL DEFAULT 'pending'
               COMMENT 'pending 待答复 / answered 已答复 / accepted 已采纳 / rejected 未采纳',
  answer       VARCHAR(1000) DEFAULT NULL COMMENT '管理员答复',
  answered_by  BIGINT        DEFAULT NULL COMMENT '答复人 users.id',
  deadline     DATETIME      DEFAULT NULL COMMENT '答复截止 = 提交时刻 + 5 个工作日',
  answered_at  DATETIME      DEFAULT NULL,
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted      TINYINT(1)    NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_ao_app (application_id, deleted),
  KEY idx_ao_student (student_id, deleted),
  KEY idx_ao_status (status, created_at),
  KEY idx_ao_deadline (deadline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='P11 家庭经济困难认定·公示期复议';

-- ------------------------------------------------------------
-- 3. 级别口径迁移（映射前计数 → 映射 → 映射后计数）
--    数据库里的历史值有两套写法：英文码（special/general/difficult）
--    与中文（特别困难/一般困难/困难）；两套都映射，幂等（只填 NULL）。
-- ------------------------------------------------------------

-- 3.1 映射前：按 level 分组计数（认定类申请）
SELECT '3.1 映射前 level 分布' AS 步骤, IFNULL(level, '(NULL)') AS level, COUNT(*) AS cnt
FROM aid_application
WHERE deleted = 0 AND aid_type IN ('need', '困难认定')
GROUP BY level
ORDER BY level;

-- 3.2 映射：level → poverty_level（只填还没有级别的行）
UPDATE aid_application
SET poverty_level = CASE
      WHEN level IN ('special', '特别困难')  THEN '特困'
      WHEN level IN ('general', '一般困难')  THEN '贫困'
      WHEN level IN ('difficult', '困难')    THEN '贫困'
      WHEN level = '特困'                    THEN '特困'
      WHEN level = '贫困'                    THEN '贫困'
      ELSE NULL
    END
WHERE deleted = 0
  AND aid_type IN ('need', '困难认定')
  AND poverty_level IS NULL;

-- 3.3 顺带把中文 level 归一成 P5 的英文码（0 行也是正常情况）
UPDATE aid_application
SET level = CASE level
      WHEN '特别困难' THEN 'special'
      WHEN '一般困难' THEN 'general'
      WHEN '困难'     THEN 'difficult'
      ELSE level
    END
WHERE deleted = 0 AND level IN ('特别困难', '一般困难', '困难');

-- 3.4 映射后：按 poverty_level 分组计数
SELECT '3.4 映射后 poverty_level 分布' AS 步骤, IFNULL(poverty_level, '(NULL)') AS poverty_level, COUNT(*) AS cnt
FROM aid_application
WHERE deleted = 0 AND aid_type IN ('need', '困难认定')
GROUP BY poverty_level
ORDER BY poverty_level;

-- ------------------------------------------------------------
-- 4. 存量认定行回填 review_stage / cert_year（幂等：只填 NULL）
-- ------------------------------------------------------------
UPDATE aid_application
SET review_stage = CASE
      WHEN status = 'approved' THEN 'approved'
      WHEN status = 'rejected' THEN 'rejected'
      ELSE 'apply'
    END
WHERE deleted = 0
  AND aid_type IN ('need', '困难认定')
  AND review_stage IS NULL;

UPDATE aid_application
SET cert_year = academic_year
WHERE deleted = 0
  AND aid_type IN ('need', '困难认定')
  AND cert_year IS NULL
  AND academic_year IS NOT NULL;

-- ------------------------------------------------------------
-- 5. 自检输出
-- ------------------------------------------------------------

-- 5.1 aid_application 上 P11 的 12 个列是否都在（应为 12 行）
SELECT '5.1 P11 新增列' AS 检查项, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aid_application'
  AND COLUMN_NAME IN ('poverty_level', 'review_stage', 'cert_year', 'publicity_start',
                      'publicity_end', 'objection_deadline', 'committee_note', 'report_note',
                      'recheck_result', 'rechecked_at', 'materials', 'family_note')
ORDER BY COLUMN_NAME;

SELECT '5.1b 新增列个数（应=12）' AS 检查项, COUNT(*) AS 列数
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aid_application'
  AND COLUMN_NAME IN ('poverty_level', 'review_stage', 'cert_year', 'publicity_start',
                      'publicity_end', 'objection_deadline', 'committee_note', 'report_note',
                      'recheck_result', 'rechecked_at', 'materials', 'family_note');

-- 5.2 认定类申请按 review_stage 分组计数（幂等验证看这张表）
SELECT '5.2 按 review_stage 分组' AS 检查项, IFNULL(review_stage, '(NULL)') AS review_stage, COUNT(*) AS cnt
FROM aid_application
WHERE deleted = 0 AND aid_type IN ('need', '困难认定')
GROUP BY review_stage
ORDER BY review_stage;

-- 5.3 认定类申请按 poverty_level 分组计数
SELECT '5.3 按 poverty_level 分组' AS 检查项, IFNULL(poverty_level, '(NULL)') AS poverty_level, COUNT(*) AS cnt
FROM aid_application
WHERE deleted = 0 AND aid_type IN ('need', '困难认定')
GROUP BY poverty_level
ORDER BY poverty_level;

-- 5.4 复议表行数
SELECT '5.4 aid_objection 行数' AS 检查项, COUNT(*) AS cnt
FROM aid_objection WHERE deleted = 0;

-- 5.5 复议表列清单
SELECT '5.5 aid_objection 列' AS 检查项, COLUMN_NAME, COLUMN_TYPE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aid_objection'
ORDER BY ORDINAL_POSITION;

-- 5.6 索引到位情况
SELECT '5.6 索引' AS 检查项, TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME = 'aid_application' AND INDEX_NAME = 'idx_aa_review')
    OR (TABLE_NAME = 'aid_objection'))
GROUP BY TABLE_NAME, INDEX_NAME
ORDER BY TABLE_NAME, INDEX_NAME;
