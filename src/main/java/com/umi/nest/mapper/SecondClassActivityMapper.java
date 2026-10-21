package com.umi.nest.mapper;

import com.umi.nest.entity.SecondClassActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 第二课堂活动（学工 P18，S26）数据访问。
 *
 * <p>学生侧只看得见 {@code status='published'} 且未删除的活动；草稿与关闭的活动只在管理端可见。
 * 状态流转全部 CAS：{@code updateStatus} 带 {@code fromStatus}，影响行数 0 表示状态已被别人改过。</p>
 */
@Mapper
public interface SecondClassActivityMapper {

    /** 学生可见：published 且未删除。 */
    List<SecondClassActivity> listPublished(@Param("categoryId") Long categoryId,
                                            @Param("keyword") String keyword,
                                            @Param("offset") int offset,
                                            @Param("size") int size);

    long countPublished(@Param("categoryId") Long categoryId,
                        @Param("keyword") String keyword);

    /** 管理端：可按状态过滤（status 为空 = 全部）。 */
    List<SecondClassActivity> listForAdmin(@Param("status") String status,
                                           @Param("categoryId") Long categoryId,
                                           @Param("keyword") String keyword,
                                           @Param("offset") int offset,
                                           @Param("size") int size);

    long countForAdmin(@Param("status") String status,
                       @Param("categoryId") Long categoryId,
                       @Param("keyword") String keyword);

    SecondClassActivity selectById(@Param("id") Long id);

    int insert(SecondClassActivity activity);

    /** 增量更新（动态 set）；CAS：{@code id + deleted=0 + status=fromStatus}。 */
    int update(@Param("a") SecondClassActivity activity, @Param("fromStatus") String fromStatus);

    /** 纯状态流转（发布/关闭）。 */
    int updateStatus(@Param("id") Long id,
                     @Param("fromStatus") String fromStatus,
                     @Param("toStatus") String toStatus);

    /** 活动下申报条数（关闭/删除前置校验用）。 */
    long countRecordsByActivity(@Param("activityId") Long activityId);
}
