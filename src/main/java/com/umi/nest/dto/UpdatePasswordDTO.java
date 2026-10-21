package com.umi.nest.dto;

import lombok.Data;

/**
 * 修改密码请求 DTO
 */
@Data
public class UpdatePasswordDTO {

    /**
     * 原密码（后端字段名）
     */
    private String oldPassword;

    /**
     * 当前密码（前端字段名，兼容）
     */
    private String currentPassword;

    /**
     * 新密码
     */
    private String newPassword;

    /**
     * 确认新密码
     */
    private String confirmPassword;

    /**
     * 获取旧密码（兼容两种字段名）
     */
    public String getOldPassword() {
        return oldPassword != null ? oldPassword : currentPassword;
    }
}
