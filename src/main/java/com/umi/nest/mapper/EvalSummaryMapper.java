package com.umi.nest.mapper;

import com.umi.nest.entity.EvalSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 综测学期汇总（总分 / 班级排名 / 等级）。
 *
 * <p>写入只走 {@link #upsert}：唯一键 (student_id, academic_term)，
 * 重算时整行覆盖（含排名与等级，排名必须在全批算完之后统一回写）。</p>
 */
@Mapper
public interface EvalSummaryMapper {

    EvalSummary findByStudentTerm(@Param("studentId") Long studentId, @Param("term") String term);

    /** 按学期取汇总列表（className 精确筛选；scopeCounselorId 非空时只取该辅导员名下学生） */
    List<EvalSummary> listByTerm(@Param("term") String term,
                                 @Param("className") String className,
                                 @Param("scopeCounselorId") Long scopeCounselorId,
                                 @Param("offset") int offset,
                                 @Param("limit") int limit);

    int countByTerm(@Param("term") String term,
                    @Param("className") String className,
                    @Param("scopeCounselorId") Long scopeCounselorId);

    /** 关键字搜索（姓名 / 学号 / 班级），与学生名册同一口径 */
    List<EvalSummary> searchByTerm(@Param("term") String term,
                                   @Param("className") String className,
                                   @Param("keyword") String keyword,
                                   @Param("scopeCounselorId") Long scopeCounselorId,
                                   @Param("offset") int offset,
                                   @Param("limit") int limit);

    int countSearch(@Param("term") String term,
                    @Param("className") String className,
                    @Param("keyword") String keyword,
                    @Param("scopeCounselorId") Long scopeCounselorId);

    int upsert(EvalSummary summary);

    /** 汇总统计：总人数 / 已评人数 / 平均分 / 各等级人数 */
    List<java.util.Map<String, Object>> statsByTerm(@Param("term") String term,
                                                    @Param("className") String className,
                                                    @Param("scopeCounselorId") Long scopeCounselorId);

    int deleteByTerm(@Param("term") String term, @Param("scopeCounselorId") Long scopeCounselorId);
}
