package com.umi.nest.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 社区活动（学工 P15）：community_activity 表。
 *
 * <p>状态机：draft 草稿 → signup 报名中 → ongoing 进行中 → finished 已结束；cancelled 已取消。
 * quota = 0 表示不限人数；quota 满了服务层返回 400（对齐说明 §10.2-8）。</p>
 */
@Data
public class CommunityActivity {

    private Long id;

    private String title;

    /** culture_festival / room_contest / room_olympic / design_contest / volunteer / other */
    private String activityType;

    private String term;

    /** school 校级 / college 院级 / community 社区级 */
    private String level;

    private LocalDateTime startAt;

    private LocalDateTime endAt;

    private String location;

    /** 报名截止时间；已过期报名返回 400 */
    private LocalDateTime signupDeadline;

    /** 名额上限，0 = 不限 */
    private Integer quota;

    private String content;

    private String host;

    /** draft / signup / ongoing / finished / cancelled */
    private String status;

    private String noticeUrl;

    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;

    // ---- 列表展示用的非表字段（JOIN 得来） ----

    private String createdByName;

    /** 已占用名额数（不含已驳回） */
    private Integer signupCount;
}
