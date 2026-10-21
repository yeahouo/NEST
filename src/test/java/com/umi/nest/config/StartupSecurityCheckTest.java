package com.umi.nest.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动期安全闸门（{@link StartupSecurityCheck}）的判定回归。
 *
 * <p>这个类只做三件事：默认值下不拦人、prod 下必须拦、改过了就不吵。另外用一条测试把
 * 「properties 里的出厂值」和「Java 里的常量」钉在一起 —— 两处漂移过一次就会让闸门静默失效。
 */
class StartupSecurityCheckTest {

    private static final String GOOD_PEPPER = "k7Qm2vX9pLw4tR8sZ3nB6cY1jH5dF0gA";

    @Test
    @DisplayName("dev + 出厂 pepper：只警告，不中止启动")
    void devWithFactoryPepperWarns() {
        StartupSecurityCheck.Report report = check(new MockEnvironment(), StartupSecurityCheck.FACTORY_PEPPER, false);

        assertFalse(report.blocking());
        assertEquals(1, report.warnings().size());
        assertTrue(report.warnings().get(0).contains("出厂值"), report.warnings().get(0));
        assertTrue(report.warnings().get(0).contains("NEST_VERIFY_PEPPER"), report.warnings().get(0));
    }

    @Test
    @DisplayName("prod + 出厂 pepper：中止启动（拦的是「照抄 application.properties 上线」）")
    void prodWithFactoryPepperBlocks() {
        MockEnvironment env = new MockEnvironment().withProperty("nest.wechat.enabled", "false");
        env.setActiveProfiles("prod");

        StartupSecurityCheck.Report report = check(env, StartupSecurityCheck.FACTORY_PEPPER, false);

        assertTrue(report.blocking());
        assertEquals(1, report.problems().size());
        assertTrue(report.problems().get(0).contains("出厂值"), report.problems().get(0));
        // 被拦下的那条不再重复进 warnings，避免同一个问题打两遍
        assertTrue(report.warnings().isEmpty(), report.warnings().toString());
    }

    @Test
    @DisplayName("production 这个别名也算 prod")
    void productionAliasBlocks() {
        MockEnvironment env = new MockEnvironment().withProperty("nest.wechat.enabled", "false");
        env.setActiveProfiles("PRODUCTION");

        assertTrue(check(env, StartupSecurityCheck.FACTORY_PEPPER, false).blocking());
    }

    @Test
    @DisplayName("显式 fail-fast=true：dev 也中止（给以后爱开严的人留的口子）")
    void failFastBlocksInDev() {
        StartupSecurityCheck.Report report = check(new MockEnvironment(), StartupSecurityCheck.FACTORY_PEPPER, true);

        assertTrue(report.blocking());
        assertTrue(report.problems().get(0).contains("出厂值"), report.problems().get(0));
    }

    @Test
    @DisplayName("自定义 pepper：dev 下不吵、不拦")
    void customPepperPasses() {
        StartupSecurityCheck.Report report = check(new MockEnvironment(), GOOD_PEPPER, false);

        assertFalse(report.blocking());
        assertTrue(report.warnings().isEmpty(), report.warnings().toString());
    }

    @Test
    @DisplayName("空 pepper / 短 pepper 也算弱（HMAC key 不能只放两三个字符）")
    void shortPepperIsWeak() {
        MockEnvironment env = new MockEnvironment().withProperty("nest.wechat.enabled", "false");
        env.setActiveProfiles("prod");

        StartupSecurityCheck.Report blank = check(env, "   ", false);
        assertTrue(blank.blocking());
        assertTrue(blank.problems().get(0).contains("短于"), blank.problems().get(0));

        StartupSecurityCheck.Report shortOne = check(env, "abc123", false);
        assertTrue(shortOne.blocking());
        assertTrue(shortOne.problems().get(0).contains(StartupSecurityCheck.MIN_PEPPER_LENGTH + " 字符"),
                shortOne.problems().get(0));
    }

    @Test
    @DisplayName("prod 下微信未配置：只提示（微信是可选功能，不该拦住学校上线）")
    void prodWithoutWeChatWarns() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("nest.wechat.enabled", "true")
                .withProperty("nest.wechat.appid", "");
        env.setActiveProfiles("prod");

        StartupSecurityCheck.Report report = check(env, GOOD_PEPPER, false);

        assertFalse(report.blocking());
        assertEquals(1, report.warnings().size());
        assertTrue(report.warnings().get(0).contains("微信绑定未配置"), report.warnings().get(0));
    }

    @Test
    @DisplayName("prod 下显式关掉微信绑定：不再提示")
    void prodWithWeChatDisabledIsQuiet() {
        MockEnvironment env = new MockEnvironment().withProperty("nest.wechat.enabled", "false");
        env.setActiveProfiles("prod");

        StartupSecurityCheck.Report report = check(env, GOOD_PEPPER, false);

        assertFalse(report.blocking());
        assertTrue(report.warnings().isEmpty(), report.warnings().toString());
    }

    @Test
    @DisplayName("application.properties 里的出厂值必须与 Java 常量一致（防漂移）")
    void factoryPepperMatchesProperties() throws Exception {
        Properties props = new Properties();
        try (InputStream in = new ClassPathResource("application.properties").getInputStream();
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            props.load(reader);
        }

        assertEquals(StartupSecurityCheck.FACTORY_PEPPER, props.getProperty("nest.verify.pepper"),
                "application.properties 的 nest.verify.pepper 与 StartupSecurityCheck.FACTORY_PEPPER 不一致，"
                        + "闸门会静默失效");
        assertEquals("false", props.getProperty("nest.verify.pepper-fail-fast"),
                "默认必须是 false，否则本地起服务会被自己的闸门拦住");
    }

    private static StartupSecurityCheck.Report check(MockEnvironment env, String pepper, boolean failFast) {
        return new StartupSecurityCheck(env, pepper, failFast).evaluate();
    }
}
