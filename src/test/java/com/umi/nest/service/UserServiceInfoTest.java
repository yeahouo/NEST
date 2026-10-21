package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.entity.User;
import com.umi.nest.mapper.UserMapper;
import com.umi.nest.wechat.WeChatOAuthClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/auth/me} 的字段契约。
 *
 * <p>这个测试存在的理由是一个真实发生过的错误：设置页判 {@code userData.phoneVerified === 1}
 * 与 {@code userData.wechatOpenid}，而 {@code buildUserInfoMap} 两个字段都不发。
 * 结果是「已验证的手机号不显示徽标、已绑定微信的用户看不到解绑按钮」，
 * 而且<b>不报任何错</b>——判断恒为假，页面就安静地少显示一样东西。
 *
 * <p>所以这里既断言「该有的字段都在」，也断言「不该给的字段没给」：
 * 前端只需要知道绑没绑微信，{@code wechatOpenid} 没有理由发到浏览器。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService.getUserInfo - 字段契约")
class UserServiceInfoTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private WeChatOAuthClient weChatOAuthClient;

    @InjectMocks
    private UserService userService;

    private User activeUser() {
        User u = new User();
        u.setId(7L);
        u.setUsername("chendw");
        u.setRole("student");
        u.setStatus(1);
        u.setDeleted(0);
        u.setPhone("13800000006");
        u.setEmail("chendw@nest.cn");
        u.setPhoneVerified(0);
        u.setEmailVerified(0);
        return u;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetch(User user) {
        when(userMapper.findById(7L)).thenReturn(user);
        Result<?> r = userService.getUserInfo(7L);
        assertEquals(200, r.getCode().intValue(), r.getMessage());
        return (Map<String, Object>) r.getData();
    }

    @Nested
    @DisplayName("设置页要用的字段必须真的在响应里")
    class FieldsArePresent {

        @Test
        @DisplayName("手机号 / 邮箱的验证标记原样透出")
        void verifiedFlags() {
            User u = activeUser();
            u.setPhoneVerified(1);
            u.setEmailVerified(null); // 从没验过就是 NULL，不能悄悄变成 0 以外的别的东西

            Map<String, Object> data = fetch(u);

            assertEquals(1, data.get("phoneVerified"));
            assertNull(data.get("emailVerified"));
        }

        @Test
        @DisplayName("未绑定微信：wechatBound=false")
        void notBound() {
            when(weChatOAuthClient.configured()).thenReturn(false);
            when(weChatOAuthClient.unavailableReason()).thenReturn("尚未接入微信开放平台：需要配置 nest.wechat.appid 与 nest.wechat.secret 才能绑定微信。");

            Map<String, Object> data = fetch(activeUser());

            assertEquals(false, data.get("wechatBound"));
            assertEquals(false, data.get("wechatAvailable"));
            assertTrue(((String) data.get("wechatUnavailableReason")).contains("尚未接入微信开放平台"));
            assertNull(data.get("wechatAppid"));
        }

        @Test
        @DisplayName("已绑定微信：wechatBound=true，并带上昵称")
        void bound() {
            when(weChatOAuthClient.configured()).thenReturn(true);
            when(weChatOAuthClient.appid()).thenReturn("wx_real_appid");

            User u = activeUser();
            u.setWechatOpenid("o_real_openid");
            u.setWechatNickname("王小明");

            Map<String, Object> data = fetch(u);

            assertEquals(true, data.get("wechatBound"));
            assertEquals("王小明", data.get("wechatNickname"));
            assertEquals(true, data.get("wechatAvailable"));
            assertNull(data.get("wechatUnavailableReason"), "能绑的时候不该有「不能绑的原因」");
            assertEquals("wx_real_appid", data.get("wechatAppid"));
        }

        @Test
        @DisplayName("绝不把 openid 发给浏览器")
        void neverLeaksOpenid() {
            when(weChatOAuthClient.configured()).thenReturn(true);

            User u = activeUser();
            u.setWechatOpenid("o_secret_openid");

            Map<String, Object> data = fetch(u);

            assertFalse(data.containsKey("wechatOpenid"), "前端只要知道绑没绑：" + data.keySet());
            // 连值都不该出现在任何字段里
            assertTrue(data.values().stream().noneMatch(v -> "o_secret_openid".equals(v)),
                    "openid 出现在响应里了：" + data);
        }
    }

    @Nested
    @DisplayName("取不到用户时不撒谎")
    class Fails {

        @Test
        @DisplayName("用户不存在")
        void noSuchUser() {
            when(userMapper.findById(7L)).thenReturn(null);
            Result<?> r = userService.getUserInfo(7L);
            assertEquals(400, r.getCode().intValue());
            assertEquals("用户不存在", r.getMessage());
        }

        @Test
        @DisplayName("账户被停用 / 已删除")
        void abnormalAccount() {
            User disabled = activeUser();
            disabled.setStatus(0);
            when(userMapper.findById(7L)).thenReturn(disabled);

            Result<?> r = userService.getUserInfo(7L);

            assertEquals(400, r.getCode().intValue());
            assertEquals("账户异常", r.getMessage());
            verify(weChatOAuthClient, never()).configured();
        }
    }
}
