package com.umi.nest.mapper;

import com.umi.nest.entity.TeacherInvitationCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 教师邀请码 Mapper 接口
 */
@Mapper
public interface TeacherInvitationCodeMapper {

    /**
     * 根据邀请码查询（有效且未删除）
     */
    TeacherInvitationCode findByCode(@Param("code") String code);

    /**
     * 根据ID查询
     */
    TeacherInvitationCode findById(@Param("id") Long id);

    /**
     * 查询所有邀请码列表
     */
    List<TeacherInvitationCode> findAll();

    /**
     * 插入邀请码
     */
    int insert(TeacherInvitationCode code);

    /**
     * 更新邀请码
     */
    int updateById(TeacherInvitationCode code);

    /**
     * 增加使用次数
     */
    int incrementUsageCount(@Param("id") Long id);

    /**
     * 逻辑删除
     */
    int deleteById(@Param("id") Long id);

    /**
     * 切换状态
     */
    int toggleStatus(@Param("id") Long id, @Param("status") Integer status);
}
