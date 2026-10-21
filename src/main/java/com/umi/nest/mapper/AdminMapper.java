package com.umi.nest.mapper;

import com.umi.nest.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 管理员 Mapper 接口
 */
@Mapper
public interface AdminMapper {

    // ==================== 仪表盘统计 ====================

    /**
     * 统计用户总数
     */
    int countUsers();

    /**
     * 统计学生数量
     */
    int countStudents();

    /**
     * 统计教师数量
     */
    int countTeachers();

    /**
     * 统计今日新增用户
     */
    int countTodayNewUsers();

    /**
     * 统计活跃用户（7天内登录）
     */
    int countActiveUsers();

    // ==================== 用户管理 ====================

    /**
     * 分页查询用户列表
     */
    List<User> findUsers(@Param("role") String role,
                         @Param("status") Integer status,
                         @Param("keyword") String keyword,
                         @Param("offset") Integer offset,
                         @Param("limit") Integer limit);

    /**
     * 统计用户数量（用于分页）
     */
    int countUsersByFilter(@Param("role") String role,
                          @Param("status") Integer status,
                          @Param("keyword") String keyword);

    /**
     * 更新用户角色
     */
    int updateUserRole(@Param("id") Long id, @Param("role") String role);

    /**
     * 更新用户状态
     */
    int updateUserStatus(@Param("id") Long id, @Param("status") Integer status);

    /**
     * 管理员更新用户信息
     */
    int adminUpdateUser(User user);

    /**
     * 查询所有教师（辅导员分派下拉使用）
     */
    List<User> findTeachers();

    /**
     * 逻辑删除用户
     */
    int deleteUser(@Param("id") Long id);

    /**
     * 管理员创建用户
     */
    int adminInsertUser(User user);
}
