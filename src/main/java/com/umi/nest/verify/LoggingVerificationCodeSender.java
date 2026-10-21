package com.umi.nest.verify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 默认投递渠道：把验证码写进服务器日志，并如实声明「没有真的送出去」。
 *
 * <p>为什么这样也算一个正经实现，而不是又一个占位：
 * <ul>
 *   <li>验证码本身是真的（随机生成、HMAC 后入库、5 分钟过期、最多试 5 次、一次性）；
 *   <li>校验是真的（走的是和线上完全同一条代码路径）；
 *   <li>只有「最后一公里」是假的，而且这一点通过 {@link #delivers()} 和
 *       {@link #channel()} 一路传到前端，用户看得见。
 * </ul>
 *
 * <p>日志格式是刻意固定的单行、机器可读，{@code tools/verify-code-e2e.ps1}
 * 就是靠它把验证码从日志里捞出来，跑完整的改手机号 / 绑邮箱 / 重置密码链路。
 */
@Component
public class LoggingVerificationCodeSender implements VerificationCodeSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingVerificationCodeSender.class);

    /** 这一行会被端到端脚本 grep，改动前先确认 tools/verify-code-e2e.ps1。 */
    static final String LOG_TAG = "[NEST-VERIFY]";

    @Override
    public String channel() {
        return "log";
    }

    @Override
    public boolean delivers() {
        return false;
    }

    @Override
    public void send(String scene, String target, String code, int ttlSeconds) {
        log.warn("{} channel=log scene={} target={} code={} expires_in={}s -- "
                        + "未接入短信/邮件网关，验证码只写进了服务器日志（开发模式）",
                LOG_TAG, scene, target, code, ttlSeconds);
    }
}
