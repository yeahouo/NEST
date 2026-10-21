package com.umi.nest.dorm.psyche;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 心理模块配置，前缀 {@code nest.psyche}。
 *
 * <p>api-key 不在这里的默认值里，只在 {@code config/local.properties}
 * （由 {@code spring.config.import} 引入）。这样主配置可以进版本库，
 * 凭据不会跟着走；文件不存在时 {@code llmReady()} 返回 false，
 * 整个模块自动退化为纯本地规则提取。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nest.psyche")
public class PsycheProperties {

    private final Llm llm = new Llm();
    private final Apply apply = new Apply();

    @Data
    public static class Llm {
        private boolean enabled = true;
        private String apiKey = "";
        private String baseUrl = "https://api.deepseek.com";
        private String model = "deepseek-chat";
        private long timeoutMs = 90_000L;
        private int maxConcurrent = 2;
        /** 单份文本送进模型的字符上限，超了截断。 */
        private int maxTextChars = 6000;
        /**
         * 同一份素材调用几次模型。
         *
         * <p>1 次 = 不做不确定性测量；≥2 次 = 拿多次采样之间的极差当作分歧度，
         * 分歧大的维度自动降置信度（见 {@code LlmExtractor.merge}）。
         * 代价是线性增长的开销，默认 2 次是在"能测出分歧"和"别烧钱"之间的折中。
         */
        private int samples = 2;
        /**
         * 第 2 次及以后的采样温度。第 1 次恒为 0（要一个确定的基线），
         * 后续用非零温度制造合理的多样性——温度全 0 的话多次采样必然完全相同，
         * 测出来的分歧永远是 0，等于没测。
         */
        private double sampleTemperature = 0.35;
    }

    @Data
    public static class Apply {
        /** 低于这个置信度的维不回写 dorm_student_feature。 */
        private double minConfidence = 0.35;
    }

    public boolean llmReady() {
        // 注意是 isEnabled() 不是 getEnabled()：Lombok 对基本类型 boolean 生成 is 前缀的 getter
        return llm.isEnabled() && llm.getApiKey() != null && !llm.getApiKey().isBlank();
    }
}
