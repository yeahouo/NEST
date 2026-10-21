-- ============================================================
-- 学工平台 —— 学工业务域数据表（请假/离校、奖惩、资助、综合素质测评、学生档案）
-- 相对教学域与宿舍域：本脚本**只新增、不删除**，学生数据必须能跨版本存活。
-- 执行：mysql --defaults-extra-file=<my.cnf> -D nest < sql/nest_xuegong.sql
--       （必须带库名 -D nest；脚本自身也 USE nest）
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，可重复执行。
-- 参照完整性：不建 FOREIGN KEY（跟随审计表的做法），
--             由 service 层校验 + 这里的索引保证查询性能；删用户时不级联删学工记录。
-- 字符集：utf8mb4 / utf8mb4_unicode_ci
-- ============================================================

CREATE DATABASE IF NOT EXISTS nest
DEFAULT CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;

USE nest;

-- ------------------------------------------------------------
-- 1. 学生档案（学工口径）
--    user_id 指向 users.id；student_no 与学校学籍号一致。
--    counselor_id 是辅导员（users.id）；dorm_* 是宿舍分配结果的只读镜像。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS student_profile (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  user_id            BIGINT       NOT NULL COMMENT 'users.id',
  student_no         VARCHAR(32)  DEFAULT NULL COMMENT '学号',
  class_name         VARCHAR(64)  DEFAULT NULL COMMENT '行政班',
  major              VARCHAR(64)  DEFAULT NULL COMMENT '专业',
  grade              VARCHAR(16)  DEFAULT NULL COMMENT '年级（如 2024）',
  counselor_id       BIGINT       DEFAULT NULL COMMENT '辅导员 users.id',
  dorm_building      VARCHAR(32)  DEFAULT NULL COMMENT '宿舍楼',
  dorm_room          VARCHAR(32)  DEFAULT NULL COMMENT '宿舍号',
  political_status   VARCHAR(16)  DEFAULT NULL COMMENT '政治面貌',
  family_contact     VARCHAR(64)  DEFAULT NULL COMMENT '家庭联系人',
  family_income_level VARCHAR(16) DEFAULT NULL COMMENT '家庭经济情况：一般/困难/特别困难',
  status             VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active 在读 / suspended 休学 / quit 退学 / graduated 毕业',
  created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted            TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sp_user (user_id),
  KEY idx_sp_student_no (student_no),
  KEY idx_sp_counselor (counselor_id, status),
  KEY idx_sp_class (class_name, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='学工平台·学生档案';

-- ------------------------------------------------------------
-- 2. 请假 / 离校 / 外出
--    状态机：pending 待审 → approved 批准 → closed 已销假
--                        ↘ rejected 驳回
--    学生也可以 cancel（pending 阶段撤回）。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS leave_request (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  student_id    BIGINT       NOT NULL COMMENT 'users.id（学生）',
  type          VARCHAR(16)  NOT NULL DEFAULT 'personal' COMMENT 'personal 事假 / sick 病假 / leave_school 离校 / outing 外出 / internship 实习',
  start_at      DATETIME     NOT NULL,
  end_at        DATETIME     NOT NULL,
  days          DECIMAL(4,1) DEFAULT NULL COMMENT '请假天数（含首尾，按半天计）',
  reason        VARCHAR(500) DEFAULT NULL,
  place         VARCHAR(128) DEFAULT NULL COMMENT '去向',
  contact       VARCHAR(32)  DEFAULT NULL COMMENT '本人联系电话',
  attachment    VARCHAR(255) DEFAULT NULL COMMENT '证明材料路径',
  status        VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending / approved / rejected / closed / cancelled',
  approver_id   BIGINT       DEFAULT NULL,
  approve_note  VARCHAR(255) DEFAULT NULL,
  approved_at   DATETIME     DEFAULT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_lr_student (student_id, created_at),
  KEY idx_lr_status (status, created_at),
  KEY idx_lr_approver (approver_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='学工平台·请假与离校';

-- ------------------------------------------------------------
-- 3. 奖励与处分
--    kind=award 时 amount 是奖励金额；kind=punishment 时 doc_no 是处分文号。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS award_punishment (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  student_id  BIGINT        NOT NULL,
  kind        VARCHAR(16)   NOT NULL COMMENT 'award 奖励 / punishment 处分',
  level       VARCHAR(16)   DEFAULT NULL COMMENT 'national / provincial / school / college',
  title       VARCHAR(128)  NOT NULL,
  reason      VARCHAR(500)  DEFAULT NULL,
  amount      DECIMAL(10,2) DEFAULT NULL,
  doc_no      VARCHAR(64)   DEFAULT NULL COMMENT '文号',
  granted_on  DATE          DEFAULT NULL,
  revoked_on  DATE          DEFAULT NULL COMMENT '处分解除日期',
  operator_id BIGINT        DEFAULT NULL COMMENT '录入人 users.id',
  created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted     TINYINT(1)    NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_ap_student (student_id, kind),
  KEY idx_ap_granted (granted_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='学工平台·奖励与处分';

-- ------------------------------------------------------------
-- 4. 困难认定与资助申请
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS aid_application (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  student_id    BIGINT        NOT NULL,
  aid_type      VARCHAR(32)   NOT NULL COMMENT 'need 困难认定 / grant 助学金 / temporary 临时困难补助 / workstudy 勤工助学',
  level         VARCHAR(16)   DEFAULT NULL COMMENT 'general 一般困难 / difficult 困难 / special 特别困难',
  amount        DECIMAL(10,2) DEFAULT NULL,
  reason        VARCHAR(500)  DEFAULT NULL,
  evidence      VARCHAR(255)  DEFAULT NULL,
  status        VARCHAR(16)   NOT NULL DEFAULT 'pending' COMMENT 'pending / approved / rejected / cancelled',
  approver_id   BIGINT        DEFAULT NULL,
  approve_note  VARCHAR(255)  DEFAULT NULL,
  academic_year VARCHAR(16)   DEFAULT NULL COMMENT '学年（如 2026-2027）',
  created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT(1)    NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_aa_student (student_id, created_at),
  KEY idx_aa_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='学工平台·困难认定与资助';

-- ------------------------------------------------------------
-- 5. 综合素质测评指标（德智体美劳五维）
--    指标可配置；weight 五维内加权，max_score 是该项满分。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS eval_indicator (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  dimension   VARCHAR(16)  NOT NULL COMMENT 'moral 德 / intellectual 智 / physical 体 / aesthetic 美 / labor 劳',
  code        VARCHAR(32)  NOT NULL COMMENT '稳定编码，前端按它取指标',
  name        VARCHAR(64)  NOT NULL,
  description VARCHAR(255) DEFAULT NULL,
  max_score   DECIMAL(5,2) NOT NULL DEFAULT 100.00,
  weight      DECIMAL(5,4) NOT NULL DEFAULT 0.2000 COMMENT '维度内权重，合计 1.0000',
  sort_order  INT          NOT NULL DEFAULT 0,
  active      TINYINT(1)   NOT NULL DEFAULT 1,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ei_code (code),
  KEY idx_ei_dim (dimension, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='学工平台·综测指标';

-- 兼容早期建表：dimension 曾经是 VARCHAR(8)，装不下 intellectual。
-- 幂等加宽（information_schema 判断 + PREPARE，可反复执行）。
SET @ddl := (SELECT IF(
  (SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'eval_indicator' AND COLUMN_NAME = 'dimension') < 16,
  'ALTER TABLE eval_indicator MODIFY COLUMN dimension VARCHAR(16) NOT NULL COMMENT ''德智体美劳维度''',
  'SELECT 1'));
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

-- ------------------------------------------------------------
-- 6. 综测逐项得分（同一学生/指标/学期/评分来源只有一条）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS eval_score (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  student_id    BIGINT       NOT NULL,
  indicator_id  BIGINT       NOT NULL,
  academic_term VARCHAR(16)  NOT NULL COMMENT '学期（如 2026-2027-1）',
  score         DECIMAL(5,2) NOT NULL DEFAULT 0.00,
  source        VARCHAR(16)  NOT NULL DEFAULT 'self' COMMENT 'self 自评 / class 班评 / counselor 辅导员 / system 系统',
  evidence      VARCHAR(255) DEFAULT NULL,
  scorer_id     BIGINT       DEFAULT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_es_once (student_id, indicator_id, academic_term, source),
  KEY idx_es_term (academic_term, student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='学工平台·综测得分';

-- ------------------------------------------------------------
-- 7. 综测学期汇总（排名与等级由 POST /api/eval/compute 重算）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS eval_summary (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  student_id    BIGINT       NOT NULL,
  academic_term VARCHAR(16)  NOT NULL,
  total_score   DECIMAL(6,2) NOT NULL DEFAULT 0.00,
  rank_in_class INT          DEFAULT NULL,
  grade_level   VARCHAR(8)   DEFAULT NULL COMMENT '优秀 / 良好 / 合格 / 不合格',
  detail_json   TEXT         DEFAULT NULL COMMENT '各维度得分快照（JSON）',
  computed_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_esum_once (student_id, academic_term),
  KEY idx_esum_rank (academic_term, total_score)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='学工平台·综测学期汇总';

-- ------------------------------------------------------------
-- 自检：应输出 7 张表的行数（指标表见 nest_xuegong_seed.sql）
-- ------------------------------------------------------------
SELECT 'student_profile' AS table_name, COUNT(*) AS rows_now FROM student_profile
UNION ALL SELECT 'leave_request',      COUNT(*) FROM leave_request
UNION ALL SELECT 'award_punishment',  COUNT(*) FROM award_punishment
UNION ALL SELECT 'aid_application',   COUNT(*) FROM aid_application
UNION ALL SELECT 'eval_indicator',    COUNT(*) FROM eval_indicator
UNION ALL SELECT 'eval_score',        COUNT(*) FROM eval_score
UNION ALL SELECT 'eval_summary',      COUNT(*) FROM eval_summary;
