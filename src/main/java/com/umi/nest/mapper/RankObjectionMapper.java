package com.umi.nest.mapper;

import com.umi.nest.entity.RankObjection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 综测公示异议（学工 P13）数据访问。
 *
 * <p>答复走 CAS（WHERE status='pending'），两个辅导员同时处理时只有一个答复能落库。</p>
 */
@Mapper
public interface RankObjectionMapper {

    int insert(RankObjection objection);

    RankObjection findById(@Param("id") Long id);

    /** 同批次同学只允许一条 pending。 */
    RankObjection findPending(@Param("batchId") Long batchId, @Param("studentId") Long studentId);

    List<RankObjection> listMine(@Param("studentId") Long studentId);

    /** 答复：CAS pending → accepted/rejected，写答复人、答复时间与答复正文。 */
    int handle(@Param("id") Long id,
               @Param("toStatus") String toStatus,
               @Param("answer") String answer,
               @Param("handledBy") Long handledBy);

    /** 管理端列表；scopeCounselorId 非空时只取该辅导员名下学生的异议。 */
    List<RankObjection> list(@Param("batchId") Long batchId,
                             @Param("status") String status,
                             @Param("scopeCounselorId") Long scopeCounselorId,
                             @Param("offset") int offset,
                             @Param("limit") int limit);

    int count(@Param("batchId") Long batchId,
              @Param("status") String status,
              @Param("scopeCounselorId") Long scopeCounselorId);

    List<Map<String, Object>> countGroupByStatus(@Param("batchId") Long batchId);
}
