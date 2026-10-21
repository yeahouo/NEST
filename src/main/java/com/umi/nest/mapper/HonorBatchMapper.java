package com.umi.nest.mapper;

import com.umi.nest.entity.HonorBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 评奖评优批次（学工 P12）数据访问。
 *
 * <p>所有状态变更都是 CAS：`WHERE id = ? AND deleted = 0 AND status = <调用方读到的状态>`，
 * service 检查影响行数 == 1。没有这一步，两名 admin 同时点「发布/公示」会把状态推两步，
 * 或者一个 admin 改批次的同时另一个已经发布了批次 —— 学生已提交的申报单就会对不上批次口径。</p>
 */
@Mapper
public interface HonorBatchMapper {

    int insert(HonorBatch batch);

    HonorBatch findById(@Param("id") Long id);

    /**
     * 批次列表。
     *
     * @param hideDraft 学生/教师视角置 true：草稿批次还没发布，不该被学生看到（更不该被申报）
     * @param status    精确筛选（空 = 不限）
     * @param keyword   批次名/通知标题模糊搜索（空 = 不限）
     */
    List<HonorBatch> list(@Param("hideDraft") boolean hideDraft,
                          @Param("status") String status,
                          @Param("keyword") String keyword);

    /** 编辑批次（CAS：fromStatus 必须是调用方读到的那个状态）。 */
    int update(@Param("batch") HonorBatch batch, @Param("fromStatus") String fromStatus);

    /** 状态推进（publish：draft→recruiting；finish：publicity→finished）。 */
    int updateStatus(@Param("id") Long id,
                     @Param("fromStatus") String fromStatus,
                     @Param("toStatus") String toStatus);

    /** 进入公示：状态与公示起止一次写入（工作日折算在 service，publicityDays 一并落库以便复核）。 */
    int startPublicity(@Param("id") Long id,
                       @Param("fromStatus") String fromStatus,
                       @Param("publicityDays") int publicityDays,
                       @Param("publicityStart") LocalDateTime publicityStart,
                       @Param("publicityEnd") LocalDateTime publicityEnd);

    /**
     * 申报截止即锁：日期已过（apply_end 当天仍可申报）的 recruiting 批次统一转 reviewing。
     *
     * <p>为什么是惰性的一条 UPDATE 而不是定时任务：本项目没有调度器，而「截止后批次不再是 recruiting」
     * 是学生侧所有写操作的判据（见 HonorService），所以必须在读入口先把它同步到位，
     * 否则会出现「申报期已过但状态还是 recruiting、学生仍能提交」的窗口。</p>
     */
    int closeExpiredRecruiting();

    /** 统计：批次按状态计数（status / cnt）。 */
    List<Map<String, Object>> countGroupByStatus();
}
