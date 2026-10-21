package com.umi.nest.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 晚归 / 夜不归宿台账（学工 P15）：community_late 表。
 *
 * <p>判定照 S19 第七条：正常出入 6:00-23:30，之外为晚归（type=late）；
 * type=absent 为夜不归宿。没有门禁硬件时 source=card 同样走 CSV 导入或人工登记，
 * 平台不假装能自动抓取闸机流水（对齐说明 §10.2-3）。</p>
 *
 * <p>handlingNote 是内部处理备注，学生端由服务层抹掉（§10.2-5）。</p>
 */
@Data
public class CommunityLate {

    private Long id;

    private Long studentId;

    private String building;

    private String roomNo;

    private LocalDate occurDate;

    /** late 晚归 / absent 夜不归宿 */
    private String type;

    private LocalTime outTime;

    private LocalTime inTime;

    /** card 门禁 / check 巡查 / report 上报 */
    private String source;

    /** 原因分类码，缺省 late_return（晚归）/ night_out（夜不归宿） */
    private String reason;

    private String reasonNote;

    /** 学生本人说明 */
    private String studentNote;

    /** 是否已核实：0 未核实 / 1 已核实 */
    private Integer confirmed;

    private Long confirmedBy;

    private LocalDateTime confirmedAt;

    private Long counselorId;

    /** pending 待处理 / contacted 已联系 / closed 已了结 */
    private String handling;

    /** 处理备注（内部字段，学生端不下发） */
    private String handlingNote;

    private Long violationId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN 得来） ----

    private String studentName;

    private String studentNo;

    private String className;

    private String counselorName;

    private String confirmedByName;
}
