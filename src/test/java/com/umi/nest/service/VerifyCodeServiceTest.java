package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.VerificationCode;
import com.umi.nest.mapper.VerificationCodeMapper;
import com.umi.nest.verify.VerificationCodeSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证码服务的单元测试。
 *
 * <p>这个类守着的是一条真实的越权路径：忘记密码的验证码如果形同虚设，
 * 任何人都能改掉任何人的密码。所以下面每一条「应当拒绝」的用例，
 * 断言的不只是返回了错误，还包括「数据库没被写」。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VerifyCodeService - 验证码生成、限流与校验")
class VerifyCodeServiceTest {

    private static final String PEPPER = "TEST-PEPPER-0123456789";
    private static final String PHONE = "+8613800138000";
    private static final String SCENE = VerifyCodeService.SCENE_BIND_PHONE;

    @Mock
    private VerificationCodeMapper mapper;

    @Mock
    private VerificationCodeSender sender;

    @InjectMocks
    private VerifyCodeService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "pepper", PEPPER);
        // 默认模拟「日志渠道」：码生成了，但没有真的送出去
        lenient().when(sender.channel()).thenReturn("log");
        lenient().when(sender.delivers()).thenReturn(false);
    }

    /** 一次发送之后，库里存了什么 + 实际发给用户的是什么。 */
    private static final class Issued {
        final VerificationCode row;
        final String code;

        Issued(VerificationCode row, String code) {
            this.row = row;
            this.code = code;
        }
    }

    /** 真发一次验证码，把落库的那行与明文码一起捞出来。每个测试只调用一次。 */
    private Issued issue(String scene, String target) {
        when(mapper.countSince(eq(scene), eq(target), any(LocalDateTime.class))).thenReturn(0);

        Result<?> result = service.send(scene, target);
        assertEquals(200, result.getCode().intValue(), "发送应当成功");

        ArgumentCaptor<VerificationCode> rowCaptor = ArgumentCaptor.forClass(VerificationCode.class);
        verify(mapper).insert(rowCaptor.capture());

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(scene), eq(target), codeCaptor.capture(), eq(VerifyCodeService.TTL_SECONDS));

        VerificationCode row = rowCaptor.getValue();
        row.setId(77L);
        return new Issued(row, codeCaptor.getValue());
    }

    // ==================== 发送 ====================

    @Nested
    @DisplayName("发送验证码")
    class Send {

        @Test
        @DisplayName("生成 6 位数字码，库里只存哈希不存明文")
        void storesHashNotPlaintext() {
            Issued issued = issue(SCENE, PHONE);

            assertTrue(issued.code.matches("\\d{6}"), "应当是 6 位数字，实际=" + issued.code);
            assertEquals(64, issued.row.getCodeHash().length());
            assertTrue(issued.row.getCodeHash().matches("[0-9a-f]{64}"), "应当是 HMAC-SHA256 的十六进制");
            assertNotEquals(issued.code, issued.row.getCodeHash(), "库里绝不能存明文，否则脱库等于拿到验证码");
            assertFalse(issued.row.getCodeHash().contains(issued.code));

            // 哈希必须与同一个 scene/target/code 对得上（校验时才可能通过）
            assertEquals(service.hash(SCENE, PHONE, issued.code), issued.row.getCodeHash());
        }

        @Test
        @DisplayName("落库字段齐备：场景、目标、有效期、尝试次数、未使用")
        void storesCompleteRow() {
            LocalDateTime before = LocalDateTime.now();
            Issued issued = issue(SCENE, PHONE);

            assertEquals(SCENE, issued.row.getScene());
            assertEquals(PHONE, issued.row.getTarget());
            assertEquals(0, issued.row.getAttempts().intValue());
            assertEquals(0, issued.row.getUsed().intValue());
            assertNotNull(issued.row.getExpiresAt());

            LocalDateTime lower = before.plusSeconds(VerifyCodeService.TTL_SECONDS - 10);
            LocalDateTime upper = LocalDateTime.now().plusSeconds(VerifyCodeService.TTL_SECONDS + 10);
            assertTrue(issued.row.getExpiresAt().isAfter(lower), "有效期不应明显短于 " + VerifyCodeService.TTL_SECONDS + " 秒");
            assertTrue(issued.row.getExpiresAt().isBefore(upper), "有效期不应明显长于 " + VerifyCodeService.TTL_SECONDS + " 秒");
        }

        @Test
        @DisplayName("重新发送前先把旧的未使用码作废")
        void revokesPreviousCodeFirst() {
            when(mapper.countSince(eq(SCENE), eq(PHONE), any(LocalDateTime.class))).thenReturn(0);

            service.send(SCENE, PHONE);

            InOrder order = inOrder(mapper);
            order.verify(mapper).invalidateActive(SCENE, PHONE);
            order.verify(mapper).insert(any(VerificationCode.class));
        }

        @Test
        @DisplayName("返回的 data 说清楚「发是发了，但没送出去」")
        void reportsDeliveryHonestly() {
            when(mapper.countSince(eq(SCENE), eq(PHONE), any(LocalDateTime.class))).thenReturn(0);

            Result<?> result = service.send(SCENE, PHONE);

            Object data = result.getData();
            assertTrue(data instanceof Map, "data 应当是个 Map");
            Map<?, ?> map = (Map<?, ?>) data;
            assertEquals("log", map.get("channel"));
            assertEquals(Boolean.FALSE, map.get("delivered"));
            assertEquals(VerifyCodeService.TTL_SECONDS, ((Number) map.get("expiresIn")).intValue());
            assertNotNull(map.get("hint"), "没真送出时必须给出提示，不能假装已发送");
        }

        @Test
        @DisplayName("接上网关后 delivered=true，且不再给「未接入」提示")
        void reportsDeliveryWhenGatewayConfigured() {
            when(sender.channel()).thenReturn("sms");
            when(sender.delivers()).thenReturn(true);
            when(mapper.countSince(eq(SCENE), eq(PHONE), any(LocalDateTime.class))).thenReturn(0);

            Result<?> result = service.send(SCENE, PHONE);

            Map<?, ?> map = (Map<?, ?>) result.getData();
            assertEquals("sms", map.get("channel"));
            assertEquals(Boolean.TRUE, map.get("delivered"));
            assertFalse(map.containsKey("hint"), "接了网关就不该再提示未接入");
        }

        @Test
        @DisplayName("手机号归一化：空格/横线/括号不影响目标，也不产生第二个目标")
        void normalizesPhoneBeforeStoring() {
            when(mapper.countSince(eq(SCENE), eq(PHONE), any(LocalDateTime.class))).thenReturn(0);

            service.send(SCENE, "+86 138-0013-8000");

            ArgumentCaptor<VerificationCode> captor = ArgumentCaptor.forClass(VerificationCode.class);
            verify(mapper).insert(captor.capture());
            assertEquals(PHONE, captor.getValue().getTarget(), "带格式的号码必须归一到同一个目标");
        }

        @Test
        @DisplayName("邮箱场景用邮箱规则校验")
        void emailSceneUsesEmailRule() {
            Result<?> bad = service.send(VerifyCodeService.SCENE_BIND_EMAIL, "not-an-email");
            assertEquals(400, bad.getCode().intValue());
            assertTrue(bad.getMessage().contains("邮箱"), "实际=" + bad.getMessage());

            when(sender.delivers()).thenReturn(true);
            when(mapper.countSince(anyString(), anyString(), any(LocalDateTime.class))).thenReturn(0);
            Result<?> ok = service.send(VerifyCodeService.SCENE_BIND_EMAIL, "a@b.com");
            assertEquals(200, ok.getCode().intValue());
        }

        @Test
        @DisplayName("手机号格式不对时拒绝，且一行都不写")
        void rejectsBadPhone() {
            Result<?> result = service.send(SCENE, "abc");

            assertEquals(400, result.getCode().intValue());
            assertTrue(result.getMessage().contains("手机号"), "实际=" + result.getMessage());
            verify(mapper, never()).insert(any(VerificationCode.class));
            verify(mapper, never()).countSince(anyString(), anyString(), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("未知场景被拒绝，避免被当成发码器滥用")
        void rejectsUnknownScene() {
            Result<?> result = service.send("free_sms", PHONE);

            assertEquals(400, result.getCode().intValue());
            assertEquals("未知的验证码场景", result.getMessage());
            verify(mapper, never()).insert(any(VerificationCode.class));
        }

        @Test
        @DisplayName("目标为空时提示先填写")
        void rejectsBlankTarget() {
            Result<?> result = service.send(SCENE, "   ");

            assertEquals(400, result.getCode().intValue());
            verify(mapper, never()).insert(any(VerificationCode.class));
        }
    }

    // ==================== 限流 ====================

    @Nested
    @DisplayName("限流")
    class RateLimit {

        @Test
        @DisplayName("60 秒内重发被拒，且不生成新码")
        void rejectsResendWithinInterval() {
            when(mapper.countSince(eq(SCENE), eq(PHONE), any(LocalDateTime.class))).thenReturn(1);

            Result<?> result = service.send(SCENE, PHONE);

            assertEquals(400, result.getCode().intValue());
            assertTrue(result.getMessage().contains("60 秒"), "实际=" + result.getMessage());
            verify(mapper, never()).insert(any(VerificationCode.class));
        }

        @Test
        @DisplayName("一小时内超过上限被拒")
        void rejectsHourlyFlooding() {
            // 第一次查（60 秒窗口）没有，第二次查（1 小时窗口）已经到上限
            when(mapper.countSince(eq(SCENE), eq(PHONE), any(LocalDateTime.class)))
                    .thenReturn(0, VerifyCodeService.MAX_PER_HOUR);

            Result<?> result = service.send(SCENE, PHONE);

            assertEquals(400, result.getCode().intValue());
            assertTrue(result.getMessage().contains("过于频繁"), "实际=" + result.getMessage());
            verify(mapper, never()).insert(any(VerificationCode.class));
        }

        @Test
        @DisplayName("清理历史记录失败不影响发送成功")
        void cleanupFailureDoesNotBreakSend() {
            when(mapper.countSince(eq(SCENE), eq(PHONE), any(LocalDateTime.class))).thenReturn(0);
            when(mapper.deleteCreatedBefore(any(LocalDateTime.class)))
                    .thenThrow(new RuntimeException("连接池忙"));

            Result<?> result = service.send(SCENE, PHONE);

            assertEquals(200, result.getCode().intValue(), "顺手清理不能把主流程拖下水");
        }
    }

    // ==================== 校验 ====================

    @Nested
    @DisplayName("校验验证码")
    class Check {

        @Test
        @DisplayName("没填验证码直接拒绝，不查库")
        void rejectsBlankCode() {
            assertEquals("请输入手机号收到的验证码", service.check(SCENE, PHONE, "  "));
            assertEquals("请输入手机号收到的验证码", service.check(SCENE, PHONE, null));
            assertEquals("请输入邮箱收到的验证码",
                    service.check(VerifyCodeService.SCENE_BIND_EMAIL, "a@b.com", ""));

            verify(mapper, never()).findLatestActive(anyString(), anyString());
        }

        @Test
        @DisplayName("没有有效记录（未发或已过期）时拒绝")
        void rejectsWhenNothingActive() {
            when(mapper.findLatestActive(SCENE, PHONE)).thenReturn(null);

            assertEquals("验证码不存在或已过期，请重新获取", service.check(SCENE, PHONE, "123456"));
            verify(mapper, never()).markUsed(anyLong());
        }

        @Test
        @DisplayName("对码通过，并立刻作废（一次性）")
        void acceptsCorrectCodeAndConsumesIt() {
            Issued issued = issue(SCENE, PHONE);
            when(mapper.findLatestActive(SCENE, PHONE)).thenReturn(issued.row);

            assertNull(service.check(SCENE, PHONE, issued.code), "正确的码必须通过");

            verify(mapper).markUsed(77L);
            verify(mapper, never()).incrementAttempts(anyLong());
        }

        @Test
        @DisplayName("用完一次之后再校验同一串码就不认了")
        void codeIsSingleUse() {
            Issued issued = issue(SCENE, PHONE);
            when(mapper.findLatestActive(SCENE, PHONE)).thenReturn(issued.row);
            assertNull(service.check(SCENE, PHONE, issued.code));

            // 作废之后 SQL 查不到那行了
            when(mapper.findLatestActive(SCENE, PHONE)).thenReturn(null);
            assertEquals("验证码不存在或已过期，请重新获取", service.check(SCENE, PHONE, issued.code));
        }

        @Test
        @DisplayName("错码被拒并累计失败次数")
        void rejectsWrongCodeAndCounts() {
            Issued issued = issue(SCENE, PHONE);
            when(mapper.findLatestActive(SCENE, PHONE)).thenReturn(issued.row);
            String wrong = issued.code.equals("000000") ? "111111" : "000000";

            assertEquals("验证码不正确，还可以再试 4 次", service.check(SCENE, PHONE, wrong));

            verify(mapper).incrementAttempts(77L);
            verify(mapper, never()).markUsed(anyLong());
        }

        @Test
        @DisplayName("失败次数到达上限后作废该码，不许再试")
        void exhaustsAttempts() {
            Issued issued = issue(SCENE, PHONE);
            issued.row.setAttempts(VerifyCodeService.MAX_ATTEMPTS);
            when(mapper.findLatestActive(SCENE, PHONE)).thenReturn(issued.row);

            assertEquals("验证码错误次数过多，请重新获取", service.check(SCENE, PHONE, "123456"));

            verify(mapper).markUsed(77L);
            verify(mapper, never()).incrementAttempts(anyLong());
        }

        @Test
        @DisplayName("最后一次机会也错时说「次数过多」，不再报还能再试 0 次")
        void lastAttemptWording() {
            Issued issued = issue(SCENE, PHONE);
            issued.row.setAttempts(VerifyCodeService.MAX_ATTEMPTS - 1);
            when(mapper.findLatestActive(SCENE, PHONE)).thenReturn(issued.row);
            String wrong = issued.code.equals("000000") ? "111111" : "000000";

            assertEquals("验证码错误次数过多，请重新获取", service.check(SCENE, PHONE, wrong));
        }

        @Test
        @DisplayName("校验时手机号同样先归一，带格式的写法也能对上")
        void normalizesTargetBeforeLookup() {
            Issued issued = issue(SCENE, PHONE);
            when(mapper.findLatestActive(SCENE, PHONE)).thenReturn(issued.row);

            assertNull(service.check(SCENE, "+86 138-0013-8000", issued.code));
        }

        @Test
        @DisplayName("别人的码用不到自己身上（scene 与 target 都参与哈希）")
        void hashBindsSceneAndTarget() {
            Issued issued = issue(SCENE, PHONE);
            // 把同一个码换到另一个 target 上查：查到的还是那行，但哈希对不上
            when(mapper.findLatestActive(SCENE, "+8613800138001")).thenReturn(issued.row);

            String message = service.check(SCENE, "+8613800138001", issued.code);

            assertNotNull(message, "换个目标就该判错");
            assertTrue(message.contains("验证码不正确"), "实际=" + message);
        }
    }

    // ==================== 哈希与归一 ====================

    @Nested
    @DisplayName("哈希与归一化")
    class HashingAndNormalization {

        @Test
        @DisplayName("哈希确定、定长、随输入变化")
        void hashProperties() {
            String a = service.hash(SCENE, PHONE, "123456");
            assertEquals(a, service.hash(SCENE, PHONE, "123456"), "同一输入必须得到同一哈希");
            assertEquals(64, a.length());

            assertNotEquals(a, service.hash(SCENE, PHONE, "123457"), "码不同哈希必须不同");
            assertNotEquals(a, service.hash(SCENE, "+8613800138001", "123456"), "目标不同哈希必须不同");
            assertNotEquals(a, service.hash(VerifyCodeService.SCENE_RESET_PASSWORD, PHONE, "123456"),
                    "场景不同哈希必须不同");
        }

        @Test
        @DisplayName("换了 pepper，历史哈希就对不上了")
        void pepperChangesHash() {
            String before = service.hash(SCENE, PHONE, "123456");

            ReflectionTestUtils.setField(service, "pepper", "ANOTHER-PEPPER");

            assertNotEquals(before, service.hash(SCENE, PHONE, "123456"),
                    "pepper 的作用就是让脱库拿不到有效验证码");
        }

        @Test
        @DisplayName("归一化只去空格横线括号，不改变数字本身")
        void normalize() {
            assertEquals("+8613812345678", VerifyCodeService.normalize("+86 138-1234-5678"));
            assertEquals("0101234", VerifyCodeService.normalize("(010) 1234"));
            assertEquals("13800000000", VerifyCodeService.normalize("138 0000 0000"));
            assertEquals(PHONE, VerifyCodeService.normalize(PHONE));
            assertNull(VerifyCodeService.normalize(null));
        }
    }
}
