package com.umi.nest.dorm.model;

/**
 * 特殊住宿需求。硬约束：声明了特殊需求的学生只能分配到 forSpecialNeeds 的房间。
 */
public enum SpecialNeed {
    /** 无特殊需求。 */
    NONE,
    /** 行动不便，需要无障碍/低层房间。 */
    WHEELCHAIR,
    /** 需要低楼层。 */
    LOW_FLOOR,
    /** 需要安静房间（医学或心理原因）。 */
    QUIET;

    public boolean isSpecial() {
        return this != NONE;
    }
}
