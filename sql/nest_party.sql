-- =============================================================================
-- 学工平台 · P16 学生党建（sql/nest_party.sql）
--
-- 口径出处（权威契约：《学工平台功能对齐说明》§11，11.1 一手事实 / 11.2 实现口径）：
--   · 11.2-1  平台**不做自动资格判定**：阶段推进由支部书记/辅导员手工操作，平台只做
--             「台账 + 时限提醒 + 公示留痕 + 材料清单」。所有「应当/不得」都是带条款引用
--             （rule_ref）的**提示文案，不阻断推进**。
--   · 11.2-2  7 张表统一 party_ 前缀。学生与支部的关联沿用 student_profile.counselor_id；
--             支部信息落在 party_stage.branch_name，**不新建支部主数据表**（支部主数据无来源）。
--   · 11.2-3  期限常量两条并存并标注来源（国标 S24 / 校规 S23），平台不替学校选：
--             · 申请后 1 个月内谈话（国标第七条 / 校规）
--             · 确定积极分子前党支部 2 个月以上培养教育（校规）
--             · 积极分子 -> 发展对象 1 年以上（国标第十三条 / 校规）
--             · 积极分子考察：国标每半年一次（第十一条）/ 校规每季度考察 —— 两条都标来源
--             · 党校培训：国标不少于 3 天（或 24 学时，第十七条）/ 校规 5-7 天（或不少于 40 学时）
--             · 预备期 1 年，延长一次 6-12 个月（国标第三十一/三十二条）
--             · 上级审批 3 个月内、最长不超过 6 个月（国标第二十六条）
--             · 公示 5 个工作日（校规 5-7 天，S22 三个公示节点均为「5 个工作日」）
--             · 思想汇报至少每季度；超 3 个月未交提示「可能取消积极分子资格」（校规）
--             · 再次确定为积极分子原则上 2 年内不得（校规）
--   · 11.2-4  学生端只下发本人台账；支部内部考察意见（internal_note）、推优讨论内容
--             （internal_discussion）与外单位内部备注（internal_remark）由**服务层抹掉**，
--             不靠前端不渲染。
--   · 11.2-5  权限沿用 users.role（student/teacher/admin）：学生只见自己；teacher 只见
--             student_profile.counselor_id 名下学生；admin 全量并可管理公示。
--   · 11.2-6  公示含起止日期、工作日数、依据文本与异议入口；异议入口只写**校级**受理渠道，
--             不写支部个人联系方式；名单公示需登录（匿名 401）。
--   · 11.2-7  党校班次（名称/起止/学时/地点/主办/名额）-> 报名（学生自报 -> 辅导员审核）
--             -> 结业登记（合格/不合格 + 结业日期 + 证书编号 + 有效期至，默认自颁发之日起
--             一年内有效，可人工覆盖）。
--   · 11.2-8  材料清单只登记「名称 + 应交阶段 + 已交与否 + 备注 + 附件文件名」；
--             平台**不生成官方表格 PDF**（三张考察表字段结构未获全文，禁止臆造）。
--
-- 未核实、因此**不进本文件**的内容（§11.1 诚实清单）：
--   ① 校内《细则(2014)》附件为扫描件，本机无 OCR；国标引句来自共产党员网公开转载；
--   ②《入党积极分子考察表》《预备党员考察表》《入党志愿书》字段结构未获全文；
--   ③ 党校结业标准（及格线、考核权重）无任何来源 —— 本表**不建**分数/及格线字段；
--   ④「发展党员公示」的载体与受理主体未明 —— 异议入口只登记**文本**，不虚构联系方式；
--   ⑤ 发展对象 -> 预备党员的校内统一间隔无规定 —— 不做该步的硬性时限校验；
--   ⑥ 各学院党支部数量与组织架构无来源 —— 不建支部主数据表，支部只作 party_stage 上的文本。
--
-- 状态迁移一律 CAS：WHERE id=#{id} AND deleted=0 AND stage=#{fromStage}（stage）/
--                    WHERE id=#{id} AND deleted=0 AND status=#{fromStatus}（status）。
-- 且服务层**必须先显式前置校验**，不能把「刚读到的值」当作放行的理由（否则 WHERE 恒真）。
--
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，可反复执行；本文件不插入任何演示数据
--       （演示数据见 sql/nest_party_demo.sql）。
--
-- 执行（Windows / cmd，不要用 mysql -e "source ..."；也不要写 `LIKE '%party_%'`，
--       cmd 会吃掉 %）：
--   cmd /c "mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest -t < sql\nest_party.sql"
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. party_application —— 入党申请登记
--    一个学生一条有效记录（UNIQUE user_id + deleted）。
--    talk_date / talk_by 对应「一个月内派人同入党申请人谈话」（国标第七条 / 校规）。
--    谈话是否在时限内由服务层按 TALK_WITHIN_DAYS 计算并**只提示不阻断**。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS party_application (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id           BIGINT       NOT NULL COMMENT '申请学生 users.id',
    branch_name       VARCHAR(128) NULL COMMENT '申请时所在党支部（文本登记；支部主数据无来源，不建表）',
    apply_date        DATE         NOT NULL COMMENT '递交入党申请书日期',
    talk_date         DATE         NULL COMMENT '党支部派人谈话日期（国标第七条 / 校规：一个月内）',
    talk_by           VARCHAR(64)  NULL COMMENT '谈话人（姓名或职务文本）',
    talk_summary      VARCHAR(500) NULL COMMENT '谈话情况摘要',
    /* 内部字段：学生端一律抹掉（§11.2-4），仅 staff 可见 */
    internal_remark   VARCHAR(500) NULL COMMENT '支部内部备注（学生端服务层抹掉）',
    status            VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active 有效 / withdrawn 撤回',
    created_by        BIGINT       NULL COMMENT '登记人 users.id',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pa_user (user_id, deleted),
    KEY idx_pa_apply (apply_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·学生党建·入党申请登记';


-- -----------------------------------------------------------------------------
-- 2. party_stage —— 阶段台账（一人一行，当前所处阶段 + 各步发生日期）
--    阶段枚举固定五档且**只能向前**：applicant -> activist -> candidate -> probationary -> full
--       applicant   申请人
--       activist    入党积极分子
--       candidate   发展对象
--       probationary 预备党员
--       full        正式党员
--    每步「发生日期 + 该步公示起止 + 依据说明」：
--       *_date          该步发生日期
--       *_ref           该步依据说明（rule_ref 条款引用文本，如「S23 第二条：两个月以上」）
--    公示起止与工作日数不落本表：公示是**独立节点**（S22 第 11/14/25 步），统一在
--    party_publicity 里留痕，本表只记 stage_publicity_id 指向当步那次公示。
--    推进 CAS：WHERE id=#{id} AND deleted=0 AND stage=#{fromStage}，fromStage 取**显式枚举前置态**。
--    回退需 admin + 原因 + 审计（rollback_reason / rollback_at）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS party_stage (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id             BIGINT       NOT NULL COMMENT '学生 users.id',
    branch_name         VARCHAR(128) NULL COMMENT '所在党支部（文本；支部主数据无来源，不建表）',
    stage               VARCHAR(16)  NOT NULL DEFAULT 'applicant' COMMENT 'applicant 申请人 / activist 入党积极分子 / candidate 发展对象 / probationary 预备党员 / full 正式党员（只向前）',
    applicant_date      DATE         NULL COMMENT '第一步·确定为入党申请人日期',
    applicant_ref       VARCHAR(300) NULL COMMENT '第一步依据说明',
    activist_date       DATE         NULL COMMENT '第二步·确定为入党积极分子日期',
    activist_ref        VARCHAR(300) NULL COMMENT '第二步依据说明（校规：党支部两个月以上培养教育）',
    candidate_date      DATE         NULL COMMENT '第三步·列为发展对象日期',
    candidate_ref       VARCHAR(300) NULL COMMENT '第三步依据说明（国标第十三条：一年以上）',
    probationary_date   DATE         NULL COMMENT '第四步·接收为预备党员日期（支部大会通过之日，国标第三十二条）',
    probationary_ref    VARCHAR(300) NULL COMMENT '第四步依据说明',
    full_date           DATE         NULL COMMENT '第五步·转为正式党员日期（党龄自此起算，国标第三十四条）',
    full_ref            VARCHAR(300) NULL COMMENT '第五步依据说明',
    /* 内部字段：学生端一律抹掉（§11.2-4），仅 staff 可见 */
    internal_note       VARCHAR(500) NULL COMMENT '支部内部考察意见（学生端服务层抹掉）',
    internal_discussion VARCHAR(500) NULL COMMENT '推优讨论内容（学生端服务层抹掉）',
    stage_publicity_id  BIGINT       NULL COMMENT '当前阶段的公示 party_publicity.id（S22 第 11/14/25 步）',
    rollback_reason     VARCHAR(500) NULL COMMENT '最近一次阶段回退原因（回退需 admin + 原因 + 审计）',
    rollback_at         DATETIME     NULL COMMENT '最近一次阶段回退时间',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted             TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ps_user (user_id, deleted),
    KEY idx_ps_stage (stage),
    KEY idx_ps_branch (branch_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·学生党建·阶段台账';


-- -----------------------------------------------------------------------------
-- 3. party_observation —— 考察与思想汇报台账
--    type = thought_report 思想汇报（学生自报）/ observation 考察记录（支部登记）
--    思想汇报：国标无频率规定，校规「至少每季度……书面思想汇报」「超过三个月不交思想汇报
--    可能取消积极分子资格」——服务层按 THOUGHT_REPORT_WARN_DAYS 计算 over_due 并**只提示**。
--    考察：国标每半年一次（第十一条）/ 校规每季度考察 —— 两条来源并存，只做提示。
--    审核人/审核时间：review_by / review_at；审核意见 review_opinion。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS party_observation (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id          BIGINT       NOT NULL COMMENT '学生 users.id',
    type             VARCHAR(16)  NOT NULL DEFAULT 'thought_report' COMMENT 'thought_report 思想汇报 / observation 考察记录',
    submitted_at     DATETIME     NOT NULL COMMENT '提交时间（思想汇报 = 学生提交；考察 = 考察组织时间）',
    period_label     VARCHAR(32)  NULL COMMENT '所属期次文本，如 2026-1（第一季度）',
    content          TEXT         NULL COMMENT '思想汇报内容 / 考察情况记录',
    review_status    VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending 待审核 / reviewed 已审核 / returned 退回',
    review_by        BIGINT       NULL COMMENT '审核人 users.id',
    review_at        DATETIME     NULL COMMENT '审核时间',
    review_opinion   VARCHAR(500) NULL COMMENT '审核意见（可下发学生本人）',
    /* 内部字段：学生端一律抹掉（§11.2-4），仅 staff 可见 */
    internal_note    VARCHAR(500) NULL COMMENT '支部内部考察意见（学生端服务层抹掉）',
    rule_ref         VARCHAR(300) NULL COMMENT '时限/依据条款引用文本（如「S23：至少每季度书面思想汇报」）',
    created_by       BIGINT       NULL COMMENT '登记人 users.id（思想汇报为学生本人）',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_po_user_type (user_id, type, deleted),
    KEY idx_po_submitted (submitted_at),
    KEY idx_po_review (review_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·学生党建·考察与思想汇报台账';


-- -----------------------------------------------------------------------------
-- 4. party_training —— 党校培训班次（S22 第 11 步「确定党校学员」）
--    培训时长两条标准并存、平台不替学校选（§11.2-3）：
--      国标 S24 第十七条：不少于三天（或不少于二十四个学时）
--      校规 S23：五至七天（或不少于四十个学时）
--    => 本表**不设及格线/考核权重**（§11.1-③ 无任何来源，禁止臆造），只登记天数与学时。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS party_training (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    training_name VARCHAR(128) NOT NULL COMMENT '班次名称，如「2026 年第一期入党积极分子培训班」',
    term          VARCHAR(32)  NULL COMMENT '期次文本，如 2026-1',
    start_date    DATE         NULL COMMENT '开班日期',
    end_date      DATE         NULL COMMENT '结业日期',
    days          INT          NULL COMMENT '培训天数（国标不少于 3 天 / 校规 5-7 天，两条来源并存，平台不校验选哪条）',
    hours         INT          NULL COMMENT '培训学时（国标不少于 24 学时 / 校规不少于 40 学时，两条并存，平台不替学校选）',
    location      VARCHAR(128) NULL COMMENT '培训地点',
    organizer     VARCHAR(128) NULL COMMENT '主办单位',
    quota         INT          NOT NULL DEFAULT 0 COMMENT '名额上限：0 = 不限',
    status        VARCHAR(16)  NOT NULL DEFAULT 'draft' COMMENT 'draft 草稿 / signup 报名中 / ongoing 进行中 / finished 已结束 / cancelled 已取消',
    content       TEXT         NULL COMMENT '培训内容/课程安排说明',
    note          VARCHAR(500) NULL COMMENT '备注',
    created_by    BIGINT       NULL COMMENT '创建人 users.id',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_pt_status (status),
    KEY idx_pt_date (start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·学生党建·党校培训班次';


-- -----------------------------------------------------------------------------
-- 5. party_training_member —— 党校学员报名与结业
--    UNIQUE(training_id, user_id, deleted)：同一班次同一学生只允许一条报名。
--    状态机：signed 已报名 -> approved 审核通过 -> completed 已结业 / rejected 审核驳回；
--            completed 之外不写结业信息。
--    结业证书「自颁发之日起一年内有效」（校规）=> cert_valid_until 默认
--    cert_issue_date + 1 年，由服务层推算，**可人工覆盖**（传了就采信传入值）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS party_training_member (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    training_id       BIGINT       NOT NULL COMMENT 'party_training.id',
    user_id           BIGINT       NOT NULL COMMENT '学员 users.id',
    student_snapshot  VARCHAR(64)  NULL COMMENT '报名时学号快照（users.student_id）',
    class_snapshot    VARCHAR(64)  NULL COMMENT '报名时班级快照（student_profile.class_name）',
    signup_at         DATETIME     NOT NULL COMMENT '报名时间',
    status            VARCHAR(16)  NOT NULL DEFAULT 'signed' COMMENT 'signed 已报名 / approved 审核通过 / rejected 审核驳回 / completed 已结业',
    review_by         BIGINT       NULL COMMENT '审核人 users.id（辅导员/管理员）',
    review_at         DATETIME     NULL COMMENT '审核时间',
    reject_reason     VARCHAR(300) NULL COMMENT '驳回原因',
    complete_result   VARCHAR(16)  NULL COMMENT '结业结论：pass 合格 / fail 不合格（§11.1-③ 无及格线来源，本表不建分数）',
    complete_date     DATE         NULL COMMENT '结业日期',
    cert_no           VARCHAR(64)  NULL COMMENT '结业证书编号',
    cert_issue_date   DATE         NULL COMMENT '证书颁发日期',
    cert_valid_until  DATE         NULL COMMENT '证书有效期至（默认 = 颁发日期 + 一年内有效，可人工覆盖）',
    note              VARCHAR(500) NULL COMMENT '备注',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ptm_training_user (training_id, user_id, deleted),
    KEY idx_ptm_user (user_id, deleted),
    KEY idx_ptm_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·学生党建·党校学员报名与结业';


-- -----------------------------------------------------------------------------
-- 6. party_publicity —— 公示留痕（S22 三个公示节点均为「公示 5 个工作日」）
--    node_type 只登记**有来源的三类节点**（S22 第 11/14/25 步）：
--       training_student  确定党校学员公示（第 11 步）
--       develop_party     发展党员公示（第 14 步）
--       probation_convert 拟转正对象公示（第 25 步）
--    状态机（全部 CAS）：
--       draft 草稿 -> publicizing 公示中 -> closed 已结束
--    规则：publish 只接受 {draft}；close 只接受 {publicizing}；
--         **已结束再 close 必须 400，不得做幂等 200**（P15 E2E 缺陷 D2 教训）。
--    异议入口 objection_channel 只写**校级**受理渠道，**不写支部个人联系方式**（§11.2-6）。
--    公示 5 个工作日：workdays 默认 5，end_date 由服务层按工作日推算（跳过周六周日）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS party_publicity (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id           BIGINT       NULL COMMENT '被公示学生 users.id（名单公示；空 = 不针对个人的事项公示）',
    node_type         VARCHAR(32)  NOT NULL COMMENT 'training_student 确定党校学员 / develop_party 发展党员 / probation_convert 拟转正对象',
    title             VARCHAR(200) NOT NULL COMMENT '公示标题',
    stage             VARCHAR(16)  NULL COMMENT '对应阶段：applicant/activist/candidate/probationary/full',
    start_date        DATE         NOT NULL COMMENT '公示起始日期',
    end_date          DATE         NOT NULL COMMENT '公示结束日期（默认按 5 个工作日推算）',
    workdays          INT          NOT NULL DEFAULT 5 COMMENT '公示工作日数（S22 各公示节点均为 5 个工作日；校规另有 5-7 天表述）',
    notice_text       TEXT         NULL COMMENT '公示正文/依据文本（含条款引用）',
    objection_channel VARCHAR(500) NULL COMMENT '异议入口（只写校级受理渠道，不写支部个人联系方式）',
    status            VARCHAR(16)  NOT NULL DEFAULT 'draft' COMMENT 'draft 草稿 / publicizing 公示中 / closed 已结束',
    result            VARCHAR(500) NULL COMMENT '公示结论/处理结果',
    published_at      DATETIME     NULL COMMENT '发布（进入公示中）时间',
    closed_at         DATETIME     NULL COMMENT '结束时间',
    created_by        BIGINT       NULL COMMENT '创建人 users.id',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_pp_status (status),
    KEY idx_pp_node (node_type),
    KEY idx_pp_user (user_id, deleted),
    KEY idx_pp_date (start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·学生党建·公示留痕';


-- -----------------------------------------------------------------------------
-- 7. party_material —— 材料清单（按阶段列出应备材料）
--    §11.2-8：**只登记名称与状态**，平台不生成官方表格 PDF
--    （三张考察表字段结构未获全文 §11.1-②，禁止臆造版式）。
--    材料名称口径取自 §11.1 有来源的名目：
--      入党申请书、入党积极分子推优登记表（S23 表样）、入党积极分子考察表、
--      预备党员考察表、入党志愿书、思想汇报（S23）、党校结业证书（S23）。
--    submitted 只登记「已交/未交 + 备注 + 附件文件名」，不落附件二进制。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS party_material (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id         BIGINT       NOT NULL COMMENT '学生 users.id',
    material_name   VARCHAR(128) NOT NULL COMMENT '材料名称（只登记名称，平台不生成官方表格 PDF）',
    stage           VARCHAR(16)  NOT NULL COMMENT '应交阶段：applicant/activist/candidate/probationary/full',
    required        TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否必备：1 必备 / 0 选备（提示口径，不阻断推进）',
    submitted       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '已交与否：1 已交 / 0 未交',
    submit_date     DATE         NULL COMMENT '交件日期',
    attachment_name VARCHAR(255) NULL COMMENT '附件文件名（只登记文件名，不落二进制）',
    note            VARCHAR(500) NULL COMMENT '备注',
    rule_ref        VARCHAR(300) NULL COMMENT '依据/来源条款引用文本',
    created_by      BIGINT       NULL COMMENT '登记人 users.id',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_pm_user_stage (user_id, stage, deleted),
    KEY idx_pm_submitted (submitted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·学生党建·材料清单';


-- -----------------------------------------------------------------------------
-- 本文件**不改动 audit_log 表结构**：audit_log.event_type 已是 VARCHAR(32)
-- （见 sql/nest_audit.sql:13），PARTY 类型无需 DDL，只需要服务层常量
-- AuditLogService.TYPE_PARTY = "PARTY" 与 typeOfPath 的 /api/party 分支。
-- 服务层另对「阶段推进 / 公示创建与关闭 / 结业登记」显式记一条业务审计（§11.2-5）。
-- -----------------------------------------------------------------------------
