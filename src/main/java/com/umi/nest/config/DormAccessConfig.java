package com.umi.nest.config;

import com.umi.nest.dorm.web.DormStaffInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把宿舍管理端的角色闸门挂到路径上。
 *
 * <p>白名单是显式列出来的，其余全部受保护：
 * <ul>
 *   <li>{@code /api/dorm/portal/**} —— 学生自助端，它自己按会话校验学号绑定，
 *       而且请求体里刻意没有 studentId 字段，所以不需要这层；</li>
 *   <li>{@code /api/dorm/health} —— 给运维和监控探活用，不含任何业务数据。</li>
 * </ul>
 *
 * <p>路径模式写成 {@code /api/dorm/**} 而不是逐个枚举控制器，是为了让<b>以后新增的端点默认受保护</b>。
 * 安全默认值只有一种写法：默认拒绝，白名单放行。
 */
@Configuration
public class DormAccessConfig implements WebMvcConfigurer {

    private final DormStaffInterceptor dormStaffInterceptor;

    public DormAccessConfig(DormStaffInterceptor dormStaffInterceptor) {
        this.dormStaffInterceptor = dormStaffInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(dormStaffInterceptor)
                .addPathPatterns("/api/dorm/**")
                .excludePathPatterns("/api/dorm/portal/**", "/api/dorm/health");
    }
}
