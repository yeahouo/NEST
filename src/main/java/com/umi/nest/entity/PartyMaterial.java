package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 材料清单（学工 P16）：party_material 表。
 *
 * <p>口径（§11.2-8）：**只登记「名称 + 应交阶段 + 已交与否 + 备注 + 附件文件名」**，
 * 平台**不生成官方表格 PDF**（三张考察表字段结构未获全文，§11.1-②，禁止臆造版式）；
 * {@code submitted} 只登记状态，附件只落文件名、不落二进制。</p>
 *
 * <p>{@code required} 是**提示口径**（必备/选备），不阻断阶段推进（§11.2-1）。</p>
 */
@Data
public class PartyMaterial {

    private Long id;

    /** 学生 users.id */
    private Long userId;

    /** 材料名称（只登记名称，平台不生成官方表格 PDF） */
    private String materialName;

    /** 应交阶段：applicant/activist/candidate/probationary/full */
    private String stage;

    /** 是否必备：1 必备 / 0 选备（提示口径，不阻断推进） */
    private Integer required;

    /** 已交与否：1 已交 / 0 未交 */
    private Integer submitted;

    /** 交件日期 */
    private LocalDate submitDate;

    /** 附件文件名（只登记文件名，不落二进制） */
    private String attachmentName;

    private String note;

    /** 依据/来源条款引用文本 */
    private String ruleRef;

    /** 登记人 users.id */
    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN / 服务层计算得出；一律 @JsonProperty 防 Lombok 大小写坑） ----

    /** 学生姓名（users.real_name） */
    @JsonProperty("userName")
    private String userName;

    /** 学号（users.student_id） */
    @JsonProperty("studentNo")
    private String studentNo;

    /** 应交阶段中文文案 */
    @JsonProperty("stageText")
    private String stageText;

    /** 是否必备中文文案（1→必备 / 0→选备） */
    @JsonProperty("requiredText")
    private String requiredText;

    /** 已交与否中文文案（1→已交 / 0→未交） */
    @JsonProperty("submittedText")
    private String submittedText;
}
