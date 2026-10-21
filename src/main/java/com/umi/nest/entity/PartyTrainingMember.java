package com.umi.nest.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 党校学员报名与结业（学工 P16）：party_training_member 表。
 *
 * <p>状态机（全部 CAS，见 PartyTrainingService）：
 * {@code signed 已报名 -> approved 审核通过 -> completed 已结业}；
 * {@code approved -> rejected 审核驳回}。completed 之外不写结业信息。</p>
 *
 * <p>结业证书口径（§11.2-7）：{@code certValidUntil} 默认 =
 * {@code certIssueDate} + 一年（校规「结业证书自颁发之日起一年内有效」），
 * 由服务层推算，**传入即采信人工覆盖值**（§11.2-7「可人工覆盖」）。</p>
 *
 * <p>§11.1-③：党校结业标准（及格线、考核权重）无任何来源 => 只登记
 * {@code completeResult = pass|fail} 的人工结论，**不建分数字段、不做自动判定**。</p>
 */
@Data
public class PartyTrainingMember {

    private Long id;

    /** party_training.id */
    private Long trainingId;

    /** 学员 users.id */
    private Long userId;

    /** 报名时学号快照（users.student_id） */
    private String studentSnapshot;

    /** 报名时班级快照（student_profile.class_name） */
    private String classSnapshot;

    /** 报名时间 */
    private LocalDateTime signupAt;

    /** signed 已报名 / approved 审核通过 / rejected 审核驳回 / completed 已结业 */
    private String status;

    /** 审核人 users.id（辅导员/管理员） */
    private Long reviewBy;

    /** 审核时间 */
    private LocalDateTime reviewAt;

    /** 驳回原因 */
    private String rejectReason;

    /** 结业结论：pass 合格 / fail 不合格（无及格线来源，只登记人工结论） */
    private String completeResult;

    /** 结业日期 */
    private LocalDate completeDate;

    /** 结业证书编号 */
    private String certNo;

    /** 证书颁发日期 */
    private LocalDate certIssueDate;

    /** 证书有效期至（默认 = 颁发日期 + 一年内有效，可人工覆盖） */
    private LocalDate certValidUntil;

    private String note;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN / 服务层计算得出；一律 @JsonProperty 防 Lombok 大小写坑） ----

    /** 学员姓名（users.real_name） */
    @JsonProperty("userName")
    private String userName;

    /** 学号（users.student_id） */
    @JsonProperty("studentNo")
    private String studentNo;

    /** 班次名称（party_training.training_name） */
    @JsonProperty("trainingName")
    private String trainingName;

    /** 状态中文文案（signed→已报名 / approved→审核通过 / rejected→已驳回 / completed→已结业） */
    @JsonProperty("statusText")
    private String statusText;

    /** 结业结论中文文案（pass→合格 / fail→不合格） */
    @JsonProperty("completeResultText")
    private String completeResultText;

    /** 证书是否仍在有效期内（1 有效 / 0 已过期 / null 无证书；服务层按当日计算） */
    @JsonProperty("certValid")
    private Integer certValid;
}
