-- =============================================================================
-- 学工平台 · P18 第二课堂成绩单（sql/nest_secondclass.sql）
--
-- 权威契约：target/p18-spec.md §2（逐字段 DDL）/ §3（枚举与状态机）/ §4（判定规则）；
--           口径来源 target/p18-sources.md：S26 = 共青团中央《关于在高校实施共青团
--           「第二课堂成绩单」制度的意见》（中青联发〔2018〕5 号，**转载非一手**）。
--
-- 诚实边界（写下来是为了以后有人想「补全」时先看这里）：
--   · S26 只给制度框架（七个内容板块、四级记录流程、可采学分式评价、与综测/评优挂钩），
--     **不给任何数值**：每类要求多少学时、学时怎么折学分、单学年上限多少，全部由学校自定。
--     因此本域的所有数值都是**可配置字段**，建表不预置任何阈值；NULL 的语义就是「学校还没定」。
--   · 成长手册 6 篇栏目原文（docs/学工平台功能对齐说明.md:38-39）通篇没有「第二课堂」「学分」
--     「学时」字样 ⇒ 校内是否已实施**未能核实**。所以页面空态直接写「待学校确认」，
--     不拿部级文件反推校内规则。
--   · 七个板块名称只在 sql/nest_secondclass_demo.sql（演示数据）里按 S26 转载版预置，
--     生产库不插入任何板块 —— 板块名与排序由学校在后台自己建。
--
-- 状态机（一律 CAS：UPDATE ... WHERE id=? AND deleted=0 AND status=#{fromStatus}）：
--   record:  submitted 已申报 → counselor_approved 辅导员已初审 → confirmed 校团委/管理员已认定
--            submitted/returned → withdrawn 学生撤回；returned → 学生改后重新提交（回到 submitted）
--            counselor_approved → rejected 认定不通过（终态）；confirmed/rejected/withdrawn 终态
--   activity: draft → published → closed（只允许前进；已 closed 的活动不能再申报）
--   category: enabled=0 只是停用（历史记录仍可查），不复用 deleted 语义
--
-- 幂等：DROP TABLE IF EXISTS + CREATE TABLE，可重复执行；**不插入任何演示数据**。
--
-- 执行（Windows / cmd；不要写 mysql -e "source ..."，也不要写 LIKE '%second_class%'）：
--   cmd /c "mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest -t < sql\nest_secondclass.sql"
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. second_class_category —— 第二课堂内容板块（可配置；7 个板块只是 S26 的建议分类）
--    required_hours / yearly_cap_hours / credit_per_hour 三列 NULL = 学校未设定，
--    页面必须显示「未设置」而不是 0 —— 0 会被误读成「要求为零」。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS second_class_record;
DROP TABLE IF EXISTS second_class_activity;
DROP TABLE IF EXISTS second_class_category;

CREATE TABLE second_class_category (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name             VARCHAR(60)  NOT NULL COMMENT '板块名称（学校自定，如「志愿公益」）',
    code             VARCHAR(40)  NOT NULL COMMENT '机器可读短码（导入/接口用，全库唯一）',
    sort_no          INT          NOT NULL DEFAULT 0 COMMENT '展示顺序（小的在前）',
    required_hours   DECIMAL(6,2) NULL COMMENT '学校设定的要求学时（毕业/学年口径由学校定），NULL=未设置',
    yearly_cap_hours DECIMAL(6,2) NULL COMMENT '单学年计入上限，NULL=不限',
    credit_per_hour  DECIMAL(5,3) NULL COMMENT '学时→学分换算系数，NULL=只统计学时不算学分',
    note             VARCHAR(200) NULL COMMENT '口径说明（页面原样展示给师生）',
    enabled          TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用（停用后不再出现在申报下拉）',
    deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '软删除',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_code (code),
    KEY idx_category_sort (enabled, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='第二课堂内容板块（可配置，无预置阈值）';


-- -----------------------------------------------------------------------------
-- 2. second_class_activity —— 第二课堂活动/项目库
--    只有 published 的活动能被学生申报；closed 表示结束（历史记录仍可查）。
--    hours/credit 是「主办方申报值」，学生申报时由服务层快照进 record，事后改活动不影响已申报记录。
-- -----------------------------------------------------------------------------
CREATE TABLE second_class_activity (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    category_id  BIGINT       NOT NULL COMMENT '所属板块 second_class_category.id',
    title        VARCHAR(200) NOT NULL COMMENT '活动名称',
    organizer    VARCHAR(200) NOT NULL COMMENT '主办单位',
    level        VARCHAR(20)  NOT NULL DEFAULT 'school' COMMENT '级别：school 校级 / college 院系 / class 班级 / other 其他',
    start_date   DATE         NOT NULL COMMENT '开始日期',
    end_date     DATE         NOT NULL COMMENT '结束日期（>= start_date，服务层校验）',
    hours        DECIMAL(6,2) NOT NULL COMMENT '主办方申报的学时（>0）',
    credit       DECIMAL(5,2) NULL COMMENT '可折学分（NULL=不折学分）',
    location     VARCHAR(120) NULL COMMENT '地点',
    description  VARCHAR(1000) NULL COMMENT '说明（申报要求、认定口径）',
    status       VARCHAR(20)  NOT NULL DEFAULT 'draft' COMMENT 'draft 草稿 / published 已发布 / closed 已结束',
    created_by   BIGINT       NULL COMMENT '创建人 users.id',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '软删除',
    PRIMARY KEY (id),
    KEY idx_activity_status (status, start_date),
    KEY idx_activity_category (category_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='第二课堂活动/项目库（学生申报的载体）';


-- -----------------------------------------------------------------------------
-- 3. second_class_record —— 学生申报与认定记录（四级流程的落库形态）
--    S26 的四级是「学生申报 → 团支部/院系审查 → 主办方审核 → 校团委认定」；
--    本系统把中间两级合并为一次「辅导员初审」（原因：团支部/主办方在系统里没有独立账号，
--    见 docs/学工平台功能对齐说明.md 的 P18 行），因此状态只有两级审核，**不假装有四级**。
--    counselor_id/reviewer_id 分别记录初审与认定是谁做的；两条时间戳分开落，便于追溯。
-- -----------------------------------------------------------------------------
CREATE TABLE second_class_record (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    activity_id     BIGINT       NULL COMMENT '活动 id（NULL=学生自报项目）',
    category_id     BIGINT       NOT NULL COMMENT '板块 id（快照来源：活动或学生选择）',
    user_id         BIGINT       NOT NULL COMMENT '学生 users.id',
    title_snapshot  VARCHAR(200) NOT NULL COMMENT '申报时标题快照（活动改名不影响历史）',
    hours           DECIMAL(6,2) NOT NULL COMMENT '申报学时（>0）',
    credit          DECIMAL(5,2) NULL COMMENT '申报学分（可空）',
    evidence_url    VARCHAR(500) NULL COMMENT '佐证材料链接/说明（自报项目建议填）',
    note            VARCHAR(500) NULL COMMENT '学生备注',
    status          VARCHAR(20)  NOT NULL DEFAULT 'submitted' COMMENT 'submitted / counselor_approved / confirmed / returned / rejected / withdrawn',
    return_reason   VARCHAR(500) NULL COMMENT '退回/不通过原因（服务层必填）',
    submitted_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次提交时间',
    counselor_id    BIGINT       NULL COMMENT '初审人 users.id',
    counselor_at    DATETIME     NULL COMMENT '初审时间',
    reviewer_id     BIGINT       NULL COMMENT '认定人 users.id',
    reviewed_at     DATETIME     NULL COMMENT '认定时间',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '软删除（历史保留；学生端用 withdrawn 而不是删除）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_record_activity_user (activity_id, user_id),
    KEY idx_record_user_status (user_id, status),
    KEY idx_record_status (status, category_id),
    KEY idx_record_reviewed (reviewed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='第二课堂申报与认定记录（两级审核，不假装四级）';
