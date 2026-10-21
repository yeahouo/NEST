-- ============================================================================
-- 短信 / 邮件验证码表（幂等：可重复执行）
-- ============================================================================
--
-- 为什么需要这张表：
--   改手机号、绑邮箱、忘记密码这三条链路，前端都摆着一个「验证码」输入框，
--   后端也摆着一个 // 验证码校验（预留） 的注释，但从来没有一个验证码被生成、
--   被发送、被校验过。也就是说：
--     * 只要往 sessionStorage 里写一个别人的手机号，就能重置那个人的密码；
--     * 改手机号 / 绑邮箱不需要证明你拥有那个手机号 / 邮箱。
--   这张表把「验证码」变成一个有生命周期、可校验、可限流、一次性的实体。
--
-- 设计取舍：
--   1. 存 code_hash 而不是明文。验证码只有 6 位，单靠哈希挡不住穷举，
--      所以哈希里混入服务端的 pepper（nest.verify.pepper），
--      这样即使有人 dump 走了整张表，也无法反推出还在有效期内的验证码。
--   2. used / attempts / expires_at 三个字段承担「一次性」「最多试 5 次」
--      「5 分钟过期」这三条规则，规则由服务端执行，表结构只是承载状态。
--   3. index 覆盖 verify 时唯一的热路径查询（scene + target + used + expires_at）。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS nest DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS nest_pilot DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS nest.verification_code (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    scene       VARCHAR(32)  NOT NULL COMMENT '场景：bind_phone / bind_email / reset_password',
    target      VARCHAR(128) NOT NULL COMMENT '接收方（手机号或邮箱）',
    code_hash   CHAR(64)     NOT NULL COMMENT 'HMAC-SHA256(scene|target|code, pepper) 的十六进制',
    expires_at  DATETIME     NOT NULL COMMENT '过期时间',
    attempts    INT          NOT NULL DEFAULT 0 COMMENT '已尝试校验次数（错满上限即作废）',
    used        TINYINT      NOT NULL DEFAULT 0 COMMENT '0=未使用 1=已使用（一次性）',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_scene_target (scene, target, used, expires_at),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='验证码（一次性、有有效期、有尝试次数上限）';

CREATE TABLE IF NOT EXISTS nest_pilot.verification_code LIKE nest.verification_code;

-- 清理历史上可能存在的过期数据（这张表天然是短生命周期的，不需要保留归档）
DELETE FROM nest.verification_code WHERE create_time < DATE_SUB(NOW(), INTERVAL 7 DAY);
DELETE FROM nest_pilot.verification_code WHERE create_time < DATE_SUB(NOW(), INTERVAL 7 DAY);
