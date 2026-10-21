package com.umi.nest.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 启动期安全闸门：把「还留着出厂配置」这件事从注释里搬到启动日志里。
 *
 * <p>背景：{@code nest.verify.pepper} 的默认值 {@code NEST-DEFAULT-PEPPER-PLEASE-CHANGE}
 * 是为了「不配置也能把系统跑起来」。但验证码哈希混了这个 pepper（见
 * {@code VerifyCodeService}），出厂值意味着任何拿到 {@code verification_code} 表 dump
 * 的人，都能用公开的 pepper 离线爆破有效期内的验证码 —— 于是生产部署照抄
 * {@code application.properties} 上线，安全就等于没有。
 *
 * <p>闸门分两档，避免把学校的一次性部署直接弄成「起不来」：
 * <ul>
 *   <li><b>warn（默认）</b>：打了 {@code prod} 环境之外仍用出厂 pepper → 启动日志里打一条
 *       长 warn，把「要改哪个配置、怎么改」写清楚。</li>
 *   <li><b>abort</b>：激活了 {@code prod}/{@code production} profile，或显式
 *       {@code nest.verify.pepper-fail-fast=true} → 直接抛异常中止启动，不让「带病上线」。</li>
 * </ul>
 *
 * <p>判定逻辑抽成 {@link #evaluate()} 的纯函数返回值，便于单测；{@link #run} 只负责
 * 打日志与抛异常。
 */
@Component
public class StartupSecurityCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSecurityCheck.class);

    /** 与 {@code application.properties} 里的默认值一致；改一处要同时改两处。 */
    static final String FACTORY_PEPPER = "NEST-DEFAULT-PEPPER-PLEASE-CHANGE";

    /** 小于这个长度的 pepper 视为「不够随机」（HMAC key 至少 16 字节）。 */
    static final int MIN_PEPPER_LENGTH = 16;

    private final Environment environment;
    private final String pepper;
    private final boolean failFast;

    public StartupSecurityCheck(Environment environment,
                                @Value("${nest.verify.pepper:" + FACTORY_PEPPER + "}") String pepper,
                                @Value("${nest.verify.pepper-fail-fast:false}") boolean failFast) {
        this.environment = environment;
        this.pepper = pepper;
        this.failFast = failFast;
    }

    @Override
    public void run(ApplicationArguments args) {
        Report report = evaluate();
        for (String warning : report.warnings()) {
            log.warn("【安全配置】{}", warning);
        }
        if (report.blocking()) {
            throw new IllegalStateException("启动中止：" + String.join("；", report.problems())
                    + "。改法：设置环境变量 NEST_VERIFY_PEPPER 为一段随机串（≥16 字符），"
                    + "或写进 application-prod.properties。确认只是本地调试可设 "
                    + "nest.verify.pepper-fail-fast=false 并把 profile 切回 dev。");
        }
        if (!report.warnings().isEmpty()) {
            log.warn("【安全配置】当前按「可运行但未加固」模式启动；上面 {} 条未处理，"
                    + "正式给学校部署前请逐条处理（详见 docs\\给学校的部署说明.md）。",
                    report.warnings().size());
        } else {
            log.info("【安全配置】出厂项检查通过（pepper 已自定义）。");
        }
    }

    /** 纯判定：不碰日志、不抛异常，只回一份清单，方便单测逐条断言。 */
    public Report evaluate() {
        List<String> warnings = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        boolean prod = isProdProfile();
        // 出厂值本身够长，所以必须先认「等于出厂值」再谈长度，否则弱判定会把它漏掉
        boolean pepperProblem = isFactoryPepper(pepper) || isWeakPepper(pepper);
        if (pepperProblem) {
            String detail = isFactoryPepper(pepper)
                    ? "nest.verify.pepper 仍是出厂值 NEST-DEFAULT-PEPPER-PLEASE-CHANGE"
                    : String.format("nest.verify.pepper 为空或短于 %d 字符（HMAC key 太短）", MIN_PEPPER_LENGTH);
            if (prod || failFast) {
                problems.add(detail);
            } else {
                warnings.add(detail + "：拿到 verification_code 表的人可离线反推验证码。"
                        + "上线前务必替换（环境变量 NEST_VERIFY_PEPPER）。");
            }
        }

        // 微信绑定是可选项：未配置只提示，不阻断（WeChatProperties 已有自己的可用性判断）。
        if (prod && isBlank(environment.getProperty("nest.wechat.appid"))
                && !"false".equalsIgnoreCase(environment.getProperty("nest.wechat.enabled", "true"))) {
            warnings.add("微信绑定未配置（nest.wechat.appid/secret 为空）：设置页的微信卡片会显示"
                    + "「尚未接入微信开放平台」，不影响账号密码登录。");
        }

        return new Report(warnings, problems);
    }

    private boolean isProdProfile() {
        String[] active = environment.getActiveProfiles();
        if (active == null) {
            return false;
        }
        return Arrays.stream(active)
                .filter(p -> p != null)
                .map(p -> p.trim().toLowerCase(Locale.ROOT))
                .anyMatch(p -> p.equals("prod") || p.equals("production"));
    }

    static boolean isFactoryPepper(String value) {
        return value != null && FACTORY_PEPPER.equals(value.trim());
    }

    static boolean isWeakPepper(String value) {
        if (value == null) {
            return true;
        }
        String v = value.trim();
        return v.isEmpty() || v.length() < MIN_PEPPER_LENGTH;
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    /** 判定结果：{@code problems} 非空即中止启动；{@code warnings} 只打日志。 */
    public record Report(List<String> warnings, List<String> problems) {
        public boolean blocking() {
            return problems != null && !problems.isEmpty();
        }
    }
}
