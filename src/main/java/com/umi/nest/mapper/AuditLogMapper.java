package com.umi.nest.mapper;

import com.umi.nest.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 审计日志读写（SQL 见 resources/mapper/AuditLogMapper.xml）。
 */
@Mapper
public interface AuditLogMapper {

    int insert(AuditLog log);

    List<AuditLog> query(@Param("eventType") String eventType,
                         @Param("username") String username,
                         @Param("success") Boolean success,
                         @Param("from") LocalDateTime from,
                         @Param("to") LocalDateTime to,
                         @Param("offset") int offset,
                         @Param("limit") int limit);

    long count(@Param("eventType") String eventType,
               @Param("username") String username,
               @Param("success") Boolean success,
               @Param("from") LocalDateTime from,
               @Param("to") LocalDateTime to);

    /** 按事件类型汇总（管理端概览用）。 */
    List<Map<String, Object>> countByType(@Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    /** 留存期清理：删除 {@code before} 之前的事件。 */
    int deleteBefore(@Param("before") LocalDateTime before);
}
