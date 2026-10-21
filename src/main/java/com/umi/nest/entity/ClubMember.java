package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 社团成员（学工 P17，S25a）：club_member 表。
 *
 * <p>{@code role}：{@code leader} 负责人 / {@code member} 成员（§3.1）。
 * 退出走 CAS（{@code quitMember}：只把 {@code quit_date} 由 NULL 置为指定日期，
 * 影响行数 0 → 400「该成员已退社或状态已变化」）。</p>
 *
 * <p>本表属学生范围表：list/count 须支持按辅导员过滤
 * （{@code JOIN student_profile sp ON sp.user_id = t.user_id AND sp.counselor_id = #{scopeCounselorId}}，§6）。</p>
 */
@Data
public class ClubMember {

    private Long id;

    /** 社团 club.id */
    private Long clubId;

    /** 成员 users.id */
    private Long userId;

    /** leader 负责人 / member 成员（§3.1） */
    private String role;

    private LocalDate joinDate;

    /** 退出日期；NULL 表示在册 */
    private LocalDate quitDate;

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

    /** 社团名称（club.club_name） */
    @JsonProperty("clubName")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String clubName;
}
