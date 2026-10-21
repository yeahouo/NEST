package com.umi.nest.service;

/**
 * 审计上下文：把「这次请求是谁、从哪来」透传给服务层。
 *
 * <p>为什么需要它：登录/注册/重置密码这三类事件的「成功与否」只有服务层知道
 * （它们失败时 HTTP 仍是 200、响应体里才是错误码），所以必须由服务层写审计记录；
 * 但来源 IP / User-Agent 只有过滤器（拦截器）里拿得到。用一个请求级的
 * {@link ThreadLocal} 把这两样东西传过去，比给 {@code UserService} 的所有方法
 * 都加一个 {@code HttpServletRequest} 参数干净得多。
 *
 * <p>拦截器的 {@code preHandle} 写入、{@code afterCompletion} 必须清理
 * （Tomcat 复用线程，不清理会把上一个请求的 IP 记到下一个请求头上）。
 */
public final class AuditContext {

    private static final ThreadLocal<String[]> HOLDER = new ThreadLocal<>();

    private AuditContext() {
    }

    public static void set(String ip, String userAgent) {
        HOLDER.set(new String[]{ip, userAgent});
    }

    public static String ip() {
        String[] v = HOLDER.get();
        return v == null ? null : v[0];
    }

    public static String userAgent() {
        String[] v = HOLDER.get();
        return v == null ? null : v[1];
    }

    public static void clear() {
        HOLDER.remove();
    }
}
