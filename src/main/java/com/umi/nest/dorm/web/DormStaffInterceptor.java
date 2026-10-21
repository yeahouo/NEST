package com.umi.nest.dorm.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.umi.nest.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 宿舍管理端的角色闸门。
 *
 * <p><b>为什么需要它</b>：本项目的 {@code SecurityConfig} 把 {@code /api/**} 全部放行，鉴权完全靠
 * 各控制器自己检查。学生门户那条线是安全的（{@code DormPortalController} 的 studentId 一律取自会话），
 * 但管理端那条线（{@code /api/dorm/**}、{@code /api/dorm/psyche/**}）此前<b>没有任何检查</b>——
 * 任何人知道地址就能拉走全校宿舍名单、心理画像、以及一键发起重新分配。这不是理论风险，
 * 是「把地址贴进浏览器就能用」。
 *
 * <p><b>为什么做成拦截器而不是在每个方法里加检查</b>：方法内的检查是可以忘记的，
 * 而且以后新增端点时一定会有人忘记。拦截器是「默认拒绝」——新增的 {@code /api/dorm/**}
 * 端点自动受保护，只有显式写进白名单的路径才放行。
 *
 * <p><b>为什么直接写响应而不抛异常</b>：从 {@code preHandle} 抛出的异常要靠
 * {@code HandlerExceptionResolver} 转成响应，而 {@code @RestControllerAdvice} 在这条路径上
 * 能不能命中取决于处理器是否已解析出来——不确定的机制不配当安全边界。这里直接把
 * {@link Result} 序列化后写回去，行为是确定的。
 *
 * <p>角色清单由 {@code nest.dorm.staff-roles} 配置，默认只有 {@code admin}。
 * 学校如果把「宿管」「学工」单独建成角色，加进这个配置即可，不用改代码。
 */
@Component
public class DormStaffInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;
    private final Set<String> staffRoles;

    public DormStaffInterceptor(ObjectMapper objectMapper,
                                @Value("${nest.dorm.staff-roles:admin}") String roles) {
        this.objectMapper = objectMapper;
        Set<String> set = new LinkedHashSet<>();
        Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase())
                .forEach(set::add);
        if (set.isEmpty()) {
            // 配置写空了就等于把闸门焊死在「谁都不许进」，而不是「谁都能进」。
            // 这两种失败方式里，只有一种会在事后被追究。
            set.add("admin");
        }
        this.staffRoles = Set.copyOf(set);
    }

    /** 允许使用宿舍管理端的角色（小写），供诊断与文案使用。 */
    public Set<String> staffRoles() {
        return staffRoles;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        HttpSession session = request.getSession(false);
        Object userId = session == null ? null : session.getAttribute("userId");
        String role = session == null ? null : (String) session.getAttribute("role");

        if (userId == null) {
            return reject(response, 401, "LOGIN_REQUIRED 未登录。宿舍管理端不对未登录用户开放，请先登录管理账号。");
        }
        if (role == null || !staffRoles.contains(role.trim().toLowerCase())) {
            return reject(response, 403, "你的账号角色是「" + role + "」，没有宿舍管理权限。"
                    + "宿舍管理端只对 " + staffRoles + " 开放。");
        }
        return true;
    }

    private boolean reject(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(code, message)));
        return false;
    }
}
