package com.umi.nest.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把审计拦截器挂到 `/api/**` 上（差距清单 P0-1）。
 *
 * <p>排除项只有 `/api/dorm/health`（监控探活，没有业务语义、调用频率高）；
 * 其余默认全部经过审计——同样是「默认拒绝/默认覆盖，白名单例外」的写法，
 * 这样以后新增的端点自动被记录，不会因为忘了加配置而留下审计空洞。
 */
@Configuration
public class AuditTrailConfig implements WebMvcConfigurer {

    private final AuditLogInterceptor auditLogInterceptor;

    public AuditTrailConfig(AuditLogInterceptor auditLogInterceptor) {
        this.auditLogInterceptor = auditLogInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auditLogInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/dorm/health");
    }
}
