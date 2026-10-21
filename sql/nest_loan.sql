-- ============================================================================
-- 学工平台 · P19 生源地信用助学贷款登记
-- 依据：S6 厦门大学嘉庚学院学生资助网设有「生源地贷款」独立栏目（第一手事实：
--   栏目存在；该站未公开可引用的字段清单与办理口径）。
-- 因此本表的字段是**学校自定的登记口径**，不是国家或省级标准：
--   ① 不内置任何金额上下限（政策上限随省份/年度/学段不同，系统不代填）；
--   ② 不内置生源地行政区划代码表（贷款按生源地县级资助中心办理，学校口径常为自由文本）；
--   ③ 不区分国家开发银行/其他经办行的字段差异，经办机构与合同编号都是自由文本；
--   ④ 「回执校验码」按可选字段设计 —— 国开行有该编码，但学校是否要求以学校为准。
-- 与 P5 资助申请（aid_application）、P11 困难认定不是一张账：贷款登记不影响助学金评定结果。
-- 执行顺序：nest_loan.sql →（可选）nest_loan_demo.sql
-- ============================================================================

CREATE TABLE IF NOT EXISTS `student_loan` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`         BIGINT       NOT NULL COMMENT '学生 users.id',
  `academic_year`   VARCHAR(16)  NOT NULL COMMENT '学年，形如 2026-2027（学校口径，系统不推定）',
  `origin_province` VARCHAR(32)  NOT NULL COMMENT '生源地·省（自由文本）',
  `origin_city`     VARCHAR(32)  DEFAULT NULL COMMENT '生源地·市（自由文本，可为空）',
  `origin_county`   VARCHAR(64)  NOT NULL COMMENT '生源地·县/区（自由文本，办理地）',
  `bank_name`       VARCHAR(64)  NOT NULL COMMENT '经办机构/贷款银行（自由文本）',
  `contract_no`     VARCHAR(64)  DEFAULT NULL COMMENT '合同编号（可空：合同尚未签或学生不清楚时）',
  `receipt_no`      VARCHAR(64)  DEFAULT NULL COMMENT '回执校验码（可选，是否要求由学校定）',
  `amount`          DECIMAL(10,2) NOT NULL COMMENT '登记金额（元）；系统只校验 >0 与防误填上限，不代表政策上限',
  `disbursed`       TINYINT      NOT NULL DEFAULT 0 COMMENT '学生申报是否已到账：0 未到账 / 1 已到账',
  `disbursed_at`    DATE         DEFAULT NULL COMMENT '到账日期（学生填，可空）',
  `status`          VARCHAR(20)  NOT NULL DEFAULT 'submitted' COMMENT 'submitted 已提交 / counselor_approved 辅导员已核 / confirmed 学校已确认 / returned 退回待改 / rejected 不予登记 / withdrawn 学生撤回',
  `return_reason`   VARCHAR(255) DEFAULT NULL COMMENT '退回或不予登记的原因（status 为 returned/rejected 时必填）',
  `submitted_at`    DATETIME     DEFAULT NULL COMMENT '提交时间',
  `counselor_id`    BIGINT       DEFAULT NULL COMMENT '核验辅导员 users.id',
  `counselor_at`    DATETIME     DEFAULT NULL COMMENT '核验时间',
  `reviewer_id`     BIGINT       DEFAULT NULL COMMENT '学校确认人 users.id',
  `reviewed_at`     DATETIME     DEFAULT NULL COMMENT '学校确认时间',
  `remark`          VARCHAR(255) DEFAULT NULL COMMENT '学生备注',
  `evidence_url`    VARCHAR(255) DEFAULT NULL COMMENT '佐证材料链接/说明（如回执单扫描件地址）',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_loan_user` (`user_id`, `academic_year`),
  KEY `idx_loan_status` (`status`),
  KEY `idx_loan_counselor` (`counselor_id`),
  KEY `idx_loan_year` (`academic_year`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='P19 生源地信用助学贷款登记（学校自定字段口径）';
