package com.umi.nest.dto;

import lombok.Data;

/**
 * 修改邮箱请求 DTO
 */
@Data
public class UpdateEmailDTO {

    /**
     * 新邮箱地址
     */
    private String newEmail;

    /**
     * 验证码
     */
    private String verificationCode;

    /**
     * 验证当前密码（安全验证）
     */
    private String currentPassword;

    // ========== 兼容前端字段名 ==========

    /**
     * 前端可能用 email 字段
     */
    private String email;

    /**
     * 前端可能用 code 字段
     */
    private String code;

    /**
     * 获取新邮箱（兼容前端）
     */
    public String getNewEmail() {
        return newEmail != null ? newEmail : email;
    }

    /**
     * 获取验证码（兼容前端）
     */
    public String getVerificationCode() {
        return verificationCode != null ? verificationCode : code;
    }
}
