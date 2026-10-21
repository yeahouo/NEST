package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学生社团台账（学工 P17，S25a）：club 表。
 *
 * <p>字段名刻意区分：{@code attachUnit} 是**挂靠单位**（S25a 口径），与
 * {@link ClubRating#getBusinessUnit()} 的**业务指导单位**（S25b 口径）**不得互换**（§2）。</p>
 *
 * <p>{@code category} 存 §3.4 实测六类中文值。{@code advisorName} 无来源可依，自由填写（§13-⑧）。
 * {@code status}：{@code active} 正常 / {@code deregistered} 已注销（§3.1）。</p>
 */
@Data
public class Club {

    private Long id;

    /** 社团名称 */
    private String clubName;

    /** 社团类别（§3.4 六类，中文值直接存库） */
    private String category;

    /** 挂靠单位（S25a 字段名；勿与 club_rating.business_unit「业务指导单位」互换） */
    private String attachUnit;

    /** 成立日期 */
    private LocalDate foundDate;

    /** 负责人姓名 */
    private String leaderName;

    /** 指导教师姓名（无来源，自由填写） */
    private String advisorName;

    /** active 正常 / deregistered 已注销（§3.1） */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN 统计带出） ----

    /** 在册成员数（club_member 中 quit_date IS NULL 的计数，不落库） */
    @JsonProperty("memberCount")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer memberCount;
}
