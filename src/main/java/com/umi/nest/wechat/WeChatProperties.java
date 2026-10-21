package com.umi.nest.wechat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信开放平台（网站应用）的凭据与调参。
 *
 * <p><b>这个类存在的理由，是一个真实发生过的错误。</b>在它之前，
 * {@code UserService.bindWechat} 收到绑定请求后直接写了一条
 * {@code "mock_openid_" + System.currentTimeMillis()} 进数据库，昵称写死「微信用户」，
 * 然后返回「微信绑定成功」。也就是说：<b>接口会撒谎</b>——
 * 它报告的是一次真实绑定，实际写进去的是一个只有本机知道、而且每次调用都不同的假串。
 * 这种假数据比「功能没做」更糟：它会被后续逻辑当作真凭据使用
 * （例如「该微信已绑定其他账号」这条校验，就是拿假串去比对的）。
 *
 * <p>所以现在的规矩和验证码一致：<b>没有凭据就说没有凭据</b>。
 * {@link #unavailableReason()} 返回非空时，绑定接口直接拒绝并把这个原因原样告诉用户，
 * 一行假数据都不写；前端也能据此把「Bind」按钮变成一句解释，而不是让用户点了没反应。
 *
 * <p>接好了 appid / secret 之后无需改代码：{@link WeChatOAuthClient} 会自动走真实 OAuth。
 */
@Component
@ConfigurationProperties(prefix = "nest.wechat")
public class WeChatProperties {

    /** 关掉它，绑定接口会明确回答「已被配置关闭」，而不是尝试联网。 */
    private boolean enabled = true;

    /** 微信开放平台网站应用的 AppID。 */
    private String appid = "";

    /** 微信开放平台网站应用的 AppSecret。不要写进版本库，放 config/local.properties。 */
    private String secret = "";

    /**
     * 微信接口的域名。抽成配置项不是为了给生产环境改，而是为了<b>可测</b>：
     * 单元测试把它指向本机的一个桩服务器，就能真的跑一遍 code 换 openid 的解析逻辑，
     * 而不是只测「没配凭据时会不会拒绝」。
     */
    private String baseUrl = "https://api.weixin.qq.com";

    /** 单次调用的连接与读取超时。微信这两步都是小请求，10 秒足够。 */
    private long timeoutMs = 10_000L;

    public boolean ready() {
        return enabled && notBlank(appid) && notBlank(secret);
    }

    /**
     * 给用户看的解释，说明为什么绑定点了没反应。
     *
     * @return {@code null} 表示凭据齐全，可以真的走 OAuth
     */
    public String unavailableReason() {
        if (!enabled) {
            return "微信绑定已被配置关闭（nest.wechat.enabled=false）。";
        }
        if (notBlank(appid) && !notBlank(secret)) {
            return "微信绑定配置不完整：有 nest.wechat.appid 但缺少 nest.wechat.secret。";
        }
        if (!notBlank(appid) && notBlank(secret)) {
            return "微信绑定配置不完整：有 nest.wechat.secret 但缺少 nest.wechat.appid。";
        }
        return "尚未接入微信开放平台：需要配置 nest.wechat.appid 与 nest.wechat.secret 才能绑定微信。";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAppid() {
        return appid;
    }

    public void setAppid(String appid) {
        this.appid = appid;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
