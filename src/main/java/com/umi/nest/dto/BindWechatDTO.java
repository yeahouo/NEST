package com.umi.nest.dto;

import lombok.Data;

/**
 * 绑定微信请求 DTO
 */
@Data
public class BindWechatDTO {

    /**
     * 微信授权码（前端通过微信OAuth获取）
     */
    private String code;

    /**
     * 验证当前密码（安全验证，可选）
     */
    private String currentPassword;
}
