package com.umi.nest.dto;

import lombok.Data;

/**
 * 注册请求 DTO
 * 适配前端页面字段
 */
@Data
public class RegisterDTO {

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码（明文，后端加密）
     */
    private String password;

    /**
     * 确认密码
     */
    private String confirmPassword;

    /**
     * 手机号（含区号，如 +8613812345678）
     */
    private String phone;

    /**
     * 用户类型（student/teacher）
     */
    private String userType;

    /**
     * 邀请码（教师注册时需要）
     */
    private String invitationCode;

    /**
     * 获取角色（兼容前端 userType 字段）
     */
    public String getRole() {
        return userType;
    }
}
