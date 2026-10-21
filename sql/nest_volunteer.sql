-- =============================================================================
-- 学工平台 · P17 校园文化 · 志愿服务时长认证（sql/nest_volunteer.sql）
--
-- 权威契约：target/p17-plan.md §2（逐字段 DDL 契约）/ §3（枚举码值）/ §4（规则常量）；
--           规则原文见 target/p17-spec.md §12.1 与逐字缓存 target/p17-sources.md（S21 全文 8 章 44 条）。
--           未在原文出现的内容一律不得编造（spec §12.2 第 13 条诚实清单）。
--
-- 设计口径（每条都能追溯到条文号）：
--   · S21 第十五条(一)：校内学生社团/学生组织申请时长认定，**应在活动开展前向中心申请立项**，
--     审批通过后方可招募 => volunteer_project.status = draft / pending / approved / rejected，
--     **只有 approved 的项目可报名**（服务层校验，本文件只落库）。
--   · S21 第二十八条 四类录入/审核主体：center 中心审核+录入 / department 院系录入、中心审核 /
--     club 中心审核+录入 / student_activity 活动组织单位登记、中心审核+录入。
--     => organizer_type 与 volunteer_record.recorder_subject 共用该四值枚举。
--   · S21 第十九~二十七条计量：基本单位「小时」；以签到/签退时间为起止点（**含场地休息时间**，
--     故本表同时落 sign_in / sign_out 与 hours 两个事实，不互相推算）；
--     区分「信用时数 credit」与「荣誉时数 honor」（无法打卡或漏签须补录）；
--     每天上限 8 小时（超出**提示需中心审核**，不硬拒）；单程交通/培训不足 30 分钟不计。
--   · S21 第二十六条：项目结束 **6 个月**后不再补录或修改 => 服务层按项目 end_time 推算窗口，
--     超窗写操作返回 400。**本表不落窗口字段**（窗口是派生量，落库会与 end_time 冲突）。
--   · S21 第三十四/三十五条黑名单情形 c34_1..c34_5 / c35；第三十六条后果「**六个月内**不再被录用」
--     => expire_at = listed_at + 6 个月，由服务层按日期推算，**不做定时任务**（spec §12.2-9）。
--   · S21 第三十七条：**5 个工作日**内向相关志愿服务组织提出申诉，由组织**所在的团委**受理并裁定
--     => accept_org 默认「团委」；within_window 由服务层判定后落库（0/1），便于统计超窗。
--   · S21 第三十条志愿者星级（50/100/150/300/500）**只推算、不授予**，故本域**不建星级表**；
--     认证权属「厦门大学嘉庚学院青年志愿服务指导中心」，页面须带出处与认证主体声明。
--
-- 未核实、因此**不进本文件**的内容（spec §12.1 诚实清单 ①-⑨）：
--   ① 志愿时长「注销/清零」规则原文未规定 —— 不建清零/结转字段与逻辑；
--   ② 附件 1/2《工时补录申请表》字段未获全文 —— 只留 makeup_note 文本，**不建官方表格字段**；
--   ③ 不与「志愿中国·志愿汇」平台对接 —— 打卡起止时间人工录入，不建外部流水号字段；
--   ④ 黑名单/申诉的通知渠道（短信/邮件）不存在 —— 只登记 notify_time（告知时间）文本事实；
--   ⑤ 第十八条 11 项「不算志愿服务」清单是**页面提示**，不参与统计，不落库成标志位。
--
-- 状态机一律 CAS（P14–P16 血泪口径，spec §12.2-9）：
--   UPDATE ... WHERE id=#{id} AND deleted=0 AND status=#{fromStatus}
--   fromStatus 取**显式枚举前置态**，不是刚读到的变量值；受影响行数 0 -> 400「状态已变化，请刷新后重试」。
--   终态不可再改（record 的 approved / rejected，project 的 approved / rejected）。
--
-- 幂等：本文件 DROP TABLE IF EXISTS + CREATE TABLE，可反复执行；**不插入任何演示数据**
--       （演示数据见 sql/nest_culture_demo.sql）。
--
-- 执行（Windows / cmd；不要写 mysql -e "source ..."，也不要写 `LIKE '%volunteer_%'`，cmd 会吃掉 %）：
--   cmd /c "mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest -t < sql\nest_volunteer.sql"
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. volunteer_project —— 志愿服务项目立项（S21 第十五条 立项前置）
--    status 枚举：draft 草稿 / pending 待审批 / approved 已批准 / rejected 已驳回
--    只有 approved 的项目可被报名（服务层校验）；approved / rejected 为终态不可再改。
--    start_time / end_time 是**服务层推算 6 个月补录窗口**的依据（S21 第二十六条）。
--    duration_note 落「时长认定说明」文本，须允许写入条款引用（如「S21 第二十二~二十四条」）。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS volunteer_project;
CREATE TABLE volunteer_project (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    project_name   VARCHAR(200) NOT NULL COMMENT '项目名称',
    category       VARCHAR(50)  NOT NULL COMMENT '志愿类别（S21 第十四条 15 类中文类别，见 plan §3.3）',
    organizer      VARCHAR(200) NOT NULL COMMENT '发起组织（S21 第二十八条）',
    organizer_type VARCHAR(30)  NOT NULL COMMENT '发起组织类型：center 青年志愿服务指导中心 / department 院系志愿服务组织 / club 校内公益类组织、社团 / student_activity 学生活动组织单位',
    status         VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT 'draft 草稿 / pending 待审批 / approved 已批准 / rejected 已驳回（approved/rejected 为终态）',
    start_time     DATETIME     NULL COMMENT '活动开始时间（服务层据此推算补录 6 个月窗口，S21 第二十六条）',
    end_time       DATETIME     NULL COMMENT '活动结束时间（补录/更正窗口 = 结束时间 + 6 个月，S21 第二十六条）',
    location       VARCHAR(200) NULL COMMENT '活动地点',
    duration_note  VARCHAR(500) NULL COMMENT '时长认定说明（可写条款引用；S21 第二十二~二十四条 8 小时上限与 30 分钟门槛）',
    recruit_limit  INT          NULL COMMENT '招募人数上限（NULL = 不限）',
    submit_user_id BIGINT       NULL COMMENT '提交人 users.id',
    review_user_id BIGINT       NULL COMMENT '审批人 users.id（S21：中心/团委，平台映射 admin）',
    review_time    DATETIME     NULL COMMENT '审批时间',
    reject_reason  VARCHAR(500) NULL COMMENT '驳回原因',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_vp_status (status),
    KEY idx_vp_category (category),
    KEY idx_vp_organizer_type (organizer_type),
    KEY idx_vp_start (start_time),
    KEY idx_vp_submit_user (submit_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·P17 校园文化·志愿服务项目立项';


-- -----------------------------------------------------------------------------
-- 2. volunteer_enrollment —— 报名（含「无故缺席」承载，S21 第三十四条(一)）
--    status 枚举：pending 待确认 / confirmed 已确认 / cancelled 已取消 / attended 已参加 / absent 无故缺席
--    UNIQUE KEY (project_id, user_id)：同一项目同一学生只能有一条报名（契约硬要求）。
--    absent 是黑名单情形 c34_1「无故缺席已确认参加的活动致活动无法开展造成恶劣影响」的事实来源。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS volunteer_enrollment;
CREATE TABLE volunteer_enrollment (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    project_id BIGINT       NOT NULL COMMENT 'volunteer_project.id',
    user_id    BIGINT       NOT NULL COMMENT '报名学生 users.id',
    apply_time DATETIME     NOT NULL COMMENT '报名时间',
    status     VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT 'pending 待确认 / confirmed 已确认 / cancelled 已取消 / attended 已参加 / absent 无故缺席',
    remark     VARCHAR(500) NULL COMMENT '备注',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_user (project_id, user_id),
    KEY idx_ve_user (user_id),
    KEY idx_ve_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·P17 校园文化·志愿服务报名';


-- -----------------------------------------------------------------------------
-- 3. volunteer_record —— 志愿服务时长记录（S21 第十九~二十八条）
--    hours DECIMAL(6,2)：基本单位「小时」，允许半小时等小数（S21 第十九条）。
--    hour_type：credit 信用时数 / honor 荣誉时数（荣誉时数须提供证明并走补录，S21 第二十一条）。
--    source   ：platform 平台打卡 / makeup 补录（补录须附证明材料，S21 第二十一条）。
--    status   ：pending 待审核 / approved 已通过 / rejected 已驳回（approved/rejected 为终态）。
--    sign_in / sign_out 落**平台打卡**的起止时间事实（含场地休息时间，S21 第二十条）；
--    每天 8 小时上限**只提示不硬拒**（超出须中心审核并附策划书等材料，S21 第二十二条），
--    故本表**不建**上限校验列；交通/培训 30 分钟门槛同理（第二十三/二十四条，前端+服务层提示）。
--    recorder_subject 取值同 organizer_type 四值枚举（S21 第二十八条四类录入主体）。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS volunteer_record;
CREATE TABLE volunteer_record (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id          BIGINT       NOT NULL COMMENT '学生 users.id',
    project_id       BIGINT       NULL COMMENT 'volunteer_project.id（NULL = 非立项项目的其它认定活动）',
    service_date     DATE         NOT NULL COMMENT '服务日期',
    sign_in          DATETIME     NULL COMMENT '签到时间（平台打卡起止点，S21 第二十条；人工录入，不与志愿汇对接）',
    sign_out         DATETIME     NULL COMMENT '签退时间（含在服务场地休息的时间，S21 第二十条）',
    hours            DECIMAL(6,2) NOT NULL COMMENT '服务时长（小时，基本单位；S21 第十九条）',
    hour_type        VARCHAR(20)  NOT NULL DEFAULT 'credit' COMMENT 'credit 信用时数 / honor 荣誉时数（S21 第二十一条）',
    source           VARCHAR(20)  NOT NULL DEFAULT 'platform' COMMENT 'platform 平台打卡 / makeup 补录（S21 第二十一条）',
    recorder_id      BIGINT       NULL COMMENT '录入人 users.id',
    recorder_subject VARCHAR(30)  NULL COMMENT '录入主体：center / department / club / student_activity（S21 第二十八条四类）',
    status           VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT 'pending 待审核 / approved 已通过 / rejected 已驳回（终态不可再改）',
    review_user_id   BIGINT       NULL COMMENT '审核人 users.id（平台映射 admin，S21 中心审核）',
    review_time      DATETIME     NULL COMMENT '审核时间',
    reject_reason    VARCHAR(500) NULL COMMENT '驳回原因',
    makeup_note      VARCHAR(500) NULL COMMENT '补录说明（附件 1/2 官方表格字段未获全文，平台只登记说明文本，不生成表格）',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_vr_user_date (user_id, service_date),
    KEY idx_vr_project (project_id),
    KEY idx_vr_status (status),
    KEY idx_vr_hour_type (hour_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·P17 校园文化·志愿服务时长记录';


-- -----------------------------------------------------------------------------
-- 4. volunteer_blacklist —— 黑名单（S21 第三十四~三十六条）
--    clause：c34_1 .. c34_5（第三十四条 (一)~(五)）+ c35（第三十五条 未按要求开展工作或受警告及以上处分）。
--       c34_1 无故缺席已确认活动致活动无法开展造成恶劣影响
--       c34_2 言行不当严重损害学校形象
--       c34_3 故意煽动负面情绪
--       c34_4 通过社交媒体发表不良言论诋毁组织或个人
--       c34_5 中心认定的其它行为
--    expire_at = listed_at + 6 个月（第三十六条「六个月内不再被录用」），**服务层按日期推算**，
--    状态 active/expired 亦由服务层按当天日期推算落库，**不做定时任务**（spec §12.2-9）。
--    revoked 仅由「撤销」写操作产生（service 层的显式动作，非时间派生）。
--    notify_time：告知时间。通知渠道（短信/邮件）不存在，故**不建**渠道字段（诚实清单 ⑨）。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS volunteer_blacklist;
CREATE TABLE volunteer_blacklist (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT       NOT NULL COMMENT '学生 users.id',
    clause      VARCHAR(20)  NOT NULL COMMENT '情形条款：c34_1..c34_5（第三十四条(一)~(五)）/ c35（第三十五条）',
    reason      VARCHAR(500) NULL COMMENT '事实与理由',
    listed_at   DATE         NOT NULL COMMENT '列入日期',
    expire_at   DATE         NOT NULL COMMENT '到期日期（= 列入日期 + 6 个月，服务层推算，S21 第三十六条）',
    notify_time DATETIME     NULL COMMENT '告知时间（平台无短信/邮件渠道，只记录已告知的事实时间）',
    status      VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT 'active 生效中 / expired 已到期 / revoked 已撤销',
    operator_id BIGINT       NULL COMMENT '登记人 users.id',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_vb_user (user_id),
    KEY idx_vb_status (status),
    KEY idx_vb_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·P17 校园文化·志愿服务黑名单';


-- -----------------------------------------------------------------------------
-- 5. volunteer_appeal —— 申诉（S21 第三十七条）
--    「接到通知起的 **5 个工作日**内向相关志愿服务组织提出申诉，由志愿服务组织所在的团委受理并裁定」：
--      accept_org    受理组织，默认「团委」；
--      within_window 是否在 5 个工作日内，**由服务层判定后落库**（0/1，便于超窗统计与审计）；
--      record_id / blacklist_id 二选一：分别指向被争议的时长记录或黑名单；
--      ruling / ruler_id / ruling_time 为裁定结果留痕。
--    超窗提交一律 400（服务层），故 within_window=0 的行只会来自「窗口内提交、事后被标记超窗」或历史导入。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS volunteer_appeal;
CREATE TABLE volunteer_appeal (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id       BIGINT        NOT NULL COMMENT '申诉学生 users.id',
    record_id     BIGINT        NULL COMMENT '被申诉的 volunteer_record.id（与 blacklist_id 二选一）',
    blacklist_id  BIGINT        NULL COMMENT '被申诉的 volunteer_blacklist.id（与 record_id 二选一）',
    reason        VARCHAR(1000) NOT NULL COMMENT '申诉事由',
    submit_time   DATETIME      NOT NULL COMMENT '提交时间',
    accept_org    VARCHAR(100)  NOT NULL DEFAULT '团委' COMMENT '受理组织（S21 第三十七条：志愿服务组织所在的团委）',
    within_window TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否在 5 个工作日内：1 在窗口内 / 0 超窗（服务层判定后落库）',
    status        VARCHAR(20)   NOT NULL DEFAULT 'pending' COMMENT 'pending 待受理 / accepted 已受理 / rejected 已驳回 / closed 已结案',
    ruling        VARCHAR(1000) NULL COMMENT '裁定结果',
    ruler_id      BIGINT        NULL COMMENT '裁定人 users.id',
    ruling_time   DATETIME      NULL COMMENT '裁定时间',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_va_user (user_id),
    KEY idx_va_status (status),
    KEY idx_va_record (record_id),
    KEY idx_va_blacklist (blacklist_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·P17 校园文化·志愿服务申诉';


-- -----------------------------------------------------------------------------
-- 本文件**不改动 audit_log 表结构**：audit_log.event_type 已是 VARCHAR(32)
-- （见 sql/nest_audit.sql:13），VOLUNTEER 类型无需 DDL，只需要服务层常量
-- AuditLogService.TYPE_VOLUNTEER = "VOLUNTEER" 与 typeOfPath 的 /api/volunteer 分支（plan §7-5）。
-- -----------------------------------------------------------------------------
