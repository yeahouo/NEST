package com.umi.nest.mapper;

import com.umi.nest.entity.CommunityViolation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 社区违规台账（学工 P15）数据访问。
 *
 * <p>状态列为 handling（pending/notified/closed），CAS 写成
 * WHERE id=? AND deleted=0 AND handling=#{fromStatus}。
 * rule_ref（依据条款）是必填字段，由服务层校验非空。</p>
 */
@Mapper
public interface CommunityViolationMapper {

    int insert(CommunityViolation violation);

    CommunityViolation findById(@Param("id") Long id);

    List<CommunityViolation> list(@Param("studentId") Long studentId,
                                  @Param("building") String building,
                                  @Param("type") String type,
                                  @Param("advice") String advice,
                                  @Param("handling") String handling,
                                  @Param("keyword") String keyword,
                                  @Param("counselorId") Long counselorId,
                                  @Param("startDate") LocalDate startDate,
                                  @Param("endDate") LocalDate endDate,
                                  @Param("offset") int offset,
                                  @Param("limit") int limit);

    /** 列表总数（过滤条件与 {@link #list} 一致）。 */
    int countList(@Param("studentId") Long studentId,
                  @Param("building") String building,
                  @Param("type") String type,
                  @Param("advice") String advice,
                  @Param("handling") String handling,
                  @Param("keyword") String keyword,
                  @Param("counselorId") Long counselorId,
                  @Param("startDate") LocalDate startDate,
                  @Param("endDate") LocalDate endDate);

    /** 增量修改（CAS 在 handling 上）。 */
    int update(@Param("violation") CommunityViolation violation, @Param("fromStatus") String fromStatus);

    /** 处理推进：handling → 新状态，写处理说明与处理人。 */
    int handle(@Param("id") Long id,
               @Param("fromStatus") String fromStatus,
               @Param("handling") String handling,
               @Param("resultNote") String resultNote,
               @Param("handledBy") Long handledBy,
               @Param("handledAt") LocalDateTime handledAt);

    List<Map<String, Object>> countGroupByType(@Param("counselorId") Long counselorId,
                                              @Param("startDate") LocalDate startDate,
                                              @Param("endDate") LocalDate endDate);

    List<Map<String, Object>> countGroupByAdvice(@Param("counselorId") Long counselorId,
                                                @Param("startDate") LocalDate startDate,
                                                @Param("endDate") LocalDate endDate);

    Map<String, Object> summary(@Param("counselorId") Long counselorId,
                                @Param("startDate") LocalDate startDate,
                                @Param("endDate") LocalDate endDate);
}
