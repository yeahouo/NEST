-- =============================================================================
-- 学工平台 · P15 社区管理（sql/nest_community.sql）
--
-- 口径出处（权威契约：《学工平台功能对齐说明》§10，10.1 一手事实 / 10.2 实现口径）：
--   · 10.2-1  社区管理 7 张表统一 community_ 前缀，与既有 dorm_* 分开；学生与房间口径
--             沿用 student_profile.dorm_building / dorm_room，不另建学生表。
--   · 10.2-2  不做独立评分表：评分项由学校录入 community_batch.criteria_json；
--             平台自带五项各 20 分的演示评分项，学校未发布前一律标注「演示评分项」。
--   · 10.2-3  晚归判定照 S19 第七条：正常出入 6:00-23:30，之外为晚归；断电（23:30/6:00）
--             只在页面提示。没有门禁硬件 => source=card 的批次走 CSV 导入或人工登记，
--             平台不假装能自动抓取闸机流水。
--   · 10.2-4  处理只到教育惩戒与建议（community_violation.advice），且必须带 rule_ref；
--             平台不生成处分决定（那是学工处分流程的事）。
--   · 10.2-5  权限：学生只看自己的晚归/违规与本宿舍检查结果；辅导员（teacher）只看
--             student_profile.counselor_id 名下学生；检查录入/批次发布/文明宿舍评比/活动管理
--             限 admin 与辅导员；文明宿舍公示登录必需（匿名 401）。
--   · 10.2-6  文明宿舍按学期汇总「已发布批次」的检查均分，按楼栋内排名生成候选，
--             awarded 由管理员人工确认，名额不自动截断。
--   · 10.2-7  整改闭环：fail(<60) 或 rectify_required=1 生成整改要求与截止日 =>
--             学生提交（submitted）=> 复查 pass=accepted / fail=rejected（可重新提交）。
--   · 10.2-8  活动：quota 满 400；同活动同学生只允许一条报名（唯一键）；
--             signup_deadline 过期 400；attended/absent 由签到置位；award 只由管理员登记。
--
-- 状态迁移一律 CAS：WHERE id=#{id} AND deleted=0 AND status=#{fromStatus}，
-- 且服务层必须先显式前置校验，**不能把「刚读到的状态」当作放行的理由**（否则 WHERE 恒真）。
--
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，可反复执行；本文件不插入任何演示数据
--       （演示数据见 sql/nest_community_demo.sql）。
--
-- 执行（Windows / cmd，不要用 mysql -e "source ..."，会报 ERROR 1064）：
--   cmd /c "mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest -t < sql\nest_community.sql"
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. community_batch —— 社区检查批次
--    状态机（全部 CAS）：draft 草稿 → checking 检查中 → scored 已评分 → published 已公布 → finished 已结束
--    评分项 criteria_json 由学校录入（形如
--      [{"key":"power","name":"用电安全","max":20,"ruleRef":"园区管理规定第十条"}]）。
--    平台默认带五项各 20 分的演示评分项，前端必须标注「演示评分项，非学校发布口径」。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS community_batch (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    batch_name      VARCHAR(128) NOT NULL COMMENT '批次名称（如「2025-2026 学年第一学期宿舍卫生检查」）',
    term            VARCHAR(16)  NOT NULL COMMENT '学期，如 2025-2026-1',
    check_type      VARCHAR(16)  NOT NULL DEFAULT 'hygiene' COMMENT '检查类型：hygiene 卫生检查 / safety 安全检查 / joint 联合检查',
    scope_building  VARCHAR(64)  NULL COMMENT '适用范围楼栋（student_profile.dorm_building 口径）；空 = 全部楼栋',
    start_date      DATE         NULL COMMENT '检查开始日期',
    end_date        DATE         NULL COMMENT '检查结束日期',
    publish_date    DATETIME     NULL COMMENT '批次发布时间（draft 之后首次进入检查中时写入）',
    criteria_json   TEXT         NULL COMMENT '评分项 JSON（学校录入）：[{"key":"power","name":"用电安全","max":20,"ruleRef":"园区管理规定第十条"}]，总和应为 100',
    status          VARCHAR(16)  NOT NULL DEFAULT 'draft' COMMENT 'draft 草稿 / checking 检查中 / scored 已评分 / published 已公布 / finished 已结束',
    note            VARCHAR(500) NULL COMMENT '批次说明',
    created_by      BIGINT       NULL COMMENT '创建人 users.id',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_cb_status (status),
    KEY idx_cb_term (term),
    KEY idx_cb_building (scope_building)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·社区检查批次';


-- -----------------------------------------------------------------------------
-- 2. community_inspection —— 宿舍检查记录（批次内每间宿舍一条）
--    UNIQUE(batch_id, building, room_no, deleted)：同一批次同一房间只允许一条有效记录。
--    grade 由服务端按 score 计算，不采信客户端传值：
--      excellent >= 90 / good 80-89 / pass 60-79 / fail < 60
--    rectify_status（整改闭环，全部 CAS）：
--      none 无需整改 / pending 待整改 / submitted 学生已提交说明 / accepted 复查通过
--      / rejected 复查未通过 —— rejected 表示「回到待整改」，学生可再次提交。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS community_inspection (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    batch_id          BIGINT       NOT NULL COMMENT 'community_batch.id',
    building          VARCHAR(64)  NOT NULL COMMENT '楼栋（student_profile.dorm_building 口径，如「A 栋」）',
    room_no           VARCHAR(32)  NOT NULL COMMENT '房间号（student_profile.dorm_room 口径，如「A-305」）',
    leader_student_id BIGINT       NULL COMMENT '宿舍长 users.id；房间内无学生档案时为 NULL',
    check_date        DATE         NOT NULL COMMENT '检查日期',
    inspector_id      BIGINT       NULL COMMENT '检查人 users.id（管理员/辅导员）',
    item_json         TEXT         NULL COMMENT '逐项得分 JSON，如 {"power":18,"fire":19}；键必须来自批次的 criteria_json',
    score             DECIMAL(5,2) NOT NULL COMMENT '总分 0-100；未传则由 item_json 各项求和',
    grade             VARCHAR(16)  NOT NULL COMMENT '等级：excellent >=90 / good 80-89 / pass 60-79 / fail <60',
    problems          VARCHAR(500) NULL COMMENT '存在问题描述',
    rectify_required  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否需要整改：1 = 需要（fail 或显式要求）',
    rectify_deadline  DATE         NULL COMMENT '整改截止日期（默认 check_date + 7 天）',
    rectify_status    VARCHAR(16)  NOT NULL DEFAULT 'none' COMMENT 'none 无需整改 / pending 待整改 / submitted 已提交 / accepted 复查通过 / rejected 复查未通过（可重新提交）',
    rectify_note      VARCHAR(500) NULL COMMENT '学生整改说明（学生本人提交）',
    rectify_at        DATETIME     NULL COMMENT '学生提交整改说明时间',
    recheck_by        BIGINT       NULL COMMENT '复查人 users.id',
    recheck_at        DATETIME     NULL COMMENT '复查时间',
    recheck_result    VARCHAR(8)   NULL COMMENT '复查结论：pass 通过 / fail 未通过',
    source            VARCHAR(8)   NOT NULL DEFAULT 'manual' COMMENT '来源：manual 人工登记 / csv CSV 导入',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ci_batch_room (batch_id, building, room_no, deleted),
    KEY idx_ci_batch (batch_id),
    KEY idx_ci_room (building, room_no),
    KEY idx_ci_grade (grade),
    KEY idx_ci_rectify (rectify_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·社区宿舍检查记录';


-- -----------------------------------------------------------------------------
-- 3. community_late —— 晚归 / 夜不归宿台账
--    判定照 S19 第七条：正常出入 6:00-23:30，之外为晚归（type=late）；
--    type=absent 为夜不归宿。没有门禁硬件：card 来源同样走 CSV 导入或人工登记。
--    handling_note 是内部处理备注（学生端不下发，服务层抹掉）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS community_late (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    student_id    BIGINT       NOT NULL COMMENT 'users.id（学生）',
    building      VARCHAR(64)  NULL COMMENT '楼栋（登记时从 student_profile 带出）',
    room_no       VARCHAR(32)  NULL COMMENT '房间号（登记时从 student_profile 带出）',
    occur_date    DATE         NOT NULL COMMENT '发生日期',
    type          VARCHAR(8)   NOT NULL DEFAULT 'late' COMMENT 'late 晚归 / absent 夜不归宿',
    out_time      TIME         NULL COMMENT '外出时间（晚归时至少 out_time/in_time 给一个，且在 6:00-23:30 之外）',
    in_time       TIME         NULL COMMENT '回园区时间（同上）',
    source        VARCHAR(8)   NOT NULL DEFAULT 'report' COMMENT '来源：card 门禁（无硬件，走导入）/ check 巡查 / report 上报',
    reason        VARCHAR(32)  NULL COMMENT '原因分类码，缺省 late_return（晚归）/ night_out（夜不归宿）',
    reason_note   VARCHAR(255) NULL COMMENT '原因说明（登记人填写）',
    student_note  VARCHAR(500) NULL COMMENT '学生本人说明（POST /my/lates/{id}/note 写入）',
    confirmed     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已核实：0 未核实 / 1 已核实',
    confirmed_by  BIGINT       NULL COMMENT '核实人 users.id',
    confirmed_at  DATETIME     NULL COMMENT '核实时间',
    counselor_id  BIGINT       NULL COMMENT '辅导员 users.id（登记时从 student_profile 带出，便于按名下学生过滤）',
    handling      VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending 待处理 / contacted 已联系 / closed 已了结',
    handling_note VARCHAR(500) NULL COMMENT '处理备注（内部字段，学生端不下发）',
    violation_id  BIGINT       NULL COMMENT '关联 community_violation.id（同一事实转违规台账时回填）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_cl_student (student_id, occur_date),
    KEY idx_cl_counselor (counselor_id),
    KEY idx_cl_occur (occur_date),
    KEY idx_cl_type (type),
    KEY idx_cl_handling (handling)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·晚归与夜不归宿台账';


-- -----------------------------------------------------------------------------
-- 4. community_violation —— 社区违规台账
--    口径（10.2-4）：只到教育惩戒与建议，rule_ref 必填（依据条文），平台不生成处分决定。
--    result_note 是内部处理结果说明（学生端不下发，服务层抹掉）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS community_violation (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    student_id    BIGINT       NOT NULL COMMENT 'users.id（学生）',
    building      VARCHAR(64)  NULL COMMENT '楼栋',
    room_no       VARCHAR(32)  NULL COMMENT '房间号',
    inspection_id BIGINT       NULL COMMENT '关联 community_inspection.id（由检查问题转违规时回填）',
    late_id       BIGINT       NULL COMMENT '关联 community_late.id（由晚归转违规时回填）',
    type          VARCHAR(16)  NOT NULL COMMENT 'late 晚归 / absence 夜不归宿 / appliance 违规电器 / fire 消防隐患 / pet 饲养宠物 / rent 出租床位 / climb 攀爬 / noise 噪音 / other 其他',
    occurred_at   DATETIME     NULL COMMENT '发生时间',
    detail        VARCHAR(500) NOT NULL COMMENT '违规事实描述',
    rule_ref      VARCHAR(128) NOT NULL COMMENT '依据条文（必填，如「园区管理规定第十条」）',
    advice        VARCHAR(24)  NOT NULL DEFAULT 'education' COMMENT '处理建议：education 教育 / circulated 通报 / warning 警告 / serious_warning 严重警告 / recorded 记录在案',
    handling      VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending 待处理 / notified 已告知 / closed 已办结',
    counselor_id  BIGINT       NULL COMMENT '辅导员 users.id',
    handled_by    BIGINT       NULL COMMENT '处理人 users.id',
    handled_at    DATETIME     NULL COMMENT '处理时间',
    result_note   VARCHAR(500) NULL COMMENT '处理结果说明（内部字段，学生端不下发）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_cv_student (student_id),
    KEY idx_cv_type (type),
    KEY idx_cv_handling (handling),
    KEY idx_cv_counselor (counselor_id),
    KEY idx_cv_occur (occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·社区违规台账';


-- -----------------------------------------------------------------------------
-- 5. community_activity —— 社区活动（文化节 / 宿舍风采赛 / 宿舍奥运会 / 设计大赛 / 志愿服务）
--    状态机：draft 草稿 → signup 报名中 → ongoing 进行中 → finished 已结束；cancelled 已取消。
--    quota = 0 表示不限人数；quota 满了服务层返回 400。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS community_activity (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    title           VARCHAR(128) NOT NULL COMMENT '活动名称',
    activity_type   VARCHAR(24)  NOT NULL DEFAULT 'other' COMMENT 'culture_festival 社区文化节 / room_contest 宿舍风采赛 / room_olympic 宿舍奥运会 / design_contest 设计大赛 / volunteer 志愿服务 / other 其他',
    term            VARCHAR(16)  NULL COMMENT '学期，如 2025-2026-1',
    level           VARCHAR(16)  NOT NULL DEFAULT 'community' COMMENT 'school 校级 / college 院级 / community 社区级',
    start_at        DATETIME     NULL COMMENT '开始时间',
    end_at          DATETIME     NULL COMMENT '结束时间',
    location        VARCHAR(128) NULL COMMENT '活动地点',
    signup_deadline DATETIME     NULL COMMENT '报名截止时间；已过期报名返回 400',
    quota           INT          NOT NULL DEFAULT 0 COMMENT '名额上限，0 = 不限',
    content         TEXT         NULL COMMENT '活动内容',
    host            VARCHAR(128) NULL COMMENT '主办单位',
    status          VARCHAR(16)  NOT NULL DEFAULT 'draft' COMMENT 'draft 草稿 / signup 报名中 / ongoing 进行中 / finished 已结束 / cancelled 已取消',
    notice_url      VARCHAR(255) NULL COMMENT '通知链接',
    created_by      BIGINT       NULL COMMENT '创建人 users.id',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_ca_status (status),
    KEY idx_ca_term (term),
    KEY idx_ca_deadline (signup_deadline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·社区活动';


-- -----------------------------------------------------------------------------
-- 6. community_activity_signup —— 活动报名（一人一活动一条）
--    UNIQUE(activity_id, student_id, deleted)：同活动同学生只允许一条报名（10.2-8）。
--    status：signed 已报名 → approved 通过 / rejected 驳回；attended 已参加 / absent 未参加
--    由 POST /activities/{id}/attendance 置位；award 只由管理员/辅导员登记。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS community_activity_signup (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    activity_id   BIGINT       NOT NULL COMMENT 'community_activity.id',
    student_id    BIGINT       NOT NULL COMMENT 'users.id（学生）',
    building      VARCHAR(64)  NULL COMMENT '楼栋（报名时从 student_profile 带出）',
    room_no       VARCHAR(32)  NULL COMMENT '房间号（报名时从 student_profile 带出）',
    signup_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '报名时间',
    status        VARCHAR(16)  NOT NULL DEFAULT 'signed' COMMENT 'signed 已报名 / approved 已通过 / rejected 已驳回 / attended 已参加 / absent 未参加',
    reject_reason VARCHAR(255) NULL COMMENT '驳回原因',
    award         VARCHAR(16)  NOT NULL DEFAULT 'none' COMMENT 'none 无 / first 一等 / second 二等 / third 三等 / excellent 优秀奖',
    score         DECIMAL(6,2) NULL COMMENT '活动成绩（可选）',
    note          VARCHAR(255) NULL COMMENT '备注',
    reviewed_by   BIGINT       NULL COMMENT '审核/登记人 users.id',
    reviewed_at   DATETIME     NULL COMMENT '审核/登记时间',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_cas_activity_student (activity_id, student_id, deleted),
    KEY idx_cas_student (student_id),
    KEY idx_cas_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·社区活动报名';


-- -----------------------------------------------------------------------------
-- 7. community_civil_room —— 文明宿舍候选与结果（按学期 + 房间）
--    UNIQUE(term, building, room_no, deleted)：一个学期一间宿舍一条。
--    compute 按「已发布批次」的检查均分汇总，rank_in_building 为楼栋内名次（不自动截断名额）；
--    awarded 由管理员人工确认；published_at 非空才对外公示（公示只露楼栋/房号/均分/等级）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS community_civil_room (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    term             VARCHAR(16)  NOT NULL COMMENT '学期，如 2025-2026-1',
    batch_ids        VARCHAR(500) NULL COMMENT '参与汇总的批次 id（JSON 数组，形如 [1,2]）',
    building         VARCHAR(64)  NOT NULL COMMENT '楼栋',
    room_no          VARCHAR(32)  NOT NULL COMMENT '房间号',
    member_count     INT          NOT NULL DEFAULT 0 COMMENT '参与汇总的检查记录条数（同房间在本学期已发布批次内的记录数）',
    avg_score        DECIMAL(5,2) NOT NULL COMMENT '学期均分（已发布批次内该房间检查记录的平均分）',
    min_score        DECIMAL(5,2) NOT NULL COMMENT '学期最低分',
    rank_in_building INT          NOT NULL COMMENT '楼栋内名次（均分降序，同分并列）',
    awarded          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否评为文明宿舍：0 候选 / 1 已评（管理员人工确认，名额不自动截断）',
    award_level      VARCHAR(16)  NULL COMMENT 'school 校级 / college 院级',
    published_at     DATETIME     NULL COMMENT '公示发布时间；NULL = 未公示',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ccr_term_room (term, building, room_no, deleted),
    KEY idx_ccr_term (term),
    KEY idx_ccr_pub (published_at),
    KEY idx_ccr_awarded (awarded)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·文明宿舍候选与结果';


-- =============================================================================
-- 8. 自检段：建完表后逐项打印（表 / 列 / 索引 / 初始行数）
--    本文件不插入演示数据，故初始行数应一律为 0（演示数据见 nest_community_demo.sql）。
-- =============================================================================

SELECT '8.1 表' AS 检查项, TABLE_NAME, TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('community_batch', 'community_inspection', 'community_late',
                     'community_violation', 'community_activity', 'community_activity_signup',
                     'community_civil_room')
ORDER BY TABLE_NAME;

SELECT '8.2 列' AS 检查项, TABLE_NAME, ORDINAL_POSITION, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('community_batch', 'community_inspection', 'community_late',
                     'community_violation', 'community_activity', 'community_activity_signup',
                     'community_civil_room')
ORDER BY TABLE_NAME, ORDINAL_POSITION;

SELECT '8.3 索引' AS 检查项, TABLE_NAME, INDEX_NAME, NON_UNIQUE,
       GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS 列
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('community_batch', 'community_inspection', 'community_late',
                     'community_violation', 'community_activity', 'community_activity_signup',
                     'community_civil_room')
GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE
ORDER BY TABLE_NAME, INDEX_NAME;

SELECT '8.4 初始行数' AS 检查项, 'community_batch' AS 表, COUNT(*) AS 行数 FROM community_batch
UNION ALL SELECT '8.4 初始行数', 'community_inspection', COUNT(*) FROM community_inspection
UNION ALL SELECT '8.4 初始行数', 'community_late', COUNT(*) FROM community_late
UNION ALL SELECT '8.4 初始行数', 'community_violation', COUNT(*) FROM community_violation
UNION ALL SELECT '8.4 初始行数', 'community_activity', COUNT(*) FROM community_activity
UNION ALL SELECT '8.4 初始行数', 'community_activity_signup', COUNT(*) FROM community_activity_signup
UNION ALL SELECT '8.4 初始行数', 'community_civil_room', COUNT(*) FROM community_civil_room;
