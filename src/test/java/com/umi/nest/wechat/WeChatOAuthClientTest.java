package com.umi.nest.wechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信换码客户端。
 *
 * <p>这个类守的是一条「不许撒谎」的规矩。它的前身直接写
 * {@code "mock_openid_" + System.currentTimeMillis()} 并回答「绑定成功」，
 * 所以下面每一个失败用例都额外断言两件事：<b>没有 openid 流出去</b>、
 * <b>错误里带着能看懂的原因</b>。
 *
 * <p>测试用一个真的本机 HTTP 桩服务器（{@code com.sun.net.httpserver}），
 * 而不是 mock 掉 HttpRequest —— 因为要验证的恰恰是 URL 怎么拼、
 * 微信那种「HTTP 200 但 errcode 非零」的响应怎么读。
 */
@DisplayName("WeChatOAuthClient - 微信换码")
class WeChatOAuthClientTest {

    private WeChatProperties props;
    private WeChatOAuthClient client;
    private HttpServer server;
    private String baseUrl;

    /** 桩服务器收到的请求（path + query），用来断言「根本没发请求」。 */
    private final List<String> hits = new CopyOnWriteArrayList<>();
    /** path -> 响应体。没配的路径返回 500。 */
    private final Map<String, String> stub = new java.util.HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        hits.clear();
        stub.clear();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.createContext("/", (HttpExchange ex) -> {
            hits.add(ex.getRequestURI().toString());
            String body = stub.get(ex.getRequestURI().getPath());
            byte[] bytes = (body == null ? "{\"errcode\":-999,\"errmsg\":\"no stub\"}" : body)
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(body == null ? 500 : 200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        props = new WeChatProperties();
        props.setBaseUrl(baseUrl);
        props.setTimeoutMs(3000);
        client = new WeChatOAuthClient(props, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void configure() {
        props.setAppid("wx_test_appid");
        props.setSecret("test_secret");
    }

    private void stubToken(String json) {
        stub.put("/sns/oauth2/access_token", json);
    }

    private void stubUserinfo(String json) {
        stub.put("/sns/userinfo", json);
    }

    @Nested
    @DisplayName("未配置凭据")
    class NotConfigured {

        @Test
        @DisplayName("不发任何网络请求，直接返回可读原因")
        void refusesWithoutTouchingTheNetwork() {
            assertFalse(client.configured());
            assertNotNull(client.unavailableReason());
            assertNull(client.appid());

            WeChatOAuthClient.Exchange ex = client.exchange("any-code");

            assertFalse(ex.ok());
            assertNull(ex.openid());
            assertTrue(ex.error().contains("nest.wechat.appid"), "原因里要指出缺哪个配置：" + ex.error());
            // 关键：连试都不该试
            assertEquals(0, hits.size(), "未配置凭据时不该发出任何请求，实际发了：" + hits);
        }

        @Test
        @DisplayName("只配了一半也能说清缺哪一半")
        void explainsWhichHalfIsMissing() {
            props.setAppid("wx_only_appid");
            assertFalse(client.configured());
            assertTrue(client.unavailableReason().contains("nest.wechat.secret"),
                    "应指出缺 secret：" + client.unavailableReason());

            props.setAppid("");
            props.setSecret("only_secret");
            assertFalse(client.configured());
            assertTrue(client.unavailableReason().contains("nest.wechat.appid"),
                    "应指出缺 appid：" + client.unavailableReason());
        }

        @Test
        @DisplayName("enabled=false 时优先说明是被配置关掉的")
        void disabledWins() {
            configure();
            props.setEnabled(false);
            assertFalse(client.configured());
            assertTrue(client.unavailableReason().contains("enabled=false"), client.unavailableReason());
        }
    }

    @Nested
    @DisplayName("配置齐全")
    class Configured {

        @Test
        @DisplayName("成功：code 换成 openid，并带上昵称")
        void exchangesCodeForOpenid() {
            configure();
            stubToken("{\"access_token\":\"AT-123\",\"expires_in\":7200,\"openid\":\"o_real_openid\"}");
            stubUserinfo("{\"openid\":\"o_real_openid\",\"nickname\":\"王小明\"}");

            WeChatOAuthClient.Exchange ex = client.exchange("CODE-abc");

            assertTrue(ex.ok(), ex.error());
            assertEquals("o_real_openid", ex.openid());
            assertEquals("王小明", ex.nickname());
            assertNull(ex.error());
            assertFalse(ex.openid().startsWith("mock_openid_"), "绝不能是编出来的 openid");

            // 拼出来的 URL 必须带齐四个参数
            assertEquals(2, hits.size(), hits.toString());
            String tokenUrl = hits.get(0);
            assertTrue(tokenUrl.startsWith("/sns/oauth2/access_token?"), tokenUrl);
            assertTrue(tokenUrl.contains("appid=wx_test_appid"), tokenUrl);
            assertTrue(tokenUrl.contains("secret=test_secret"), tokenUrl);
            assertTrue(tokenUrl.contains("code=CODE-abc"), tokenUrl);
            assertTrue(tokenUrl.contains("grant_type=authorization_code"), tokenUrl);
            // 第二个请求是用 access_token + openid 换昵称
            assertTrue(hits.get(1).startsWith("/sns/userinfo?"), hits.get(1));
            assertTrue(hits.get(1).contains("openid=o_real_openid"), hits.get(1));
        }

        @Test
        @DisplayName("微信业务错误：HTTP 200 + errcode 也算失败，且不返回 openid")
        void errcodeMeansFailure() {
            configure();
            stubToken("{\"errcode\":40029,\"errmsg\":\"invalid code\"}");

            WeChatOAuthClient.Exchange ex = client.exchange("CODE-used");

            assertFalse(ex.ok());
            assertNull(ex.openid(), "失败时不能有 openid");
            assertTrue(ex.error().contains("授权码无效或已过期"), ex.error());
            assertTrue(ex.error().contains("40029"), ex.error());
            assertTrue(ex.error().contains("invalid code"), "要保留微信原文：" + ex.error());
            // 拿不到 openid 就不该再去问昵称
            assertEquals(1, hits.size(), hits.toString());
        }

        @Test
        @DisplayName("授权码被用过（40163）也能单独识别")
        void reusedCode() {
            configure();
            stubToken("{\"errcode\":40163,\"errmsg\":\"code been used\"}");
            WeChatOAuthClient.Exchange ex = client.exchange("CODE-reused");
            assertFalse(ex.ok());
            assertTrue(ex.error().contains("已经被使用过"), ex.error());
        }

        @Test
        @DisplayName("拿不到昵称不影响绑定——openid 才是身份")
        void nicknameIsOptional() {
            configure();
            stubToken("{\"access_token\":\"AT-1\",\"openid\":\"o_real\"}");
            stubUserinfo("{\"errcode\":40001,\"errmsg\":\"invalid credential\"}");

            WeChatOAuthClient.Exchange ex = client.exchange("CODE-ok");

            assertTrue(ex.ok(), "昵称失败不该让绑定失败：" + ex.error());
            assertEquals("o_real", ex.openid());
            assertNull(ex.nickname());
        }

        @Test
        @DisplayName("userinfo 直接返回非 JSON 也不会把绑定弄挂")
        void brokenUserinfoBody() {
            configure();
            stubToken("{\"access_token\":\"AT-1\",\"openid\":\"o_real\"}");
            stubUserinfo("<<<not json>>>");

            WeChatOAuthClient.Exchange ex = client.exchange("CODE-ok");

            assertTrue(ex.ok(), ex.error());
            assertEquals("o_real", ex.openid());
            assertNull(ex.nickname());
        }

        @Test
        @DisplayName("缺 code 就明确拒绝，不浪费一次外部调用")
        void missingCode() {
            configure();
            assertFalse(client.exchange(null).ok());
            assertFalse(client.exchange("  ").ok());
            assertEquals(0, hits.size(), hits.toString());
        }

        @Test
        @DisplayName("网络不通时失败信息可读，不抛异常")
        void networkFailureIsSwallowed() {
            configure();
            props.setBaseUrl("http://127.0.0.1:1"); // 没人监听
            WeChatOAuthClient.Exchange ex = client.exchange("CODE-ok");
            assertFalse(ex.ok());
            assertNull(ex.openid());
            assertTrue(ex.error().contains("连接微信服务器失败"), ex.error());
        }
    }

    @Nested
    @DisplayName("授权页地址")
    class AuthorizeUrl {

        @Test
        @DisplayName("带上 appid / redirect_uri / state")
        void wellFormed() {
            configure();
            String url = client.authorizeUrl("https://nest.example.edu/settings.html", "st-1");
            assertNotNull(url);
            assertTrue(url.startsWith("https://open.weixin.qq.com/connect/qrconnect?"), url);
            assertTrue(url.contains("appid=wx_test_appid"), url);
            assertTrue(url.contains("redirect_uri=https%3A%2F%2Fnest.example.edu%2Fsettings.html"), url);
            assertTrue(url.contains("scope=snsapi_login"), url);
            assertTrue(url.contains("state=st-1"), url);
            assertTrue(url.endsWith("#wechat_redirect"), url);
            assertFalse(url.contains("test_secret"), "授权链接里绝不能出现 AppSecret");
        }

        @Test
        @DisplayName("未配置凭据时没有授权页可去")
        void notConfigured() {
            assertNull(client.authorizeUrl("https://x/y", "st"));
        }
    }
}
