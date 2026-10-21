package com.umi.nest.service;

import com.umi.nest.common.Result;
import com.umi.nest.dto.BindWechatDTO;
import com.umi.nest.entity.User;
import com.umi.nest.mapper.UserMapper;
import com.umi.nest.wechat.WeChatOAuthClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 微信绑定服务的单元测试。
 *
 * <p>守的是一条曾经真实存在的过错：{@code bindWechat} 收到请求后写
 * {@code "mock_openid_" + System.currentTimeMillis()}，昵称写死「微信用户」，
 * 然后回答「微信绑定成功」。那不是功能没做，那是<b>接口在撒谎</b>——
 * 而且写进去的假 openid 会被「该微信已绑定其他账号」这条校验当真凭据比对。
 *
 * <p>所以每个拒绝用例都额外断言「数据库没被写」；
 * 成功用例则断言写进去的 openid 恰好是微信返回的那个。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService.bindWechat - 微信绑定")
class UserServiceWechatTest {

    private static final Long USER_ID = 42L;

    @Mock
    private UserMapper userMapper;

    @Mock
    private WeChatOAuthClient weChatOAuthClient;

    @InjectMocks
    private UserService userService;

    private User user(Long id, String openid) {
        User u = new User();
        u.setId(id);
        u.setUsername("student" + id);
        u.setWechatOpenid(openid);
        return u;
    }

    private BindWechatDTO dto(String code) {
        BindWechatDTO d = new BindWechatDTO();
        d.setCode(code);
        return d;
    }

    @Nested
    @DisplayName("不能绑的时候，绝不写假数据")
    class Refuses {

        @Test
        @DisplayName("未接微信开放平台：拒绝，且一行数据都不写")
        void notConfigured() {
            when(userMapper.findById(USER_ID)).thenReturn(user(USER_ID, null));
            when(weChatOAuthClient.configured()).thenReturn(false);
            when(weChatOAuthClient.unavailableReason())
                    .thenReturn("尚未接入微信开放平台：需要配置 nest.wechat.appid 与 nest.wechat.secret 才能绑定微信。");

            Result<?> r = userService.bindWechat(USER_ID, dto("any-code"));

            assertEquals(400, r.getCode().intValue());
            assertTrue(r.getMessage().contains("尚未接入微信开放平台"), r.getMessage());
            // 关键：既没查过、也没写过
            verify(userMapper, never()).bindWechat(any(), anyString(), any());
            verify(userMapper, never()).findByWechatOpenid(anyString());
        }

        @Test
        @DisplayName("凭据齐全但没带 code：拒绝，不写")
        void missingCode() {
            when(userMapper.findById(USER_ID)).thenReturn(user(USER_ID, null));
            when(weChatOAuthClient.configured()).thenReturn(true);

            Result<?> r = userService.bindWechat(USER_ID, dto(null));

            assertEquals(400, r.getCode().intValue());
            assertTrue(r.getMessage().contains("缺少微信授权码"), r.getMessage());
            verify(userMapper, never()).bindWechat(any(), anyString(), any());
        }

        @Test
        @DisplayName("微信说 code 无效：拒绝，不写，原话转告")
        void exchangeFailed() {
            when(userMapper.findById(USER_ID)).thenReturn(user(USER_ID, null));
            when(weChatOAuthClient.configured()).thenReturn(true);
            when(weChatOAuthClient.exchange("bad-code"))
                    .thenReturn(new WeChatOAuthClient.Exchange(null, null, "授权码无效或已过期（errcode 40029, invalid code）"));

            Result<?> r = userService.bindWechat(USER_ID, dto("bad-code"));

            assertEquals(400, r.getCode().intValue());
            assertTrue(r.getMessage().contains("授权码无效或已过期"), r.getMessage());
            verify(userMapper, never()).bindWechat(any(), anyString(), any());
        }

        @Test
        @DisplayName("这个 openid 已经属于别人：拒绝，不写")
        void openidTakenByAnotherAccount() {
            when(userMapper.findById(USER_ID)).thenReturn(user(USER_ID, null));
            when(weChatOAuthClient.configured()).thenReturn(true);
            when(weChatOAuthClient.exchange("c")).thenReturn(new WeChatOAuthClient.Exchange("o_taken", "别人", null));
            when(userMapper.findByWechatOpenid("o_taken")).thenReturn(user(99L, "o_taken"));

            Result<?> r = userService.bindWechat(USER_ID, dto("c"));

            assertEquals(400, r.getCode().intValue());
            assertEquals("该微信已绑定其他账号", r.getMessage());
            verify(userMapper, never()).bindWechat(any(), anyString(), any());
        }

        @Test
        @DisplayName("已经绑过：先解绑再说")
        void alreadyBound() {
            when(userMapper.findById(USER_ID)).thenReturn(user(USER_ID, "o_existing"));

            Result<?> r = userService.bindWechat(USER_ID, dto("c"));

            assertEquals(400, r.getCode().intValue());
            assertEquals("已绑定微信，请先解绑", r.getMessage());
            verify(userMapper, never()).bindWechat(any(), anyString(), any());
        }

        @Test
        @DisplayName("用户不存在")
        void noSuchUser() {
            when(userMapper.findById(USER_ID)).thenReturn(null);

            Result<?> r = userService.bindWechat(USER_ID, dto("c"));

            assertEquals(400, r.getCode().intValue());
            assertEquals("用户不存在", r.getMessage());
            verify(userMapper, never()).bindWechat(any(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("能绑的时候，写的是真 openid")
    class Binds {

        @Test
        @DisplayName("成功：写进库的必须是微信返回的那个 openid")
        void writesRealOpenid() {
            when(userMapper.findById(USER_ID)).thenReturn(user(USER_ID, null));
            when(weChatOAuthClient.configured()).thenReturn(true);
            when(weChatOAuthClient.exchange("good-code"))
                    .thenReturn(new WeChatOAuthClient.Exchange("o_real_openid_1234", "王小明", null));
            when(userMapper.findByWechatOpenid("o_real_openid_1234")).thenReturn(null);
            when(userMapper.bindWechat(USER_ID, "o_real_openid_1234", "王小明")).thenReturn(1);

            Result<?> r = userService.bindWechat(USER_ID, dto("good-code"));

            assertEquals(200, r.getCode().intValue());
            // 项目惯例：Result.success(String) 把文案放进 data，message 恒为 "success"
            assertEquals("微信绑定成功", r.getData());

            ArgumentCaptor<String> openid = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> nickname = ArgumentCaptor.forClass(String.class);
            verify(userMapper).bindWechat(any(), openid.capture(), nickname.capture());
            assertEquals("o_real_openid_1234", openid.getValue());
            assertFalse(openid.getValue().startsWith("mock_openid_"),
                    "再也不能出现编出来的 openid：" + openid.getValue());
            assertEquals("王小明", nickname.getValue());
        }

        @Test
        @DisplayName("没拿到昵称也照绑，昵称留空而不是编一个")
        void nullNicknameStaysNull() {
            when(userMapper.findById(USER_ID)).thenReturn(user(USER_ID, null));
            when(weChatOAuthClient.configured()).thenReturn(true);
            when(weChatOAuthClient.exchange("c")).thenReturn(new WeChatOAuthClient.Exchange("o_real", null, null));
            when(userMapper.findByWechatOpenid("o_real")).thenReturn(null);
            when(userMapper.bindWechat(USER_ID, "o_real", null)).thenReturn(1);

            Result<?> r = userService.bindWechat(USER_ID, dto("c"));

            assertEquals(200, r.getCode().intValue());

            ArgumentCaptor<String> nickname = ArgumentCaptor.forClass(String.class);
            verify(userMapper).bindWechat(any(), anyString(), nickname.capture());
            assertNull(nickname.getValue(), "拿不到昵称就存 null，不要编「微信用户」");
        }

        @Test
        @DisplayName("同一个人重复绑同一个 openid 不算「被别人占了」")
        void sameUserRebindIsNotTaken() {
            when(userMapper.findById(USER_ID)).thenReturn(user(USER_ID, null));
            when(weChatOAuthClient.configured()).thenReturn(true);
            when(weChatOAuthClient.exchange("c")).thenReturn(new WeChatOAuthClient.Exchange("o_mine", "我", null));
            when(userMapper.findByWechatOpenid("o_mine")).thenReturn(user(USER_ID, "o_mine"));
            when(userMapper.bindWechat(USER_ID, "o_mine", "我")).thenReturn(1);

            Result<?> r = userService.bindWechat(USER_ID, dto("c"));

            assertEquals(200, r.getCode().intValue());
        }

        @Test
        @DisplayName("写库失败要如实报失败")
        void writeFails() {
            when(userMapper.findById(USER_ID)).thenReturn(user(USER_ID, null));
            when(weChatOAuthClient.configured()).thenReturn(true);
            when(weChatOAuthClient.exchange("c")).thenReturn(new WeChatOAuthClient.Exchange("o_real", "王小明", null));
            when(userMapper.findByWechatOpenid("o_real")).thenReturn(null);
            when(userMapper.bindWechat(USER_ID, "o_real", "王小明")).thenReturn(0);

            Result<?> r = userService.bindWechat(USER_ID, dto("c"));

            assertEquals(400, r.getCode().intValue());
            assertEquals("微信绑定失败", r.getMessage());
        }
    }
}
