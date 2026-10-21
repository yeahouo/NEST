package com.umi.nest.verify;

/**
 * 验证码投递渠道。
 *
 * <p>这是「验证码真的生成、真的存、真的校验」与「验证码真的送到用户手机上」之间的那条缝。
 * 缝在这里，是因为它就是一条缝：没有短信/邮件服务商的账号和密钥，
 * 代码写得再漂亮也发不出去。与其假装发了（这正是这个项目原来做的事——
 * {@code UserService.sendVerificationCode} 直接返回「验证码已发送」），
 * 不如把这条缝做成一个接口，并让默认实现老老实实承认自己没送出去。
 *
 * <p>接真实网关时：实现本接口，把你的实现标成 {@code @Primary}（或去掉
 * {@link LoggingVerificationCodeSender} 的 {@code @Component}），
 * {@code delivers()} 返回 true，前端就会停止显示「开发模式」提示。
 */
public interface VerificationCodeSender {

    /** 渠道标识，会随响应回给前端（{@code log} / {@code sms} / {@code smtp} …）。 */
    String channel();

    /**
     * 是否真的把验证码送到了用户手上。
     *
     * <p>默认 true。返回 false 时服务层会在响应里带上 {@code delivered=false} 和一句
     * 说明，前端据此如实告诉用户「验证码没有真的发出去」。
     */
    default boolean delivers() {
        return true;
    }

    /**
     * 发送。
     *
     * @param scene      场景（bind_phone / bind_email / reset_password）
     * @param target     手机号或邮箱
     * @param code       6 位明文验证码
     * @param ttlSeconds 有效期秒数，供文案使用
     */
    void send(String scene, String target, String code, int ttlSeconds);
}
