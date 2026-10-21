package com.umi.nest.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 教师邀请码实体类
 */
@Data
public class TeacherInvitationCode {

    private Long id;

    /**
     * 邀请码
     */
    private String code;

    /**
     * 描述/备注
     */
    private String description;

    /**
     * 状态: 1有效 0禁用
     */
    private Integer status;

    /**
     * 已使用次数
     */
    private Integer usageCount;

    /**
     * 最大使用次数(NULL无限制)
     */
    private Integer maxUsage;

    /**
     * 过期时间(NULL永不过期)
     */
    private LocalDateTime expiresAt;

    /**
     * 创建者ID
     */
    private Long createdBy;

    /**
     * 逻辑删除: 0正常 1删除
     */
    private Integer deleted;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
