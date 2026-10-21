package com.umi.nest.mapper;

import com.umi.nest.entity.VolunteerRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 志愿服务时长记录（学工 P17，S21）数据访问。
 *
 * <p>累计时长口径（§6、§10）：**只有 {@code status='approved'} 且 {@code deleted=0}** 的记录计入。
 * {@code sumHoursByUser} 的 {@code hourType} 可为空表示信用+荣誉合计；非空时只算该类型
 * （信用/荣誉时数分别用于 S21 第三十条的星级推算）。</p>
 *
 * <p>审核是 CAS（§7-1），{@code approved}/{@code rejected} 为终态不可重复审核（§7-2）。
 * 本表属学生范围表（§6），scope 过滤同 {@code VolunteerEnrollmentMapper}。</p>
 */
@Mapper
public interface VolunteerRecordMapper {

    int insert(VolunteerRecord record);

    /** 批量录入用（同一事务内逐条插入，service 保证 {@code serviceDate} 等一致性）。 */
    int insertBatch(@Param("records") List<VolunteerRecord> records);

    VolunteerRecord selectById(@Param("id") Long id);

    /** 增量修改（动态 set）。CAS：{@code id + deleted=0 + status=fromStatus}。 */
    int update(@Param("r") VolunteerRecord record, @Param("fromStatus") String fromStatus);

    int softDelete(@Param("id") Long id);

    List<VolunteerRecord> selectList(@Param("scopeUserId") Long scopeUserId,
                                     @Param("scopeCounselorId") Long scopeCounselorId,
                                     @Param("projectId") Long projectId,
                                     @Param("status") String status,
                                     @Param("hourType") String hourType,
                                     @Param("source") String source,
                                     @Param("startDate") LocalDate startDate,
                                     @Param("endDate") LocalDate endDate,
                                     @Param("keyword") String keyword,
                                     @Param("offset") int offset,
                                     @Param("size") int size);

    long selectCount(@Param("scopeUserId") Long scopeUserId,
                     @Param("scopeCounselorId") Long scopeCounselorId,
                     @Param("projectId") Long projectId,
                     @Param("status") String status,
                     @Param("hourType") String hourType,
                     @Param("source") String source,
                     @Param("startDate") LocalDate startDate,
                     @Param("endDate") LocalDate endDate,
                     @Param("keyword") String keyword);

    /** 审核 CAS：{@code pending → approved / rejected}（§7-1）。 */
    int reviewRecord(@Param("id") Long id,
                     @Param("fromStatus") String fromStatus,
                     @Param("toStatus") String toStatus,
                     @Param("reviewUserId") Long reviewUserId,
                     @Param("rejectReason") String rejectReason);

    /**
     * 累计时长（累计/星级推算的**唯一**数据源，S21 第三十条）。
     *
     * <p>只算 {@code status='approved'} 且未删除；{@code hourType} 为空表示合计，
     * 非空只算该类型；{@code startDate}/{@code endDate} 为空表示不限（学年口径由服务层算好区间传入）。</p>
     */
    BigDecimal sumHoursByUser(@Param("userId") Long userId,
                              @Param("hourType") String hourType,
                              @Param("startDate") LocalDate startDate,
                              @Param("endDate") LocalDate endDate);

    /** 某天已认定时长（S21 第二十二条「每天 8 小时上限」提示用，不硬拒）。 */
    BigDecimal sumHoursByUserAndDate(@Param("userId") Long userId,
                                     @Param("serviceDate") LocalDate serviceDate);

    /** 某天已有记录条数（补录/更正前置校验用）。 */
    long countByUserAndDate(@Param("userId") Long userId,
                            @Param("serviceDate") LocalDate serviceDate);

    /** 前置校验用：按状态计数。 */
    long countByStatus(@Param("status") String status);

    /** 统计用：按状态分组计数（{@code userId} 可为空）。 */
    List<Map<String, Object>> countGroupByStatus(@Param("userId") Long userId);

    /** 统计用：按类别/来源等维度汇总（导出与统计页用）。 */
    List<Map<String, Object>> countGroupBySource(@Param("userId") Long userId);
}
