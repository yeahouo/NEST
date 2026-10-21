package com.umi.nest.mapper;

import com.umi.nest.entity.PsycheAppointment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 学工 P14 心理健康 · 预约咨询数据访问（psyche_appointment）。
 *
 * <p>状态流转是 CAS（requested → confirmed → done / cancelled）；
 * scope 非空 = teacher 口径（名下学生或自己作为咨询师的预约），scope = null = admin。</p>
 */
@Mapper
public interface PsycheAppointmentMapper {

    int insert(PsycheAppointment appointment);

    PsycheAppointment findById(@Param("id") Long id);

    List<PsycheAppointment> list(@Param("status") String status,
                                 @Param("mode") String mode,
                                 @Param("keyword") String keyword,
                                 @Param("scope") Long scope,
                                 @Param("offset") int offset,
                                 @Param("limit") int limit);

    int count(@Param("status") String status,
              @Param("mode") String mode,
              @Param("keyword") String keyword,
              @Param("scope") Long scope);

    List<PsycheAppointment> listByStudent(@Param("studentId") Long studentId);

    /** requested/confirmed 的条数：>0 时不允许重复预约（400「你已有待处理的预约」）。 */
    int countPendingByStudent(@Param("studentId") Long studentId);

    /** requested → confirmed（CAS），写 confirmed_at / confirmed_by。 */
    int confirm(@Param("id") Long id,
                @Param("fromStatus") String fromStatus,
                @Param("confirmedAt") LocalDateTime confirmedAt,
                @Param("confirmedBy") Long confirmedBy);

    /** confirmed → done（CAS），写 finished_at。 */
    int complete(@Param("id") Long id,
                 @Param("fromStatus") String fromStatus,
                 @Param("finishedAt") LocalDateTime finishedAt);

    /** requested/confirmed → cancelled（CAS），写 cancel_reason。 */
    int cancel(@Param("id") Long id,
               @Param("fromStatus") String fromStatus,
               @Param("cancelReason") String cancelReason);

    /** [{status, cnt}]，只回聚合数；scope 非空 = teacher 口径（名下学生或自己是咨询师）。 */
    List<Map<String, Object>> countGroupByStatus(@Param("scope") Long scope);
}
