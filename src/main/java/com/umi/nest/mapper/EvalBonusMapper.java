package com.umi.nest.mapper;

import com.umi.nest.entity.EvalBonus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 综合素质表现得分 C 的加分申报（学工 P13）数据访问。
 *
 * <p>所有状态流转都是 CAS（WHERE 带 status = fromStatus），学生侧的语句还额外锁 student_id，
 * 保证「别人的单子改不动」不依赖服务层的 if。</p>
 */
@Mapper
public interface EvalBonusMapper {

    int insert(EvalBonus bonus);

    EvalBonus findById(@Param("id") Long id);

    /** 学生改稿/重提：CAS（本人 + fromStatus），改完落 submitted。 */
    int updateMine(@Param("bonus") EvalBonus bonus, @Param("fromStatus") String fromStatus);

    /** 撤回：submitted → draft（CAS + 本人）。 */
    int withdraw(@Param("id") Long id,
                 @Param("studentId") Long studentId,
                 @Param("fromStatus") String fromStatus);

    /** 审核：CAS pending 状态 → counselor_approved / rejected，写审核人与审核时间。 */
    int review(@Param("id") Long id,
               @Param("fromStatus") String fromStatus,
               @Param("toStatus") String toStatus,
               @Param("rejectReason") String rejectReason,
               @Param("reviewerId") Long reviewerId);

    /** 我的申报（academicTerm 为空取全部学期），按学期倒序、id 倒序。 */
    List<EvalBonus> listMine(@Param("studentId") Long studentId,
                            @Param("academicTerm") String academicTerm);

    /** 审核列表（学生/班级 JOIN）；scopeCounselorId 非空时只取该辅导员名下学生。 */
    List<EvalBonus> listReview(@Param("academicTerm") String academicTerm,
                               @Param("status") String status,
                               @Param("keyword") String keyword,
                               @Param("scopeCounselorId") Long scopeCounselorId,
                               @Param("offset") int offset,
                               @Param("limit") int limit);

    int countReview(@Param("academicTerm") String academicTerm,
                    @Param("status") String status,
                    @Param("keyword") String keyword,
                    @Param("scopeCounselorId") Long scopeCounselorId);

    /**
     * 该学期全部已通过（counselor_approved）的加分/扣分记录。
     *
     * <p>compute 与 /my 都用这一条：C 的口径只允许一处实现 —— 服务层按大类分组封顶、
     * 再按办法第十二条总封顶 8 分，SQL 里不做任何聚合，免得两处算出口径不一致。</p>
     */
    List<EvalBonus> listApprovedByTerm(@Param("academicTerm") String academicTerm);

    /** 该学期待审条数（stats 的 bonusPending）。 */
    int countSubmittedByTerm(@Param("academicTerm") String academicTerm);
}
