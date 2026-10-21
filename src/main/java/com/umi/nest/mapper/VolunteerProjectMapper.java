package com.umi.nest.mapper;

import com.umi.nest.entity.VolunteerProject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 志愿服务项目（学工 P17，S21）数据访问。
 *
 * <p>状态机是 CAS（{@code WHERE id=? AND deleted=0 AND status=fromStatus}），影响行数 ≠ 1 即
 * 「状态已变化，请刷新后重试」，由服务层统一转 400（§7-1）。服务层必须先做显式前置校验，
 * 不能把「刚读到的状态」当成放行理由（否则 WHERE 恒真）。</p>
 *
 * <p>所有查询一律带 {@code deleted = 0}。scope 口径见 §6：{@code scopeUserId} 用
 * {@code t.user_id = #{scopeUserId}}；本表按项目维度组织，辅导员范围只用
 * {@code scopeCounselorId}（{@code submit_user_id} 名下学生）过滤由服务层决定是否传。</p>
 */
@Mapper
public interface VolunteerProjectMapper {

    int insert(VolunteerProject project);

    VolunteerProject selectById(@Param("id") Long id);

    /** 增量修改（动态 set）。CAS：{@code id + deleted=0 + status=fromStatus}。 */
    int update(@Param("p") VolunteerProject project, @Param("fromStatus") String fromStatus);

    int softDelete(@Param("id") Long id);

    List<VolunteerProject> selectList(@Param("scopeUserId") Long scopeUserId,
                                      @Param("scopeCounselorId") Long scopeCounselorId,
                                      @Param("status") String status,
                                      @Param("category") String category,
                                      @Param("organizerType") String organizerType,
                                      @Param("keyword") String keyword,
                                      @Param("offset") int offset,
                                      @Param("size") int size);

    long selectCount(@Param("scopeUserId") Long scopeUserId,
                     @Param("scopeCounselorId") Long scopeCounselorId,
                     @Param("status") String status,
                     @Param("category") String category,
                     @Param("organizerType") String organizerType,
                     @Param("keyword") String keyword);

    /** 立项审批 CAS：{@code pending → approved / rejected}（§7-1）。 */
    int reviewProject(@Param("id") Long id,
                      @Param("fromStatus") String fromStatus,
                      @Param("toStatus") String toStatus,
                      @Param("reviewUserId") Long reviewUserId,
                      @Param("rejectReason") String rejectReason);

    /** 前置校验用：按状态计数（{@code status} 可为空表示全部）。 */
    long countByStatus(@Param("status") String status);

    /** 统计用：按状态分组计数。 */
    List<Map<String, Object>> countGroupByStatus();
}
