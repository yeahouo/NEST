package com.umi.nest.config;

import com.umi.nest.entity.AuditLog;
import com.umi.nest.service.AuditContext;
import com.umi.nest.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 把「谁、什么时候、对哪个接口做了什么、成没成」记进 audit_log（差距清单 P0-1）。
 *
 * <p>三条刻意为之的取舍：
 * <ul>
 *   <li><b>只记写操作</b>（POST/PUT/PATCH/DELETE）。读操作也记的话，一张表会在几天内被
 *       GET 淹掉，真正要查的「谁改了成绩」反而翻不出来。等保要求的是重要操作可审计，
 *       不是全流量留痕。</li>
 *   <li><b>登录/注册/重置密码不在本拦截器里记</b>（见 {@code EXPLICIT}）。这三类失败时
 *       HTTP 仍然是 200、失败信息在响应体里，用状态码判断会把失败记成成功；
 *       它们的真实结果只有 {@code UserService} 知道，所以由服务层写。</li>
 *   <li><b>不记请求体</b>。登录接口的请求体就是密码，任何「顺手记录一下请求体」的改动
 *       都会把明文密码写进数据库。</li>
 * </ul>
 *
 * <p>为什么依赖写成 {@link ObjectProvider} 而不是直接注入：{@code @WebMvcTest} 会加载
 * {@code HandlerInterceptor}／{@code WebMvcConfigurer} 这类 Web 组件，但<strong>不会</strong>加载
 * {@code @Service}。直接构造注入会让 9 个控制器切片测试全部启动失败
 * （{@code NoSuchBeanDefinitionException: AuditLogService}）。用 provider 后，切片里取不到
 * 服务就静默跳过审计，真实运行时照常写入。
 */
@Component
public class AuditLogInterceptor implements HandlerInterceptor {

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** 由服务层记录的三条路径，避免「成功/失败」被 HTTP 状态码误判。 */
    private static final Set<String> EXPLICIT = Set.of(
            "/api/auth/login", "/api/auth/register", "/api/auth/reset-password");

    private static final String ATTR_START = "audit.startNanos";
    private static final String ATTR_USER_ID = "audit.userId";
    private static final String ATTR_ROLE = "audit.role";

    private final ObjectProvider<AuditLogService> auditLogServiceProvider;

    public AuditLogInterceptor(ObjectProvider<AuditLogService> auditLogServiceProvider) {
        this.auditLogServiceProvider = auditLogServiceProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        AuditContext.set(clientIp(request), request.getHeader("User-Agent"));
        request.setAttribute(ATTR_START, System.nanoTime());
        // 会话可能在本请求里被销毁（登出），所以属性在 preHandle 就先抓下来
        HttpSession session = request.getSession(false);
        if (session != null) {
            request.setAttribute(ATTR_USER_ID, session.getAttribute("userId"));
            request.setAttribute(ATTR_ROLE, session.getAttribute("role"));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            String path = request.getRequestURI();
            if (path == null || !path.startsWith("/api/") || EXPLICIT.contains(path)) {
                return;
            }
            String method = request.getMethod();
            if (method == null || !MUTATING.contains(method)) {
                return;
            }

            AuditLog entry = new AuditLog();
            entry.setEventType("/api/auth/logout".equals(path)
                    ? AuditLogService.TYPE_LOGOUT
                    : AuditLogService.typeOfPath(path));
            entry.setEventAction(method);
            entry.setTarget(path);
            entry.setHttpMethod(method);
            entry.setRole((String) request.getAttribute(ATTR_ROLE));
            Object uid = request.getAttribute(ATTR_USER_ID);
            if (uid instanceof Long) {
                entry.setUserId((Long) uid);
            } else if (uid instanceof Number) {
                entry.setUserId(((Number) uid).longValue());
            }
            entry.setStatusCode(response.getStatus());
            Object start = request.getAttribute(ATTR_START);
            if (start instanceof Long) {
                entry.setDurationMs((int) ((System.nanoTime() - (Long) start) / 1_000_000L));
            }

            boolean success = ex == null && response.getStatus() < 400;
            entry.setSuccess(success);
            if (ex != null) {
                entry.setDetail("异常 " + ex.getClass().getSimpleName() + "：" + ex.getMessage());
            } else if (!success) {
                entry.setDetail("HTTP " + response.getStatus());
            } else {
                entry.setDetail(AuditLogService.maskQuery(request.getQueryString()));
            }
            // 取不到服务就跳过（切片测试）；审计永远不能影响请求结果
            AuditLogService auditLogService = auditLogServiceProvider.getIfAvailable();
            if (auditLogService != null) {
                auditLogService.record(entry);
            }
        } catch (Exception ignored) {
            // 审计自身的问题不允许影响请求结果
        } finally {
            AuditContext.clear();
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isEmpty()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }
}
