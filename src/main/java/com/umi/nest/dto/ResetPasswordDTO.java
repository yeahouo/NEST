package com.umi.nest.dto;

import lombok.Data;

/**
 * 重置密码请求 DTO
 */
@Data
public class ResetPasswordDTO {

    /**
     * 手机号（含区号，如 +8613812345678）
     */
    private String phone;

    /**
     * 新密码
     */
    private String newPassword;

    /**
     * 确认新密码
     */
    private String confirmPassword;

    /**
     * 验证码。<b>必填</b>——服务端会校验它，并且校验通过后立刻作废。
     *
     * <p>这个字段的注释以前写着「预留，暂不强制校验」，而服务端也确实不校验；
     * 加上手机号是从前端 {@code sessionStorage} 传进来的，等于任何人都能重置
     * 任意已注册账号的密码。现在它是唯一证明「手机号归你」的东西。
     */
    private String verificationCode;
}
