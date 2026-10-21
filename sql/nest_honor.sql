-- ============================================================
-- nest_honor.sql —— P12（评奖评优在线申报）建表
--
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，可反复执行；
--       末尾附自检查询（列/索引/行数），验证只看那段输出。
-- 执行：cmd /c "mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest -t < sql\nest_honor.sql"
--       （注意：mysql -e 里不能用 source，会报 ERROR 1064）
--
-- 一手出处：《关于评选2026届优秀毕业生的通知》
--   https://xgb.xujc.com/2026/0507/c1962a169810/page.htm
--   · 程序 5 步：学生「线上+线下」申请 → 学院审核材料、初评名单 →
--     学工部复核并报评审领导小组审批 → 公示 → 发文表彰
--   · 条件要点：无违法违纪、无不及格科目、综测毕业排名前 15%、
--     校级以上荣誉称号 ≥2 次（或 ≥1 次 + 国家/校级奖学金 ≥1 次）、体测 ≥50
--   · 名单以平台导出为准；驳回要让学生看到进度与意见；
--     学院推荐意见不可只写「同意推荐」
--   · 称号办法页（.psp）抓不到正文 ⇒ 名额/比例一律不作断言，
--     批次表留 quota / quota_note 由管理员按当年文件填
-- ============================================================

-- ------------------------------------------------------------
-- 1. 评优批次（一次评选 = 一个批次）
--    status：draft 草稿 → recruiting 申报中 → reviewing 审核中
--            → publicity 公示中 → finished 已结束
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS honor_batch (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  batch_name      VARCHAR(128) NOT NULL COMMENT '批次名，如「2026届优秀毕业生评选」',
  honor_type      VARCHAR(32)  NOT NULL COMMENT 'graduate_outstanding 优秀毕业生 / merit_outstanding 优秀三好学生 / merit_student 三好学生 / cadres_outstanding 优秀学生干部',
  academic_year   VARCHAR(16)  NOT NULL COMMENT '学年，如 2025-2026',
  eval_term       VARCHAR(16)  DEFAULT NULL COMMENT '用于自动核验综测排名的学期，如 2025-2026-1；为空则该项只能人工核验',
  quota           INT          DEFAULT NULL COMMENT '名额（空＝按文件比例，不在此断言）',
  quota_note      VARCHAR(255) DEFAULT NULL COMMENT '名额说明（比例口径等，来自当年文件）',
  criteria        TEXT         DEFAULT NULL COMMENT '参评条件摘要（通知原文要点）',
  materials       VARCHAR(500) DEFAULT NULL COMMENT '需交材料说明（线下交件）',
  apply_start     DATE         DEFAULT NULL COMMENT '申报开始日',
  apply_end       DATE         DEFAULT NULL COMMENT '申报截止日（通知写「5/13 24:00」时记 5/14）',
  review_end      DATE         DEFAULT NULL COMMENT '学院审核截止日',
  publicity_days  INT          NOT NULL DEFAULT 5 COMMENT '公示天数（工作日口径由 service 折算）',
  publicity_start DATETIME     DEFAULT NULL,
  publicity_end   DATETIME     DEFAULT NULL,
  status          VARCHAR(16)  NOT NULL DEFAULT 'draft' COMMENT 'draft / recruiting / reviewing / publicity / finished',
  notice_title    VARCHAR(255) DEFAULT NULL COMMENT '通知标题',
  notice_url      VARCHAR(255) DEFAULT NULL COMMENT '通知链接',
  created_by      BIGINT       DEFAULT NULL COMMENT '创建人 users.id',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_hb_status (status, deleted),
  KEY idx_hb_year (academic_year, honor_type),
  KEY idx_hb_apply (apply_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='学工平台·评优批次';

-- ------------------------------------------------------------
-- 2. 申报单（一名学生在一个批次里只有一张）
--    status：draft → submitted → counselor_approved → college_approved
--            → dept_approved → publicized → awarded
--            任一审核节点 reject ⇒ rejected（写 reject_reason，申报期内可改后重提）
--            学生可在 submitted 之前 withdraw 回 draft
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS honor_application (
  id                    BIGINT        NOT NULL AUTO_INCREMENT,
  batch_id              BIGINT        NOT NULL,
  student_id            BIGINT        NOT NULL COMMENT 'users.id（学生）',
  honor_type            VARCHAR(32)   NOT NULL COMMENT '冗余批次类型，便于统计与导出',
  academic_year         VARCHAR(16)   NOT NULL,
  self_statement        VARCHAR(2000) NOT NULL COMMENT '个人自述（服务端要求 ≥30 字）',
  honors_text           VARCHAR(1000) DEFAULT NULL COMMENT '荣誉称号清单（自报，人工核验用）',
  scholarships_text     VARCHAR(500)  DEFAULT NULL COMMENT '奖学金清单（自报）',
  pe_score              DECIMAL(5,2)  DEFAULT NULL COMMENT '体测成绩（自报，人工核验）',
  no_fail_declared      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '自报无不及格科目（教学数据已随 P8 下线，只能自报+人工核验）',
  no_violation_declared TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '自报无违法违纪',
  material_note         VARCHAR(500)  DEFAULT NULL COMMENT '材料说明/清单（线下交件，不假装上传）',
  contact_phone         VARCHAR(32)   DEFAULT NULL COMMENT '联系电话',
  status                VARCHAR(24)   NOT NULL DEFAULT 'draft' COMMENT 'draft / submitted / counselor_approved / college_approved / dept_approved / publicized / awarded / rejected',
  reject_reason         VARCHAR(500)  DEFAULT NULL COMMENT '驳回原因（学生可见）',
  counselor_id          BIGINT        DEFAULT NULL COMMENT '提交时固化该生辅导员 users.id（审核范围判定用）',
  submitted_at          DATETIME      DEFAULT NULL,
  created_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted               TINYINT(1)    NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ha_batch_student (batch_id, student_id),
  KEY idx_ha_status (status, created_at),
  KEY idx_ha_student (student_id, created_at),
  KEY idx_ha_counselor (counselor_id, status),
  KEY idx_ha_type (academic_year, honor_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='学工平台·评优申报单';

-- ------------------------------------------------------------
-- 3. 审核流水（每一步都留痕：谁、什么时候、什么意见）
--    node：counselor 辅导员初审 / college 学院初评 / dept 学工部复核
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS honor_review (
  id             BIGINT        NOT NULL AUTO_INCREMENT,
  application_id BIGINT        NOT NULL,
  node           VARCHAR(16)   NOT NULL COMMENT 'counselor / college / dept',
  verdict        VARCHAR(16)   NOT NULL COMMENT 'pass 通过 / reject 驳回',
  comment        VARCHAR(1000) DEFAULT NULL COMMENT '审核意见（通过也要求写实：≥8 字且不是「同意推荐」这类空话）',
  reviewer_id    BIGINT        DEFAULT NULL COMMENT '审核人 users.id',
  reviewer_role  VARCHAR(16)   DEFAULT NULL COMMENT '审核时的角色快照',
  reviewed_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted        TINYINT(1)    NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_hr_app (application_id, node, reviewed_at),
  KEY idx_hr_reviewer (reviewer_id, reviewed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='学工平台·评优审核流水';

-- ------------------------------------------------------------
-- 4. 自检输出（跑完看这一段）
-- ------------------------------------------------------------

-- 4.1 三张表是否都在（应 3 行）
SELECT '4.1 表' AS 检查项, TABLE_NAME, TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('honor_batch', 'honor_application', 'honor_review')
ORDER BY TABLE_NAME;

-- 4.2 列清单
SELECT '4.2 列' AS 检查项, TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('honor_batch', 'honor_application', 'honor_review')
ORDER BY TABLE_NAME, ORDINAL_POSITION;

-- 4.3 索引
SELECT '4.3 索引' AS 检查项, TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('honor_batch', 'honor_application', 'honor_review')
GROUP BY TABLE_NAME, INDEX_NAME
ORDER BY TABLE_NAME, INDEX_NAME;

-- 4.4 行数
SELECT '4.4 行数' AS 检查项, 'honor_batch' AS 表, COUNT(*) AS cnt FROM honor_batch WHERE deleted = 0
UNION ALL
SELECT '4.4 行数', 'honor_application', COUNT(*) FROM honor_application WHERE deleted = 0
UNION ALL
SELECT '4.4 行数', 'honor_review', COUNT(*) FROM honor_review WHERE deleted = 0;

-- 4.5 申报单状态分布（幂等重跑后应保持不变）
SELECT '4.5 申报状态' AS 检查项, IFNULL(status, '(NULL)') AS status, COUNT(*) AS cnt
FROM honor_application WHERE deleted = 0
GROUP BY status ORDER BY status;
