-- ============================================================
-- NEST 宿舍智能分配 —— 宿舍域数据表
-- 说明：与教学域表（users/courses/...）完全分开，互不干扰。
--       全部以 dorm_ 前缀，可整域 DROP 重建。
-- 字符集：utf8mb4 / utf8mb4_unicode_ci
-- ============================================================

CREATE DATABASE IF NOT EXISTS nest
DEFAULT CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;

USE nest;

-- ------------------------------------------------------------
-- 先整体清场。必须显式关外键检查并**按反向依赖顺序**删：
-- 单独 DROP dorm_student 会被 dorm_student_feature / dorm_preference 的外键挡住，
-- 报 ERROR 3730，而报错信息只说"被引用"，不会告诉你该按什么顺序删。
-- ------------------------------------------------------------
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS dorm_ground_truth;
DROP TABLE IF EXISTS dorm_audit;
DROP TABLE IF EXISTS dorm_appeal;
DROP TABLE IF EXISTS dorm_explanation;
DROP TABLE IF EXISTS dorm_assignment;
DROP TABLE IF EXISTS dorm_batch;
DROP TABLE IF EXISTS dorm_preference;
DROP TABLE IF EXISTS dorm_student_feature;
DROP TABLE IF EXISTS dorm_student;
DROP TABLE IF EXISTS dorm_room;
SET FOREIGN_KEY_CHECKS = 1;

-- ------------------------------------------------------------
-- 1. 学生基本信息（硬约束判定只依赖这张表）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS dorm_student;
CREATE TABLE dorm_student (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    student_no      VARCHAR(32)  NOT NULL COMMENT '学号，业务唯一键',
    name            VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '姓名',
    gender          VARCHAR(1)   NOT NULL COMMENT 'M/F，与房间性别必须一致（硬约束）',
    grade           VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '年级，如 2024',
    major           VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '专业',
    major_cluster   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '专业大类，分块依据',
    building_pref   VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '楼栋志愿（软）',
    special_need    VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT 'NONE/WHEELCHAIR/LOW_FLOOR/QUIET（硬约束，排他）',
    user_id         BIGINT       NULL COMMENT '关联 users.id，学生端门户登录用；允许为空（名单先到、账号后开）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dorm_student_no (student_no),
    KEY idx_dorm_student_gender_grade (gender, grade),
    KEY idx_dorm_student_cluster (major_cluster),
    KEY idx_dorm_student_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT '宿舍域-学生基本信息';

-- ------------------------------------------------------------
-- 2. 学生特征（问卷观测值；可空 = 缺失，算法必须能处理缺失）
--    NULL 语义明确：这道题学生没答 / 答了但无效，不是 0 分。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS dorm_student_feature;
CREATE TABLE dorm_student_feature (
    student_id      BIGINT       NOT NULL,
    sleep_start     DOUBLE       NULL COMMENT '入睡时刻（24 小时制小数，24.5 = 次日 0:30）',
    sleep_end       DOUBLE       NULL COMMENT '起床时刻',
    smoking         DOUBLE       NULL COMMENT '吸烟倾向 [0,1]',
    cleanliness     DOUBLE       NULL COMMENT '卫生要求 [0,1]',
    extroversion    DOUBLE       NULL COMMENT '性格外向度 [0,1]',
    study_in_room   DOUBLE       NULL COMMENT '在寝室学习/需要安静 [0,1]',
    noise_tolerance DOUBLE       NULL COMMENT '噪声容忍度 [0,1]',
    social_activity DOUBLE       NULL COMMENT '社交活跃度 [0,1]',
    share_items     DOUBLE       NULL COMMENT '物品共享意愿 [0,1]',
    gaming          DOUBLE       NULL COMMENT '游戏/开黑 [0,1]',
    ambition        DOUBLE       NULL COMMENT '学业拼搏度 [0,1]（算法模型不含此维，仅真值使用）',
    source          VARCHAR(32)  NOT NULL DEFAULT 'QUESTIONNAIRE' COMMENT 'QUESTIONNAIRE/AGENT_LLM/MANUAL',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (student_id),
    CONSTRAINT fk_dorm_feat_student FOREIGN KEY (student_id) REFERENCES dorm_student (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT '宿舍域-学生特征观测值（可空表示缺失）';

-- ------------------------------------------------------------
-- 3. 志愿（想同住 / 不想同住 / 楼栋志愿）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS dorm_preference;
CREATE TABLE dorm_preference (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    student_id      BIGINT      NOT NULL,
    kind            VARCHAR(16) NOT NULL COMMENT 'ROOMMATE/AVOID/BUILDING',
    target_student  BIGINT      NULL COMMENT 'TYPE=ROOMMATE/AVOID 时的目标同学',
    building_id     VARCHAR(32) NULL COMMENT 'TYPE=BUILDING 时的楼栋',
    priority        INT         NOT NULL DEFAULT 1 COMMENT '越小越优先',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_dorm_pref_student (student_id, kind),
    KEY idx_dorm_pref_target (target_student),
    CONSTRAINT fk_dorm_pref_student FOREIGN KEY (student_id) REFERENCES dorm_student (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT '宿舍域-志愿';

-- ------------------------------------------------------------
-- 4. 房间
--    for_special_needs 语义为排他：置 1 时只有特殊需求学生可入住。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS dorm_room;
CREATE TABLE dorm_room (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    room_no           VARCHAR(32) NOT NULL COMMENT '房号，如 1-305',
    building_id       VARCHAR(32) NOT NULL COMMENT '楼栋',
    gender            VARCHAR(1)  NOT NULL COMMENT 'M/F，该楼栋性别（硬约束）',
    capacity          INT         NOT NULL DEFAULT 4,
    for_special_needs TINYINT(1)  NOT NULL DEFAULT 0,
    grade_restriction VARCHAR(16) NOT NULL DEFAULT '' COMMENT '留空 = 不限；否则只收该年级',
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dorm_room_no (building_id, room_no),
    KEY idx_dorm_room_pool (gender, for_special_needs)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT '宿舍域-房间';

-- ------------------------------------------------------------
-- 5. 分配批次（一次"一键分配"的运行记录）
--    指标快照直接落在这里：审计要能回答"当时系统说它做到了什么"。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS dorm_batch;
CREATE TABLE dorm_batch (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    name              VARCHAR(128) NOT NULL DEFAULT '',
    solver            VARCHAR(64)  NOT NULL DEFAULT '',
    status            VARCHAR(16)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/DONE/FAILED/SUPERSEDED',
    config_json       MEDIUMTEXT   NULL COMMENT '本次运行用的完整约束配置（可复现）',
    student_count     INT          NOT NULL DEFAULT 0,
    room_count        INT          NOT NULL DEFAULT 0,
    capacity_total    INT          NOT NULL DEFAULT 0,
    assigned_count    INT          NOT NULL DEFAULT 0,
    unassigned_count  INT          NOT NULL DEFAULT 0,
    hard_violations   INT          NOT NULL DEFAULT 0,
    mean_compat       DOUBLE       NULL COMMENT '房内两两相容度均值（真值）',
    worst_compat      DOUBLE       NULL COMMENT '最差那一对（木桶指标）',
    room_variance     DOUBLE       NULL COMMENT '房内相容度方差',
    blocking_pairs    INT          NOT NULL DEFAULT 0 COMMENT '核心指标：阻塞对数量',
    preference_rate   DOUBLE       NULL COMMENT '志愿满足率',
    fairness_major    DOUBLE       NULL COMMENT '各专业大类满意度的极差',
    fairness_grade    DOUBLE       NULL COMMENT '各年级满意度的极差',
    runtime_ms        BIGINT       NOT NULL DEFAULT 0,
    block_count       INT          NOT NULL DEFAULT 0,
    max_block_size    INT          NOT NULL DEFAULT 0,
    diagnostics_json  MEDIUMTEXT   NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at       DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_dorm_batch_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT '宿舍域-分配批次';

-- ------------------------------------------------------------
-- 6. 分配结果（学生 → 房间）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS dorm_assignment;
CREATE TABLE dorm_assignment (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    batch_id      BIGINT      NOT NULL,
    student_id    BIGINT      NOT NULL,
    room_id       BIGINT      NULL COMMENT 'NULL = 本次未分配到',
    room_label    VARCHAR(64) NOT NULL DEFAULT '',
    building_id   VARCHAR(32) NOT NULL DEFAULT '',
    is_appealed   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否因采纳申诉而改动过',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dorm_assign_batch_student (batch_id, student_id),
    KEY idx_dorm_assign_room (batch_id, room_id),
    CONSTRAINT fk_dorm_assign_batch FOREIGN KEY (batch_id) REFERENCES dorm_batch (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT '宿舍域-分配结果';

-- ------------------------------------------------------------
-- 7. 四维解释（分配完成时<b>预先批量算好落库</b>，查询时直接读）
--    为什么不实时算：解释要算 O(室友数 × 维度数) 的分解 + 块内对比方案，
--    学生一刷新就重算一遍，上千人同时查会打爆服务；且"解释"必须和"当时的分"
--    完全一致，实时算有可能因配置变动而对不上。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS dorm_explanation;
CREATE TABLE dorm_explanation (
    id           BIGINT     NOT NULL AUTO_INCREMENT,
    batch_id     BIGINT     NOT NULL,
    student_id   BIGINT     NOT NULL,
    room_id      BIGINT     NULL,
    payload_json MEDIUMTEXT NOT NULL COMMENT 'Explanation 的完整 JSON（四维 + 叙事）',
    created_at   DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dorm_expl_batch_student (batch_id, student_id),
    CONSTRAINT fk_dorm_expl_batch FOREIGN KEY (batch_id) REFERENCES dorm_batch (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT '宿舍域-四维解释（预算好）';

-- ------------------------------------------------------------
-- 8. 申诉
-- ------------------------------------------------------------
DROP TABLE IF EXISTS dorm_appeal;
CREATE TABLE dorm_appeal (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    batch_id       BIGINT       NOT NULL,
    student_id     BIGINT       NOT NULL,
    kind           VARCHAR(24)  NOT NULL DEFAULT 'ROOMMATE' COMMENT 'ROOMMATE/BUILDING/SINGLE_ROOM',
    target_ids     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '逗号分隔的同学 id',
    status         VARCHAR(24)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SATISFIED/UNSATISFIABLE/NOT_FOUND_IN_SCOPE/ACCEPTED/REJECTED',
    scope          VARCHAR(255) NOT NULL DEFAULT '' COMMENT '试算范围，对学生透明',
    message        TEXT         NULL,
    cost_delta     DOUBLE       NULL,
    cost_budget    DOUBLE       NULL,
    payload_json   MEDIUMTEXT   NULL COMMENT 'AppealResult 完整 JSON（含建议的两人对调）',
    elapsed_ms     BIGINT       NOT NULL DEFAULT 0,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    handled_at     DATETIME     NULL,
    handled_by     VARCHAR(64)  NULL,
    PRIMARY KEY (id),
    KEY idx_dorm_appeal_batch (batch_id, status),
    CONSTRAINT fk_dorm_appeal_batch FOREIGN KEY (batch_id) REFERENCES dorm_batch (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT '宿舍域-申诉';

-- ------------------------------------------------------------
-- 9. 公平性审计明细（按分组落盘，供结论回溯）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS dorm_audit;
CREATE TABLE dorm_audit (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    batch_id    BIGINT       NOT NULL,
    metric      VARCHAR(48)  NOT NULL COMMENT '如 satisfaction.byMajorCluster',
    group_key   VARCHAR(64)  NOT NULL,
    group_size  INT          NOT NULL DEFAULT 0,
    value       DOUBLE       NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_dorm_audit_batch (batch_id, metric),
    CONSTRAINT fk_dorm_audit_batch FOREIGN KEY (batch_id) REFERENCES dorm_batch (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT '宿舍域-公平性审计明细';

-- ------------------------------------------------------------
-- 10. 合成数据的隐真值（仅仿真用）
--
--     这里存的是"上帝视角"的真值特征 z_i 与两个隐变量。它<b>不是</b> batch 的附属物，
--     而是数据集本身的属性，所以按 student_id 存，不带 batch 外键。
--
--     为什么必须落到数据库：dorm_student_feature 存的是"发问卷观测到的 o_i"（带噪声、
--     可能缺失），solver 只读那张表。真值单独一张表，让"算法永远看不到 g"
--     从一个口头承诺变成一条物理隔离——求解器的代码里根本没有读这张表的路径。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS dorm_ground_truth;
CREATE TABLE dorm_ground_truth (
    student_id        BIGINT NOT NULL,
    true_sleep_start  DOUBLE NULL,
    true_sleep_end    DOUBLE NULL,
    true_smoking      DOUBLE NULL,
    true_cleanliness  DOUBLE NULL,
    true_extroversion DOUBLE NULL,
    true_study        DOUBLE NULL,
    true_noise_tol    DOUBLE NULL,
    true_social       DOUBLE NULL,
    true_sharing      DOUBLE NULL,
    true_gaming       DOUBLE NULL,
    true_ambition     DOUBLE NULL,
    latent_affinity   DOUBLE NULL COMMENT '隐变量：磁场项，模型看不见',
    smoke_tolerance   DOUBLE NULL COMMENT '隐变量：个人对烟味的容忍度，模型看不见',
    dataset_tag       VARCHAR(64) NOT NULL DEFAULT '' COMMENT '数据批次标记，如 synth-n1000-s20260927',
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (student_id),
    KEY idx_dorm_gt_tag (dataset_tag),
    CONSTRAINT fk_dorm_gt_student FOREIGN KEY (student_id) REFERENCES dorm_student (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT '宿舍域-合成数据隐真值（仅仿真用；求解器禁止读此表）';
