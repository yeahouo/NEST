package com.umi.nest.mapper;

import com.umi.nest.entity.HonorReview;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 评优审核流水（学工 P12）数据访问。
 *
 * <p>只追加、不修改、不删除：学生申诉时要能看到「哪一步、谁、什么时候、写了什么意见」，
 * 所以没有 update/delete 方法。</p>
 */
@Mapper
public interface HonorReviewMapper {

    int insert(HonorReview review);

    /** 一张申报单的全部审核意见（按时间正序，前端直接顺次展示）。 */
    List<HonorReview> listByApplication(@Param("applicationId") Long applicationId);

    /**
     * 批量取多张申报单的审核意见（「我的申报」列表用）。
     *
     * <p>避免 N+1：列表里每条申报单各查一次，20 条就是 21 次往返。
     * 调用方必须保证 ids 非空（IN () 是语法错误）。</p>
     */
    List<HonorReview> listByApplications(@Param("ids") List<Long> ids);
}
