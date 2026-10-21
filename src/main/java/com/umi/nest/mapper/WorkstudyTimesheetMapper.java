package com.umi.nest.mapper;

import com.umi.nest.entity.WorkstudyTimesheet;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 勤工助学工时 mapper（学工 P10）。动态 SQL 全在 WorkstudyTimesheetMapper.xml。
 */
@Mapper
public interface WorkstudyTimesheetMapper {

    int insert(WorkstudyTimesheet record);

    WorkstudyTimesheet findById(@Param("id") Long id);

    /** 学生端「我的工时」；workMonth 形如 2026-10 */
    List<WorkstudyTimesheet> listMine(@Param("studentId") Long studentId,
                                      @Param("workMonth") String workMonth,
                                      @Param("limit") int limit,
                                      @Param("offset") int offset);

    int countMine(@Param("studentId") Long studentId,
                  @Param("workMonth") String workMonth);

    /** 用工单位/资助中心工时列表 */
    List<WorkstudyTimesheet> listManage(@Param("workMonth") String workMonth,
                                        @Param("postId") Long postId,
                                        @Param("studentId") Long studentId,
                                        @Param("unitUserId") Long unitUserId,
                                        @Param("status") String status,
                                        @Param("limit") int limit,
                                        @Param("offset") int offset);

    int countManage(@Param("workMonth") String workMonth,
                    @Param("postId") Long postId,
                    @Param("studentId") Long studentId,
                    @Param("unitUserId") Long unitUserId,
                    @Param("status") String status);

    /** submitted → confirmed（fromStatus 作为乐观条件） */
    int updateStatus(@Param("id") Long id,
                     @Param("fromStatus") String fromStatus,
                     @Param("toStatus") String toStatus);

    /** 某学生某月某状态的工时合计（null = 全部状态） */
    BigDecimal sumHours(@Param("studentId") Long studentId,
                        @Param("workMonth") String workMonth,
                        @Param("status") String status);

    /** 用于单周 > 8 小时提示：统计某学生某岗位在 [start,end] 区间的已记录工时 */
    BigDecimal sumHoursByDateRange(@Param("studentId") Long studentId,
                                   @Param("postId") Long postId,
                                   @Param("startDate") LocalDate startDate,
                                   @Param("endDate") LocalDate endDate);

    /** 按状态汇总工时：每行 {status, hours}，供概览统计 */
    List<Map<String, Object>> sumGroupByStatus(@Param("workMonth") String workMonth,
                                               @Param("unitUserId") Long unitUserId);

    /** 酬金汇总原料：某月已确认工时按 (学生,岗位,申请) 分组，每行 {student_id,post_id,application_id,hours} */
    List<Map<String, Object>> listConfirmedGroups(@Param("workMonth") String workMonth);
}
