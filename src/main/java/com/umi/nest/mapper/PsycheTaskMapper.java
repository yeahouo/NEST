package com.umi.nest.mapper;

import com.umi.nest.entity.PsycheTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 学工 P14 心理健康 · 普查名单与进度数据访问（psyche_task）。
 *
 * <p>作答与状态流转都是 CAS（状态必须还是 fromStatus）；batchId 传 null 表示不限批次（导出与全局统计用）。</p>
 */
@Mapper
public interface PsycheTaskMapper {

    int insert(PsycheTask task);

    PsycheTask findById(@Param("id") Long id);

    /** 一人一批次只允许一条（uk_pt_batch_student），导入时用它去重。 */
    PsycheTask findByBatchAndStudent(@Param("batchId") Long batchId, @Param("studentId") Long studentId);

    List<PsycheTask> list(@Param("batchId") Long batchId,
                          @Param("status") String status,
                          @Param("className") String className,
                          @Param("keyword") String keyword,
                          @Param("offset") int offset,
                          @Param("limit") int limit);

    int count(@Param("batchId") Long batchId,
              @Param("status") String status,
              @Param("className") String className,
              @Param("keyword") String keyword);

    List<PsycheTask> listByStudent(@Param("studentId") Long studentId);

    /** pending → in_progress：学生第一次拉题时落 started_at（窗口外不落）。 */
    int startTask(@Param("id") Long id,
                  @Param("totalItems") Integer totalItems,
                  @Param("startedAt") LocalDateTime startedAt);

    /** pending/in_progress → completed：同时写分数、档位与提交时间。 */
    int submit(@Param("id") Long id,
               @Param("fromStatus") String fromStatus,
               @Param("answerCount") Integer answerCount,
               @Param("totalItems") Integer totalItems,
               @Param("scoreTotal") BigDecimal scoreTotal,
               @Param("scoreBand") String scoreBand,
               @Param("durationSeconds") Integer durationSeconds,
               @Param("submittedAt") LocalDateTime submittedAt);

    /** 中心/辅导员回填结果等级（平台不自动判定）。 */
    int updateResult(@Param("id") Long id,
                     @Param("resultLevel") String resultLevel,
                     @Param("resultNote") String resultNote,
                     @Param("resultBy") Long resultBy);

    /** 抽查面对面访谈记录（S8）。 */
    int updateInterview(@Param("id") Long id,
                        @Param("interviewed") Integer interviewed,
                        @Param("interviewNote") String interviewNote,
                        @Param("interviewedBy") Long interviewedBy,
                        @Param("interviewedAt") LocalDateTime interviewedAt);

    /** 关窗后把该批次 pending/in_progress 的名单置为 absent（S8：未答完按未参加处理）。 */
    int markAbsentByBatch(@Param("batchId") Long batchId);

    /** [{status, cnt}]，用于列表 stats 与批次统计。 */
    List<Map<String, Object>> countGroupByStatus(@Param("batchId") Long batchId);

    /** [{class_name, cnt, done}]，按班级完成率。 */
    List<Map<String, Object>> countGroupByClass(@Param("batchId") Long batchId);

    /** [{major, cnt, done}]，按专业完成率。 */
    List<Map<String, Object>> countGroupByMajor(@Param("batchId") Long batchId);
}
