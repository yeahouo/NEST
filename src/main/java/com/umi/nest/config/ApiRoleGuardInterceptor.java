package com.umi.nest.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.umi.nest.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 学工 API 的<b>框架层粗筛</b>：按「路径 → 允许角色」在进入控制器之前先挡一道。
 *
 * <p><b>它补的是哪一层</b>：本项目 {@code SecurityConfig} 把 {@code /api/**} 全部 {@code permitAll}，
 * 授权完全靠各服务方法里手写的 {@code guardLogin / guardStaff / guardAdmin}。真机矩阵
 * （{@code tools/authz-live.mjs}，149 条路由）证明这些守卫目前是齐的，但「今天齐」不等于
 * 「下次加接口不漏」——漏一条就是整片敞开，而服务层守卫是<b>可以忘记</b>的。
 *
 * <p><b>为什么不能直接写 {@code .hasAnyRole(...)}</b>：本仓库没有 {@code UserDetailsService}、
 * 没有 {@code AuthenticationManager}，登录只是往 {@code HttpSession} 塞 userId/role，
 * 不存在 SecurityContext 身份。此时加 {@code hasAnyRole} 的结果不是「只放行管理员」，
 * 而是<b>谁都进不去</b>（包括管理员自己）。所以粗筛只能做成 {@link HandlerInterceptor}。
 *
 * <p><b>为什么只覆盖这些路径，而不覆盖全部 151 条</b>：很多前缀是<b>双用途</b>的——
 * 学生页也在用的 {@code /api/honor/batches}（看可申报批次）、{@code /api/rank/batches}、
 * {@code /api/community/inspections/{id}}（本宿舍检查结果）、{@code /api/community/activities}
 * （报名 POST 与创建 POST 同前缀）、{@code /api/club/reviews}（学生评价）。对这些前缀按路径粗筛
 * 会直接打断学生端页面，而它们的归属判断本来就需要「这条记录是不是你的」这种数据级信息，
 * 只能留在服务层。这里只收<b>调用方完全没有学生页面</b>的面：
 * <ul>
 *   <li>管理端 {@code /api/admin/**} —— 12 条接口，页面侧全部在 {@code isAdmin} 分支里调用；</li>
 *   <li>辅导员 / 审核 / 批量计算 / 导出 / 台账管理这类纯员工面（见 {@link #RULES}）。</li>
 * </ul>
 * 路径清单的取舍依据是「静态页里的调用方」实测（见 {@code docs/权限与数据级授权审计.md} §7）。
 *
 * <p><b>默认拒绝的方向</b>：{@code /api/admin/**} 用的是前缀匹配，所以以后往管理端新增端点
 * <b>自动受保护</b>，不需要谁记得来改这里。反过来，普通业务端点新增时不受这道闸门约束，
 * 仍由服务层守卫负责——这是有意的分层，不是遗漏。
 *
 * <p><b>为什么响应是 HTTP 200 + {@code code:403}</b>：本项目的接口约定是「所有业务响应 200 +
 * 业务码」，前端每个页面都只读 {@code json.code}（见 {@code second-class.html} 里的
 * {@code result.code === 401} 跳登录）。若这里直接回 HTTP 403，部分 fetch 包装会把响应当成
 * 网络失败而不是业务拒绝，行为反而不一致。宿舍管理端那个拦截器回 HTTP 状态码，是因为它守的是
 * 另一套只看 HTTP 状态的面，两者不冲突。拒绝时会打一条 WARN，便于上线后回看「谁在打哪个面」。
 */
@Component
public class ApiRoleGuardInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ApiRoleGuardInterceptor.class);

    /**
     * 「路径 → 允许谁」。前缀以 {@code /**} 结尾；其余按「等于或以 / 开头」匹配。
     * audience 只有两种取值：{@code admin}（配置 {@code nest.api.admin-roles}）
     * 或 {@code staff}（配置 {@code nest.api.staff-roles}）。
     */
    private record Rule(String pattern, String audience) {
    }

    /**
     * 员工面清单。每一条都经过「静态页调用方」核对：这些前缀只有员工页面在用。
     * 双用途前缀（honor/batches、rank/batches、community/inspections 等）有意不在表里。
     */
    private static final List<Rule> RULES = List.of(
            new Rule("/api/admin/**", "admin"),
            new Rule("/api/counselor/**", "staff"),
            new Rule("/api/aid/review/**", "staff"),
            new Rule("/api/honor/review/**", "staff"),
            new Rule("/api/honor/export", "staff"),
            new Rule("/api/honor/stats", "staff"),
            new Rule("/api/rank/academic/**", "staff"),
            new Rule("/api/rank/academic", "staff"),
            new Rule("/api/rank/stats", "staff"),
            new Rule("/api/rank/export", "staff"),
            new Rule("/api/workstudy/manage/**", "staff"),
            new Rule("/api/workstudy/overview", "staff"),
            new Rule("/api/psyche/batches/**", "staff"),
            new Rule("/api/psyche/stats", "staff"),
            new Rule("/api/second-class/review/**", "staff"),
            new Rule("/api/second-class/admin/**", "staff"),
            new Rule("/api/loan/review/**", "staff"),
            new Rule("/api/loan/export", "staff"),
            new Rule("/api/eval/compute", "staff"),
            new Rule("/api/community/batches/**", "staff"),
            new Rule("/api/community/stats", "staff"),
            new Rule("/api/community/export", "staff"),
            new Rule("/api/community/civil-rooms/compute", "staff"),
            new Rule("/api/community/inspections/import", "staff"));

    private final ObjectMapper objectMapper;
    private final Set<String> adminRoles;
    private final Set<String> staffRoles;

    public ApiRoleGuardInterceptor(ObjectMapper objectMapper,
                                   @Value("${nest.api.admin-roles:admin}") String adminRoles,
                                   @Value("${nest.api.staff-roles:admin,teacher}") String staffRoles) {
        this.objectMapper = objectMapper;
        this.adminRoles = splitRoles(adminRoles, "admin");
        this.staffRoles = splitRoles(staffRoles, "admin,teacher");
    }

    /** 解析逗号分隔的角色清单；写成空串等于把闸门焊在「谁都不许进」，而不是「谁都能进」。 */
    private static Set<String> splitRoles(String raw, String fallback) {
        String source = raw == null || raw.isBlank() ? fallback : raw;
        Set<String> set = new LinkedHashSet<>();
        Arrays.stream(source.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase())
                .forEach(set::add);
        if (set.isEmpty()) {
            // 配置里全是逗号和空格时也要兜底：失败方向只能是「谁都不许进」。
            Arrays.stream(fallback.split(","))
                    .map(String::trim)
                    .map(s -> s.toLowerCase())
                    .forEach(set::add);
        }
        return Set.copyOf(set);
    }

    /** 管理员角色（小写），供诊断使用。 */
    public Set<String> adminRoles() {
        return adminRoles;
    }

    /** 员工角色（小写），供诊断使用。 */
    public Set<String> staffRoles() {
        return staffRoles;
    }

    /** 命中的规则；没命中返回 {@code null}（表示这道闸门不管这个路径）。 */
    static Rule match(String path) {
        for (Rule rule : RULES) {
            if (matches(rule.pattern(), path)) {
                return rule;
            }
        }
        return null;
    }

    private static boolean matches(String pattern, String path) {
        if (pattern.endsWith("/**")) {
            // 去掉 "/**" 三个字符，而不是只去掉 "**"——否则前缀会以 "/" 结尾，
            // 再拼 "/" 就变成 "//"，所有请求都匹配不上（写过一次，测试立刻抓出来）。
            String prefix = pattern.substring(0, pattern.length() - 3);
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        return path.equals(pattern) || path.startsWith(pattern + "/");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        Rule rule = match(request.getRequestURI());
        if (rule == null) {
            return true;
        }
        Set<String> allowed = "admin".equals(rule.audience()) ? adminRoles : staffRoles;
        HttpSession session = request.getSession(false);
        Object userId = session == null ? null : session.getAttribute("userId");
        String role = session == null ? null : (String) session.getAttribute("role");
        String normalized = role == null ? "" : role.trim().toLowerCase();

        if (userId == null) {
            return reject(request, response, 401,
                    "未登录。该接口只对 " + allowed + " 开放，请先登录。");
        }
        if (!allowed.contains(normalized)) {
            return reject(request, response, 403,
                    "你的账号角色是「" + role + "」，无权访问该接口（需要 " + allowed + "）。");
        }
        return true;
    }

    private boolean reject(HttpServletRequest request, HttpServletResponse response, int code, String message)
            throws IOException {
        log.warn("API 角色粗筛拦截：{} {} role={} → {}", request.getMethod(), request.getRequestURI(),
                sessionRole(request), code);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(code, message)));
        return false;
    }

    private static String sessionRole(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object role = session == null ? null : session.getAttribute("role");
        return role == null ? "(未登录)" : String.valueOf(role);
    }

    /** 供测试与诊断：当前规则表摘要。 */
    public static List<String> ruleSummary() {
        List<String> lines = new ArrayList<>();
        for (Rule rule : RULES) {
            lines.add(rule.pattern() + " → " + rule.audience());
        }
        return lines;
    }
}
