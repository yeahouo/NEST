package com.umi.nest.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 辅助出题 / 批改用的大模型凭据与调参。
 *
 * <p>为什么另起一个 {@code nest.ai.llm} 前缀，而不是直接复用宿舍模块的
 * {@code nest.psyche.llm}：宿舍模块的配置类在 {@code com.umi.nest.dorm.psyche} 包里，
 * 教学模块去 import 它，就等于把「课堂管理系统」焊死在「宿舍分配」上——
 * 而这个项目的答辩材料里恰恰写着「keep 教学系统作基座但不依赖其业务模型」。
 * 两个前缀在 {@code application.properties} 里用占位符指到同一个 key，
 * 一份凭据供两处使用，代码上却互不相识。
 */
@Component
@ConfigurationProperties(prefix = "nest.ai.llm")
public class AiProperties {

    /** 关掉它，AI 相关按钮会明确告诉用户「未配置」并给出纯本地的手动路径，而不是静默失败。 */
    private boolean enabled = true;

    private String apiKey = "";

    private String baseUrl = "https://api.deepseek.com";

    private String model = "deepseek-chat";

    /** 出题生成一整套卷子比单次特征抽取慢，默认给到 2 分钟。 */
    private long timeoutMs = 120_000L;

    /** 并发闸门。学生的作答文本要出校门，串行化一点更稳妥，也避免撞上服务商的限流。 */
    private int maxConcurrent = 2;

    private int maxTokens = 4000;

    /** 出题要有点发散性，默认给 0.7；批改会覆盖成低温。 */
    private double temperature = 0.7;

    /** 单次送进模型的参考资料上限，防止老师粘一整本书把上下文撑爆。 */
    private int maxReferenceChars = 12_000;

    public boolean ready() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /** 给用户看的解释，说明为什么按钮点了没反应。 */
    public String unavailableReason() {
        if (!enabled) {
            return "AI 功能已被配置关闭（nest.ai.llm.enabled=false）。";
        }
        if (apiKey == null || apiKey.isBlank()) {
            return "未配置大模型 API Key：请在 config/local.properties 里填写 nest.ai.llm.api-key。";
        }
        return null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxReferenceChars() {
        return maxReferenceChars;
    }

    public void setMaxReferenceChars(int maxReferenceChars) {
        this.maxReferenceChars = maxReferenceChars;
    }
}
