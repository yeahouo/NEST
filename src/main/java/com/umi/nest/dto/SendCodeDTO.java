package com.umi.nest.dto;

import lombok.Data;

/**
 * 发送验证码请求 DTO
 */
@Data
public class SendCodeDTO {

    /**
     * 手机号（含区号，如 +8613812345678）
     */
    private String phone;
}
