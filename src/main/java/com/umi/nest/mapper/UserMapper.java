package com.umi.nest.mapper;

import com.umi.nest.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户 Mapper 接口
 */
@Mapper
public interface UserMapper {

    /**
     * 根据用户名查询
     */
    User findByUsername(@Param("username") String username);

    /**
     * 根据邮箱查询
     */
    User findByEmail(@Param("email") String email);

    /**
     * 根据手机号查询
     */
    User findByPhone(@Param("phone") String phone);

    /**
     * 根据用户名或邮箱查询（用于登录）
     */
    User findByUsernameOrEmail(@Param("account") String account);

    /**
     * 插入用户
     */
    int insert(User user);

    /**
     * 更新最后登录时间
     */
    int updateLastLoginTime(@Param("id") Long id);

    /**
     * 根据ID查询用户
     */
    User findById(@Param("id") Long id);

    /**
     * 根据手机号更新密码
     */
    int updatePasswordByPhone(@Param("phone") String phone, @Param("passwordHash") String passwordHash);

    // ==================== 设置模块新增方法 ====================

    /**
     * 通用更新用户信息（用于个人资料修改）
     */
    int updateById(User user);

    /**
     * 通过用户ID更新密码
     */
    int updatePasswordById(@Param("id") Long id, @Param("passwordHash") String passwordHash);

    /**
     * 更新手机号
     */
    int updatePhone(@Param("id") Long id, @Param("phone") String phone);

    /**
     * 更新邮箱
     */
    int updateEmail(@Param("id") Long id, @Param("email") String email);

    /**
     * 绑定微信
     */
    int bindWechat(@Param("id") Long id,
                   @Param("openid") String openid,
                   @Param("nickname") String nickname);

    /**
     * 解绑微信
     */
    int unbindWechat(@Param("id") Long id);

    /**
     * 更新通知设置
     */
    int updateNotificationSettings(User user);

    /**
     * 根据微信OpenID查询用户
     */
    User findByWechatOpenid(@Param("openid") String openid);

    /**
     * 根据学号查询用户
     */
    User findByStudentId(@Param("studentId") String studentId);

    /**
     * 记录一次登录失败：累加失败次数，必要时写入锁定截止时间（等保：登录失败锁定）。
     *
     * @param id          用户 id
     * @param count       失败后的累计次数
     * @param lockedUntil 达到阈值时的锁定截止时间；未达阈值传 null（等于解锁）
     */
    int registerLoginFailure(@Param("id") Long id,
                             @Param("count") int count,
                             @Param("lockedUntil") java.time.LocalDateTime lockedUntil);

    /**
     * 登录成功后清零失败计数并解除锁定。
     */
    int resetLoginFailure(@Param("id") Long id);
}
