package com.umi.nest.mapper;

import com.umi.nest.entity.VerificationCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 验证码数据访问。
 *
 * <p>刻意只暴露服务层真正需要的六个动作，没有通用 CRUD —— 这张表的语义太窄，
 * 一个 {@code updateById} 只会被误用。
 */
@Mapper
public interface VerificationCodeMapper {

    int insert(VerificationCode entity);

    /**
     * 取该 scene+target 下「未使用且未过期」的最新一条。没有则返回 null。
     * 这是校验时唯一的热路径查询，走 idx_scene_target。
     */
    VerificationCode findLatestActive(@Param("scene") String scene, @Param("target") String target);

    int incrementAttempts(@Param("id") Long id);

    int markUsed(@Param("id") Long id);

    /** 把该 scene+target 下所有未使用的码一次性作废（重新发送时调用）。 */
    int invalidateActive(@Param("scene") String scene, @Param("target") String target);

    /** 该 scene+target 自 since 起发过多少条（限流用）。 */
    int countSince(@Param("scene") String scene,
                   @Param("target") String target,
                   @Param("since") LocalDateTime since);

    /** 清掉 before 之前的记录。这张表天然短生命周期，不需要归档。 */
    int deleteCreatedBefore(@Param("before") LocalDateTime before);
}
