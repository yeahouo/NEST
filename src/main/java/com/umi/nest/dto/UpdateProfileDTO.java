package com.umi.nest.dto;

import lombok.Data;

/**
 * 更新个人资料请求 DTO
 */
@Data
public class UpdateProfileDTO {

    /**
     * 真实姓名
     */
    private String realName;

    /**
     * 学号（学生专用）
     */
    private String studentId;

    /**
     * 所属学校
     */
    private String school;

    /**
     * 所属院系
     */
    private String department;

    /**
     * 所属专业
     */
    private String major;

    /**
     * 班级名称
     */
    private String className;

    /**
     * 年级
     */
    private String grade;

    /**
     * 入学时间
     */
    private String enrollmentDate;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 头像URL
     */
    private String avatar;
}
