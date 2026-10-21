package com.umi.nest.dto;

import lombok.Data;

/**
 * 通知设置请求 DTO
 */
@Data
public class NotificationSettingsDTO {

    /**
     * 作业到期提醒：0关闭 1开启
     */
    private Integer notifyAssignmentDeadline;

    /**
     * 私信提醒：0关闭 1开启
     */
    private Integer notifyPrivateMessage;

    /**
     * 话题回复提醒：0关闭 1开启
     */
    private Integer notifyTopicReply;
}
