package com.umi.nest.mapper;

import com.umi.nest.entity.PartyPublicity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 公示留痕（学工 P16）数据访问。
 *
 * <p>状态流转 CAS：<b>publish 只接受 {draft}、close 只接受 {publicizing}</b>（P16 硬口径）。
 * 服务层先显式前置校验，再 CAS；<b>已结束再 close 必须 400</b>，不能靠幂等 200
 * 掩盖状态机退化（P15 E2E 缺陷 D2）。</p>
 */
@Mapper
public interface PartyPublicityMapper {

    int insert(PartyPublicity publicity);

    PartyPublicity findById(@Param("id") Long id);

    /** 只按 {draft} / {publicizing} 放行的状态流转查询，供前置校验使用。 */
    List<PartyPublicity> list(@Param("userId") Long userId,
                              @Param("counselorId") Long counselorId,
                              @Param("nodeType") String nodeType,
                              @Param("status") String status,
                              @Param("onlyOpen") boolean onlyOpen,
                              @Param("offset") int offset,
                              @Param("limit") int limit);

    long count(@Param("userId") Long userId,
               @Param("counselorId") Long counselorId,
               @Param("nodeType") String nodeType,
               @Param("status") String status,
               @Param("onlyOpen") boolean onlyOpen);

    /** 增量修改（CAS on status）。 */
    int update(@Param("publicity") PartyPublicity publicity, @Param("fromStatus") String fromStatus);

    /** draft → publicizing（CAS on status）。 */
    int publish(@Param("id") Long id,
                @Param("fromStatus") String fromStatus,
                @Param("publishedAt") LocalDateTime publishedAt);

    /** publicizing → closed（CAS on status）。 */
    int close(@Param("id") Long id,
              @Param("fromStatus") String fromStatus,
              @Param("result") String result,
              @Param("closedAt") LocalDateTime closedAt);

    /** 状态分布（统计用）。 */
    List<Map<String, Object>> countGroupByStatus();

    /** 节点类型分布（统计用）。 */
    List<Map<String, Object>> countGroupByNodeType();
}
