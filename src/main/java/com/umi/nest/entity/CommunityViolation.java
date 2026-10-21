package com.umi.nest.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 社区违规台账（学工 P15）：community_violation 表。
 *
 * <p>口径（对齐说明 §10.2-4）：只到教育惩戒与建议（advice），ruleRef 必填；
 * 平台不生成处分决定（处分走学工处分流程）。</p>
 *
 * <p>resultNote 是内部处理结果说明，学生端由服务层抹掉（§10.2-5）。</p>
 */
@Data
public class CommunityViolation {

    private Long id;

    private Long studentId;

    private String building;

    private String roomNo;

    private Long inspectionId;

    private Long lateId;

    /** late / absence / appliance / fire / pet / rent / climb / noise / other */
    private String type;

    private LocalDateTime occurredAt;

    private String detail;

    /** 依据条文（必填） */
    private String ruleRef;

    /** education 教育 / circulated 通报 / warning 警告 / serious_warning 严重警告 / recorded 记录在案 */
    private String advice;

    /** pending 待处理 / notified 已告知 / closed 已办结 */
    private String handling;

    private Long counselorId;

    private Long handledBy;

    private LocalDateTime handledAt;

    /** 处理结果说明（内部字段，学生端不下发） */
    private String resultNote;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN 得来） ----

    private String studentName;

    private String studentNo;

    private String className;

    private String counselorName;

    private String handledByName;
}
