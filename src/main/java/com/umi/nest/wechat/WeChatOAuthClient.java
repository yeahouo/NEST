package com.umi.nest.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 微信开放平台「网站应用」的 OAuth 客户端：把授权码 {@code code} 换成 {@code openid}。
 *
 * <p>这是<b>唯一</b>一处需要替换就能接通真实微信的地方。流程是标准的两步：
 * <ol>
 *   <li>{@code GET /sns/oauth2/access_token} —— 用 code 换 {@code access_token} 与 {@code openid}；</li>
 *   <li>{@code GET /sns/userinfo} —— 用 access_token 换昵称（拿不到也不算失败）。</li>
 * </ol>
 *
 * <p>三条硬规定，都是踩过坑之后定下的：
 * <ol>
 *   <li><b>失败不抛异常，返回带 {@code error} 的结果</b>（与 {@code AiChatClient} 同一套路）。
 *       调用方据此把原话转告用户，而不是让请求 500。</li>
 *   <li><b>未配置凭据时直接拒绝</b>，不写任何数据。这个类的上一版实现
 *       （在 {@code UserService} 里）会写一条 {@code "mock_openid_" + System.currentTimeMillis()}
 *       然后说「绑定成功」——那是在骗用户，而且假 openid 会被后续校验当真凭据比对。</li>
 *   <li><b>日志里不出现 AppSecret 与 access_token</b>。它们的传输方式恰恰是 URL 查询串，
 *       所以记日志时只记路径，不记整条 URL。</li>
 * </ol>
 */
@Component
public class WeChatOAuthClient {

    private static final Logger log = LoggerFactory.getLogger(WeChatOAuthClient.class);

    /** 网站应用登录用的 scope。 */
    public static final String SCOPE = "snsapi_login";

    /** 一次换码的结果。{@code error} 非空表示这次没成，此时 {@code openid} 不可信。 */
    public record Exchange(String openid, String nickname, String error) {
        public boolean ok() {
            return error == null && openid != null && !openid.isBlank();
        }

        static Exchange failure(String why) {
            return new Exchange(null, null, why);
        }
    }

    private final WeChatProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public WeChatOAuthClient(WeChatProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 凭据是否齐全。前端用它决定「Bind」按钮是走 OAuth 还是直接说明原因。 */
    public boolean configured() {
        return props.ready();
    }

    /** 给用户看的不可用原因；{@code null} 表示可以真的走 OAuth。 */
    public String unavailableReason() {
        return props.unavailableReason();
    }

    /**
     * AppID。它不是秘密 —— 微信授权页地址里就必须明文带上它，浏览器地址栏可见，
     * 所以可以发给前端去拼授权链接。AppSecret 则绝不能出服务端。
     *
     * @return 凭据不齐时返回 {@code null}
     */
    public String appid() {
        return props.ready() ? props.getAppid() : null;
    }

    /** 网站应用登录页地址，前端拿 code 用的。凭据不齐时返回 {@code null}。 */
    public String authorizeUrl(String redirectUri, String state) {
        if (!props.ready() || redirectUri == null || redirectUri.isBlank()) {
            return null;
        }
        return "https://open.weixin.qq.com/connect/qrconnect?appid=" + enc(props.getAppid())
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code&scope=" + SCOPE
                + "&state=" + enc(state == null ? "" : state)
                + "#wechat_redirect";
    }

    /**
     * 用授权码换 openid 与昵称。
     *
     * @param code 微信回调带回的授权码，最长 128 字节，5 分钟有效且只能用一次
     */
    public Exchange exchange(String code) {
        if (!props.ready()) {
            return Exchange.failure(props.unavailableReason());
        }
        if (code == null || code.isBlank()) {
            return Exchange.failure("缺少微信授权码（code）。");
        }

        JsonNode token;
        try {
            token = get("/sns/oauth2/access_token", Map.of(
                    "appid", props.getAppid(),
                    "secret", props.getSecret(),
                    "code", code,
                    "grant_type", "authorization_code"));
        } catch (Exception e) {
            // 不打印 URL：查询串里有 AppSecret。
            log.warn("调用微信 /sns/oauth2/access_token 失败：{}", e.toString());
            return Exchange.failure("连接微信服务器失败：" + e.getMessage());
        }

        String openid = text(token, "openid");
        String accessToken = text(token, "access_token");
        if (openid == null || openid.isBlank()) {
            return Exchange.failure(explain(token));
        }

        // 昵称拿不到不影响绑定：openid 才是身份，昵称只是显示。
        String nickname = null;
        if (accessToken != null && !accessToken.isBlank()) {
            try {
                JsonNode info = get("/sns/userinfo", Map.of(
                        "access_token", accessToken,
                        "openid", openid,
                        "lang", "zh_CN"));
                nickname = text(info, "nickname");
                if (nickname == null) {
                    log.info("拿到 openid 但没拿到昵称，绑定照常进行：{}", explain(info));
                }
            } catch (Exception e) {
                log.info("拿昵称时调用微信失败，绑定照常进行：{}", e.toString());
            }
        }
        return new Exchange(openid, nickname, null);
    }

    // ==================== 内部 ====================

    private JsonNode get(String path, Map<String, String> params) throws Exception {
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (q.length() > 0) {
                q.append('&');
            }
            q.append(e.getKey()).append('=').append(enc(e.getValue()));
        }
        String url = props.getBaseUrl() + path + "?" + q;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("微信返回 HTTP " + resp.statusCode());
        }
        JsonNode node = mapper.readTree(resp.body());
        if (node == null || node.isMissingNode()) {
            throw new IllegalStateException("微信返回的不是 JSON 对象");
        }
        return node;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText(null);
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * 把微信的错误码翻译成人话。微信的错误一律是 200 + {@code errcode/errmsg}，
     * 所以「HTTP 成功」并不代表成功，必须看 errcode。
     */
    private static String explain(JsonNode node) {
        if (node == null) {
            return "微信没有返回可用的结果。";
        }
        int errcode = node.hasNonNull("errcode") ? node.get("errcode").asInt() : -1;
        String errmsg = text(node, "errmsg");
        String base = switch (errcode) {
            case 40029 -> "授权码无效或已过期";
            case 40163 -> "授权码已经被使用过（同一个 code 只能用一次）";
            case 40013 -> "AppID 无效";
            case 40125 -> "AppSecret 无效";
            case 41008 -> "缺少 code 参数";
            case 45011 -> "微信接口调用频率受限，请稍后再试";
            case 40001 -> "AppSecret 错误，或 access_token 与 openid 不匹配";
            case -1 -> "微信返回了无法识别的结果";
            default -> "微信返回错误";
        };
        StringBuilder sb = new StringBuilder(base);
        if (errcode >= 0) {
            sb.append("（errcode ").append(errcode);
            if (errmsg != null) {
                sb.append(", ").append(errmsg);
            }
            sb.append('）');
        } else if (errmsg != null) {
            sb.append("（").append(errmsg).append('）');
        }
        return sb.toString();
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }
}
