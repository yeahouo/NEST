package com.umi.nest.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * P19 生源地信用助学贷款登记：student_loan 表。
 *
 * <p>字段口径是**学校自定**（见 sql/nest_loan.sql 头注释）：系统不内置金额上下限、不内置生源地
 * 行政区划代码、不区分经办行，也不替学校决定是否强制要求回执校验码。</p>
 *
 * <p>状态机（唯一权威在 LoanService）：</p>
 * <pre>
 *   submitted（学生提交）
 *     --辅导员核验通过--> counselor_approved
 *     --辅导员退回----> returned --学生改写重交--> submitted
 *     --辅导员不予登记--> rejected（终态）
 *     --学生撤回-------> withdrawn（终态）
 *   counselor_approved --学校确认--> confirmed（终态）
 * </pre>
 */
@Data
public class StudentLoan {

    private Long id;

    /** 学生 users.id */
    private Long userId;

    /** 学年，形如 2026-2027 */
    private String academicYear;

    private String originProvince;

    private String originCity;

    /** 县/区 —— 生源地贷款的实际办理地 */
    private String originCounty;

    /** 经办机构/贷款银行 */
    private String bankName;

    private String contractNo;

    /** 回执校验码（可选） */
    private String receiptNo;

    private BigDecimal amount;

    /** 0 未到账 / 1 已到账（学生申报） */
    private Integer disbursed;

    private LocalDate disbursedAt;

    /** submitted / counselor_approved / confirmed / returned / rejected / withdrawn */
    private String status;

    private String returnReason;

    private LocalDateTime submittedAt;

    private Long counselorId;

    private LocalDateTime counselorAt;

    /** 学校确认人 */
    private Long reviewerId;

    private LocalDateTime reviewedAt;

    private String remark;

    private String evidenceUrl;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN users / student_profile 得来） ----

    /** 学生姓名 */
    private String studentName;

    /** 学号 */
    private String studentNo;

    /** 班级 */
    private String className;

    /** 辅导员姓名（student_profile.counselor_id → users.real_name） */
    private String counselorName;
}
