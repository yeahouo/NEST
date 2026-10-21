package com.umi.nest.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码复杂度策略测试（差距清单 P0-2）。
 *
 * <p>这一层以前完全不存在：注册与改密码只校验「两次输入一致」，
 * 所以 {@code 123456} 是合法密码。下面这些用例就是那条边界。
 */
@DisplayName("PasswordPolicy - 密码复杂度策略")
class PasswordPolicyTest {

    @Test
    @DisplayName("合法密码通过：四类字符 + 长度足够")
    void strongPasswordPasses() {
        assertNull(PasswordPolicy.check("Nest@2026", "chendw", "13800138000"));
        assertNull(PasswordPolicy.check("Wang#1990x", "wangwb", null));
        assertNull(PasswordPolicy.check("Str0ng!Pass", null, null));
    }

    @Test
    @DisplayName("长度不足被拒")
    void tooShortRejected() {
        String msg = PasswordPolicy.check("Ab1@", "user", null);
        assertNotNull(msg);
        assertTrue(msg.contains("至少"), msg);
    }

    @Test
    @DisplayName("超长被拒")
    void tooLongRejected() {
        String longPwd = "Aa1!".repeat(20); // 80 位
        String msg = PasswordPolicy.check(longPwd, "user", null);
        assertNotNull(msg);
        assertTrue(msg.contains("超过"), msg);
    }

    @Test
    @DisplayName("空密码与含空格被拒")
    void blankAndWhitespaceRejected() {
        assertNotNull(PasswordPolicy.check(null, "user", null));
        assertNotNull(PasswordPolicy.check("   ", "user", null));
        String msg = PasswordPolicy.check("Abc1 2345", "user", null);
        assertNotNull(msg);
        assertTrue(msg.contains("空格"), msg);
    }

    @Test
    @DisplayName("字符类别不足三类被拒")
    void charClassesRejected() {
        String msg = PasswordPolicy.check("abcdefghij", "user", null); // 只有小写
        assertNotNull(msg);
        assertTrue(msg.contains("三类"), msg);
        assertNotNull(PasswordPolicy.check("abcdefgh12", "user", null)); // 两类
    }

    @Test
    @DisplayName("弱口令与连续序列被拒")
    void weakAndSequencesRejected() {
        assertNotNull(PasswordPolicy.check("Password1", "user", null));
        assertNotNull(PasswordPolicy.check("Abcd1234", "user", null));  // 含 abcd / 1234
        assertNotNull(PasswordPolicy.check("Qwer@2026", "user", null)); // 含 qwer
        assertNotNull(PasswordPolicy.check("AaAaAa1!x", "user", null)); // 含 aaaa 连续重复
    }

    @Test
    @DisplayName("与账号相同或包含账号被拒")
    void accountBasedRejected() {
        String same = PasswordPolicy.check("Chendw@2026".toLowerCase(), "chendw@2026", null);
        assertNotNull(same);
        String contains = PasswordPolicy.check("Xx!chendw2026", "chendw", null);
        assertNotNull(contains);
        assertTrue(contains.contains("包含账号"), contains);
    }

    @Test
    @DisplayName("包含手机号被拒")
    void phoneInsideRejected() {
        String msg = PasswordPolicy.check("Ab1!13800138000", "user", "13800138000");
        assertNotNull(msg);
        assertTrue(msg.contains("手机号"), msg);
    }

    @Test
    @DisplayName("规则说明包含最小长度，便于前端与文档复用")
    void describeMentionsMinLength() {
        assertTrue(PasswordPolicy.describe().contains(String.valueOf(PasswordPolicy.MIN_LENGTH)));
        assertEquals(8, PasswordPolicy.MIN_LENGTH);
    }
}
