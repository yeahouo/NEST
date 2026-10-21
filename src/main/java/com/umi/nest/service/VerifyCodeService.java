package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.VerificationCode;
import com.umi.nest.mapper.VerificationCodeMapper;
import com.umi.nest.verify.VerificationCodeSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 验证码：生成、限流、投递、校验。
 *
 * <p>这个类存在的理由，是它原来不存在。改手机号 / 绑邮箱 / 忘记密码三条链路的
 * 前端都有一个验证码输入框，后端也有三行 {@code // 验证码校验（预留）} 注释，
 * 但从来没有一个验证码被生成过。结果是：
 * <ul>
 *   <li>忘记密码：手机号来自客户端 {@code sessionStorage}，服务端不校验任何东西，
 *       往里写一个别人的手机号就能改掉那个人的密码；</li>
 *   <li>改手机号 / 绑邮箱：不需要证明你拥有那个手机号 / 邮箱。</li>
 * </ul>
 *
 * <p>规则（都由服务端执行，前端只是显示）：
 * <ul>
 *   <li>6 位数字，{@link SecureRandom} 生成，绝不复用；</li>
 *   <li>{@link #TTL_SECONDS} 秒有效；</li>
 *   <li>同一 scene+target 每 {@link #RESEND_INTERVAL_SECONDS} 秒最多一条，
 *       每小时最多 {@link #MAX_PER_HOUR} 条；</li>
 *   <li>最多校验失败 {@link #MAX_ATTEMPTS} 次；</li>
 *   <li>一次性：校验通过立刻作废。</li>
 * </ul>
 */
@Service
public class VerifyCodeService {

    private static final Logger log = LoggerFactory.getLogger(VerifyCodeService.class);

    // ==================== 场景 ====================

    /** 改手机号 / 首次绑定手机号。 */
    public static final String SCENE_BIND_PHONE = "bind_phone";
    /** 绑邮箱。 */
    public static final String SCENE_BIND_EMAIL = "bind_email";
    /** 忘记密码。 */
    public static final String SCENE_RESET_PASSWORD = "reset_password";

    // ==================== 规则常量 ====================

    /** 验证码有效期（秒）。 */
    public static final int TTL_SECONDS = 300;
    /** 两次发送之间的最小间隔（秒）。 */
    public static final int RESEND_INTERVAL_SECONDS = 60;
    /** 同一 scene+target 每小时最多发送条数。 */
    public static final int MAX_PER_HOUR = 5;
    /** 同一条验证码最多允许校验失败几次。 */
    public static final int MAX_ATTEMPTS = 5;
    /** 记录保留天数：这张表天然短生命周期，过期的删掉即可，不留归档。 */
    private static final int RETENTION_DAYS = 7;

    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{6,20}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private VerificationCodeMapper mapper;

    @Autowired
    private VerificationCodeSender sender;

    /**
     * 哈希用的 pepper。默认值只是为了「不配置也能跑」，生产部署应该在
     * {@code application.properties}（或环境变量）里换掉它 ——
     * 它的唯一作用就是让「拿到整张表的 dump」不等于「拿到有效期内的验证码」。
     */
    @Value("${nest.verify.pepper:NEST-DEFAULT-PEPPER-PLEASE-CHANGE}")
    private String pepper;

    /**
     * 发送验证码。
     *
     * @return 成功时 data 里带 {@code channel / delivered / expiresIn}，
     *         其中 {@code delivered=false} 表示「码是真的，但没真的送出去」（日志渠道）。
     */
    public Result<?> send(String scene, String target) {
        String normalized = normalize(target);

        String invalid = reject(scene, normalized);
        if (invalid != null) {
            return Result.error(invalid);
        }

        LocalDateTime now = LocalDateTime.now();

        // 限流 1：太密
        if (mapper.countSince(scene, normalized, now.minusSeconds(RESEND_INTERVAL_SECONDS)) > 0) {
            // 注意用词：这是「拒绝再发一条」，不是「已经发了」。
            // 原来的文案是「验证码已发送，请 60 秒后再试」，却带着 code=400 返回，
            // 前端把它当失败处理（弹错误、不起倒计时），用户看到的是自相矛盾的一句话。
            return Result.error("验证码刚刚已经发过一条，请 " + RESEND_INTERVAL_SECONDS + " 秒后再试");
        }
        // 限流 2：太多
        if (mapper.countSince(scene, normalized, now.minusHours(1)) >= MAX_PER_HOUR) {
            return Result.error("该" + label(scene) + "获取验证码过于频繁，请一小时后再试");
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));

        VerificationCode entity = new VerificationCode();
        entity.setScene(scene);
        entity.setTarget(normalized);
        entity.setCodeHash(hash(scene, normalized, code));
        entity.setExpiresAt(now.plusSeconds(TTL_SECONDS));
        entity.setAttempts(0);
        entity.setUsed(0);

        // 重新发送时把旧码作废：同时存在两条有效码，会让「哪条才算对」变得含糊。
        int revoked = mapper.invalidateActive(scene, normalized);
        if (revoked > 0) {
            log.info("作废了 {} 条未使用的旧验证码 scene={} target={}", revoked, scene, normalized);
        }
        mapper.insert(entity);

        sender.send(scene, normalized, code, TTL_SECONDS);

        // 顺手清理：不需要调度框架，这张表小且 create_time 有索引。
        try {
            int purged = mapper.deleteCreatedBefore(now.minusDays(RETENTION_DAYS));
            if (purged > 0) {
                log.info("清理了 {} 条 {} 天前的验证码记录", purged, RETENTION_DAYS);
            }
        } catch (Exception e) {
            // 清理失败不能影响发送本身
            log.warn("清理历史验证码失败：{}", e.getMessage());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channel", sender.channel());
        data.put("delivered", sender.delivers());
        data.put("expiresIn", TTL_SECONDS);
        if (!sender.delivers()) {
            data.put("hint", "当前未接入短信/邮件网关，验证码只写进了服务器日志");
        }
        return Result.success(data);
    }

    /**
     * 校验验证码，通过则立刻作废（一次性）。
     *
     * @return 通过返回 {@code null}；否则返回可以直接展示给用户的原因。
     */
    public String check(String scene, String target, String rawCode) {
        String normalized = normalize(target);

        if (rawCode == null || rawCode.trim().isEmpty()) {
            return "请输入" + label(scene) + "收到的验证码";
        }
        String code = rawCode.trim();

        VerificationCode latest = mapper.findLatestActive(scene, normalized);
        if (latest == null) {
            return "验证码不存在或已过期，请重新获取";
        }

        int tried = latest.getAttempts() == null ? 0 : latest.getAttempts();
        if (tried >= MAX_ATTEMPTS) {
            mapper.markUsed(latest.getId());
            return "验证码错误次数过多，请重新获取";
        }

        if (!constantTimeEquals(latest.getCodeHash(), hash(scene, normalized, code))) {
            mapper.incrementAttempts(latest.getId());
            int left = MAX_ATTEMPTS - tried - 1;
            return left > 0
                    ? "验证码不正确，还可以再试 " + left + " 次"
                    : "验证码错误次数过多，请重新获取";
        }

        mapper.markUsed(latest.getId());
        return null;
    }

    // ==================== 内部 ====================

    /** 参数体检。返回 null 表示通过。 */
    private String reject(String scene, String target) {
        if (!SCENE_BIND_PHONE.equals(scene)
                && !SCENE_BIND_EMAIL.equals(scene)
                && !SCENE_RESET_PASSWORD.equals(scene)) {
            return "未知的验证码场景";
        }
        if (target == null || target.isEmpty()) {
            return "请先填写" + label(scene);
        }
        if (SCENE_BIND_EMAIL.equals(scene)) {
            if (!EMAIL.matcher(target).matches()) {
                return "邮箱格式不正确";
            }
        } else {
            if (!PHONE.matcher(target).matches()) {
                return "手机号格式不正确";
            }
        }
        return null;
    }

    private static String label(String scene) {
        if (SCENE_BIND_EMAIL.equals(scene)) {
            return "邮箱";
        }
        if (SCENE_BIND_PHONE.equals(scene)) {
            return "手机号";
        }
        return "手机号";
    }

    /** 去掉空格、横线、括号，避免 "+86 138-1234-5678" 和 "+8613812345678" 被当成两个目标。 */
    static String normalize(String target) {
        return target == null ? null : target.replaceAll("[\\s\\-()]", "");
    }

    /** HMAC-SHA256(scene|target|code, pepper) 的十六进制。 */
    String hash(String scene, String target, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((scene + "|" + target + "|" + code).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // 算法固定存在、密钥固定合法，走到这里说明 JVM 环境有问题，不能静默降级成明文比较。
            throw new IllegalStateException("无法计算验证码哈希: " + e.getMessage(), e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
