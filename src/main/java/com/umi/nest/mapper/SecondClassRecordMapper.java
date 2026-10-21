package com.umi.nest.mapper;

import com.umi.nest.entity.SecondClassRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 第二课堂申报记录（学工 P18，S26）数据访问。
 *
 * <p>统计口径：<b>只有 {@code status='confirmed'} 且未删除</b>的记录计入「已认定学时/学分」；
 * {@code submitted} / {@code counselor_approved} 计入「待认定学时」，单独展示，不与已认定混算。</p>
 *
 * <p>数据级授权（P0-3）：学生侧一律带 {@code scopeUserId}；员工侧带 {@code scopeCounselorId}
 * （为 null = 不过滤，即管理员或未分派学生的辅导员，与 P13 {@code scopeCounselor} 同口径）。</p>
 */
@Mapper
public interface SecondClassRecordMapper {

    int insert(SecondClassRecord record);

    SecondClassRecord selectById(@Param("id") Long id);

    /**
     * 增量更新 + CAS：{@code id + deleted=0 + status=fromStatus}。
     * 状态、退回原因、审核人/时间、申报内容（学生重交时改学时/材料）都走这一个方法。
     */
    int update(@Param("r") SecondClassRecord record, @Param("fromStatus") String fromStatus);

    /** 我的申报（year 为空 = 不限年份；按 submitted_at 自然年过滤）。 */
    List<SecondClassRecord> selectMine(@Param("userId") Long userId,
                                       @Param("year") Integer year,
                                       @Param("status") String status,
                                       @Param("offset") int offset,
                                       @Param("size") int size);

    long countMine(@Param("userId") Long userId,
                   @Param("year") Integer year,
                   @Param("status") String status);

    /**
     * 学生改后退回重交：状态 {@code returned → submitted}，覆盖申报内容，{@code submitted_at = NOW()}，
     * 并<b>清空 return_reason</b>（动态 set 清不掉 NULL，所以单独一个方法）。CAS 同样带 fromStatus。
     */
    int resubmit(@Param("r") SecondClassRecord record, @Param("fromStatus") String fromStatus);

    /**
     * 员工审核列表。
     *
     * @param scopeCounselorId 非空时按<b>学生档案的辅导员</b>过滤（{@code student_profile.counselor_id}，
     *                         与 P13 {@code scopeCounselor} 同口径）；null = 不过滤（管理员或未分派学生的辅导员）。
     *                         注意过滤的是「学生归谁管」，不是「谁审过」——否则待初审的单子谁也看不见。
     */
    List<SecondClassRecord> selectForStaff(@Param("scopeCounselorId") Long scopeCounselorId,
                                           @Param("status") String status,
                                           @Param("categoryId") Long categoryId,
                                           @Param("keyword") String keyword,
                                           @Param("offset") int offset,
                                           @Param("size") int size);

    long countForStaff(@Param("scopeCounselorId") Long scopeCounselorId,
                       @Param("status") String status,
                       @Param("categoryId") Long categoryId,
                       @Param("keyword") String keyword);

    /** 同活动同学员是否已申报过（防重复；activityId 为 null 时不过滤活动）。 */
    long countByActivityAndUser(@Param("activityId") Long activityId,
                                @Param("userId") Long userId);

    /**
     * 取同活动同学员的既有申报（含已撤回/被退回/被否定的）。
     *
     * <p>表上有 {@code uk_record_activity_user}，一行占住唯一键后不能靠 INSERT 重开，
     * 所以重新申报走「复用此行 + 改回 submitted」（{@link #resubmit}），历史痕迹留在同一行更可审计。</p>
     */
    SecondClassRecord selectByActivityAndUser(@Param("activityId") Long activityId,
                                              @Param("userId") Long userId);

    /** 同一学员未结束的申报条数（限制刷单：submitted/counselor_approved 合计上限由服务层定）。 */
    long countPendingByUser(@Param("userId") Long userId);

    /**
     * 按板块汇总（学生成绩单 / 导出的唯一数据源）。
     *
     * <p>返回列：{@code category_id, category_name, sort_no, required_hours, yearly_cap_hours,
     * confirmed_hours, pending_hours, confirmed_credit, record_count}。三列可能为 NULL（学校未设置）。</p>
     */
    List<Map<String, Object>> summaryByCategory(@Param("userId") Long userId,
                                               @Param("year") Integer year);

    /** 学生年度合计（用于页头卡片与上限提示）。 */
    Map<String, Object> totalsByUser(@Param("userId") Long userId,
                                     @Param("year") Integer year);

    /** 导出用：范围内已认定/待认定的记录（不分页）。 */
    List<SecondClassRecord> selectForExport(@Param("scopeCounselorId") Long scopeCounselorId,
                                            @Param("status") String status,
                                            @Param("year") Integer year);

    /** 全批次统计（管理端看板）：按状态分组计数。 */
    List<Map<String, Object>> countGroupByStatus();

    /** 状态分组计数（某学生）。 */
    List<Map<String, Object>> countGroupByStatusForUser(@Param("userId") Long userId);
}
