package com.umi.nest.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学工平台·学生档案（学工口径）。
 *
 * <p>user_id 指向 users.id；users 里的 real_name / student_id / class_name / major / grade
 * 是账号自带的资料，本表补学工需要的辅导员、宿舍、政治面貌与家庭情况。</p>
 *
 * <p>姓名不在本表：展示时 JOIN users.real_name（mapper 带出 name 字段）。</p>
 */
@Data
public class StudentProfile {

    private Long id;
    private Long userId;
    private String studentNo;
    private String className;
    private String major;
    private String grade;
    private Long counselorId;
    private String dormBuilding;
    private String dormRoom;
    private String politicalStatus;
    private String familyContact;
    private String familyIncomeLevel;
    /** active 在读 / suspended 休学 / quit 退学 / graduated 毕业 */
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;

    /** 列表展示用（JOIN users 带出，非表字段） */
    private String name;
    private String username;
    private String phone;
    private String counselorName;
}
