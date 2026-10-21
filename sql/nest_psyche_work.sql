-- =============================================================================
-- 学工平台 · P14 心理健康（约 8 张 psyche_* 业务表）（sql/nest_psyche_work.sql）
--
-- 口径出处（权威契约：《学工平台功能对齐说明》§9，2026-10-16 抓取的一手材料）：
--   · S16《心理健康服务中心预约方式与制度》
--        四条原则 = 保密性 / 自愿性 / 时间限定（每次 50 分钟）/ 信赖性；
--        预约方式 = 电话 0596-6288525、18960027525；来访预约「主5#118」；
--        接受预约时间 = 周一至周五 8:00-17:00；
--        非应激必须「提前至少一个工作日」预约；预约需提供姓名 / 联系方式 / 主要咨询问题。
--   · S17《关于开展线上心理咨询服务的通知》
--        线上五步 = 腾讯会议调试 → 提前半天短信确认 → 开始前 10 分钟发会议室号 → 视频咨询
--                  → 续约可直接向咨询师申请、免去网上预约程序。
--   · S8《关于开展 2021 级新生心理普查的通知》
--        对象按年级；以院系为单位分别进行（相同时段内分批）；
--        普查结束后「随机抽查一部分学生面对面访谈」；
--        必须完成所有题目，否则测试无效、按未参加处理；
--        点名量表 = SCL-90 与 SDS（题本不在公开出处，见下）。
--   · S18《我院心理健康服务中心开始运行》= 中心沿革与服务形态（一对一会谈 / 心理测试 / 长期治疗）。
--
-- 与「宿舍域」的边界（重要）：既有的 dorm_psyche_answer / dorm_psyche_eval / dorm_psyche_profile /
-- dorm_psyche_text（sql/nest_psyche.sql、sql/nest_psyche_v2.sql）与 /api/dorm/psyche/** 是
-- **宿舍分配用的 LLM 心理文本抽取**（PsycheEvalService 等），不是心理普查。两套数据严格分开：
-- 本文件只建 psyche_* 前缀的表，路由只走 /api/psyche/**。
--
-- 关键口径（不要"好心"越界）：
--   1. 平台**不做量表计分诊断**：题本（item_json）与分档阈值（band_json）都由学校导入；
--      默认只带一个「演示自评问卷」并标注「非临床量表、不可用于诊断」。
--      SCL-90 / SDS 只留记录位（source_note 写明题本不在公开出处，平台不内置题目）。
--   2. 结果结论（result_level = normal / attention / warning）由心理中心/辅导员按测评系统结果
--      **回填**或 CSV 导入，平台不自动判定学生心理状况（公开材料没有任何可自动化的判定规则）。
--   3. 保密性（S16）：列表脱敏（姓名打码、学号后四位），关注台账与转介只给 admin 与该生辅导员。
--
-- 与 §9 的偏差（如实登记）：§9 写的是 7 张表，路由里却有 POST /focus/{id}/followups，
-- 跟进记录需要留痕 ⇒ 这里多建第 8 张 psyche_followup（跟进流水），其余 7 张与 §9 一致。
--
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，可反复执行；本文件不插入任何演示数据
--       （演示数据在 sql/nest_psyche_work_demo.sql）。
--
-- 执行（Windows / cmd；不要用 mysql -e "source ..."，会报 ERROR 1064）：
--   cmd /c "mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest -t < sql\nest_psyche_work.sql"
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. psyche_instrument —— 量表/问卷定义（题本 + 分档阈值由学校导入）
--    item_json：题目数组，形如
--      [{"no":1,"text":"……","options":[{"value":0,"label":"没有"},…]}, …]
--    band_json：分档阈值，形如
--      [{"band":"normal","max":20},{"band":"attention","max":40},{"band":"warning","max":999}]
--    平台只按 band_json 折算 score_band，不解释临床意义。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS psyche_instrument (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    code         VARCHAR(64)  NOT NULL COMMENT '量表编码，如 demo-self-rating / scl90 / sds',
    name         VARCHAR(128) NOT NULL COMMENT '量表名称，如「演示自评问卷」',
    source_note  VARCHAR(500) NULL COMMENT '出处说明：学校测评系统在用/公开出处缺失/非临床量表不可用于诊断等',
    item_json    LONGTEXT     NULL COMMENT '题本 JSON（由学校导入；SCL-90/SDS 不内置题目）',
    band_json    TEXT         NULL COMMENT '分档阈值 JSON（band -> 分数上限）',
    item_count   INT          NOT NULL DEFAULT 0 COMMENT '题目数（由 item_json 汇总，供校验"必须答完"）',
    guide_text   VARCHAR(1000) NULL COMMENT '作答提示，如「按最近 2 周左右的实际状况填写」',
    active       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 / 0 停用',
    created_by   BIGINT       NULL COMMENT '创建人 users.id',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pi_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·P14 心理健康量表定义（题本与阈值由学校导入）';


-- -----------------------------------------------------------------------------
-- 2. psyche_batch —— 普查批次（S8：按年级组织 + 时间窗 + 抽查访谈）
--    status：draft 草稿 → open 进行中（已发布）→ closed 已关闭（10-30 关闭系统后进入数据分析）
--    一个批次带一个量表；要同时做 SCL-90 与 SDS 就建两个批次（同一 academic_year / grade）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS psyche_batch (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    batch_name         VARCHAR(128) NOT NULL COMMENT '批次名称，如「2026 级新生心理普查」',
    grade              VARCHAR(16)  NOT NULL COMMENT '普查对象年级，如 2026（S8：按年级）',
    academic_year      VARCHAR(16)  NULL COMMENT '学年，如 2026-2027',
    instrument_id      BIGINT       NULL COMMENT 'psyche_instrument.id（一个批次一个量表）',
    open_at            DATETIME     NULL COMMENT '开放时间（S8：10-12 至 10-30）',
    close_at           DATETIME     NULL COMMENT '关闭时间',
    interview_required TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否需要抽查面对面访谈（S8：普查结束后随机抽查一部分学生）',
    interview_note     VARCHAR(500) NULL COMMENT '抽查访谈说明（给管理端提示）',
    notice_title       VARCHAR(255) NULL COMMENT '通知标题（正文由 P8 公告或外部文件承载）',
    notice_url         VARCHAR(500) NULL COMMENT '通知链接',
    status             VARCHAR(16)  NOT NULL DEFAULT 'draft' COMMENT '状态：draft 草稿 / open 进行中 / closed 已关闭',
    published_at       DATETIME     NULL COMMENT '发布时间',
    closed_at          DATETIME     NULL COMMENT '关闭时间',
    created_by         BIGINT       NULL COMMENT '创建人 users.id',
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted            TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_pb_status (status),
    KEY idx_pb_grade (grade, academic_year),
    KEY idx_pb_window (open_at, close_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·P14 心理普查批次（S8 按年级/时间窗/抽查访谈）';


-- -----------------------------------------------------------------------------
-- 3. psyche_task —— 批次内学生名单与作答进度（一人一批次一条）
--    status：pending 未开始 / in_progress 作答中 / completed 已完成 / absent 未参加
--            （S8：必须完成所有题目，否则测试无效、按未参加处理 ⇒ absent 由系统在关窗后置位）
--    result_level：normal 正常 / attention 关注 / warning 预警 —— **回填**字段，平台不自动判定
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS psyche_task (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    batch_id        BIGINT       NOT NULL COMMENT 'psyche_batch.id',
    student_id      BIGINT       NOT NULL COMMENT 'users.id（学生）',
    status          VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT '状态：pending 未开始 / in_progress 作答中 / completed 已完成 / absent 未参加',
    answer_count    INT          NOT NULL DEFAULT 0 COMMENT '已答题目数',
    total_items     INT          NULL COMMENT '该批次量表题目数（提交时校验必须答完）',
    score_total     DECIMAL(6,2) NULL COMMENT '卷面总分（按 band_json 折算用；不是诊断结论）',
    score_band      VARCHAR(32)  NULL COMMENT '按 band_json 折算出的档位名',
    result_level    VARCHAR(16)  NULL COMMENT '结果等级（**中心/辅导员回填**）：normal 正常 / attention 关注 / warning 预警',
    result_note     VARCHAR(500) NULL COMMENT '结果备注（中心/辅导员回填的说明）',
    result_by       BIGINT       NULL COMMENT '回填人 users.id',
    interviewed     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已抽查访谈（S8：随机抽查一部分学生面对面访谈）',
    interview_note  VARCHAR(500) NULL COMMENT '访谈记录',
    interviewed_by  BIGINT       NULL COMMENT '访谈人 users.id',
    interviewed_at  DATETIME     NULL COMMENT '访谈时间',
    started_at      DATETIME     NULL COMMENT '开始作答时间',
    submitted_at    DATETIME     NULL COMMENT '提交时间',
    duration_seconds INT         NULL COMMENT '作答用时（秒）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pt_batch_student (batch_id, student_id),
    KEY idx_pt_batch_status (batch_id, status),
    KEY idx_pt_student (student_id, status),
    KEY idx_pt_level (result_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·P14 心理普查名单与进度（含抽查访谈与结果回填）';


-- -----------------------------------------------------------------------------
-- 4. psyche_answer —— 整卷作答留痕（一份卷一条，答案在 JSON 里）
--    保留原始作答是为了让学生能回看、也让中心在必要时核对（S8：可上一题/下一题查看与修改）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS psyche_answer (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    task_id          BIGINT       NOT NULL COMMENT 'psyche_task.id',
    student_id       BIGINT       NOT NULL COMMENT 'users.id（学生，冗余便于脱敏导出）',
    answer_json      LONGTEXT     NULL COMMENT '作答 JSON：[{"no":1,"value":2}, …]',
    answer_count     INT          NOT NULL DEFAULT 0 COMMENT '已答题目数',
    total_items      INT          NULL COMMENT '题目总数',
    score_total      DECIMAL(6,2) NULL COMMENT '卷面总分',
    score_band       VARCHAR(32)  NULL COMMENT '档位名',
    duration_seconds INT          NULL COMMENT '作答用时（秒）',
    submitted_at     DATETIME     NULL COMMENT '提交时间',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pa_task (task_id),
    KEY idx_pa_student (student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·P14 心理普查整卷作答';


-- -----------------------------------------------------------------------------
-- 5. psyche_focus —— 关注台账（等级 / 来源 / 措施 / 跟进负责人）
--    level：general 一般关注 / key 重点关注 / risk 危机预警
--    source：task 普查结果 / apply 学生求助 / counselor 辅导员发现 / appointment 咨询中发现 / referral 转介
--    **注意**：level 不在学生端展示（§9 口径 7：自愿性与保密性原则下不给学生贴标签）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS psyche_focus (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    student_id  BIGINT        NOT NULL COMMENT 'users.id（学生）',
    batch_id    BIGINT        NULL COMMENT '来源批次 psyche_batch.id（可空）',
    level       VARCHAR(16)   NOT NULL DEFAULT 'general' COMMENT '等级：general 一般关注 / key 重点关注 / risk 危机预警',
    source      VARCHAR(32)   NOT NULL DEFAULT 'task' COMMENT '来源：task 普查 / apply 学生求助 / counselor 辅导员发现 / appointment 咨询 / referral 转介 / other',
    source_id   BIGINT        NULL COMMENT '来源记录 id（如 psyche_task.id / psyche_appointment.id）',
    summary     VARCHAR(500)  NULL COMMENT '关注缘由（不含临床诊断结论）',
    measures    VARCHAR(1000) NULL COMMENT '已采取措施',
    counselor_id BIGINT       NULL COMMENT '跟进辅导员 users.id',
    status      VARCHAR(16)   NOT NULL DEFAULT 'open' COMMENT '状态：open 待跟进 / following 跟进中 / closed 已关闭',
    opened_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
    closed_at   DATETIME      NULL COMMENT '关闭时间',
    created_by  BIGINT        NULL COMMENT '登记人 users.id',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_pf_student (student_id, status),
    KEY idx_pf_level (level, status),
    KEY idx_pf_counselor (counselor_id, status),
    KEY idx_pf_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·P14 心理关注台账（仅 admin 与该生辅导员可见）';


-- -----------------------------------------------------------------------------
-- 6. psyche_followup —— 关注台账跟进流水（§9 的 7 张表之外，为本文件的第 8 张，理由见文件头）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS psyche_followup (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    focus_id    BIGINT        NOT NULL COMMENT 'psyche_focus.id',
    content     VARCHAR(1000) NOT NULL COMMENT '跟进内容（谈话 / 联系中心 / 家长沟通等）',
    measures    VARCHAR(1000) NULL COMMENT '本次措施',
    next_plan   VARCHAR(500)  NULL COMMENT '下一步计划',
    operator_id BIGINT        NULL COMMENT '记录人 users.id',
    happened_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '跟进时间',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_pfl_focus (focus_id, happened_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·P14 心理关注台账跟进流水';


-- -----------------------------------------------------------------------------
-- 7. psyche_appointment —— 预约咨询（S16 时间限定 50 分钟 + S17 线上五步与续约）
--    mode：office 面询（主5#118）/ online 线上（腾讯会议）
--    status：requested 已申请 → confirmed 已确认 → done 已完成 / cancelled 已取消 / no_show 未到
--    renewed_from：续约来源（S17：续约可直接向咨询师申请，免去网上预约程序）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS psyche_appointment (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    student_id       BIGINT        NOT NULL COMMENT 'users.id（学生）',
    counselor_id     BIGINT        NULL COMMENT '咨询师/辅导员 users.id（续约时沿用上次）',
    mode             VARCHAR(16)   NOT NULL DEFAULT 'office' COMMENT '方式：office 面询（主5#118）/ online 线上（腾讯会议）',
    main_issue       VARCHAR(500)  NULL COMMENT '主要咨询问题（S16：预约时需提供；**导出与列表不含全文**）',
    contact_phone    VARCHAR(32)   NULL COMMENT '联系方式（S16：预约时需提供，信息保密）',
    expect_start     DATETIME      NOT NULL COMMENT '期望开始时间（S16：须提前至少一个工作日；时段 周一至周五 8:00-17:00）',
    expect_end       DATETIME      NULL COMMENT '期望结束时间（默认 = 开始 + 50 分钟）',
    duration_minutes INT           NOT NULL DEFAULT 50 COMMENT '单次时长（分钟，S16 时间限定：每次 50 分钟）',
    status           VARCHAR(16)   NOT NULL DEFAULT 'requested' COMMENT '状态：requested 已申请 / confirmed 已确认 / done 已完成 / cancelled 已取消 / no_show 未到',
    confirmed_at     DATETIME      NULL COMMENT '确认时间（S17：提前半天短信确认）',
    confirmed_by     BIGINT        NULL COMMENT '确认人 users.id',
    finished_at      DATETIME      NULL COMMENT '完成时间',
    cancel_reason    VARCHAR(500)  NULL COMMENT '取消原因',
    renewed_from     BIGINT        NULL COMMENT '续约来源 psyche_appointment.id（S17：续约免网上预约程序）',
    note             VARCHAR(500)  NULL COMMENT '中心内部备注（学生端不可见）',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted          TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_pap_student (student_id, status),
    KEY idx_pap_status_expect (status, expect_start),
    KEY idx_pap_counselor (counselor_id, status),
    KEY idx_pap_renewed (renewed_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·P14 心理咨询预约（S16/S17 时间限定与续约）';


-- -----------------------------------------------------------------------------
-- 8. psyche_referral —— 转介登记与跟踪（校级中心 / 医院 / 家庭）
--    target：campus 校内心理健康服务中心 / hospital 医院 / family 家庭配合
--    公开出处**没有**校内危机干预与转介制度文本（S7「流程与制度」栏目只有 2 篇）⇒ 字段为推定，
--    只做「登记 + 跟踪留痕」，不写任何流程结论。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS psyche_referral (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    student_id   BIGINT        NOT NULL COMMENT 'users.id（学生）',
    source_type  VARCHAR(32)   NOT NULL DEFAULT 'focus' COMMENT '来源类型：task 普查 / focus 关注台账 / appointment 咨询 / other',
    source_id    BIGINT        NULL COMMENT '来源记录 id',
    target       VARCHAR(16)   NOT NULL DEFAULT 'campus' COMMENT '去向：campus 校内中心 / hospital 医院 / family 家庭配合',
    reason       VARCHAR(1000) NULL COMMENT '转介原因（不含临床诊断结论）',
    referred_by  BIGINT        NULL COMMENT '转介人 users.id',
    referred_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '转介时间',
    status       VARCHAR(16)   NOT NULL DEFAULT 'open' COMMENT '状态：open 待跟踪 / following 跟踪中 / closed 已结案',
    follow_note  VARCHAR(1000) NULL COMMENT '跟踪记录（最新一条摘要）',
    closed_at    DATETIME      NULL COMMENT '结案时间',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_pr_student (student_id, status),
    KEY idx_pr_target (target, status),
    KEY idx_pr_source (source_type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·P14 心理转介登记与跟踪（字段为推定，只留痕不下结论）';


-- =============================================================================
-- 自检（读完就能确认建表结果；-t 参数下以表格输出）
-- =============================================================================
SELECT '1) psyche_* 表清单' AS item;
SELECT TABLE_NAME AS name, TABLE_ROWS AS approx_rows
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'psyche\_%'
 ORDER BY TABLE_NAME;

SELECT '2) 每张表的列数' AS item;
SELECT TABLE_NAME AS name, COUNT(*) AS cols
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'psyche\_%'
 GROUP BY TABLE_NAME ORDER BY TABLE_NAME;

SELECT '3) 唯一键与索引' AS item;
SELECT TABLE_NAME AS name, INDEX_NAME AS idx, NON_UNIQUE AS non_unique, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'psyche\_%' AND INDEX_NAME <> 'PRIMARY'
 GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE ORDER BY TABLE_NAME, INDEX_NAME;

SELECT '4) 状态字段取值域（来自列注释）' AS item;
SELECT TABLE_NAME AS name, COLUMN_NAME AS col, COLUMN_COMMENT AS cmt
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'psyche\_%'
   AND COLUMN_NAME IN ('status','level','mode','target','source','verdict')
 ORDER BY TABLE_NAME, COLUMN_NAME;

SELECT '5) 行数（建表后应为全 0）' AS item;
SELECT
  (SELECT COUNT(*) FROM psyche_instrument)  AS instrument_rows,
  (SELECT COUNT(*) FROM psyche_batch)       AS batch_rows,
  (SELECT COUNT(*) FROM psyche_task)        AS task_rows,
  (SELECT COUNT(*) FROM psyche_answer)      AS answer_rows,
  (SELECT COUNT(*) FROM psyche_focus)       AS focus_rows,
  (SELECT COUNT(*) FROM psyche_followup)    AS followup_rows,
  (SELECT COUNT(*) FROM psyche_appointment) AS appointment_rows,
  (SELECT COUNT(*) FROM psyche_referral)    AS referral_rows;
