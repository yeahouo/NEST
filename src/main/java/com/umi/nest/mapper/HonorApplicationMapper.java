package com.umi.nest.mapper;

import com.umi.nest.entity.HonorApplication;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 评优申报单（学工 P12）数据访问。
 *
 * <p>这里同时承载 P12 的「自动核验」只读查询（荣誉次数/奖学金次数）。放在本 mapper 而不是改
 * {@code AwardPunishmentMapper}：既有 mapper 的 count 只支持单个 level，表达不了
 * `level IN ('school','provincial','national')`，而任务约束不允许改既有 mapper 的既有语句。</p>
 */
@Mapper
public interface HonorApplicationMapper {

    int insert(HonorApplication application);

    HonorApplication findById(@Param("id") Long id);

    HonorApplication findByBatchAndStudent(@Param("batchId") Long batchId, @Param("studentId") Long studentId);

    /** 我的申报单（含批次名/批次状态，前端据此决定还能不能改）。 */
    List<HonorApplication> listMine(@Param("studentId") Long studentId);

    /** 学生改稿/重提（CAS：fromStatus 必须是调用方读到的那个状态，且必须是本人）。 */
    int updateMine(@Param("application") HonorApplication application, @Param("fromStatus") String fromStatus);

    /** 审核流转（CAS）。reject 时 rejectReason 写审核意见；pass 时传 null 清空历史驳回原因。 */
    int updateStatus(@Param("id") Long id,
                     @Param("fromStatus") String fromStatus,
                     @Param("toStatus") String toStatus,
                     @Param("rejectReason") String rejectReason);

    /** 撤回：submitted → draft（CAS + 本人）。 */
    int withdraw(@Param("id") Long id,
                 @Param("studentId") Long studentId,
                 @Param("fromStatus") String fromStatus);

    /**
     * 批次进入公示：该批次 dept_approved 的申报单一并置 publicized。
     *
     * <p>集合式 CAS —— WHERE 里带 status='dept_approved'，重复点击不会把已 awarded 的单子打回去；
     * 这里不检查影响行数 == 1（本来就是 0..N 行），状态正确性由 WHERE 条件保证。</p>
     */
    int publicizeByBatch(@Param("batchId") Long batchId);

    /** 批次结束：该批次 publicized 的申报单一并置 awarded。 */
    int awardByBatch(@Param("batchId") Long batchId);

    /** 审核列表（学生/班级/批次三表 JOIN）。scopeCounselorId 非空时只取该辅导员名下学生的单子。 */
    List<HonorApplication> listReview(@Param("status") String status,
                                      @Param("batchId") Long batchId,
                                      @Param("keyword") String keyword,
                                      @Param("scopeCounselorId") Long scopeCounselorId,
                                      @Param("offset") int offset,
                                      @Param("limit") int limit);

    int countReview(@Param("status") String status,
                    @Param("batchId") Long batchId,
                    @Param("keyword") String keyword,
                    @Param("scopeCounselorId") Long scopeCounselorId);

    /**
     * 公示名单 —— 直接在 SQL 里脱敏：只出姓名、学号后四位、荣誉类型、班级、专业。
     *
     * <p>刻意不返回实体，也不用 SELECT * ：公示是面向全院的公开页，学号整串/身份证/联系方式
     * 一旦落进结果集就迟早会被某个前端顺手渲染出来，所以从数据出口就切断。</p>
     */
    List<Map<String, Object>> listPublicity(@Param("batchId") Long batchId);

    /** 导出用（不带自述正文；teacher 视角只看自己名下学生）。 */
    List<HonorApplication> listForExport(@Param("batchId") Long batchId,
                                         @Param("scopeCounselorId") Long scopeCounselorId);

    /**
     * 校级及以上荣誉称号次数（自动核验 cond7）。
     * level 取值为英文码 national/provincial/school（见 sql\nest_xuegong.sql 的列注释）。
     */
    int countHonorAwards(@Param("studentId") Long studentId);

    /** 其中标题含「奖学金」的次数（荣誉次数不足 2 次时用「一等奖学金」之类的佐证）。 */
    int countScholarshipAwards(@Param("studentId") Long studentId);

    /** 统计：申报单按状态计数（batchId / scopeCounselorId 可为空）。 */
    List<Map<String, Object>> countGroupByStatus(@Param("batchId") Long batchId,
                                                 @Param("scopeCounselorId") Long scopeCounselorId);
}
