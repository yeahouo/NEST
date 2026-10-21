package com.umi.nest.dto;

import lombok.Data;

/**
 * 登录请求 DTO
 * 适配前端页面字段
 */
@Data
public class LoginDTO {

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码
     */
    private String password;

    /**
     * 获取账号（兼容后端 account 字段名）
     */
    public String getAccount() {
        return username;
    }
}
