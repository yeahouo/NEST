package com.umi.nest.dto;

import lombok.Data;

/**
 * 创建邀请码 DTO
 */
@Data
public class InvitationCodeCreateDTO {

    /**
     * 邀请码（可选，不填则自动生成）
     */
    private String code;

    /**
     * 描述/备注
     */
    private String description;

    /**
     * 最大使用次数（可选，不填则无限制）
     */
    private Integer maxUsage;

    /**
     * 过期时间（可选，格式：yyyy-MM-dd HH:mm:ss）
     */
    private String expiresAt;
}
