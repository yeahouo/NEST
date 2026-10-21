package com.umi.nest.dto;

import lombok.Data;

/**
 * 管理员更新用户 DTO
 */
@Data
public class AdminUserUpdateDTO {

    /**
     * 用户角色: student/teacher/admin
     */
    private String role;

    /**
     * 状态: 1正常 0禁用
     */
    private Integer status;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 手机号
     */
    private String phone;
}
