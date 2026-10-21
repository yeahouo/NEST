package com.umi.nest.service;

import java.util.Set;

/**
 * 密码复杂度策略（差距清单 P0-2）。
 *
 * <p>依据是等保测评指标：「身份标识具有唯一性、鉴别信息具有复杂度要求并定期更换」。
 * 这条规则此前完全不存在——注册和改密码只校验「两次输入一致」，所以
 * {@code 123456} 一直是合法密码（种子数据里的账号就是这个）。
 *
 * <p>策略（可被服务层直接调用，返回 {@code null} 表示通过，否则返回给用户看的中文原因）：
 * <ol>
 *   <li>长度 8–64，不含空白字符；</li>
 *   <li>大写字母 / 小写字母 / 数字 / 符号 四类中至少包含三类；</li>
 *   <li>不得与账号相同，账号长度 ≥4 时不得包含账号（忽略大小写）；</li>
 *   <li>不得包含手机号（手机号长度 ≥7 时）；</li>
 *   <li>不得命中常见弱口令与连续键盘序列。</li>
 * </ol>
 *
 * <p>为什么校验放在服务端：前端提示只是体验，绕过前端提交即可失效——
 * 与 {@code VerifyCodeService} 的注释同理，「校验必须在服务端」。
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;

    /** 常见弱口令（比较时先转小写；只存前缀匹配用的片段，不做全表穷举）。 */
    private static final Set<String> WEAK = Set.of(
            "12345678", "123456789", "1234567890", "password", "password1", "passw0rd",
            "qwerty", "qwerty123", "abc123", "abc12345", "admin", "admin123", "admin888",
            "11111111", "00000000", "88888888", "66666666", "iloveyou", "letmein",
            "woaini", "woaini1314", "a1234567", "1qaz2wsx", "zxcvbnm", "nest1234");

    /** 连续键盘/字符序列（只要密码里包含这些片段就直接拒绝）。 */
    private static final Set<String> SEQUENCES = Set.of(
            "qwer", "asdf", "zxcv", "1234", "2345", "3456", "4567", "5678", "6789",
            "abcd", "bcde", "cdef", "1111", "0000", "8888", "aaaa");

    private PasswordPolicy() {
    }

    /** 供前端/文档展示的规则说明。 */
    public static String describe() {
        return "密码长度至少 " + MIN_LENGTH + " 位，且需包含大写字母、小写字母、数字、符号中的至少三类；"
                + "不得与账号相同或包含账号、手机号；不得使用常见弱口令或连续序列。";
    }

    /**
     * 校验密码强度。
     *
     * @param password 待校验密码
     * @param username 账号（可为 null；注册时用于比对）
     * @param phone    手机号（可为 null）
     * @return 通过返回 {@code null}，否则返回中文原因
     */
    public static String check(String password, String username, String phone) {
        if (password == null || password.trim().isEmpty()) {
            return "密码不能为空";
        }
        if (password.length() < MIN_LENGTH) {
            return "密码长度至少 " + MIN_LENGTH + " 位";
        }
        if (password.length() > MAX_LENGTH) {
            return "密码长度不能超过 " + MAX_LENGTH + " 位";
        }
        for (int i = 0; i < password.length(); i++) {
            if (Character.isWhitespace(password.charAt(i))) {
                return "密码不能包含空格";
            }
        }

        int classes = 0;
        if (password.chars().anyMatch(Character::isUpperCase)) {
            classes++;
        }
        if (password.chars().anyMatch(Character::isLowerCase)) {
            classes++;
        }
        if (password.chars().anyMatch(Character::isDigit)) {
            classes++;
        }
        if (password.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) {
            classes++;
        }
        if (classes < 3) {
            return "密码需包含大写字母、小写字母、数字、符号中的至少三类";
        }

        String lower = password.toLowerCase();
        if (username != null && !username.isEmpty()) {
            String u = username.toLowerCase();
            if (lower.equals(u)) {
                return "密码不能与账号相同";
            }
            if (u.length() >= 4 && lower.contains(u)) {
                return "密码不能包含账号";
            }
        }
        if (phone != null && phone.length() >= 7 && password.contains(phone)) {
            return "密码不能包含手机号";
        }
        for (String w : WEAK) {
            if (lower.contains(w)) {
                return "密码过于简单，请勿使用常见弱口令";
            }
        }
        for (String s : SEQUENCES) {
            if (lower.contains(s)) {
                return "密码不能包含连续序列（如 1234、abcd、qwer）";
            }
        }
        return null;
    }
}
