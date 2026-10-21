package com.umi.nest.mapper;

import com.umi.nest.entity.PsycheReferral;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 学工 P14 心理健康 · 转介登记与跟踪数据访问（psyche_referral）。
 *
 * <p>scope 非空 = teacher 口径（只看名下学生的转介）；状态流转是 CAS。</p>
 */
@Mapper
public interface PsycheReferralMapper {

    int insert(PsycheReferral referral);

    PsycheReferral findById(@Param("id") Long id);

    List<PsycheReferral> list(@Param("target") String target,
                              @Param("status") String status,
                              @Param("scope") Long scope,
                              @Param("offset") int offset,
                              @Param("limit") int limit);

    int count(@Param("target") String target,
              @Param("status") String status,
              @Param("scope") Long scope);

    int update(@Param("referral") PsycheReferral referral, @Param("fromStatus") String fromStatus);
}
