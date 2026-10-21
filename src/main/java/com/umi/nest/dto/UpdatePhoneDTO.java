package com.umi.nest.dto;

import lombok.Data;

/**
 * 修改手机号请求 DTO
 */
@Data
public class UpdatePhoneDTO {

    /**
     * 新手机号（含区号，如 +8613812345678）
     */
    private String newPhone;

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
     * 前端可能用 phone 字段
     */
    private String phone;

    /**
     * 前端可能用 code 字段
     */
    private String code;

    /**
     * 获取新手机号（兼容前端）
     */
    public String getNewPhone() {
        return newPhone != null ? newPhone : phone;
    }

    /**
     * 获取验证码（兼容前端）
     */
    public String getVerificationCode() {
        return verificationCode != null ? verificationCode : code;
    }
}
