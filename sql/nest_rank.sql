-- =============================================================================
-- 学工平台 · P13 综测排名公示与导出（sql/nest_rank.sql）
--
-- 口径出处（权威契约：《学工平台功能对齐说明》§8，源自
-- 《厦门大学嘉庚学院优秀学生奖学金综合测评实施办法（2022年修订）》）：
--   · 第六条   以专业为单位测评（转专业学生归入当前专业）
--   · 第七条   前 30% 具备获奖学金资格；一等占年级专业人数 5%、二等 10%、三等 15%（四舍五入）
--   · 第八条   A（学习成绩）由教务部/IT 负责，平台只登记
--   · 第十条   M = A + B；毕业学年第二学期只按往年学期 A 排名，C 不计（B=0、M=A）
--   · 第十一条 A 由必修/选修加权分构成（本表只登记教务给的最终 A）
--   · 第十二条 B = C × μ，μ = (H − L) / n 取同专业同年级已录 A 的最高/最低与人数，μ ∈ [0.5, 0.7]
--   · 第十二条 奖励加分与惩处扣分合计后 C 不得超过 8 分
--   · 第十四条 公示期不少于 5 个工作日（跳过周六周日，不处理法定节假日）
--   · 第十三条 参评排除项（不及格课程 / 缓考降级学分不足 75% / 未解除处分等）：平台底表没有
--             成绩明细与处分数据，**不做硬拦截**，只在 /api/rank/stats 里以提示字段给出，避免误伤
--
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，可反复执行；不插入任何演示数据。
--
-- 执行（Windows / cmd，不要用 mysql -e "source ..."，会报 ERROR 1064）：
--   cmd /c "mysql --defaults-extra-file=%TEMP%\nest_my.cnf -D nest -t < sql\nest_rank.sql"
--
-- 与 P6 的边界（重要）：eval_indicator / eval_score / eval_summary（德智体美劳五维加权，0-100）
-- 是民主评议 / 记实测评的过程材料，**不是**办法第十二条里的 C —— C 是加分制，量纲不同。
-- 排名引擎只认三样东西：A（本表登记）、C（eval_bonus 里 counselor_approved 的分值）、μ（A 的分布）。
-- 五维得分只在 /api/rank/my 里并列展示作佐证，不参与 M 的计算。
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. eval_academic —— 学习成绩 A 登记台账
--    A 不在本平台生成（教务部/IT 负责），这里只登记教务给来的分数。
--    缺 A 的学生不参与排名（不补 0）：score_a 允许为 NULL，
--    compute 会把「已排名 / 缺数据」两个数都报出来，管理端另有 /api/rank/academic/missing 待补清单。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS eval_academic (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    student_id     BIGINT       NOT NULL COMMENT 'users.id（学生）',
    academic_term  VARCHAR(16)  NOT NULL COMMENT '学期，如 2025-2026-1',
    required_avg   DECIMAL(6,2) NULL COMMENT '必修加权平均分（教务源数据，平台只登记）',
    elective_avg   DECIMAL(6,2) NULL COMMENT '选修加权平均分（办法第十一条：选修以 80 为基准二次调整，调整过程在校外完成）',
    total_credits  DECIMAL(6,2) NULL COMMENT '取得成绩的课程总学分',
    course_count   INT          NULL COMMENT '课程门数',
    score_a        DECIMAL(6,2) NULL COMMENT '学习成绩 A —— 排名引擎唯一认可的学业分；为 NULL 表示尚未登记，该生不参与排名',
    source         VARCHAR(16)  NOT NULL DEFAULT 'manual' COMMENT '来源：import 教务导入 / manual 手工登记',
    imported_by    BIGINT       NULL COMMENT '登记/导入人 users.id',
    note           VARCHAR(255) NULL COMMENT '备注',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ea_student_term (student_id, academic_term),
    KEY idx_ea_term (academic_term)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·综测学习成绩 A 登记台账';


-- -----------------------------------------------------------------------------
-- 2. eval_bonus —— 综合素质表现得分 C 的加分/扣分申报
--    状态机：draft 草稿 → submitted 已提交 → counselor_approved 辅导员通过（唯一计入 C 的状态）
--            / rejected 驳回（学生可在申报期改后重提）
--    分值细则出处：《学生综合素质表现评分参考细则》六大类，各自累计上限（分类封顶见 RankService 的
--    @Value 配置，不改 application.properties）：
--      学术科技竞赛 ≤6 / 非学术科技竞赛 ≤4 / 科研成果 ≤4 / 综合素质 ≤1 / 宣传文化作品 ≤4 /
--      社会工作 ≤3 / 惩处扣分不设上限（负分）
--    两点取舍，写在列注释里以免后来者误读：
--      · quality（综合素质）取 1 是最保守口径：细则对「劳动技能证书 / 通用素质技能 / 第二课堂讲座
--        0.1 每次 / 打卡与优秀读者」四类子项各自 ≤1，未给该大类的总上限，这里按「任一类最多 1 分」封顶。
--      · C 总和再封顶 8 分（第十二条：奖励加分与惩处扣分合计不得超过 8 分），先分类封顶、再总封顶。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS eval_bonus (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    student_id     BIGINT       NOT NULL COMMENT 'users.id（学生）',
    academic_term  VARCHAR(16)  NOT NULL COMMENT '学期，如 2025-2026-1',
    category       VARCHAR(32)  NOT NULL COMMENT '加分大类：academic_competition 学术科技竞赛 / nonacademic_competition 非学术科技竞赛 / research 科研成果 / quality 综合素质 / media 宣传文化作品 / service 社会工作 / penalty 惩处扣分（负分，不设上限）',
    item_name      VARCHAR(128) NOT NULL COMMENT '项目名称（如「全国大学生数学建模竞赛一等奖」）',
    points         DECIMAL(5,2) NOT NULL COMMENT '申报分值，可负（惩处扣分）',
    evidence       VARCHAR(255) NULL COMMENT '佐证材料说明（校外证书线下交件，不假装上传）',
    status         VARCHAR(24)  NOT NULL DEFAULT 'draft' COMMENT 'draft 草稿 / submitted 已提交待审 / counselor_approved 辅导员通过（唯一计入 C 的状态）/ rejected 已驳回',
    reject_reason  VARCHAR(255) NULL COMMENT '驳回原因（辅导员审核意见，学生端可见）',
    reviewer_id    BIGINT       NULL COMMENT '审核人 users.id',
    reviewed_at    DATETIME     NULL COMMENT '审核时间',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_eb_student_term (student_id, academic_term),
    KEY idx_eb_term_status (academic_term, status),
    KEY idx_eb_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·综测加分申报（综合素质表现得分 C）';


-- -----------------------------------------------------------------------------
-- 3. rank_batch —— 综测排名批次
--    状态机（全部 CAS）：draft 草稿 → computed 已计算 → publicity 公示中 → finished 已结束
--    比例与 μ 边界都按批次存：办法的比例是全校口径，学院/年级可以按批次单独调，
--    历史批次也要能复现当时的算法（所以不写死在代码里）。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rank_batch (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    batch_name       VARCHAR(128) NOT NULL COMMENT '批次名称（如「2025-2026 学年第一学期综测排名」）',
    academic_term    VARCHAR(16)  NOT NULL COMMENT '综测学期，如 2025-2026-1',
    graduation_last  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否毕业学年第二学期：1 = 只按往年学期 A 排名，C 不计（第十条：B=0、M=A）',
    mu_min           DECIMAL(4,3) NOT NULL DEFAULT 0.500 COMMENT 'μ 下界（第十二条：越界取边界）',
    mu_max           DECIMAL(4,3) NOT NULL DEFAULT 0.700 COMMENT 'μ 上界',
    qualify_pct      INT          NOT NULL DEFAULT 30 COMMENT '具备获奖学金资格的专业排名百分比（第七条：前 30%）',
    tier1_pct        INT          NOT NULL DEFAULT 5 COMMENT '一等奖名额占年级专业人数比例（%）',
    tier2_pct        INT          NOT NULL DEFAULT 10 COMMENT '二等奖名额比例（%）',
    tier3_pct        INT          NOT NULL DEFAULT 15 COMMENT '三等奖名额比例（%）',
    publicity_days   INT          NOT NULL DEFAULT 5 COMMENT '公示天数（工作日，第十四条：不少于 5 个工作日；跳过周六周日）',
    status           VARCHAR(16)  NOT NULL DEFAULT 'draft' COMMENT 'draft 草稿 / computed 已计算 / publicity 公示中 / finished 已结束',
    notice_title     VARCHAR(255) NULL COMMENT '公示通知标题',
    notice_url       VARCHAR(255) NULL COMMENT '公示通知链接',
    publicity_start  DATETIME     NULL COMMENT '公示开始时间',
    publicity_end    DATETIME     NULL COMMENT '公示结束时间（= 开始 + publicity_days 个工作日）',
    computed_at      DATETIME     NULL COMMENT '最近一次计算时间',
    created_by       BIGINT       NULL COMMENT '创建人 users.id',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 删除',
    PRIMARY KEY (id),
    KEY idx_rb_status (status),
    KEY idx_rb_term (academic_term)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·综测排名批次';


-- -----------------------------------------------------------------------------
-- 4. rank_row —— 批次内的排名快照（compute 先删本批次再全量写入）
--    为什么存快照而不是每次现场算：公示期内页面展示的必须是「当时算出来的那一版」，
--    期间任何 A 补录或加分审核都不该悄悄改变已公示的名次（有异议就走异议流程再重算）。
--    award_tier 存英文码 t1/t2/t3（列宽 VARCHAR(4) 放不下 full word），文案由服务端给中文。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rank_row (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    batch_id            BIGINT       NOT NULL COMMENT 'rank_batch.id',
    student_id          BIGINT       NOT NULL COMMENT 'users.id（学生）',
    major               VARCHAR(64)  NULL COMMENT '专业（student_profile.major，测评单位）',
    class_name          VARCHAR(64)  NULL COMMENT '班级',
    grade               VARCHAR(16)  NULL COMMENT '年级',
    score_a             DECIMAL(6,2) NOT NULL COMMENT '学习成绩 A',
    c_score             DECIMAL(6,2) NOT NULL DEFAULT 0.00 COMMENT '综合素质表现得分 C（分类封顶后再总封顶 8 分）',
    mu                  DECIMAL(4,3) NOT NULL COMMENT '本专业年级的 μ，已钳制到 [mu_min, mu_max]',
    score_b             DECIMAL(6,2) NOT NULL DEFAULT 0.00 COMMENT '综合素质 B = C × μ（毕业学年第二学期为 0）',
    score_m             DECIMAL(6,2) NOT NULL COMMENT '综合测评 M = A + B',
    rank_in_major       INT          NOT NULL COMMENT '专业内排名（M 降序，同分并列 RANK() 语义 1,2,2,4）',
    rank_in_grade_major INT          NOT NULL COMMENT '同专业同年级排名',
    total_in_major      INT          NOT NULL COMMENT '本专业已排名人数',
    award_tier          VARCHAR(4)   NULL COMMENT '奖学金等级：t1 一等 / t2 二等 / t3 三等 / NULL 无名额',
    qualified           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否具备获奖学金资格：专业排名 ≤ 专业人数 × qualify_pct%（四舍五入）',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '快照生成时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rr_batch_student (batch_id, student_id),
    KEY idx_rr_batch_rank (batch_id, rank_in_major),
    KEY idx_rr_batch_major (batch_id, major, grade)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·综测排名快照';


-- -----------------------------------------------------------------------------
-- 5. rank_objection —— 公示期异议
--    第十四条：公示期内有异议向辅导员反馈，辅导员答复；同一批次同一学生只允许一条待处理异议。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rank_objection (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    batch_id    BIGINT       NOT NULL COMMENT 'rank_batch.id',
    student_id  BIGINT       NOT NULL COMMENT 'users.id（提出异议的学生）',
    reason      VARCHAR(500) NOT NULL COMMENT '异议内容（去空白后 ≥4 字）',
    status      VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending 待处理 / accepted 采纳 / rejected 不采纳',
    answer      VARCHAR(500) NULL COMMENT '辅导员答复（去空白后 ≥4 字）',
    handled_by  BIGINT       NULL COMMENT '处理人 users.id',
    handled_at  DATETIME     NULL COMMENT '处理时间',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_ro_batch_status (batch_id, status),
    KEY idx_ro_student (student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学工平台·综测公示异议';


-- =============================================================================
-- 6. 自检段：建完表后逐项打印（表 / 列 / 索引 / 行数 / 状态分布）
--    不插入任何演示数据，故行数一律应为 0（除非业务已经开始用）。
-- =============================================================================

SELECT '6.1 表' AS 检查项, TABLE_NAME, TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('eval_academic', 'eval_bonus', 'rank_batch', 'rank_row', 'rank_objection')
ORDER BY TABLE_NAME;

SELECT '6.2 列' AS 检查项, TABLE_NAME, ORDINAL_POSITION, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('eval_academic', 'eval_bonus', 'rank_batch', 'rank_row', 'rank_objection')
ORDER BY TABLE_NAME, ORDINAL_POSITION;

SELECT '6.3 索引' AS 检查项, TABLE_NAME, INDEX_NAME, NON_UNIQUE,
       GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS 列
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('eval_academic', 'eval_bonus', 'rank_batch', 'rank_row', 'rank_objection')
GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE
ORDER BY TABLE_NAME, INDEX_NAME;

SELECT '6.4 行数' AS 检查项, 'eval_academic' AS 表, COUNT(*) AS 行数 FROM eval_academic WHERE deleted = 0
UNION ALL SELECT '6.4 行数', 'eval_bonus', COUNT(*) FROM eval_bonus WHERE deleted = 0
UNION ALL SELECT '6.4 行数', 'rank_batch', COUNT(*) FROM rank_batch WHERE deleted = 0
UNION ALL SELECT '6.4 行数', 'rank_row', COUNT(*) FROM rank_row
UNION ALL SELECT '6.4 行数', 'rank_objection', COUNT(*) FROM rank_objection;

SELECT '6.5 状态分布' AS 检查项, 'eval_bonus.status' AS 维度, status AS 值, COUNT(*) AS 行数
FROM eval_bonus WHERE deleted = 0 GROUP BY status
UNION ALL SELECT '6.5 状态分布', 'rank_batch.status', status, COUNT(*) FROM rank_batch WHERE deleted = 0 GROUP BY status
UNION ALL SELECT '6.5 状态分布', 'rank_objection.status', status, COUNT(*) FROM rank_objection GROUP BY status;
