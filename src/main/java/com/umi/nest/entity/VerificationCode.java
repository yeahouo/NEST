package com.umi.nest.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 验证码。
 *
 * <p>一条记录就是「某个场景下、发给某个手机号/邮箱的那个 6 位码」的完整生命周期：
 * 创建 → （最多 5 次）校验 → 用掉或者过期。
 *
 * <p>表里存的是 {@code codeHash}，不是明文。哈希里混了服务端的 pepper
 * （{@code nest.verify.pepper}），所以拿到整张表的 dump 也反推不出还在有效期内的码。
 */
@Data
public class VerificationCode {

    private Long id;

    /** 场景，见 {@code VerifyCodeService.SCENE_*}。 */
    private String scene;

    /** 接收方：手机号或邮箱（已做归一化，去掉空格/横线/括号）。 */
    private String target;

    /** HMAC-SHA256(scene|target|code, pepper) 的十六进制。 */
    private String codeHash;

    private LocalDateTime expiresAt;

    /** 已尝试校验的次数。错满 {@code MAX_ATTEMPTS} 次即作废。 */
    private Integer attempts;

    /** 0=未使用 1=已使用。一次性，用过就废。 */
    private Integer used;

    private LocalDateTime createTime;
}
