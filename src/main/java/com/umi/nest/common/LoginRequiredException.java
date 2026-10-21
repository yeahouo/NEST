package com.umi.nest.common;

/**
 * 未登录 / 未绑定身份。
 *
 * <p><b>为什么必须是一个专门的异常类型，而不是直接抛 {@link IllegalStateException}。</b>
 * 后者会被兜底处理器当成"服务器内部错误"吞掉，返回 <b>HTTP 500</b>——
 * 于是"你没登录"和"代码崩了"对学生、对运维、对监控是同一个信号。
 * 这有两个实际后果：监控告警会被正常的登录跳转刷屏；
 * 而真正 500 的时候，前端会按"去登录"处理，把故障掩盖成一次静默跳转。
 *
 * <p>消息<b>以 {@code LOGIN_REQUIRED} 开头</b>是该异常的契约：
 * 学生端页面的 {@code api()} 封装靠这个前缀决定"跳登录页"还是"弹错误"，
 * 改动前缀会让所有需要登录的接口在页面上变成普通报错。
 */
public class LoginRequiredException extends RuntimeException {

    /** 前端识别前缀，别改。 */
    public static final String PREFIX = "LOGIN_REQUIRED";

    public LoginRequiredException(String detail) {
        super(PREFIX + " " + detail);
    }
}
