package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 志愿服务报名（学工 P17，S21）：volunteer_enrollment 表。
 *
 * <p>{@code UNIQUE KEY uk_project_user (project_id, user_id)}：一个学生对一个项目只有一条报名。
 * 状态机（§3.1）：{@code pending} 待确认 → {@code confirmed} 已确认 → {@code attended} 已参加 /
 * {@code absent} 无故缺席；{@code pending}/{@code confirmed} 可 {@code cancelled} 已取消。
 * {@code cancelled} 报名不得登记出勤（§7-4）。</p>
 *
 * <p>本表属学生范围表：list/count 须支持按辅导员过滤
 * （{@code JOIN student_profile sp ON sp.user_id = t.user_id AND sp.counselor_id = #{scopeCounselorId}}，§6）。</p>
 */
@Data
public class VolunteerEnrollment {

    private Long id;

    /** 志愿项目 volunteer_project.id */
    private Long projectId;

    /** 报名学生 users.id */
    private Long userId;

    private LocalDateTime applyTime;

    /** pending / confirmed / cancelled / attended / absent（§3.1） */
    private String status;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN 带出） ----

    /** 学生姓名（users.real_name） */
    @JsonProperty("studentName")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String studentName;

    /** 学号（student_profile.student_no） */
    @JsonProperty("studentNo")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String studentNo;

    /** 班级（student_profile.class_name） */
    @JsonProperty("className")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String className;

    /** 项目名称（volunteer_project.project_name） */
    @JsonProperty("projectName")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String projectName;
}
