-- =====================================================================
-- NEST 学工平台 · P10 勤工助学（workstudy）建表脚本
-- 口径出处：docs/学工平台功能对齐说明.md §3（《学生勤工助学管理办法》第十六~三十一条）
--
-- 说明：
--   1. 幂等：全部 CREATE TABLE IF NOT EXISTS，不含 DROP TABLE，可重复执行；
--   2. 不用外键：关联完整性由 WorkstudyService 在服务层校验（与 nest_xuegong.sql 一致）；
--   3. 时间列统一 datetime NOT NULL DEFAULT CURRENT_TIMESTAMP，更新由 SQL 显式 updated_at = NOW()；
--   4. 唯一键带 deleted 列（软删后同键可重建）。
-- 执行：mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest < sql\nest_workstudy.sql
-- =====================================================================

USE nest;

-- ---------------------------------------------------------------------
-- 1. workstudy_post 勤工助学岗位（设岗申报 + 资助中心审批 + 招募公告）
--    第二十条：公告须写明岗位名称、招聘人数、招聘条件、工作内容、劳务报酬、劳动时间、面试时间
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS workstudy_post (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    post_type      VARCHAR(16)  NOT NULL DEFAULT 'fixed'   COMMENT 'fixed 固定岗（≥1 学期）/ temporary 临时岗（一次性或几次）',
    unit           VARCHAR(64)  NOT NULL                   COMMENT '用工单位（如 图书馆 / 教务处）',
    unit_user_id   BIGINT       DEFAULT NULL               COMMENT '用工单位负责人 users.id（=teacher）',
    name           VARCHAR(100) NOT NULL                   COMMENT '岗位名称',
    content        VARCHAR(500) DEFAULT NULL               COMMENT '工作内容',
    skill          VARCHAR(255) DEFAULT NULL               COMMENT '技能要求',
    headcount      INT          NOT NULL DEFAULT 1         COMMENT '招聘人数',
    hired_count    INT          NOT NULL DEFAULT 0         COMMENT '已录用人数',
    hourly_wage    DECIMAL(10,2) NOT NULL DEFAULT 0.00     COMMENT '时薪（元/小时，临时岗 ≥15）',
    hours_per_week DECIMAL(4,1) NOT NULL DEFAULT 0.0       COMMENT '每周工时上限（办法：≤8）',
    hours_per_month DECIMAL(4,1) NOT NULL DEFAULT 0.0      COMMENT '每月工时上限（办法：≤40）',
    academic_term  VARCHAR(16)  NOT NULL                   COMMENT '学期，如 2025-2026-1',
    apply_deadline DATETIME     DEFAULT NULL               COMMENT '申请截止时间',
    interview_at   DATETIME     DEFAULT NULL               COMMENT '面试时间',
    status         VARCHAR(16)  NOT NULL DEFAULT 'draft'   COMMENT 'draft 草稿 / pending 待审批 / recruiting 招募中 / closed 已关闭 / rejected 未通过',
    review_note    VARCHAR(500) DEFAULT NULL               COMMENT '审批意见 / 终止情形说明',
    reviewer_id    BIGINT       DEFAULT NULL               COMMENT '审批人 users.id（资助中心 = admin）',
    reviewed_at    DATETIME     DEFAULT NULL               COMMENT '审批时间',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_ws_post_term_status (academic_term, status),
    KEY idx_ws_post_unit_user (unit_user_id),
    KEY idx_ws_post_type (post_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='勤工助学岗位（P10）';

-- ---------------------------------------------------------------------
-- 2. workstudy_application 勤工助学申请（申请 → 面试 → 录用备案 → 辞职）
--    第二十一条：同一学生同一学期原则上不能同时在两个及以上固定岗位在岗（服务端硬校验）
--    第二十二条：用工单位面试、考核、签署意见 → 报资助中心备案 → 建档
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS workstudy_application (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    post_id          BIGINT       NOT NULL              COMMENT 'workstudy_post.id',
    student_id       BIGINT       NOT NULL              COMMENT 'users.id（学生）',
    academic_term    VARCHAR(16)  DEFAULT NULL          COMMENT '学期快照，如 2025-2026-1',
    reason           VARCHAR(500) NOT NULL              COMMENT '申请理由',
    evidence         VARCHAR(255) DEFAULT NULL          COMMENT '证明材料',
    status           VARCHAR(16)  NOT NULL DEFAULT 'pending'
                     COMMENT 'pending 待审核 / interview_passed 面试通过 / interview_failed 面试未通过 / hired 已录用 / rejected 未录用 / resigning 辞职申请中 / resigned 已离岗',
    interview_result VARCHAR(8)   DEFAULT NULL          COMMENT 'pass / fail',
    interview_note   VARCHAR(500) DEFAULT NULL          COMMENT '面试意见',
    applied_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    hired_at         DATETIME     DEFAULT NULL          COMMENT '录用时间',
    resign_reason    VARCHAR(500) DEFAULT NULL          COMMENT '辞职理由（第三十一条：书面提前 7 个工作日）',
    note             VARCHAR(500) DEFAULT NULL          COMMENT '用工单位 / 资助中心备注',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_post_student (post_id, student_id, deleted),
    KEY idx_ws_app_student (student_id, status),
    KEY idx_ws_app_post_status (post_id, status),
    KEY idx_ws_app_term (academic_term, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='勤工助学申请（P10）';

-- ---------------------------------------------------------------------
-- 3. workstudy_timesheet 勤工助学工时记录（用工单位记录 → 单位确认）
--    第十六条：每周 ≤8 小时、每月 ≤40 小时（寒暑假可延长）→ over_limit=1 只提示不阻断
--    第二十四条：固定岗每月报送、临时岗活动结束后次月报送
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS workstudy_timesheet (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    application_id BIGINT       NOT NULL              COMMENT 'workstudy_application.id（岗位+学生+学期的一笔录用关系）',
    post_id        BIGINT       NOT NULL              COMMENT 'workstudy_post.id',
    student_id     BIGINT       NOT NULL              COMMENT 'users.id（学生）',
    work_month     VARCHAR(7)   NOT NULL              COMMENT '用工月份，如 2026-10',
    work_date      DATE         NOT NULL              COMMENT '用工日期',
    hours          DECIMAL(4,1) NOT NULL DEFAULT 0.0  COMMENT '本次工时（小时）',
    content        VARCHAR(255) DEFAULT NULL          COMMENT '工作内容',
    over_limit     TINYINT(1)   NOT NULL DEFAULT 0    COMMENT '是否超办法第十六条上限（1=提示，不阻断）',
    status         VARCHAR(16)  NOT NULL DEFAULT 'submitted' COMMENT 'submitted 待确认 / confirmed 已确认',
    recorder_id    BIGINT       DEFAULT NULL          COMMENT '记录人 users.id（用工单位）',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_ws_ts_student_month (student_id, work_month),
    KEY idx_ws_ts_post_month (post_id, work_month),
    KEY idx_ws_ts_status (status),
    KEY idx_ws_ts_application (application_id),
    KEY idx_ws_ts_recorder (recorder_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='勤工助学工时记录（P10）';

-- ---------------------------------------------------------------------
-- 4. workstudy_payroll 勤工助学月酬金发放表（资助中心按月汇总「已确认」工时）
--    第二十三条：按小时计酬；第二十四条：中心汇总编制月酬金发放表 → 发放到银行卡
--    幂等键 (student_id, post_id, work_month)：重跑只更新工时与金额，不新增重复行
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS workstudy_payroll (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    student_id     BIGINT        NOT NULL              COMMENT 'users.id（学生）',
    post_id        BIGINT        NOT NULL              COMMENT 'workstudy_post.id',
    application_id BIGINT        DEFAULT NULL          COMMENT 'workstudy_application.id',
    work_month     VARCHAR(7)    NOT NULL              COMMENT '发放月份，如 2026-10',
    total_hours    DECIMAL(6,1)  NOT NULL DEFAULT 0.0  COMMENT '该学生该岗位该月已确认工时',
    hourly_wage    DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '时薪快照',
    amount         DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'amount = total_hours * hourly_wage',
    assessment     VARCHAR(500)  DEFAULT NULL          COMMENT '工作考核结果（《学生勤工助学工作考核表》摘要）',
    status         VARCHAR(16)   NOT NULL DEFAULT 'pending' COMMENT 'pending 待发放 / paid 已发放',
    payer_id       BIGINT        DEFAULT NULL          COMMENT '发放人 users.id（资助中心）',
    paid_at        DATETIME      DEFAULT NULL          COMMENT '发放时间',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        TINYINT(1)    NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payroll (student_id, post_id, work_month, deleted),
    KEY idx_ws_payroll_month_status (work_month, status),
    KEY idx_ws_payroll_student (student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='勤工助学月酬金发放表（P10）';

-- ---------------------------------------------------------------------
-- 建表自检
-- ---------------------------------------------------------------------
SELECT 'workstudy_post' AS table_name, COUNT(*) AS rows_now FROM workstudy_post
UNION ALL SELECT 'workstudy_application', COUNT(*) FROM workstudy_application
UNION ALL SELECT 'workstudy_timesheet', COUNT(*) FROM workstudy_timesheet
UNION ALL SELECT 'workstudy_payroll', COUNT(*) FROM workstudy_payroll;
