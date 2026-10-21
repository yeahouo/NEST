package com.umi.nest.mapper;

import com.umi.nest.entity.WorkstudyPayroll;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 勤工助学月酬金 mapper（学工 P10）。动态 SQL 全在 WorkstudyPayrollMapper.xml。
 */
@Mapper
public interface WorkstudyPayrollMapper {

    WorkstudyPayroll findById(@Param("id") Long id);

    /**
     * 幂等汇总：唯一键 (student_id, post_id, work_month) 冲突时只更新工时/金额/考核，
     * 不新增重复行（办法第二十四条：资助中心按学生+岗位+月份汇总月酬金发放表）。
     */
    int upsert(WorkstudyPayroll record);

    int countByMonth(@Param("workMonth") String workMonth, @Param("status") String status);

    List<WorkstudyPayroll> listByMonth(@Param("workMonth") String workMonth,
                                       @Param("status") String status,
                                       @Param("limit") int limit,
                                       @Param("offset") int offset);

    int countList(@Param("workMonth") String workMonth, @Param("status") String status);

    /** pending → paid，记发放人与发放时间 */
    int updatePay(@Param("id") Long id,
                  @Param("fromStatus") String fromStatus,
                  @Param("payerId") Long payerId,
                  @Param("paidAt") LocalDateTime paidAt);

    /** 未发放金额合计（status='pending'） */
    BigDecimal sumAmount(@Param("workMonth") String workMonth, @Param("status") String status);
}
