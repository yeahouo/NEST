package com.umi.nest.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 学习成绩 A 登记台账（学工 P13）：eval_academic 表。
 *
 * <p>A 由教务部/IT 负责（办法第八条），平台只登记：教务给的加权分从 import 进，手工补录走 manual。
 * 一条唯一键 (student_id, academic_term)，重复登记即更新。</p>
 *
 * <p><b>缺 A 不补 0</b>：score_a 为 null 表示尚未登记，该生不参与本批次排名，
 * compute 会把「已排名 / 缺数据」都报出来，管理端用 /api/rank/academic/missing 拿待补清单。</p>
 */
@Data
public class EvalAcademic {

    private Long id;

    /** users.id（学生） */
    private Long studentId;

    /** 学期，如 2025-2026-1 */
    private String academicTerm;

    /** 必修加权平均分（教务源数据） */
    private BigDecimal requiredAvg;

    /** 选修加权平均分 */
    private BigDecimal electiveAvg;

    /** 取得成绩的课程总学分 */
    private BigDecimal totalCredits;

    /** 课程门数 */
    private Integer courseCount;

    /** 学习成绩 A —— 排名引擎唯一认可的学业分；为 null 则不参与排名 */
    private BigDecimal scoreA;

    /** import 教务导入 / manual 手工登记 */
    private String source;

    /** 登记/导入人 users.id */
    private Long importedBy;

    private String note;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN 得来） ----

    private String studentName;

    private String studentNo;

    private String className;

    private String major;

    private String grade;
}
