package com.umi.nest.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Semaphore;

/**
 * 一个很小的大模型聊天客户端：只做一件事，把「系统提示 + 用户提示」发出去，
 * 拿回一段应该是 JSON 的文本。
 *
 * <p>三条硬规定：
 * <ol>
 *   <li><b>只走 JSON 模式</b>（{@code response_format=json_object}）。出题和批改都要结构化结果，
 *       让模型直接说人话再正则去抠，是自找麻烦。</li>
 *   <li><b>失败不抛异常</b>。网络抖动、余额不足、模型抽风，都返回一个带 {@code error} 的
 *       {@link Reply}。调用方据此走降级路径（提示老师「AI 不可用，请手动出题」），
 *       而不是让整个请求 500。老师的出题页面不该因为外部服务而崩。</li>
 *   <li><b>有并发闸门</b>。学生的作答文本要出校门，串行化一点更稳，也避免撞上服务商限流。</li>
 * </ol>
 */
@Component
public class AiChatClient {

    private static final Logger log = LoggerFactory.getLogger(AiChatClient.class);

    private final AiProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Semaphore gate;

    public AiChatClient(AiProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.gate = new Semaphore(Math.max(1, props.getMaxConcurrent()));
    }

    /** 一次调用的结果。{@code error} 非空表示这次没成，{@code content} 不可信。 */
    public record Reply(String content, String model, long elapsedMs,
                        int promptTokens, int completionTokens, String error) {

        public boolean ok() {
            return error == null && content != null && !content.isBlank();
        }

        static Reply failure(String model, long elapsedMs, String error) {
            return new Reply(null, model, elapsedMs, 0, 0, error);
        }
    }

    /**
     * 发一次 JSON 模式的对话请求。
     *
     * @param system      系统提示，放角色设定与硬性输出格式
     * @param user        用户提示，放本次的具体任务
     * @param temperature 采样温度；出题偏高（发散），批改偏低（稳定）
     * @param maxTokens   回复长度上限
     */
    public Reply chatJson(String system, String user, double temperature, int maxTokens) {
        String model = props.getModel();
        long started = System.currentTimeMillis();

        String why = props.unavailableReason();
        if (why != null) {
            return Reply.failure(model, 0, why);
        }

        String body;
        try {
            ObjectNode req = mapper.createObjectNode();
            req.put("model", model);
            req.put("temperature", temperature);
            req.put("stream", false);
            req.put("max_tokens", maxTokens);
            ObjectNode rf = req.putObject("response_format");
            rf.put("type", "json_object");
            ArrayNode msgs = req.putArray("messages");
            msgs.addObject().put("role", "system").put("content", system);
            msgs.addObject().put("role", "user").put("content", user);
            body = mapper.writeValueAsString(req);
        } catch (Exception e) {
            log.warn("构造 AI 请求体失败：{}", e.toString());
            return Reply.failure(model, 0, "构造请求失败：" + e.getMessage());
        }

        String url = props.getBaseUrl();
        if (url == null || url.isBlank()) {
            return Reply.failure(model, 0, "未配置 base-url");
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        url = url + "/chat/completions";

        boolean acquired;
        try {
            acquired = gate.tryAcquire(props.getTimeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return Reply.failure(model, 0, "调用被中断");
        }
        if (!acquired) {
            return Reply.failure(model, 0, "AI 调用排队超时（并发上限 " + props.getMaxConcurrent() + "），请稍后重试");
        }

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(props.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsed = System.currentTimeMillis() - started;

            if (resp.statusCode() / 100 != 2) {
                String snippet = truncate(resp.body(), 400);
                log.warn("AI 返回 HTTP {}：{}", resp.statusCode(), snippet);
                return Reply.failure(model, elapsed, "模型服务返回 HTTP " + resp.statusCode() + "：" + snippet);
            }

            JsonNode root = mapper.readTree(resp.body());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return Reply.failure(model, elapsed, "模型没有返回任何候选结果");
            }
            JsonNode msg = choices.get(0).path("message").path("content");
            if (msg.isMissingNode() || msg.isNull()) {
                return Reply.failure(model, elapsed, "模型返回了空内容");
            }

            String content = stripFence(msg.asText());
            if (content == null || content.isBlank()) {
                return Reply.failure(model, elapsed, "模型返回了空内容");
            }

            JsonNode usage = root.path("usage");
            return new Reply(
                    content,
                    root.path("model").asText(model),
                    elapsed,
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0),
                    null);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - started;
            log.warn("AI 调用异常：{}", e.toString());
            return Reply.failure(model, elapsed, "调用模型失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            gate.release();
        }
    }

    /**
     * 连通性自检，给「AI 设置」页用。
     *
     * <p>故意要求模型回一个固定词，这样它能区分「网络通、但这个 key 没有额度」
     * 与「真的能对话」——只 ping 一下域名是分不出这两者的。
     */
    public Reply probe() {
        return chatJson(
                "你是一个连通性探针。必须输出 JSON。",
                "请输出 {\"status\":\"在线\"} 这个 JSON，不要加任何其它内容。",
                0.0, 64);
    }

    /** 模型偶尔会画蛇添足地包一层 \`\`\`json 围栏；这里兜一层底。 */
    static String stripFence(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) {
                s = s.substring(nl + 1);
            }
            int end = s.lastIndexOf("```");
            if (end >= 0) {
                s = s.substring(0, end);
            }
            s = s.trim();
        }
        return s;
    }

    static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
