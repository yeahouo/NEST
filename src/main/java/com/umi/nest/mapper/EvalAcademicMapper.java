package com.umi.nest.mapper;

import com.umi.nest.entity.EvalAcademic;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 学习成绩 A 登记台账（学工 P13）数据访问。
 *
 * <p>A 的写入只有两条路：手工登记/修正（PUT /api/rank/academic）与教务 CSV 导入
 * （POST /api/rank/academic/import），两条路共用本 mapper 的 insert / update。</p>
 */
@Mapper
public interface EvalAcademicMapper {

    int insert(EvalAcademic academic);

    /** 按主键更新（唯一键 (student_id, academic_term) 已保证一人一学期只有一条）。 */
    int update(EvalAcademic academic);

    EvalAcademic findByStudentTerm(@Param("studentId") Long studentId,
                                   @Param("academicTerm") String academicTerm);

    /** 该学期全部 A 台账（含学生姓名/学号/班级/专业/年级，compute 一次取全，避免 N+1）。 */
    List<EvalAcademic> listByTerm(@Param("academicTerm") String academicTerm);

    /** 该学期「缺 A」待补清单：在读且（没有台账行 或 score_a 为空）的学生。 */
    List<Map<String, Object>> listMissingByTerm(@Param("academicTerm") String academicTerm);

    /** 该学期已登记 A 的条数（stats 用）。 */
    int countByTerm(@Param("academicTerm") String academicTerm);

    /**
     * 学号 → 用户 id（CSV 导入用）。
     *
     * <p>导入文件里只有学号，而台账按 user_id 存；这里只认学生档案里非删除记录的 student_no，
     * 查不到就是「不在学生档案里」的行级错误，由服务层记进 failed 明细。</p>
     */
    Long findUserIdByStudentNo(@Param("studentNo") String studentNo);
}
