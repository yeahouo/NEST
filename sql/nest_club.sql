-- =============================================================================
-- 学工平台 · P17 校园文化 · 学生社团年审与星级评定（sql/nest_club.sql）
--
-- 权威契约：target/p17-plan.md §2（逐字段 DDL 契约）/ §3（枚举码值）；规则原文见
--           target/p17-spec.md §12.1 与逐字缓存 target/p17-sources.md（S25a 年审附件 L345-419；
--           S25b 星级公示 L446-470）。未在原文出现的内容一律不得编造（spec §12.2 第 13 条）。
--
-- **消歧（事实性，必须遵守，spec §12.1）**：
--   · 本域「星级」= 学生社团星级（三星级/四星级/五星级），与 S21 第三十条的**志愿者**星级
--     （一~五星级志愿者，50/100/150/300/500 小时）是**两套不同制度**，数据模型与文案分开命名，
--     不得互相套用 —— 故本文件不出现任何志愿者星级字段。
--   · 「挂靠单位」（S25a 年审附件表头）与「业务指导单位」（S25b 星级公示表头）**字段名不同、不得互换**：
--     club.attach_unit（挂靠单位） / club_rating.business_unit（业务指导单位）。
--
-- 设计口径：
--   · S25a：附件表头逐字 `序号 | 组织名称 | 类别 | 挂靠单位 | 年审结果`；年审聚焦 **4 项**
--     （组织架构合理性 / 骨干成员胜任力 / 学期活动运行质量 / 经费使用合规性）
--     => club_review 的四列材料 architecture_material / backbone_material /
--        activity_material / fund_material；结果三值 合格 qualified / 不合格 unqualified / 注销 deregistered。
--   · S25b：星级公示表头逐字 `序号 | 社团名称 | 评定星级 | 业务指导单位 | 社团类别`；等级实测
--     五星级/四星级/三星级 => club_rating.star_level = star5 / star4 / star3；
--     聚焦 3 项（校园文化传承、活动策划执行、社会实践参与）=> 落 basis_note（评定依据说明，必填）。
--   · 社团类别为 S25a 附件实测 **6 类**：创新创业类 / 思想政治类 / 文化体育类 / 学术科技类 /
--     志愿公益类 / 自律互助类（plan §3.4）。
--   · 公示 5 天（对齐 S25b 实际 2026-07-07~07-11）；异议邮箱 twh@xujc.com（S25a / S25b 逐字）。
--     club_publicity.status：open 公示中 / closed 已结束；**已结束再 close 必须 400**，不做幂等 200
--     （P15 E2E 缺陷 D2 教训，spec §12.2-9）。
--
-- 未核实、因此**不进本文件**的内容（spec §12.1 诚实清单 ⑤⑥⑧）：
--   ⑤「挂靠单位」定义原文未规定 —— 只作 VARCHAR 文本，不建单位主数据表、不做枚举约束；
--   ⑥ 社团星级完整档位与升降细则原文未规定 —— 只登记本次实有的 star3/star4/star5，
--     **不建分数、不建升降记录表、不做自动评级**；星级由 admin 登记（平台不自动评级）；
--   ⑧ 社团成员名册构成、社团经费、指导老师字段无来源 —— club_member 只建最小四字段；
--     club.advisor_name 列为**自由填写**并注明无来源；**不建**经费表、不建经费金额字段
--     （club_review.fund_material 只登记「经费使用合规性」审查**材料文本**，不含金额）。
--   ④ 年审「合格/不合格/注销」的判定标准原文未规定 —— 只登记结论与材料，不写判定建议；
--   ③「未参加年审的后果」无来源 —— 不做提示。
--
-- 状态机一律 CAS：UPDATE ... WHERE id=#{id} AND deleted=0 AND status=#{fromStatus}
--   （club.status / club_publicity.status / club_review.result 的登记），fromStatus 取**显式枚举前置态**；
--   终态不可再改（年审结果登记后不得重设、公示 closed 后不得再 close）。
--
-- 幂等：本文件 DROP TABLE IF EXISTS + CREATE TABLE，可反复执行；**不插入任何演示数据**
--       （演示数据见 sql/nest_culture_demo.sql）。
--
-- 执行（Windows / cmd；不要写 mysql -e "source ..."，也不要写 `LIKE '%club_%'`，cmd 会吃掉 %）：
--   cmd /c "mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest -t < sql\nest_club.sql"
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. club —— 社团台账
--    attach_unit = **挂靠单位**（S25a 年审附件表头逐字），与 club_rating.business_unit
--    （**业务指导单位**，S25b 表头逐字）**不得互换**。
--    advisor_name：指导老师**无来源**，按诚实清单 ⑧ 列为自由填写。
--    status：active 正常 / deregistered 已注销（注销由 admin 写操作产生）。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS club;
CREATE TABLE club (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    club_name   VARCHAR(200) NOT NULL COMMENT '社团名称（S25a 表头「组织名称」/ S25b 表头「社团名称」）',
    category    VARCHAR(50)  NULL COMMENT '社团类别（S25a 附件实测 6 类：创新创业类/思想政治类/文化体育类/学术科技类/志愿公益类/自律互助类）',
    attach_unit VARCHAR(200) NULL COMMENT '挂靠单位（S25a 年审附件表头逐字；与业务指导单位不得互换）',
    found_date  DATE         NULL COMMENT '成立日期',
    leader_name VARCHAR(50)  NULL COMMENT '负责人姓名（S25b「优秀社团负责人」名单可按姓名登记）',
    advisor_name VARCHAR(50) NULL COMMENT '指导老师姓名（**无公开来源**，自由填写；诚实清单 ⑧）',
    status      VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT 'active 正常 / deregistered 已注销',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_club_category (category),
    KEY idx_club_status (status),
    KEY idx_club_attach_unit (attach_unit)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·P17 校园文化·学生社团台账';


-- -----------------------------------------------------------------------------
-- 2. club_member —— 社团成员与负责人
--    role：leader 负责人 / member 成员。
--    quit_date 为空 = 在册；退出社团时填 quit_date（不做物理删除，便于留痕）。
--    成员名册构成无来源（诚实清单 ⑧），故只建「社团 / 学生 / 角色 / 入社 / 退社」最小字段，
--    **不建**院系、职务、贡献等无来源字段。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS club_member;
CREATE TABLE club_member (
    id        BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    club_id   BIGINT      NOT NULL COMMENT 'club.id',
    user_id   BIGINT      NOT NULL COMMENT '学生 users.id',
    role      VARCHAR(20) NOT NULL DEFAULT 'member' COMMENT 'leader 负责人 / member 成员',
    join_date DATE        NULL COMMENT '入社日期',
    quit_date DATE        NULL COMMENT '退出日期（空 = 在册）',
    created_at DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_cm_club (club_id, role),
    KEY idx_cm_user (user_id),
    KEY idx_cm_join (join_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·P17 校园文化·社团成员';


-- -----------------------------------------------------------------------------
-- 3. club_review —— 社团年审（S25a）
--    school_year 形式 '2025-2026'；四项聚焦材料对应 S25a 审查 4 项：
--      architecture_material 组织架构合理性 / backbone_material 骨干成员胜任力 /
--      activity_material 学期活动运行质量 / fund_material 经费使用合规性。
--    attach_opinion = **挂靠单位意见**（S25a 表头有「挂靠单位」，意见由挂靠单位出具）。
--    result：qualified 合格 / unqualified 不合格 / deregistered 注销（**三值，登记后不得重设**；
--            判定标准原文未规定 —— 平台只登记结论，不给判定建议）。
--    publicity_id -> club_publicity.id（本次年审结果对应的公示）。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS club_review;
CREATE TABLE club_review (
    id                   BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    club_id              BIGINT        NOT NULL COMMENT 'club.id',
    school_year          VARCHAR(20)   NOT NULL COMMENT '学年，形式 2025-2026',
    architecture_material TEXT         NULL COMMENT '四项聚焦①组织架构合理性材料（S25a）',
    backbone_material    TEXT          NULL COMMENT '四项聚焦②骨干成员胜任力材料（S25a）',
    activity_material    TEXT          NULL COMMENT '四项聚焦③学期活动运行质量材料（S25a）',
    fund_material        TEXT          NULL COMMENT '四项聚焦④经费使用合规性材料（S25a；只登记材料文本，不含金额）',
    attach_opinion       VARCHAR(1000) NULL COMMENT '挂靠单位意见（S25a 表头「挂靠单位」）',
    result               VARCHAR(20)   NULL COMMENT 'qualified 合格 / unqualified 不合格 / deregistered 注销（登记后为终态不得重设）',
    submit_time          DATETIME      NULL COMMENT '提交时间',
    review_user_id       BIGINT        NULL COMMENT '审核人 users.id（校团委社团管理部，平台映射 admin）',
    review_time          DATETIME      NULL COMMENT '审核时间',
    publicity_id         BIGINT        NULL COMMENT '所属公示 club_publicity.id',
    created_at           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted              TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_cr_club_year (club_id, school_year),
    KEY idx_cr_result (result),
    KEY idx_cr_publicity (publicity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·P17 校园文化·社团年审';


-- -----------------------------------------------------------------------------
-- 4. club_rating —— 社团星级评定登记（S25b）
--    star_level：star5 五星级 / star4 四星级 / star3 三星级（S25b 实测三档；完整档位与升降细则
--    原文未规定，故只登记这三档，**不建分数、不建升降表、不做自动评级**）。
--    business_unit = **业务指导单位**（S25b 表头逐字），与 club.attach_unit（**挂靠单位**，S25a）不得互换。
--    basis_note 评定依据说明**必填**（S25b 聚焦 3 项：校园文化传承 / 活动策划执行 / 社会实践参与）。
--    publicity_id -> club_publicity.id；由 admin 登记（register_user_id / register_time）。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS club_rating;
CREATE TABLE club_rating (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    club_id          BIGINT        NOT NULL COMMENT 'club.id',
    school_year      VARCHAR(20)   NOT NULL COMMENT '学年，形式 2025-2026',
    star_level       VARCHAR(20)   NOT NULL COMMENT 'star5 五星级 / star4 四星级 / star3 三星级（S25b 实测三档）',
    business_unit    VARCHAR(200)  NULL COMMENT '业务指导单位（S25b 表头逐字；与挂靠单位不得互换）',
    basis_note       VARCHAR(1000) NULL COMMENT '评定依据说明（必填；S25b 聚焦校园文化传承/活动策划执行/社会实践参与 3 项）',
    publicity_id     BIGINT        NULL COMMENT '所属公示 club_publicity.id',
    register_user_id BIGINT        NULL COMMENT '登记人 users.id（平台不自动评级，由 admin 登记）',
    register_time    DATETIME      NULL COMMENT '登记时间',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted          TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_crt_club_year (club_id, school_year),
    KEY idx_crt_star_level (star_level),
    KEY idx_crt_publicity (publicity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·P17 校园文化·社团星级评定登记';


-- -----------------------------------------------------------------------------
-- 5. club_publicity —— 公示留痕（年审结果 / 星级评定）
--    publicity_type：club_review 年审结果公示 / club_rating 星级评定公示。
--    contact_email 默认 twh@xujc.com（S25a / S25b 异议渠道逐字）。
--    status：open 公示中 / closed 已结束 —— **closed 后不得再 close（重复关闭必须 400，不做幂等 200）**。
--    公示期 5 天（对齐 S25b 实际 2026-07-07 ~ 2026-07-11）；起止日期由服务层按天推算（自然日）。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS club_publicity;
CREATE TABLE club_publicity (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    publicity_type  VARCHAR(30)  NOT NULL COMMENT 'club_review 年审结果公示 / club_rating 星级评定公示',
    title           VARCHAR(200) NOT NULL COMMENT '公示标题',
    content         TEXT         NULL COMMENT '公示正文（含依据与异议渠道说明）',
    start_date      DATE         NOT NULL COMMENT '公示起始日期',
    end_date        DATE         NOT NULL COMMENT '公示结束日期（公示期 5 天）',
    contact_email   VARCHAR(100) NOT NULL DEFAULT 'twh@xujc.com' COMMENT '异议邮箱（S25a/S25b 逐字 twh@xujc.com）',
    status          VARCHAR(20)  NOT NULL DEFAULT 'open' COMMENT 'open 公示中 / closed 已结束（closed 后不得再关闭）',
    publish_user_id BIGINT       NULL COMMENT '发布人 users.id',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_cp_publicity_type (publicity_type),
    KEY idx_cp_status (status),
    KEY idx_cp_date (start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·P17 校园文化·社团公示';


-- -----------------------------------------------------------------------------
-- 本文件**不改动 audit_log 表结构**：audit_log.event_type 已是 VARCHAR(32)
-- （见 sql/nest_audit.sql:13），CLUB 类型无需 DDL，只需要服务层常量
-- AuditLogService.TYPE_CLUB = "CLUB" 与 typeOfPath 的 /api/club 分支（plan §7-5）。
-- -----------------------------------------------------------------------------
