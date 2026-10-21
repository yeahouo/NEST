package com.umi.nest.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志（差距清单 P0-1）。
 *
 * <p>字段与等保测评指标一一对应：「事件的日期和时间」= {@code eventTime}，
 * 「用户」= {@code userId}/{@code username}，「事件类型」= {@code eventType}/{@code eventAction}，
 * 「事件是否成功」= {@code success}。其余字段（IP、UA、耗时、HTTP 状态码）用于事后取证。
 *
 * <p>{@code detail} 里<b>不允许</b>出现密码、验证码、令牌等敏感原文——写入前统一走
 * {@link com.umi.nest.service.AuditLogService} 的脱敏与截断。
 */
@Data
public class AuditLog {

    private Long id;

    private LocalDateTime eventTime;

    /** 事件类型，见 sql/nest_audit.sql 的列注释。 */
    private String eventType;

    /** 动作：HTTP 方法，或服务端判定的领域动作（如 LOGIN）。 */
    private String eventAction;

    private Long userId;

    private String username;

    private String role;

    /** 目标：请求路径或业务对象标识。 */
    private String target;

    private String httpMethod;

    private String ip;

    private String userAgent;

    private Boolean success;

    private Integer statusCode;

    private Integer durationMs;

    private String detail;
}
