package com.umi.nest.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码哈希生成工具
 * 运行此类可生成 BCrypt 密码哈希
 */
public class PasswordHashGenerator {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        // 生成 teacher123 的哈希
        String password = "teacher123";
        String hash = encoder.encode(password);

        System.out.println("========================================");
        System.out.println("密码: " + password);
        System.out.println("BCrypt 哈希: " + hash);
        System.out.println("========================================");
    }
}
