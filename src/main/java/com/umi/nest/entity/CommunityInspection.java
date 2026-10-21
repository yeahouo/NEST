package com.umi.nest.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 宿舍检查记录（学工 P15）：community_inspection 表。
 *
 * <p>grade 由服务端按 score 计算，不采信客户端传值：
 * excellent ≥ 90 / good 80-89 / pass 60-79 / fail &lt; 60。</p>
 *
 * <p>整改闭环（全部 CAS，见 CommunityService）：none 无需整改 → pending 待整改 →
 * submitted 学生已提交说明 → accepted 复查通过 / rejected 复查未通过（回到待整改，可重新提交）。</p>
 */
@Data
public class CommunityInspection {

    private Long id;

    private Long batchId;

    /** 楼栋（student_profile.dorm_building 口径，如「A 栋」） */
    private String building;

    /** 房间号（student_profile.dorm_room 口径，如「A-305」） */
    private String roomNo;

    /** 宿舍长 users.id；房间内无学生档案时为 null */
    private Long leaderStudentId;

    private LocalDate checkDate;

    private Long inspectorId;

    /** 逐项得分 JSON，如 {"power":18,"fire":19}；键必须来自批次的 criteriaJson */
    private String itemJson;

    /** 总分 0-100 */
    private BigDecimal score;

    /** excellent / good / pass / fail */
    private String grade;

    private String problems;

    /** 是否需要整改：1 = 需要（fail 或显式要求） */
    private Integer rectifyRequired;

    private LocalDate rectifyDeadline;

    /** none / pending / submitted / accepted / rejected */
    private String rectifyStatus;

    /** 学生整改说明 */
    private String rectifyNote;

    private LocalDateTime rectifyAt;

    private Long recheckBy;

    private LocalDateTime recheckAt;

    /** pass / fail */
    private String recheckResult;

    /** manual 人工登记 / csv CSV 导入 */
    private String source;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN 得来） ----

    private String batchName;

    private String term;

    /** 批次状态（列表展示用；draft/finished 的批次不允许录入检查记录） */
    private String batchStatus;

    private String inspectorName;
}
