package com.umi.nest.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把「学工 API 角色粗筛」挂到 {@code /api/**} 上。
 *
 * <p>不在这里写路径白名单，是因为路径清单本身就活在
 * {@link ApiRoleGuardInterceptor#match} 的规则表里——白名单分散在两个文件里，
 * 迟早会出现「这里放行、那里拦」的对不上号。{@code addPathPatterns} 只负责把请求送进拦截器，
 * 拦不拦、拦谁，统一由规则表回答。
 *
 * <p>{@code /api/dorm/**} 由 {@code DormAccessConfig} 那个拦截器独立看守（它默认拒绝、白名单放行），
 * 本拦截器的规则表里没有任何 dorm 路径，两者互不干扰。
 */
@Configuration
public class ApiRoleGuardConfig implements WebMvcConfigurer {

    private final ApiRoleGuardInterceptor apiRoleGuardInterceptor;

    public ApiRoleGuardConfig(ApiRoleGuardInterceptor apiRoleGuardInterceptor) {
        this.apiRoleGuardInterceptor = apiRoleGuardInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiRoleGuardInterceptor).addPathPatterns("/api/**");
    }
}
