package com.umi.nest.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 配置
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 密码加密器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 安全过滤链配置
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 关闭 CSRF（前后端分离不需要）
            .csrf(csrf -> csrf.disable())
            // 配置请求授权
            .authorizeHttpRequests(auth -> auth
                // 放行静态资源和页面
                .requestMatchers("/", "/index.html", "/login.html", "/register.html").permitAll()
                .requestMatchers("/css/**", "/js/**", "/images/**", "/fonts/**", "/student/**").permitAll()
                // 放行学生端和教师端页面
                .requestMatchers("/student/**", "/teacher/**", "/admin/**").permitAll()
                // 放行所有 API 接口（后续可根据需要添加权限控制）
                .requestMatchers("/api/**").permitAll()
                // 其他请求放行
                .anyRequest().permitAll()
            )
            // 关闭默认登录页
            .formLogin(form -> form.disable())
            // 关闭默认登出
            .logout(logout -> logout.disable());

        return http.build();
    }
}
