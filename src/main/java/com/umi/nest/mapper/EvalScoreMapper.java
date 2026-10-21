package com.umi.nest.mapper;

import com.umi.nest.entity.EvalScore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 综测得分。
 *
 * <p>写入一律走 {@link #upsert}：唯一键 (student_id, indicator_id, academic_term, source)
 * 保证同一来源重复提交只留一条。</p>
 */
@Mapper
public interface EvalScoreMapper {

    /** 某学生某学期的全部得分（含指标名/维度/权重，前台直接渲染） */
    List<EvalScore> listByStudent(@Param("studentId") Long studentId, @Param("term") String term);

    /** 某学期的全部得分（汇总重算用；scopeCounselorId 非空时只取该辅导员名下学生） */
    List<EvalScore> listByTerm(@Param("term") String term, @Param("scopeCounselorId") Long scopeCounselorId);

    /** 某学生某学期某来源的得分（辅导员评分页回显用） */
    List<EvalScore> listByStudentSource(@Param("studentId") Long studentId,
                                        @Param("term") String term,
                                        @Param("source") String source);

    int upsert(EvalScore score);

    int deleteByStudentTermSource(@Param("studentId") Long studentId,
                                  @Param("term") String term,
                                  @Param("source") String source);

    /** 有得分记录的学生 + 其班级（汇总重算的输入；Map 的键是列原名 student_id / class_name） */
    List<Map<String, Object>> listScoredStudents(@Param("term") String term,
                                                @Param("scopeCounselorId") Long scopeCounselorId);

    /** 某学期有得分的学生数（scopeCounselorId 非空时只算该辅导员名下） */
    int countScoredStudents(@Param("term") String term, @Param("scopeCounselorId") Long scopeCounselorId);

    /** 出现过得分的学期（倒序），给前台学期选择器 */
    List<String> listTerms();
}
