-- ============================================================
-- NEST 心理特征提取域（申请书创新点 4：智能体驱动的信息采集与特征理解）
--
-- 与 nest_dorm.sql 的分工：
--   dorm_student_feature  = 算法最终看到的观测 o_i（多来源融合后的结果）
--   dorm_psyche_*         = 这些数字是"怎么来的"——原材料、量表作答、证据链
--
-- 为什么要分开存：真值 g 存在 dorm_ground_truth 且算法读不到，那一边是"物理隔离"；
-- 这里反过来是"必须留痕"——导师或学生质疑"你凭什么说我外向度 0.7"时，
-- 必须能翻出当时那句话。所以 evidence 不是日志，是产品的一部分。
--
-- 执行顺序：先 nest_dorm.sql 再本文件（本文件引用 dorm_student）。
-- ============================================================

USE nest;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS dorm_psyche_profile;
DROP TABLE IF EXISTS dorm_psyche_answer;
DROP TABLE IF EXISTS dorm_psyche_text;
SET FOREIGN_KEY_CHECKS = 1;

-- ------------------------------------------------------------
-- 1. 原始文本素材：聊天记录 / 自我介绍 / 辅导员评语
-- 存原文是为了可解释与可复算；这是全库最敏感的表。
-- consent 为 0 的行不参与任何提取——同意是前置条件，不是免责声明。
-- ------------------------------------------------------------
CREATE TABLE dorm_psyche_text (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    student_id  BIGINT       NOT NULL COMMENT 'dorm_student.id',
    kind        VARCHAR(16)  NOT NULL DEFAULT 'SELF_INTRO'
                             COMMENT 'CHAT/ESSAY/SELF_INTRO/COUNSELOR',
    content     MEDIUMTEXT   NOT NULL,
    char_count  INT          NOT NULL DEFAULT 0,
    consent     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '学生是否同意用于特征分析',
    source      VARCHAR(32)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/API/DEMO',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_psyche_text_student (student_id, kind),
    CONSTRAINT fk_psyche_text_student FOREIGN KEY (student_id)
        REFERENCES dorm_student (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT '心理特征提取的原始文本素材';

-- ------------------------------------------------------------
-- 2. 量表作答。dimension 冗余存一份：题库将来会改版，
-- 但已经答过的题必须永远能追溯到它当时量的是什么。
-- ------------------------------------------------------------
CREATE TABLE dorm_psyche_answer (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    student_id  BIGINT       NOT NULL,
    item_code   VARCHAR(32)  NOT NULL COMMENT '题库里的稳定编码',
    item_no     INT          NOT NULL DEFAULT 0,
    dimension   VARCHAR(32)  NULL COMMENT '作答时的目标维度（快照）；故意可为空——第 15 题问作息规律性，与「入睡时刻」不同量纲，硬挂在同一维会把 0.5 和 23.5 平均成 12 点',
    answer_num  DECIMAL(8,4) NULL COMMENT '绝大多数维归一化到 0..1；作息两题存的是小时（22/22.5/23.5/24.5/26），所以必须留足整数位',
    answer_text VARCHAR(512) NULL COMMENT '自由文本题的原文；数字题留空。数字题写空串会让人分不清「答了 0」和「没答」',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_psyche_answer (student_id, item_code),
    KEY idx_psyche_answer_dim (student_id, dimension),
    CONSTRAINT fk_psyche_answer_student FOREIGN KEY (student_id)
        REFERENCES dorm_student (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT '量表作答';

-- ------------------------------------------------------------
-- 3. 提取结果（一人一行，保留最近一次）。
--
-- features_json    : 11 维，缺失为 null（绝不写 0 顶替）
-- confidence_json  : 11 维置信度 0..1，供上层决定"敢不敢用这一维"
-- evidence_json    : 证据链，每维引用原文片段或不引用（引用不到就不给分）
-- big_five_json     : 大五人格五维原始输出（与 11 维分开存，
--                    因为映射关系将来会改，原始判断不该被覆盖）
-- applied          : 是否已经写回 dorm_student_feature
-- ------------------------------------------------------------
CREATE TABLE dorm_psyche_profile (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    student_id        BIGINT       NOT NULL,
    method            VARCHAR(48)  NOT NULL COMMENT '提取器标识，如 rule-lexicon-v1 / llm-deepseek / ensemble',
    llm_used          TINYINT(1)   NOT NULL DEFAULT 0,
    big_five_json     TEXT         NULL,
    features_json     TEXT         NULL,
    confidence_json   TEXT         NULL,
    evidence_json     TEXT         NULL,
    narrative         TEXT         NULL COMMENT '人话总结，给学生看的',
    text_chars        INT          NOT NULL DEFAULT 0,
    answered_items    INT          NOT NULL DEFAULT 0,
    coverage          DECIMAL(5,4) NOT NULL DEFAULT 0 COMMENT '11 维里有几维拿到了值',
    applied           TINYINT(1)   NOT NULL DEFAULT 0,
    applied_source    VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '写回 dorm_student_feature 时用的 source',
    prompt_tokens     INT          NOT NULL DEFAULT 0,
    completion_tokens INT          NOT NULL DEFAULT 0,
    elapsed_ms        BIGINT       NOT NULL DEFAULT 0,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_at        DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_psyche_profile (student_id),
    CONSTRAINT fk_psyche_profile_student FOREIGN KEY (student_id)
        REFERENCES dorm_student (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT '心理特征提取结果与证据链';
