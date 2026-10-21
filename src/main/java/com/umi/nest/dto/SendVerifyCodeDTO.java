package com.umi.nest.dto;

import lombok.Data;

/**
 * 请求发送验证码（设置页：改手机号 / 绑邮箱）。
 *
 * <p>{@code scene} 用短名 {@code phone} / {@code email}，由控制器映射到
 * {@code VerifyCodeService} 的场景常量。
 */
@Data
public class SendVerifyCodeDTO {

    /** phone / email */
    private String scene;

    /** 目标手机号或邮箱（若用户要绑定的新值）。 */
    private String target;
}
