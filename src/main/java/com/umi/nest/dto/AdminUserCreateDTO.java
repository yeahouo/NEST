package com.umi.nest.dto;

import lombok.Data;

/**
 * 管理员创建用户 DTO
 */
@Data
public class AdminUserCreateDTO {

    private String username;

    private String password;

    private String nickname;

    private String email;

    private String phone;

    /**
     * 角色: student/teacher/admin
     */
    private String role;
}
