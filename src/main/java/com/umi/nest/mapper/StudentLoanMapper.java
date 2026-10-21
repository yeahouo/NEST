package com.umi.nest.mapper;

import com.umi.nest.entity.StudentLoan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * P19 生源地信用助学贷款登记数据访问。
 *
 * <p>两条约定：</p>
 * <ol>
 *   <li>所有状态流转都带 from 条件（CAS），命中 0 行说明状态已被别人改过，由服务层报冲突；</li>
 *   <li>辅导员的可见范围按 {@code student_profile.counselor_id} 过滤（与 P5 收件箱同一口径），
 *       {@code scopeCounselorId} 为 null 表示不限（admin）。</li>
 * </ol>
 */
@Mapper
public interface StudentLoanMapper {

    int insert(StudentLoan record);

    /** 含学生/班级/辅导员姓名（JOIN users、student_profile）。 */
    StudentLoan findById(@Param("id") Long id);

    List<StudentLoan> listMine(@Param("userId") Long userId,
                               @Param("status") String status,
                               @Param("academicYear") String academicYear,
                               @Param("offset") int offset,
                               @Param("limit") int limit);

    int countMine(@Param("userId") Long userId,
                  @Param("status") String status,
                  @Param("academicYear") String academicYear);

    /**
     * 员工名单：keyword 匹配姓名/学号/合同编号/经办机构（空则不过滤）。
     */
    List<StudentLoan> listForReview(@Param("status") String status,
                                    @Param("academicYear") String academicYear,
                                    @Param("keyword") String keyword,
                                    @Param("scopeCounselorId") Long scopeCounselorId,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);

    int countForReview(@Param("status") String status,
                       @Param("academicYear") String academicYear,
                       @Param("keyword") String keyword,
                       @Param("scopeCounselorId") Long scopeCounselorId);

    /** 各状态计数（key = status，value = 条数）。 */
    List<Map<String, Object>> countByStatus(@Param("academicYear") String academicYear,
                                            @Param("scopeCounselorId") Long scopeCounselorId);

    /** 导出用：与列表同口径，但不受分页限制（服务层仍会给上限）。 */
    List<StudentLoan> listForExport(@Param("status") String status,
                                    @Param("academicYear") String academicYear,
                                    @Param("scopeCounselorId") Long scopeCounselorId,
                                    @Param("limit") int limit);

    /** 学年度清单（下拉框用）。 */
    List<String> listAcademicYears();

    /**
     * 学生改写自己「待核验 / 被退回」的登记：CAS 只允许 submitted / returned。
     * 命中 0 行即说明状态已变（已核验/已确认/已撤回）。
     */
    int updateByStudent(StudentLoan record);

    /** 学生撤回自己的登记（CAS：只允许 submitted / returned）。 */
    int withdraw(@Param("id") Long id, @Param("userId") Long userId);

    /** 辅导员核验：submitted --approve--> counselor_approved / --return--> returned / --reject--> rejected。 */
    int counselorReview(@Param("id") Long id,
                        @Param("fromStatus") String fromStatus,
                        @Param("toStatus") String toStatus,
                        @Param("reason") String reason,
                        @Param("counselorId") Long counselorId);

    /** 学校确认：counselor_approved --confirm--> confirmed（CAS）。 */
    int confirm(@Param("id") Long id,
                @Param("reviewerId") Long reviewerId);

    /** 学生自助修改「是否到账」（只允许在未被退回的登记上改，CAS 由服务层保证）。 */
    int updateDisbursed(@Param("id") Long id,
                        @Param("userId") Long userId,
                        @Param("disbursed") Integer disbursed,
                        @Param("disbursedAt") java.time.LocalDate disbursedAt);
}
